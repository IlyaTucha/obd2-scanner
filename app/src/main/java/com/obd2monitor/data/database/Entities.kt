package com.obd2monitor.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val vehicleProfileId: String,
    val startTime: Long,
    val endTime: Long?,
    val distanceKm: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val fuelUsedLiters: Float = 0f,
    val avgFuelConsumption: Float = 0f,
    val maxCoolantTemp: Int = 0,
    val maxRpm: Int = 0,
    val routePointsJson: String = "[]",
    /** Накопленное время на паузе, мс. Источник истины — TripController флашит сюда
     *  раз в ~5 с пока paused == true. Реальное «в пути» = (endTime ?: now) - startTime - pausedMs. */
    val pausedMs: Long = 0L,
)

@Serializable
@Entity(tableName = "obd_readings")
data class OBDReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val tripId: String,
    val timestamp: Long,
    val speedKmh: Float = 0f,
    val rpm: Float = 0f,
    val coolantTempC: Float = 0f,
    val throttlePercent: Float = 0f,
    val fuelLevelPercent: Float = 0f,
    val mafGs: Float = 0f,
    val instantFuelLPer100: Float = 0f,
    val batteryVoltage: Float = 0f,
    val engineLoadPercent: Float = 0f,
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

@Entity(tableName = "vehicle_profiles")
data class VehicleProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val make: String,
    val model: String,
    val year: Int,
    val engineType: String,
    val displacementL: Float,
    val fuelType: String,
    val tankCapacity: Float,
    val fuelPrice: Float,
    val coolantTempMin: Int,
    val coolantTempMax: Int,
    val rpmMin: Int,
    val rpmMax: Int,
    val idleRpmMin: Int,
    val idleRpmMax: Int,
    val batteryVMin: Float,
    val batteryVMax: Float,
    val protocol: String,
    val supportedPidsCsv: String,
    val isActive: Boolean = false,
    val maxTorqueNm: Int = 130,
    val maxPowerKw: Int = 70,
    val vin: String = "",
    val licensePlate: String = "",
)

@Entity(tableName = "mileage_entries")
data class MileageEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(index = true) val vehicleProfileId: String,
    val date: Long,
    val odometerKm: Int,
    val note: String = "",
)

@Entity(tableName = "expense_entries")
data class ExpenseEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(index = true) val vehicleProfileId: String,
    val date: Long,
    val type: String,
    val description: String,
    val amountRub: Float,
    val odometerKm: Int? = null,
)

@Entity(tableName = "maintenance_entries")
data class MaintenanceEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(index = true) val vehicleProfileId: String,
    val type: String,
    val date: Long,
    val odometerKm: Int,
    val intervalKm: Int,
    val note: String = "",
)

@Entity(tableName = "dtc_codes")
data class DTCCodeEntity(
    @PrimaryKey val code: String,
    val description: String,
    val possibleCauses: String = "",
    val recommendedActions: String = "",
    val isCritical: Boolean = false,
)
