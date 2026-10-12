package com.obd2monitor

import com.obd2monitor.data.obd.TripMath
import com.obd2monitor.domain.model.OBDReading
import org.junit.Assert.assertEquals
import org.junit.Test

class TripMathTest {

    private fun r(ts: Long, speed: Float) = OBDReading(
        tripId = "t", timestamp = ts, speedKmh = speed, rpm = 0f, coolantTempC = 0f,
        throttlePercent = 0f, fuelLevelPercent = 0f, mafGs = 0f, instantFuelLPer100 = 0f,
        batteryVoltage = 0f, engineLoadPercent = 0f,
    )

    @Test
    fun `distance integrates speed over time`() {
        // 60 км/ч в течение 60 секунд = 1 км.
        val readings = (0..120).map { r(it * 500L, 60f) }
        assertEquals(1f, TripMath.distanceKm(readings), 0.001f)
    }

    @Test
    fun `pause gap does not add phantom distance`() {
        // Связь оборвалась на скорости 60 и вернулась через час на той же скорости:
        // этот час — пауза, а не 60 км пробега.
        val before = (0..120).map { r(it * 500L, 60f) }
        val after = (0..120).map { r(3_600_000L + it * 500L, 60f) }
        assertEquals(2f, TripMath.distanceKm(before + after), 0.001f)
    }

    @Test
    fun `acceleration from speed change`() {
        // 0 → 36 км/ч (10 м/с) за 2 секунды = 5 м/с².
        val readings = listOf(r(0, 0f), r(500, 9f), r(1000, 18f), r(1500, 27f), r(2000, 36f))
        assertEquals(5f, TripMath.accelAt(readings, 0, 4), 0.001f)
    }
}
