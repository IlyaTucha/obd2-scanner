package com.obd2monitor.ui.screen.trip

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.data.obd.ReadingMetrics
import com.obd2monitor.data.obd.TripMath
import com.obd2monitor.domain.model.GeoPoint
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TripParameterDetailUiState(
    val series: List<Float> = emptyList(),
    val times: List<Long> = emptyList(),
    val min: Float? = null,
    val max: Float? = null,
    val avg: Float? = null,
    val movingAvg: Float? = null,
    val unit: String = "",
    val profile: VehicleProfile? = null,
    val loading: Boolean = true,
    /** Только для «Остановок»: список и сводка вместо мин/сред/макс. */
    val stops: List<TripMath.Stop> = emptyList(),
    val stats: List<Pair<String, String>>? = null,
    /** Маршрут поездки (по времени) — для карты «где это было». */
    val route: List<GeoPoint> = emptyList(),
) {
    /** Ближайшая по времени точка GPS к моменту [ms]; null — рядом координат нет. */
    fun locationAt(ms: Long): GeoPoint? {
        if (route.isEmpty()) return null
        var lo = 0
        var hi = route.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (route[mid].timestamp < ms) lo = mid + 1 else hi = mid
        }
        val best = listOfNotNull(route.getOrNull(lo - 1), route[lo]).minBy { kotlin.math.abs(it.timestamp - ms) }
        return best.takeIf { kotlin.math.abs(it.timestamp - ms) <= MAX_LOCATION_GAP_MS }
    }

    private companion object {
        /** Дальше этого по времени точка GPS уже не «там, где это было». */
        const val MAX_LOCATION_GAP_MS = 60_000L
    }
}

/**
 * Подгружает все readings для конкретной поездки и считает full-resolution серию
 * для запрошенного key (тот же набор формул, что в TripSummaryViewModel.buildParameters).
 * Используется на детальном экране параметра из истории поездок.
 */
@HiltViewModel
class TripParameterDetailViewModel @Inject constructor(
    private val tripRepo: TripRepository,
    private val vehicleRepo: VehicleProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val tripId: String = checkNotNull(savedStateHandle["tripId"])
    val metricKey: String = checkNotNull(savedStateHandle["key"])

    private val _state = MutableStateFlow(TripParameterDetailUiState())
    val state: StateFlow<TripParameterDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val trip = tripRepo.getTrip(tripId)
        val readings = tripRepo.readingsFor(tripId)
        val profile = trip?.vehicleProfileId?.let { vehicleRepo.getById(it) }
        val series = withContext(Dispatchers.Default) { ReadingMetrics.series(metricKey, readings, profile) }
        val unit = unitFor(metricKey)
        // Тот же расчёт, что в итогах сессии: цифры на двух экранах должны совпадать.
        val stats = paramStats(metricKey, series, trip, ReadingMetrics.seriesMoving(metricKey, readings))
        val stops = if (metricKey == "stops") TripMath.stops(readings) else emptyList()
        _state.value = TripParameterDetailUiState(
            stops = stops,
            stats = if (metricKey == "stops") stopStats(stops) else null,
            route = readings.mapNotNull { r ->
                val lat = r.latitude; val lon = r.longitude
                if (lat != null && lon != null) GeoPoint(lat, lon, r.timestamp) else null
            },
            series = series,
            times = ReadingMetrics.seriesTimes(metricKey, readings),
            min = stats?.min,
            max = stats?.max,
            avg = stats?.avg,
            movingAvg = stats?.movingAvg,
            unit = unit,
            profile = profile,
            loading = false,
        )
    }

    private fun unitFor(key: String): String = when (key) {
        "speed" -> "км/ч"
        "rpm" -> "об/мин"
        "coolant", "intake", "oil" -> "°C"
        "battery" -> "В"
        "throttle", "load", "fuellevel", "warmup" -> "%"
        "fuel" -> "л/100км"
        "map", "vacuum" -> "кПа"
        "timing" -> "°"
        "stft", "ltft" -> "%"
        "maf" -> "г/с"
        "distmil", "distclear" -> "км"
        "power" -> "л.с."
        "torque" -> "Н·м"
        "accel" -> "м/с²"
        else -> ""
    }
}
