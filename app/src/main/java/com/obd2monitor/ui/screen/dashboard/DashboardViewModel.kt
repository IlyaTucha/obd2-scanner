package com.obd2monitor.ui.screen.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.data.obd.FuelCalculator
import kotlinx.coroutines.launch
import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.domain.model.FuelType
import com.obd2monitor.domain.model.OBDData
import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
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
import javax.inject.Inject

/** Метрика для sparkline-карточки: ключ + история значений.
 *  Хранится in-memory вся история текущей сессии приложения (capacity 2000 ≈ 17 минут при 0.5с поллинге).
 *  Полная история сессии (на детальном экране и в отчёте) подгружается из БД. */
data class MetricHistory(val key: String, val values: List<Float> = emptyList()) {
    fun add(v: Float?, capacity: Int = 2000): MetricHistory {
        if (v == null || !v.isFinite()) return this
        val updated = (values + v).takeLast(capacity)
        return copy(values = updated)
    }
}

data class DashboardUiState(
    val data: OBDData = OBDData(),
    val connection: ConnectionStatus = ConnectionStatus(),
    val activeProfile: VehicleProfile? = null,
    val histories: Map<String, MetricHistory> = emptyMap(),
    val tripReadings: List<OBDReading> = emptyList(),
    /** Литры, потраченные в текущей активной сессии (трапециевидное интегрирование). 0 если сессии нет. */
    val tripFuelLiters: Float = 0f,
    /** Средний расход за сессию, л/100км = total_liters / distance × 100 (как в отчёте сессии).
     *  null если данных мало (поездка только началась) или distance < 0.1 км. */
    val tripAvgConsumption: Float? = null,
    /** Запас хода до пустого бака, км. null если уровень бака не известен или расход 0. */
    val rangeKm: Float? = null,
    /** Стоимость топлива за активную сессию, ₽. null если в профиле fuelPrice = 0. */
    val tripCostRub: Float? = null,
    /** Мгновенная стоимость на 100 км, ₽/100км. null если профиль без fuelPrice. */
    val costPer100Rub: Float? = null,
    /** Мгновенное ускорение, м/с² (производная скорости). 0 если данных мало. */
    val accelMs2: Float = 0f,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val obdRepo: OBDRepository,
    private val vehicleRepo: VehicleProfileRepository,
    private val tripRepo: TripRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    val connection: StateFlow<ConnectionStatus> = obdRepo.connectionStatus.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus()
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tripReadingsFlow = tripRepo.observeActiveTrip().flatMapLatest { trip ->
        if (trip == null) flowOf(emptyList()) else tripRepo.observeReadings(trip.id)
    }

    init {
        obdRepo.liveData.onEach(::onLive).launchIn(viewModelScope)
        vehicleRepo.observeActive().onEach { profile ->
            _state.value = _state.value.copy(activeProfile = profile)
            // При смене профиля пересчитываем session-метрики (изменился fuelPrice/tank).
            recomputeSessionMetrics(profile = profile)
        }.launchIn(viewModelScope)
        obdRepo.connectionStatus.onEach { status ->
            _state.value = _state.value.copy(connection = status)
        }.launchIn(viewModelScope)
        tripReadingsFlow.onEach { readings ->
            recomputeSessionMetrics(readings = readings)
        }.launchIn(viewModelScope)
    }

    /** Пересчёт всех session-level метрик. Вызывается при новых readings или live-данных. */
    private fun recomputeSessionMetrics(
        readings: List<OBDReading> = _state.value.tripReadings,
        live: OBDData = _state.value.data,
        profile: VehicleProfile? = _state.value.activeProfile,
    ) {
        val liters = computeTripLiters(readings)
        val price = profile?.fuelPrice ?: 0f
        val tank = profile?.tankCapacity ?: 0f

        // Запас хода: уровень_бака × объём / средний расход. Средний берём через
        // total_liters / distance × 100 (как в Trip), иначе fallback — мгновенный.
        val avgCons = computeAvgConsumption(readings, totalLiters = liters)
            ?: live.instantFuelLPer100?.takeIf { it > 0.5f }
        val rangeKm: Float? = if (tank > 0f && live.fuelLevelPercent != null && avgCons != null && avgCons > 0.5f) {
            val tankLeft = live.fuelLevelPercent / 100f * tank
            (tankLeft / avgCons * 100f).coerceAtLeast(0f)
        } else null

        val tripCost: Float? = if (price > 0f && liters > 0.001f) liters * price else null
        val costPer100: Float? = FuelCalculator.displayConsumption(live.instantFuelLPer100)
            ?.takeIf { price > 0f && it > 0f }
            ?.let { it * price }

        val accel = computeAccelMs2(readings)
        val histories = _state.value.histories.toMutableMap()
        fun upd(key: String, value: Float?) {
            val existing = histories[key] ?: MetricHistory(key)
            histories[key] = existing.add(value)
        }
        upd("range", rangeKm)
        upd("tripcost", tripCost)
        upd("cost100", costPer100)
        upd("accel", accel)

        _state.value = _state.value.copy(
            tripReadings = readings,
            tripFuelLiters = liters,
            tripAvgConsumption = computeAvgConsumption(readings, totalLiters = liters),
            rangeKm = rangeKm,
            tripCostRub = tripCost,
            costPer100Rub = costPer100,
            accelMs2 = accel,
            histories = histories,
        )
    }

    /** Интегрируем мгновенный расход (л/100км) × скорость(км/ч) × dt → литры за сессию. */
    private fun computeTripLiters(readings: List<OBDReading>): Float {
        if (readings.size < 2) return 0f
        return FuelCalculator.integrateTotalLiters(
            timestamps = LongArray(readings.size) { readings[it].timestamp },
            consumptionLPer100 = FloatArray(readings.size) { readings[it].instantFuelLPer100 },
            speedsKmh = FloatArray(readings.size) { readings[it].speedKmh },
            fuelRateLPerHour = FloatArray(readings.size) { fuelRateLhOf(readings[it], _state.value.activeProfile) },
        )
    }

    /** Средний расход за сессию: total_liters / distance × 100. Совпадает с Trip.avgFuelConsumption. */
    private fun computeAvgConsumption(readings: List<OBDReading>, totalLiters: Float): Float? {
        if (readings.size < 2 || totalLiters <= 0.001f) return null
        var distKm = 0f
        for (i in 1 until readings.size) {
            val dtH = (readings[i].timestamp - readings[i - 1].timestamp) / 3_600_000f
            if (dtH <= 0f || dtH > 0.1f) continue
            val avgSpd = (readings[i].speedKmh + readings[i - 1].speedKmh) / 2f
            distKm += avgSpd * dtH
        }
        if (distKm < 0.1f) return null
        return totalLiters / distKm * 100f
    }

    /** Среднее ускорение по последним 5 точкам поллинга. Возвращает м/с². */
    private fun computeAccelMs2(readings: List<OBDReading>): Float {
        if (readings.size < 2) return 0f
        val tail = readings.takeLast(5)
        if (tail.size < 2) return 0f
        val first = tail.first()
        val last = tail.last()
        val dts = (last.timestamp - first.timestamp) / 1000f
        if (dts <= 0.05f) return 0f
        val dvMs = (last.speedKmh - first.speedKmh) / 3.6f
        return dvMs / dts
    }

    /** Полная история значений ключа за текущую сессию, из БД (или fallback на in-memory). */
    fun fullHistory(key: String): List<Float> {
        val readings = _state.value.tripReadings
        if (readings.isNotEmpty()) {
            val profile = _state.value.activeProfile
            val series = readings.map { r ->
                when (key) {
                    "speed" -> r.speedKmh
                    "rpm" -> r.rpm
                    "coolant" -> r.coolantTempC
                    "intake" -> r.intakeTempC
                    "battery" -> r.batteryVoltage
                    "throttle" -> r.throttlePercent
                    "load" -> r.engineLoadPercent
                    "fuel" -> r.instantFuelLPer100
                    "fuelh" -> fuelRateLhOf(r, profile)
                    "map" -> r.mapKpa
                    "timing" -> r.ignitionTimingDeg
                    "oil" -> r.oilTempC
                    "stft" -> r.shortFuelTrim1Pct
                    "ltft" -> r.longFuelTrim1Pct
                    "fuellevel" -> r.fuelLevelPercent
                    "distmil" -> r.distanceMilKm
                    "distclear" -> r.distanceSinceClearKm
                    "maf" -> r.mafGs
                    "power" -> powerHpOf(r, profile)
                    "torque" -> torqueOf(r, profile)
                    "vacuum" -> vacuumOf(r)
                    "warmup" -> warmupOf(r, profile)
                    else -> 0f
                }
            }
            if (series.any { it != 0f && it.isFinite() }) return series
            if (key == "accel") return accelSeries(readings)
        }
        return _state.value.histories[key]?.values.orEmpty()
    }

    private fun onLive(data: OBDData) {
        val current = _state.value
        val histories = current.histories.toMutableMap()
        fun upd(key: String, value: Float?) {
            val existing = histories[key] ?: MetricHistory(key)
            histories[key] = existing.add(value)
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
        upd("power", data.powerKw?.let(::kwToHp))
        upd("torque", data.torqueNm)
        upd("vacuum", data.vacuumKpa)
        upd("warmup", data.warmupPct)
        upd("cutoff", if (data.fuelCutoffActive) 1f else 0f)
        _state.value = current.copy(data = data, histories = histories)
        recomputeSessionMetrics(live = data)
    }

    fun resetSession() {
        _state.value = _state.value.copy(histories = emptyMap())
    }

    /** Принудительное переподключение к OBD по той же кнопке слева от статуса.
     *  Делегируем репозиторию: он держит deviceAddress и инвариант «реконнект не
     *  закрывает trip» (см. OBDRepositoryImpl.reconnect). */
    fun reconnect() {
        viewModelScope.launch { obdRepo.reconnect() }
    }

    private fun kwToHp(kw: Float): Float = kw * 1.35962f

    private fun powerKwOf(r: OBDReading, profile: VehicleProfile?): Float {
        val maxTorqueNm = (profile?.maxTorqueNm ?: 130).toFloat()
        val maxPowerKw = (profile?.maxPowerKw ?: 70).toFloat()
        return when {
            r.mafGs > 0f -> (r.mafGs * 0.88f).coerceIn(0f, maxPowerKw * 1.1f)
            r.engineLoadPercent > 0f && r.rpm > 0f -> {
                val tq = r.engineLoadPercent / 100f * maxTorqueNm
                (tq * r.rpm / 9549f).coerceIn(0f, maxPowerKw * 1.1f)
            }
            else -> 0f
        }
    }

    private fun powerHpOf(r: OBDReading, profile: VehicleProfile?): Float = kwToHp(powerKwOf(r, profile))

    private fun torqueOf(r: OBDReading, profile: VehicleProfile?): Float {
        val p = powerKwOf(r, profile)
        val maxTorqueNm = (profile?.maxTorqueNm ?: 130).toFloat()
        return if (p > 0f && r.rpm > 200f) (p * 9549f / r.rpm).coerceIn(0f, maxTorqueNm * 1.2f) else 0f
    }

    private fun vacuumOf(r: OBDReading): Float = if (r.mapKpa > 0f) (101f - r.mapKpa).coerceAtLeast(0f) else 0f

    private fun warmupOf(r: OBDReading, profile: VehicleProfile?): Float {
        val target = (profile?.normalCoolantTempC?.first ?: 85).toFloat()
        return if (r.coolantTempC > 0f) ((r.coolantTempC - 20f) / (target - 20f) * 100f).coerceIn(0f, 100f) else 0f
    }

    private fun fuelRateLhOf(r: OBDReading, profile: VehicleProfile?): Float {
        val fuel = profile?.fuelType ?: FuelType.PETROL
        val displacementL = profile?.displacementL ?: 1.5f
        return when {
            r.mafGs > 0f -> FuelCalculator.fromMAF(r.mafGs, fuel)
            r.mapKpa > 0f && r.rpm > 0f && r.intakeTempC != 0f ->
                FuelCalculator.fromMAP(r.mapKpa, r.rpm, r.intakeTempC, displacementL, fuel)
            r.rpm > 0f -> FuelCalculator.estimateIdleLitersPerHour(displacementL, r.rpm)
            else -> 0f
        }
    }

    private fun accelSeries(readings: List<OBDReading>): List<Float> {
        if (readings.size < 2) return emptyList()
        return readings.indices.map { i ->
            val start = (i - 4).coerceAtLeast(0)
            val first = readings[start]
            val last = readings[i]
            val dts = (last.timestamp - first.timestamp) / 1000f
            if (dts <= 0.05f) 0f else ((last.speedKmh - first.speedKmh) / 3.6f) / dts
        }
    }
}
