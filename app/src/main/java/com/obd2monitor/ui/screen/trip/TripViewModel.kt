package com.obd2monitor.ui.screen.trip

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.data.database.DatabaseBackupManager
import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.domain.model.GeoPoint
import com.obd2monitor.domain.model.OBDData
import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.model.Trip
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import com.obd2monitor.service.TripController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

data class TripUiState(
    val activeTrip: Trip? = null,
    val activeProfile: VehicleProfile? = null,
    val connection: ConnectionStatus = ConnectionStatus(),
    val live: OBDData = OBDData(),
    val routePoints: List<GeoPoint> = emptyList(),
    val maxSpeed: Float = 0f,
    val avgMovingSpeed: Float = 0f,
    val distanceKm: Float = 0f,
    val durationMs: Long = 0L,
    val pausedMs: Long = 0L,
    val paused: Boolean = false,
    /** Timestamp начала текущей непрерывной паузы, либо null. UI вычитает из (start + AUTO_FINISH)
     *  чтобы показать сколько осталось до авто-завершения сессии. */
    val pauseSinceMs: Long? = null,
)

@HiltViewModel
class TripViewModel @Inject constructor(
    private val tripRepo: TripRepository,
    private val vehicleRepo: VehicleProfileRepository,
    private val obdRepo: OBDRepository,
    private val controller: TripController,
    private val backup: DatabaseBackupManager,
) : ViewModel() {

    private val _backupMessage = MutableStateFlow<String?>(null)
    /** Одноразовое уведомление об успехе/ошибке экспорта-импорта. UI читает и сбрасывает. */
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()
    fun consumeBackupMessage() { _backupMessage.value = null }

    /** Экспорт ТОЛЬКО поездок (trips + obd_readings) в JSON.
     *  Полный бэкап БД делается из настроек, не отсюда. */
    fun exportTrips(uri: Uri) {
        viewModelScope.launch {
            backup.exportTripsTo(uri)
                .onSuccess { n -> _backupMessage.value = "Экспортировано поездок: $n" }
                .onFailure { _backupMessage.value = "Ошибка экспорта: ${it.message}" }
        }
    }

    /** Импорт поездок из JSON. Существующие поездки с тем же id заменяются.
     *  Профили/пробег/расходы/ТО не трогаются — это отдельный полный бэкап. */
    fun importTrips(uri: Uri) {
        viewModelScope.launch {
            backup.importTripsFrom(uri)
                .onSuccess { n -> _backupMessage.value = "Импортировано поездок: $n" }
                .onFailure { _backupMessage.value = "Ошибка импорта: ${it.message}" }
        }
    }

    private val _state = MutableStateFlow(TripUiState())
    val state: StateFlow<TripUiState> = _state.asStateFlow()

    fun disconnect() {
        // Помечаем разрыв как «явный finish» ПЕРЕД disconnect() — иначе
        // AutoSessionManager примет последующий DISCONNECTED за случайный
        // обрыв (отошёл от машины) и не закроет trip, а лишь поставит паузу.
        controller.requestFinish()
        viewModelScope.launch { obdRepo.disconnect() }
    }

    val trips: StateFlow<List<Trip>> = tripRepo.observeTrips().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val readingsFlow = tripRepo.observeActiveTrip().flatMapLatest { trip ->
        if (trip == null) flowOf(emptyList()) else tripRepo.observeReadings(trip.id)
    }

    init {
        tripRepo.observeActiveTrip().onEach { trip ->
            _state.value = _state.value.copy(activeTrip = trip)
            if (trip == null) {
                _state.value = _state.value.copy(
                    routePoints = emptyList(),
                    distanceKm = 0f,
                    maxSpeed = 0f,
                    avgMovingSpeed = 0f,
                )
            }
        }.launchIn(viewModelScope)
        readingsFlow.onEach { readings ->
            val points = filterGpsJitter(readings)
            val (distKm, avgMoving) = computeDistanceAndAvg(readings)
            val maxSpd = readings.maxOfOrNull { it.speedKmh } ?: 0f
            _state.value = _state.value.copy(
                routePoints = points,
                distanceKm = distKm,
                maxSpeed = maxSpd,
                avgMovingSpeed = avgMoving,
            )
        }.launchIn(viewModelScope)
        vehicleRepo.observeActive().onEach { profile ->
            _state.value = _state.value.copy(activeProfile = profile)
        }.launchIn(viewModelScope)
        obdRepo.connectionStatus.onEach { status ->
            _state.value = _state.value.copy(connection = status)
        }.launchIn(viewModelScope)
        obdRepo.liveData.onEach { data ->
            _state.value = _state.value.copy(live = data)
        }.launchIn(viewModelScope)
        controller.durationMs.onEach { ms ->
            _state.value = _state.value.copy(durationMs = ms)
        }.launchIn(viewModelScope)
        controller.pausedMs.onEach { ms ->
            _state.value = _state.value.copy(pausedMs = ms)
        }.launchIn(viewModelScope)
        controller.paused.onEach { p ->
            _state.value = _state.value.copy(paused = p)
        }.launchIn(viewModelScope)
        controller.pauseSinceMs.onEach { t ->
            _state.value = _state.value.copy(pauseSinceMs = t)
        }.launchIn(viewModelScope)
    }

    /**
     * Антидрожание GPS:
     *  - принимаем только точки с валидными координатами;
     *  - если расстояние от предыдущей принятой точки < MIN_MOVE_M — игнорируем
     *    (стоим на месте → точки скачут на ±5..15 м, рисуют клубок).
     */
    private fun filterGpsJitter(readings: List<OBDReading>): List<GeoPoint> {
        val out = mutableListOf<GeoPoint>()
        var lastKept: GeoPoint? = null
        for (r in readings) {
            val lat = r.latitude ?: continue
            val lon = r.longitude ?: continue
            val candidate = GeoPoint(lat, lon, r.timestamp)
            val keep = lastKept == null ||
                haversineMeters(lastKept!!.latitude, lastKept!!.longitude, lat, lon) >= MIN_MOVE_M
            if (keep) {
                out += candidate
                lastKept = candidate
            }
        }
        return out
    }

    /**
     * Считаем дистанцию через интегрирование скорости (надёжнее GPS-дистанции,
     * т.к. OBD speed корректнее на низких скоростях). Среднюю скорость — только
     * по фрагментам, где speed > MOVING_THRESHOLD_KMH (исключая стоянки).
     */
    private fun computeDistanceAndAvg(readings: List<OBDReading>): Pair<Float, Float> {
        if (readings.size < 2) return 0f to 0f
        var distKm = 0f
        var movingTimeS = 0f
        var movingDistKm = 0f
        for (i in 1 until readings.size) {
            val dtH = (readings[i].timestamp - readings[i - 1].timestamp) / 3_600_000f
            if (dtH <= 0f || dtH > 0.1f) continue // пропускаем разрывы > 6 минут
            val avgSpd = (readings[i].speedKmh + readings[i - 1].speedKmh) / 2f
            val seg = avgSpd * dtH
            distKm += seg
            if (avgSpd >= MOVING_THRESHOLD_KMH) {
                movingTimeS += dtH * 3600f
                movingDistKm += seg
            }
        }
        val avgMoving = if (movingTimeS > 0f) movingDistKm / (movingTimeS / 3600f) else 0f
        return distKm to avgMoving
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * acos((1 - 2 * a).coerceIn(-1.0, 1.0)) / 2
        return r * c
    }

    fun deleteTrip(id: String) {
        viewModelScope.launch { tripRepo.deleteTrip(id) }
    }

    companion object {
        private const val MIN_MOVE_M = 8.0
        private const val MOVING_THRESHOLD_KMH = 3f
    }
}
