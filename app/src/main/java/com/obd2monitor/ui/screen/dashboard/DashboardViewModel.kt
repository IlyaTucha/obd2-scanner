package com.obd2monitor.ui.screen.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.data.obd.FuelCalculator
import com.obd2monitor.data.obd.ReadingMetrics
import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.domain.model.OBDData
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import com.obd2monitor.service.ActiveTripTracker
import com.obd2monitor.service.LiveTripStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Метрика для sparkline-карточки: ключ + последние значения в памяти.
 *  Полная история сессии (на детальном экране) подгружается из БД. */
data class MetricHistory(val key: String, val values: List<Float> = emptyList()) {
    fun add(v: Float?, capacity: Int = 1200): MetricHistory {
        if (v == null || !v.isFinite()) return this
        return copy(values = (values + v).takeLast(capacity))
    }
}

data class DashboardUiState(
    val data: OBDData = OBDData(),
    val connection: ConnectionStatus = ConnectionStatus(),
    val activeProfile: VehicleProfile? = null,
    val histories: Map<String, MetricHistory> = emptyMap(),
    val trip: LiveTripStats = LiveTripStats(),
    /** Средний расход за сессию, л/100км = литры / пробег × 100 (как в отчёте сессии).
     *  null если данных мало (поездка только началась). */
    val tripAvgConsumption: Float? = null,
    /** Запас хода до пустого бака, км. null если уровень бака не известен или расход 0. */
    val rangeKm: Float? = null,
    /** Стоимость топлива за активную сессию, ₽. null если в профиле не задана цена. */
    val tripCostRub: Float? = null,
    /** Мгновенная стоимость на 100 км, ₽. null если в профиле не задана цена. */
    val costPer100Rub: Float? = null,
    /** Ускорение по последним замерам сессии, м/с². */
    val accelMs2: Float = 0f,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val obdRepo: OBDRepository,
    vehicleRepo: VehicleProfileRepository,
    private val tripRepo: TripRepository,
    tracker: ActiveTripTracker,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        obdRepo.liveData.onEach(::onLive).launchIn(viewModelScope)
        vehicleRepo.observeActive().onEach { profile ->
            _state.value = _state.value.copy(activeProfile = profile)
            recomputeSessionMetrics()
        }.launchIn(viewModelScope)
        obdRepo.connectionStatus.onEach { status ->
            _state.value = _state.value.copy(connection = status)
        }.launchIn(viewModelScope)
        tracker.stats.onEach { stats ->
            _state.value = _state.value.copy(trip = stats)
            recomputeSessionMetrics()
        }.launchIn(viewModelScope)
    }

    /** Пересчёт показателей сессии из накопленной статистики, live-снимка и профиля. */
    private fun recomputeSessionMetrics() {
        val s = _state.value
        val live = s.data
        val price = s.activeProfile?.fuelPrice ?: 0f
        val tank = s.activeProfile?.tankCapacity ?: 0f
        val liters = s.trip.fuelLiters
        val avg = s.trip.avgConsumption

        // Запас хода: остаток в баке / средний расход; пока среднего нет — мгновенный.
        val consForRange = avg ?: live.instantFuelLPer100?.takeIf { it > 0.5f }
        val rangeKm = if (tank > 0f && live.fuelLevelPercent != null && consForRange != null && consForRange > 0.5f) {
            (live.fuelLevelPercent / 100f * tank / consForRange * 100f).coerceAtLeast(0f)
        } else null
        val tripCost = if (price > 0f && liters > 0.001f) liters * price else null
        val costPer100 = FuelCalculator.displayConsumption(live.instantFuelLPer100)
            ?.takeIf { price > 0f && it > 0f }
            ?.let { it * price }

        val histories = s.histories.toMutableMap()
        fun upd(key: String, value: Float?) {
            histories[key] = (histories[key] ?: MetricHistory(key)).add(value)
        }
        upd("range", rangeKm)
        upd("tripcost", tripCost)
        upd("cost100", costPer100)
        upd("accel", s.trip.accelMs2)

        _state.value = s.copy(
            tripAvgConsumption = avg,
            rangeKm = rangeKm,
            tripCostRub = tripCost,
            costPer100Rub = costPer100,
            accelMs2 = s.trip.accelMs2,
            histories = histories,
        )
    }

    /**
     * Полная история параметра за активную сессию — из БД; если сессии нет или
     * параметр не пишется в замеры (запас хода, стоимость) — то, что есть в памяти.
     */
    suspend fun sessionSeries(key: String): List<Float> {
        val tripId = _state.value.trip.tripId
        val inMemory = _state.value.histories[key]?.values.orEmpty()
        if (tripId == null) return inMemory
        val profile = _state.value.activeProfile
        val fromDb = withContext(Dispatchers.Default) {
            val readings = tripRepo.readingsFor(tripId)
            if (key == "accel" || readings.isNotEmpty() && ReadingMetrics.value(key, readings.first(), profile) != null) {
                ReadingMetrics.series(key, readings, profile)
            } else null
        }
        return fromDb?.takeIf { s -> s.any { it != 0f && it.isFinite() } } ?: inMemory
    }

    private fun onLive(data: OBDData) {
        val histories = _state.value.histories.toMutableMap()
        fun upd(key: String, value: Float?) {
            histories[key] = (histories[key] ?: MetricHistory(key)).add(value)
        }
        upd("speed", data.speedKmh)
        upd("rpm", data.rpm?.toFloat())
        upd("coolant", data.coolantTempC?.toFloat())
        upd("intake", data.intakeTempC?.toFloat())
        upd("battery", data.batteryVoltage)
        upd("throttle", data.throttlePercent)
        upd("load", data.engineLoadPercent)
        upd("fuel", FuelCalculator.displayConsumption(data.instantFuelLPer100))
        upd("fuelh", data.instantFuelLPerH)
        upd("map", data.mapKpa?.toFloat())
        upd("timing", data.ignitionTimingDeg)
        upd("oil", data.oilTempC?.toFloat())
        upd("stft", data.shortFuelTrim1Pct)
        upd("ltft", data.longFuelTrim1Pct)
        upd("fuellevel", data.fuelLevelPercent)
        upd("distmil", data.distanceMilKm?.toFloat())
        upd("distclear", data.distanceSinceClearKm?.toFloat())
        upd("ambient", data.ambientTempC?.toFloat())
        upd("power", data.powerKw?.let { it * ReadingMetrics.KW_TO_HP })
        upd("torque", data.torqueNm)
        upd("vacuum", data.vacuumKpa)
        upd("warmup", data.warmupPct)
        upd("cutoff", if (data.fuelCutoffActive) 1f else 0f)
        _state.value = _state.value.copy(data = data, histories = histories)
        recomputeSessionMetrics()
    }

    /** Переподключение к OBD кнопкой в шапке. Репозиторий держит адрес устройства и
     *  инвариант «реконнект не закрывает сессию» (см. OBDRepositoryImpl.reconnect). */
    fun reconnect() {
        viewModelScope.launch { obdRepo.reconnect() }
    }
}
