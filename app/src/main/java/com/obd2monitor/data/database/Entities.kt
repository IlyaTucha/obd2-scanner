package com.obd2monitor.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@Entity(tableName = "trips", indices = [Index(value = ["no"], unique = true)])
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
    /** Короткий номер поездки: по нему на неё ссылаются замеры (OBDReadingEntity.tripNo). */
    val no: Long = 0L,
)

/**
 * Замер поездки. Поездка указана номером trips.no, а не её UUID: 36 символов в
 * каждой из сотен тысяч строк и ещё раз в индексе занимали почти половину базы.
 * Показания хранятся целыми числами в сотых долях (×[SCALE]): так строка намного
 * короче, а погрешность в 0,005 нигде не видна. Перевод — в Mappers.
 */
@Entity(tableName = "obd_readings")
data class OBDReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val tripNo: Long,
    val timestamp: Long,
    val speedKmh: Int = 0,
    val rpm: Int = 0,
    val coolantTempC: Int = 0,
    val throttlePercent: Int = 0,
    val fuelLevelPercent: Int = 0,
    val mafGs: Int = 0,
    val instantFuelLPer100: Int = 0,
    val batteryVoltage: Int = 0,
    val engineLoadPercent: Int = 0,
    val intakeTempC: Int = 0,
    val mapKpa: Int = 0,
    val ignitionTimingDeg: Int = 0,
    val oilTempC: Int = 0,
    val shortFuelTrim1Pct: Int = 0,
    val longFuelTrim1Pct: Int = 0,
    val distanceMilKm: Int = 0,
    val distanceSinceClearKm: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    companion object {
        const val SCALE = 100f
    }
}

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
    /** Литры заправки (только для топлива). null — не указаны. */
    val fuelLiters: Float? = null,
    /** Заправка до полного бака — точка отсчёта для точного расхода. */
    val fullTank: Boolean = false,
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
