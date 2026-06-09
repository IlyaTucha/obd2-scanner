package com.obd2monitor.ui.screen.trip

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
        val series = computeSeries(readings, metricKey, profile)
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

    private fun computeSeries(
        readings: List<OBDReading>,
        key: String,
        profile: VehicleProfile?,
    ): List<Float> {
        if (readings.isEmpty()) return emptyList()
        val maxTorqueNm = (profile?.maxTorqueNm ?: 130).toFloat()
        val maxPowerKw = (profile?.maxPowerKw ?: 70).toFloat()
        val coolantTargetC = (profile?.normalCoolantTempC?.first ?: 85).toFloat()

        fun powerKwOf(r: OBDReading): Float = when {
            r.mafGs > 0f -> (r.mafGs * 0.88f).coerceIn(0f, maxPowerKw * 1.1f)
            r.engineLoadPercent > 0f && r.rpm > 0f -> {
                val tq = r.engineLoadPercent / 100f * maxTorqueNm
                (tq * r.rpm / 9549f).coerceIn(0f, maxPowerKw * 1.1f)
            }
            else -> 0f
        }

        return readings.map { r ->
            when (key) {
                "speed" -> r.speedKmh
                "rpm" -> r.rpm
                "coolant" -> r.coolantTempC
                "intake" -> r.intakeTempC
                "battery" -> r.batteryVoltage
                "throttle" -> r.throttlePercent
                "load" -> r.engineLoadPercent
                "fuel" -> r.instantFuelLPer100
                "map" -> r.mapKpa
                "timing" -> r.ignitionTimingDeg
                "oil" -> r.oilTempC
                "stft" -> r.shortFuelTrim1Pct
                "ltft" -> r.longFuelTrim1Pct
                "fuellevel" -> r.fuelLevelPercent
                "maf" -> r.mafGs
                "distmil" -> r.distanceMilKm
                "distclear" -> r.distanceSinceClearKm
                // В UI показываем л.с. — это привычная единица. Внутри хранится
                // и считается в кВт (SI), конверсия 1 кВт = 1.35962 л.с.
                "power" -> powerKwOf(r) * 1.35962f
                "torque" -> {
                    val p = powerKwOf(r)
                    if (p > 0f && r.rpm > 200f) p * 9549f / r.rpm else 0f
                }
                "vacuum" -> if (r.mapKpa > 0f) (101f - r.mapKpa).coerceAtLeast(0f) else 0f
                "warmup" -> if (r.coolantTempC > 0f) {
                    ((r.coolantTempC - 20f) / (coolantTargetC - 20f) * 100f).coerceIn(0f, 100f)
                } else 0f
                else -> 0f
            }
        }
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
        "torque" -> "Нм"
        else -> ""
    }
}
