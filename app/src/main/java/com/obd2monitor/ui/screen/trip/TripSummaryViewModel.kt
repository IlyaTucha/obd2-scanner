package com.obd2monitor.ui.screen.trip

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.domain.model.GeoPoint
import com.obd2monitor.domain.model.MetricNames
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
    val movingAvg: Float? = null,
    /** Время каждой точки [series], мс. */
    val times: List<Long> = emptyList(),
    /** Свои подписи вместо «Мин. · Сред. · Макс.» — у остановок это не числа ряда. */
    val stats: List<Pair<String, String>>? = null,
)

/** Сводка по остановкам: сколько, сколько всего стояли, самая долгая. */
fun stopStats(stops: List<TripMath.Stop>): List<Pair<String, String>> = listOf(
    "Остановок" to stops.size.toString(),
    "Стояли" to formatShortDuration(stops.sumOf { it.durationMs }),
    "Самая долгая" to formatShortDuration(stops.maxOfOrNull { it.durationMs } ?: 0L),
)

/** «45 с», «2 мин 10 с», «1 ч 05 мин». */
fun formatShortDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s с"
        s < 3600 -> if (s % 60 == 0L) "${s / 60} мин" else "${s / 60} мин ${s % 60} с"
        else -> "${s / 3600} ч %02d мин".format((s % 3600) / 60)
    }
}

/** Пояснение к двум средним на экране параметра. */
const val MOVING_AVG_HINT = "«Сред.» считается за всё время сессии вместе со стоянками и пробками, " +
    "«Сред. на ходу» только пока машина ехала."

/** Параметры, у которых стоянки заметно занижают среднюю: им нужна ещё «Сред. на ходу».
 *  Температурам, напряжению, коррекциям и баку — нет: на стоянке они почти те же. */
val MOVING_AVG_KEYS = setOf("speed", "rpm", "power", "torque", "load", "throttle", "map", "vacuum", "maf", "fuelh", "timing")

/** Средняя [series] только по точкам, где машина ехала ([moving] — той же длины). */
fun movingAverage(key: String, series: List<Float>, moving: List<Boolean>?): Float? {
    if (key !in MOVING_AVG_KEYS || moving == null || moving.size != series.size) return null
    return series.filterIndexed { i, v -> moving[i] && v.isFinite() }
        .takeIf { it.isNotEmpty() }?.average()?.toFloat()
}

data class ParamStats(
    val min: Float,
    val avg: Float,
    val max: Float,
    /** Средняя без стоянок — для параметров из MOVING_AVG_KEYS. */
    val movingAvg: Float? = null,
)

/**
 * Мин/сред/макс параметра за сессию — один расчёт для итогов сессии и экрана
 * параметра, чтобы цифры совпадали. Стоянки (нули) входят: средняя скорость —
 * та же, что «Ср. скорость» в итогах. «Сред.» для скорости и расхода берём из
 * итогов сессии (пробег / время в пути, литры / пробег), а не арифметическое
 * среднее замеров — иначе паузы записи и всплески мгновенного расхода на малой
 * скорости искажают цифру. null — параметр ни разу не отдавался (всегда 0).
 */
fun paramStats(key: String, series: List<Float>, trip: Trip?, moving: List<Boolean>? = null): ParamStats? {
    val values = series.filter { it.isFinite() }
    if (values.none { it != 0f }) return null
    val tripAvg = when (key) {
        "speed" -> trip?.avgSpeedKmh
        "fuel" -> trip?.avgFuelConsumption
        else -> null
    }?.takeIf { it > 0f }
    return ParamStats(
        min = values.min(),
        avg = tripAvg ?: values.average().toFloat(),
        max = values.max(),
        // Скорость — по ненулевым замерам; остальное — по отметкам «ехала».
        movingAvg = if (key == "speed") values.filter { it > 0f }.average().toFloat()
            else movingAverage(key, series, moving),
    )
}

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

        fun summary(key: String, unit: String): ParamSummary? {
            val all = if (key == "accel") accel else ReadingMetrics.series(key, readings, profile)
            val stats = paramStats(key, all, trip, ReadingMetrics.seriesMoving(key, readings)) ?: return null
            val times = ReadingMetrics.seriesTimes(key, readings)
            return ParamSummary(
                key = key,
                title = MetricNames.of(key),
                unit = unit,
                min = stats.min,
                avg = stats.avg,
                max = stats.max,
                movingAvg = stats.movingAvg,
                series = all.filterIndexed { i, _ -> i % step == 0 },
                times = times.filterIndexed { i, _ -> i % step == 0 },
            )
        }

        // Остановки: ряд 0/1. Прореживаем по максимуму в окне, а не каждой N-й
        // точкой — иначе короткие остановки между выбранными точками пропадут.
        fun stopsSummary(): ParamSummary? {
            val stops = TripMath.stops(readings)
            if (stops.isEmpty()) return null
            val series = TripMath.stopSeries(readings)
            return ParamSummary(
                key = "stops",
                title = MetricNames.of("stops"),
                unit = "",
                min = 0f,
                avg = 0f,
                max = 0f,
                series = series.chunked(step) { it.max() },
                times = readings.map { it.timestamp }.chunked(step) { it.first() },
                stats = stopStats(stops),
            )
        }

        return listOfNotNull(
            summary("speed", "км/ч"),
            stopsSummary(),
            summary("rpm", "об/мин"),
            summary("power", "л.с."),
            summary("torque", "Н·м"),
            summary("accel", "м/с²"),
            summary("fuel", "л/100 км"),
            summary("load", "%"),
            summary("throttle", "%"),
            summary("vacuum", "кПа"),
            summary("warmup", "%"),
            summary("coolant", "°C"),
            summary("battery", "В"),
            summary("intake", "°C"),
            summary("map", "кПа"),
            summary("timing", "°"),
            summary("oil", "°C"),
            summary("stft", "%"),
            summary("ltft", "%"),
            summary("fuellevel", "%"),
            summary("maf", "г/с"),
            summary("distmil", "км"),
            summary("distclear", "км"),
        )
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            tripRepo.deleteTrip(tripId)
            onDone()
        }
    }
}
