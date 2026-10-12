package com.obd2monitor.ui.screen.trip

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.domain.model.GeoPoint
import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.model.Trip
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.obd2monitor.data.obd.ReadingMetrics
import com.obd2monitor.data.obd.TripMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ParamSummary(
    val key: String,
    val title: String,
    val unit: String,
    val min: Float,
    val avg: Float,
    val max: Float,
    val series: List<Float>,
)

data class TripSummaryUiState(
    val trip: Trip? = null,
    val routePoints: List<GeoPoint> = emptyList(),
    val parameters: List<ParamSummary> = emptyList(),
    val loading: Boolean = true,
    /** Стоимость поездки в ₽. null если в профиле fuelPrice = 0. */
    val tripCostRub: Float? = null,
    /** Пиковое ускорение разгона, м/с². null если разгона не было. */
    val peakAccelMs2: Float? = null,
    val peakBrakeMs2: Float? = null,
)

@HiltViewModel
class TripSummaryViewModel @Inject constructor(
    private val tripRepo: TripRepository,
    private val vehicleRepo: VehicleProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val tripId: String = checkNotNull(savedStateHandle["tripId"])

    private val _state = MutableStateFlow(TripSummaryUiState())
    val state: StateFlow<TripSummaryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val trip = tripRepo.getTrip(tripId)
        val readings = tripRepo.readingsFor(tripId)
        val profile = trip?.vehicleProfileId?.let { vehicleRepo.getById(it) }
        // Разбор десятков тысяч замеров — не в главном потоке.
        _state.value = withContext(Dispatchers.Default) {
            val points = readings.mapNotNull { r ->
                val lat = r.latitude; val lon = r.longitude
                if (lat != null && lon != null) GeoPoint(lat, lon, r.timestamp) else null
            }
            val accel = TripMath.accelSeries(readings)
            val cost = if (trip != null && profile != null && profile.fuelPrice > 0f && trip.fuelUsedLiters > 0f) {
                trip.fuelUsedLiters * profile.fuelPrice
            } else null
            TripSummaryUiState(
                trip = trip,
                routePoints = points,
                parameters = buildParameters(readings, accel, trip, profile),
                loading = false,
                tripCostRub = cost,
                peakAccelMs2 = accel.maxOrNull()?.takeIf { it > 0.05f },
                peakBrakeMs2 = accel.minOrNull()?.takeIf { it < -0.05f },
            )
        }
    }

    private fun buildParameters(
        readings: List<OBDReading>,
        accel: List<Float>,
        trip: Trip?,
        profile: VehicleProfile?,
    ): List<ParamSummary> {
        if (readings.isEmpty()) return emptyList()
        // Для графиков прореживаем до ~120 точек, чтобы не тормозить отрисовку.
        val step = (readings.size / 120).coerceAtLeast(1)

        fun summary(key: String, title: String, unit: String, avgOverride: Float? = null): ParamSummary? {
            val all = if (key == "accel") accel else ReadingMetrics.series(key, readings, profile)
            val values = all.filter { it.isFinite() }
            // Параметр, который ни разу не отдавался (всегда 0), не показываем.
            if (values.none { it != 0f }) return null
            return ParamSummary(
                key = key,
                title = title,
                unit = unit,
                min = values.min(),
                avg = avgOverride ?: values.average().toFloat(),
                max = values.max(),
                series = all.filterIndexed { i, _ -> i % step == 0 },
            )
        }

        // «Сред.» для скорости и расхода берём из итогов сессии (пробег / время в пути,
        // литры / пробег), а не арифметическое среднее замеров — иначе стоянки и
        // всплески мгновенного расхода на малой скорости искажают цифру.
        return listOfNotNull(
            summary("speed", "Скорость", "км/ч", avgOverride = trip?.avgSpeedKmh),
            summary("rpm", "Обороты", "об/мин"),
            summary("power", "Мощность", "л.с."),
            summary("torque", "Крутящий момент", "Н·м"),
            summary("accel", "Ускорение", "м/с²"),
            summary("fuel", "Расход", "л/100 км", avgOverride = trip?.avgFuelConsumption),
            summary("load", "Нагрузка двигателя", "%"),
            summary("throttle", "Дроссель", "%"),
            summary("vacuum", "Разрежение во впуске", "кПа"),
            summary("warmup", "Прогрев", "%"),
            summary("coolant", "Охлаждающая жидкость", "°C"),
            summary("battery", "Бортсеть", "В"),
            summary("intake", "Воздух на впуске", "°C"),
            summary("map", "Давление впуска", "кПа"),
            summary("timing", "Угол зажигания", "°"),
            summary("oil", "Температура масла", "°C"),
            summary("stft", "Краткая коррекция топлива", "%"),
            summary("ltft", "Долгая коррекция топлива", "%"),
            summary("fuellevel", "Топливо в баке", "%"),
            summary("maf", "Расход воздуха", "г/с"),
            summary("distmil", "Пробег с Check Engine", "км"),
            summary("distclear", "Пробег после сброса ошибок", "км"),
        )
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            tripRepo.deleteTrip(tripId)
            onDone()
        }
    }
}
