package com.obd2monitor.ui.screen.trip

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.data.obd.ReadingMetrics
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
    val min: Float? = null,
    val max: Float? = null,
    val avg: Float? = null,
    val unit: String = "",
    val profile: VehicleProfile? = null,
    val loading: Boolean = true,
)

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
        val nonZero = series.filter { it.isFinite() && it != 0f }
        val min = nonZero.minOrNull()
        val max = nonZero.maxOrNull()
        // Для расхода берём итоговый Trip.avgFuelConsumption (литры / дистанцию × 100),
        // а не арифметическое среднее по мгновенным samples. Иначе всплески л/100км
        // на низкой скорости перекосят результат и он не сойдётся с цифрой в отчёте.
        val avg = when {
            metricKey == "fuel" && trip != null -> trip.avgFuelConsumption.takeIf { it > 0f }
            nonZero.isNotEmpty() -> nonZero.average().toFloat()
            else -> null
        }
        _state.value = TripParameterDetailUiState(
            series = series,
            min = min,
            max = max,
            avg = avg,
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
