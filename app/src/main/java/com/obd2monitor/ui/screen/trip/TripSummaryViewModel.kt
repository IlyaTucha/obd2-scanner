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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
        val points = readings.mapNotNull { r ->
            val lat = r.latitude; val lon = r.longitude
            if (lat != null && lon != null) GeoPoint(lat, lon, r.timestamp) else null
        }
        val params = buildParameters(readings, trip, profile)
        val cost = if (trip != null && profile != null && profile.fuelPrice > 0f && trip.fuelUsedLiters > 0f) {
            trip.fuelUsedLiters * profile.fuelPrice
        } else null
        val accelSeries = computeAccelSeries(readings)
        val peakAccel = accelSeries.maxOrNull()?.takeIf { it > 0.05f }
        val peakBrake = accelSeries.minOrNull()?.takeIf { it < -0.05f }
        _state.value = TripSummaryUiState(
            trip = trip,
            routePoints = points,
            parameters = params,
            loading = false,
            tripCostRub = cost,
            peakAccelMs2 = peakAccel,
            peakBrakeMs2 = peakBrake,
        )
    }

    private fun computeAccelSeries(readings: List<OBDReading>): List<Float> {
        if (readings.size < 2) return emptyList()
        return readings.indices.map { i ->
            val start = (i - 4).coerceAtLeast(0)
            val first = readings[start]
            val last = readings[i]
            val dts = (last.timestamp - first.timestamp) / 1000f
            if (dts <= 0.05f) {
                0f
            } else {
                val dvMs = (last.speedKmh - first.speedKmh) / 3.6f
                dvMs / dts
            }
        }
    }

    private fun buildParameters(
        readings: List<OBDReading>,
        trip: Trip?,
        profile: VehicleProfile?,
    ): List<ParamSummary> {
        if (readings.isEmpty()) return emptyList()
        // Прореживаем для графиков: не более ~120 точек, чтобы не тормозить рендер.
        val step = (readings.size / 120).coerceAtLeast(1)
        val sampled = readings.filterIndexed { i, _ -> i % step == 0 }

        fun summary(
            key: String,
            title: String,
            unit: String,
            avgOverride: Float? = null,
            sel: (OBDReading) -> Float,
        ): ParamSummary? {
            val values = readings.map(sel).filter { it.isFinite() }
            if (values.isEmpty()) return null
            val nonZero = values.filter { it != 0f }
            // Если параметр всегда был 0 (PID не отдавался) — не показываем.
            if (nonZero.isEmpty()) return null
            val series = sampled.map(sel)
            return ParamSummary(
                key = key,
                title = title,
                unit = unit,
                min = values.min(),
                avg = avgOverride ?: values.average().toFloat(),
                max = values.max(),
                series = series,
            )
        }

        // ── Расчётные метрики из сохранённых PID. Важно: считаем КАЖДЫЙ раз
        // здесь же, чтобы не плодить колонки в OBDReadingEntity. Формулы те же,
        // что в OBDRepositoryImpl.readSnapshot — для согласованности.
        val maxTorqueNm = (profile?.maxTorqueNm ?: 130).toFloat()
        val maxPowerKw = (profile?.maxPowerKw ?: 70).toFloat()
        val coolantTargetC = (profile?.normalCoolantTempC?.first ?: 85).toFloat()
        val accelValues = computeAccelSeries(readings)

        fun powerKwOf(r: OBDReading): Float = when {
            r.mafGs > 0f -> (r.mafGs * 0.88f).coerceIn(0f, maxPowerKw * 1.1f)
            r.engineLoadPercent > 0f && r.rpm > 0f -> {
                val tq = r.engineLoadPercent / 100f * maxTorqueNm
                (tq * r.rpm / 9549f).coerceIn(0f, maxPowerKw * 1.1f)
            }
            else -> 0f
        }

        fun powerHpOf(r: OBDReading): Float = powerKwOf(r) * 1.35962f

        fun torqueOf(r: OBDReading): Float {
            val p = powerKwOf(r)
            return if (p > 0f && r.rpm > 200f) (p * 9549f / r.rpm).coerceIn(0f, maxTorqueNm * 1.2f) else 0f
        }

        fun vacuumOf(r: OBDReading): Float =
            if (r.mapKpa > 0f) (101f - r.mapKpa).coerceAtLeast(0f) else 0f

        fun warmupOf(r: OBDReading): Float = if (r.coolantTempC > 0f) {
            ((r.coolantTempC - 20f) / (coolantTargetC - 20f) * 100f).coerceIn(0f, 100f)
        } else 0f

        // СР. в карточке «Скорость» используем то же значение, что и в верхней сводке
        // (distance / wall-time из Trip). Иначе они расходились: верхняя СР. — distance/duration,
        // а в карточке — арифметическое среднее samples (включая стоянки), и значения отличались.
        // Аналогично для «Расход»: верхний блок ТОПЛИВО показывает avgFuelConsumption
        // (= total_liters / distance × 100, корректно интегрированный), а арифметическое среднее
        // мгновенных л/100км по точкам было заметно меньше из-за нулей на простоях.
        return listOfNotNull(
            // Самые наглядные «живые» метрики первыми.
            summary("speed", "Скорость", "км/ч", avgOverride = trip?.avgSpeedKmh) { it.speedKmh },
            summary("rpm", "Обороты", "об/мин") { it.rpm },
            summary("power", "Мощность", "л.с.", sel = ::powerHpOf),
            summary("torque", "Крутящий момент", "Нм", sel = ::torqueOf),
            accelSummary(readings, sampled, accelValues),
            summary("fuel", "Расход", "л/100км", avgOverride = trip?.avgFuelConsumption) {
                // Без clamp — показываем сырое значение. Avg остаётся честным
                // (avgOverride ← trip.avgFuelConsumption из integrate, а не арифметика).
                it.instantFuelLPer100
            },
            summary("load", "Усилие двигателя", "%") { it.engineLoadPercent },
            summary("throttle", "Дроссель", "%") { it.throttlePercent },
            summary("vacuum", "Вакуум впуска", "кПа", sel = ::vacuumOf),
            summary("warmup", "Прогрев", "%", sel = ::warmupOf),
            // Сырые PID
            summary("coolant", "Темп. ОЖ", "°C") { it.coolantTempC },
            summary("battery", "Бортсеть", "В") { it.batteryVoltage },
            summary("intake", "Темп. впуска", "°C") { it.intakeTempC },
            summary("map", "Давление впуска", "кПа") { it.mapKpa },
            summary("timing", "Угол зажигания", "°") { it.ignitionTimingDeg },
            summary("oil", "Темп. масла", "°C") { it.oilTempC },
            summary("stft", "Краткая коррекция", "%") { it.shortFuelTrim1Pct },
            summary("ltft", "Долгая коррекция", "%") { it.longFuelTrim1Pct },
            summary("fuellevel", "Уровень бака", "%") { it.fuelLevelPercent },
            summary("maf", "MAF", "г/с") { it.mafGs },
            summary("distmil", "Пробег с Check Engine", "км") { it.distanceMilKm },
            summary("distclear", "Пробег после сброса", "км") { it.distanceSinceClearKm },
        )
    }

    private fun accelSummary(
        readings: List<OBDReading>,
        sampled: List<OBDReading>,
        accelValues: List<Float>,
    ): ParamSummary? {
        val values = accelValues.filter { it.isFinite() }
        if (values.none { it != 0f }) return null
        val sampledValues = sampled.map { reading ->
            accelValues.getOrElse(readings.indexOf(reading)) { 0f }
        }
        return ParamSummary(
            key = "accel",
            title = "Ускорение",
            unit = "м/с²",
            min = values.min(),
            avg = values.average().toFloat(),
            max = values.max(),
            series = sampledValues,
        )
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            tripRepo.deleteTrip(tripId)
            onDone()
        }
    }
}
