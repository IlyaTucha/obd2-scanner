package com.obd2monitor.data.obd

import com.obd2monitor.domain.model.FuelType
import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.model.VehicleProfile

/**
 * Расчётные показатели по сохранённому замеру. Одни и те же формулы для дашборда,
 * отчёта сессии и детальных графиков — те же, что OBDRepositoryImpl.readSnapshot
 * считает на лету. Раньше они были скопированы в три ViewModel и репозиторий.
 */
object ReadingMetrics {

    const val KW_TO_HP = 1.35962f

    /** Мощность, кВт: из MAF (если есть), иначе нагрузка × пиковый момент × обороты. */
    fun powerKw(r: OBDReading, profile: VehicleProfile?): Float {
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

    fun torqueNm(r: OBDReading, profile: VehicleProfile?): Float {
        val p = powerKw(r, profile)
        val maxTorqueNm = (profile?.maxTorqueNm ?: 130).toFloat()
        return if (p > 0f && r.rpm > 200f) (p * 9549f / r.rpm).coerceIn(0f, maxTorqueNm * 1.2f) else 0f
    }

    fun vacuumKpa(r: OBDReading): Float = if (r.mapKpa > 0f) (101f - r.mapKpa).coerceAtLeast(0f) else 0f

    fun warmupPct(r: OBDReading, profile: VehicleProfile?): Float {
        val target = (profile?.normalCoolantTempC?.first ?: 85).toFloat()
        return if (r.coolantTempC > 0f) ((r.coolantTempC - 20f) / (target - 20f) * 100f).coerceIn(0f, 100f) else 0f
    }

    /** Мгновенный расход, л/ч: MAF → MAP+RPM → оценка холостого хода по объёму. */
    fun fuelRateLh(r: OBDReading, profile: VehicleProfile?): Float {
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

    /** Значение параметра [key] в замере; null — ключ не относится к сохраняемым замерам. */
    fun value(key: String, r: OBDReading, profile: VehicleProfile?): Float? = when (key) {
        "speed" -> r.speedKmh
        "rpm" -> r.rpm
        "coolant" -> r.coolantTempC
        "intake" -> r.intakeTempC
        "battery" -> r.batteryVoltage
        "throttle" -> r.throttlePercent
        "load" -> r.engineLoadPercent
        "fuel" -> r.instantFuelLPer100
        "fuelh" -> fuelRateLh(r, profile)
        "map" -> r.mapKpa
        "timing" -> r.ignitionTimingDeg
        "oil" -> r.oilTempC
        "stft" -> r.shortFuelTrim1Pct
        "ltft" -> r.longFuelTrim1Pct
        "fuellevel" -> r.fuelLevelPercent
        "maf" -> r.mafGs
        "distmil" -> r.distanceMilKm
        "distclear" -> r.distanceSinceClearKm
        "power" -> powerKw(r, profile) * KW_TO_HP
        "torque" -> torqueNm(r, profile)
        "vacuum" -> vacuumKpa(r)
        "warmup" -> warmupPct(r, profile)
        else -> null
    }

    /** Ряд значений [key] по замерам поездки. Ускорение считается по соседним точкам. */
    fun series(key: String, readings: List<OBDReading>, profile: VehicleProfile?): List<Float> =
        if (key == "accel") TripMath.accelSeries(readings)
        else readings.map { value(key, it, profile) ?: 0f }
}

/** Общая математика поездки: пробег, движение, ускорение. */
object TripMath {
    /** Разрыв между соседними замерами больше этого — пауза записи, а не движение.
     *  Без отсечки пауза «на скорости 60» превращалась в десятки фантомных километров. */
    const val MAX_GAP_MS = 60_000L
    /** Ниже этой скорости машина считается стоящей. */
    const val MOVING_THRESHOLD_KMH = 3f

    /** Длительность отрезка в часах; 0 — если замеры не соседние (разрыв/обратный порядок). */
    fun segmentHours(prevTs: Long, ts: Long): Float {
        val dt = ts - prevTs
        return if (dt <= 0L || dt > MAX_GAP_MS) 0f else dt / 3_600_000f
    }

    fun distanceKm(readings: List<OBDReading>): Float {
        var km = 0f
        for (i in 1 until readings.size) {
            val h = segmentHours(readings[i - 1].timestamp, readings[i].timestamp)
            km += (readings[i - 1].speedKmh + readings[i].speedKmh) / 2f * h
        }
        return km
    }

    /** Ускорение (м/с²) в каждой точке по окну из 5 последних замеров. */
    fun accelSeries(readings: List<OBDReading>): List<Float> {
        if (readings.size < 2) return emptyList()
        return readings.indices.map { i -> accelAt(readings, (i - 4).coerceAtLeast(0), i) }
    }

    fun accelAt(readings: List<OBDReading>, from: Int, to: Int): Float {
        val first = readings[from]
        val last = readings[to]
        val dts = (last.timestamp - first.timestamp) / 1000f
        if (dts <= 0.05f || dts > MAX_GAP_MS / 1000f) return 0f
        return (last.speedKmh - first.speedKmh) / 3.6f / dts
    }
}
