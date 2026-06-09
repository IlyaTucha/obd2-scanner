package com.obd2monitor.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double, val timestamp: Long = 0L)

data class Trip(
    val id: String,
    val vehicleProfileId: String,
    val startTime: Long,
    val endTime: Long?,
    val distanceKm: Float,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val fuelUsedLiters: Float,
    val avgFuelConsumption: Float,
    val maxCoolantTempC: Int,
    val maxRpm: Int,
    val routePoints: List<GeoPoint>,
    /** Накопленное время на паузе, мс (см. TripEntity.pausedMs). */
    val pausedMs: Long = 0L,
) {
    /** Общее время от старта до завершения (или до «сейчас», если активна). */
    val durationMs: Long get() = (endTime ?: System.currentTimeMillis()) - startTime
    /** Реальное время записи, минус паузы. Не может быть отрицательным. */
    val activeMs: Long get() = (durationMs - pausedMs).coerceAtLeast(0L)
    val isActive: Boolean get() = endTime == null
}

data class OBDReading(
    val tripId: String,
    val timestamp: Long,
    val speedKmh: Float,
    val rpm: Float,
    val coolantTempC: Float,
    val throttlePercent: Float,
    val fuelLevelPercent: Float,
    val mafGs: Float,
    val instantFuelLPer100: Float,
    val batteryVoltage: Float,
    val engineLoadPercent: Float,
    val intakeTempC: Float = 0f,
    val mapKpa: Float = 0f,
    val ignitionTimingDeg: Float = 0f,
    val oilTempC: Float = 0f,
    val shortFuelTrim1Pct: Float = 0f,
    val longFuelTrim1Pct: Float = 0f,
    val distanceMilKm: Float = 0f,
    val distanceSinceClearKm: Float = 0f,
    val latitude: Double? = null,
    val longitude: Double? = null,
)
