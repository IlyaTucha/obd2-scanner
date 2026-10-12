package com.obd2monitor.data.obd

import com.obd2monitor.domain.model.FuelType

/**
 * Расчёт мгновенного и суммарного расхода (ТЗ §5.3, §12).
 *
 * Метод 1 (MAF):     L/h = MAF * 3600 / (AFR * density)
 * Метод 2 (MAP/RPM): L/h ≈ (MAP * RPM * VE * displacement) / (R * T * AFR * density)
 * Метод 3 (PID 5E):  L/h = (256A + B) * 0.05  — прямой
 */
object FuelCalculator {

    private const val DEFAULT_VE = 0.85f         // объёмная эффективность
    private const val MAX_REASONABLE_L_PER_100 = 300f

    /** Метод 1: расход в л/ч из MAF (г/с) и характеристик топлива. */
    fun fromMAF(mafGs: Float, fuel: FuelType): Float {
        if (mafGs <= 0f) return 0f
        return mafGs * 3600f / (fuel.afr * fuel.density)
    }

    /**
     * Метод 2: расход в л/ч из MAP (кПа), RPM, температуры впуска (°C), объёма двигателя (л).
     * Используется только если MAF не доступен.
     */
    fun fromMAP(
        mapKpa: Float,
        rpm: Float,
        intakeTempC: Float,
        displacementL: Float,
        fuel: FuelType,
        ve: Float = DEFAULT_VE,
    ): Float {
        if (mapKpa <= 0f || rpm <= 0f || displacementL <= 0f) return 0f
        val tempK = intakeTempC + 273.15f
        // Массовый расход воздуха (г/с) ≈ (MAP_Pa * RPM/60/2 * VE * V_m3) / (R_specific * T)
        // R_specific для воздуха ≈ 287 Дж/(кг·К)
        val mapPa = mapKpa * 1000f
        val volumePerSec = (rpm / 60f / 2f) * (displacementL / 1000f) * ve   // м³/с
        val airMassGs = (mapPa * volumePerSec) / (287f * tempK) * 1000f     // г/с
        return fromMAF(airMassGs, fuel)
    }

    fun estimateIdleLitersPerHour(displacementL: Float, rpm: Float): Float {
        if (displacementL <= 0f || rpm <= 0f) return 0f
        return (displacementL * 0.45f * (rpm / 800f)).coerceIn(0.15f, 4f)
    }

    /** Перевод л/ч в л/100км. При нулевой скорости возвращает 0.
     *  Никаких clamp — отдаём реальную математику. Артефакты при околонулевой скорости
     *  (200+ л/100км) — это правда: «если бы я ехал так 100 км, ушло бы 200 литров».
     *  Бессмысленно физически (никто 100 км со скоростью 1 км/ч не едет), но честно. */
    fun toLPer100Km(litersPerHour: Float, speedKmh: Float): Float {
        if (speedKmh <= 0.5f) return 0f
        return litersPerHour / speedKmh * 100f
    }

    /** Совместимость со старыми вызовами — clamp убран, просто прокси через identity. */
    fun displayConsumption(rawLPer100: Float?): Float? = rawLPer100

    /**
     * Трапециевидное интегрирование расхода за поездку (ТЗ §12).
     * timestamps в мс, consumption в л/100км, speed в км/ч.
     */
    fun integrateTotalLiters(
        timestamps: LongArray,
        consumptionLPer100: FloatArray,
        speedsKmh: FloatArray,
        fuelRateLPerHour: FloatArray? = null,
    ): Float {
        require(timestamps.size == consumptionLPer100.size && timestamps.size == speedsKmh.size)
        require(fuelRateLPerHour == null || fuelRateLPerHour.size == timestamps.size)
        if (timestamps.size < 2) return 0f
        var total = 0f
        for (i in 1 until timestamps.size) {
            val dtH = TripMath.segmentHours(timestamps[i - 1], timestamps[i])
            if (dtH <= 0f) continue
            val c0 = normalizedConsumption(consumptionLPer100[i - 1])
            val c1 = normalizedConsumption(consumptionLPer100[i])
            val avgCons = (c0 + c1) / 2f
            val avgSpd = (speedsKmh[i] + speedsKmh[i - 1]) / 2f
            if (fuelRateLPerHour != null) {
                val lh0 = normalizedFuelRate(fuelRateLPerHour[i - 1])
                val lh1 = normalizedFuelRate(fuelRateLPerHour[i])
                val avgLh = (lh0 + lh1) / 2f
                if (avgLh > 0f) {
                    total += avgLh * dtH
                } else if (avgSpd > 0.5f && avgCons > 0f) {
                    total += avgCons / 100f * avgSpd * dtH
                }
            } else if (avgSpd > 0.5f && avgCons > 0f) {
                total += avgCons / 100f * avgSpd * dtH
            }
        }
        return total
    }

    private fun normalizedConsumption(value: Float): Float = when {
        !value.isFinite() -> 0f
        value < 0f -> 0f
        value > MAX_REASONABLE_L_PER_100 -> 0f
        else -> value
    }

    private fun normalizedFuelRate(value: Float): Float = when {
        !value.isFinite() -> 0f
        value < 0f -> 0f
        value > 80f -> 0f
        else -> value
    }
}
