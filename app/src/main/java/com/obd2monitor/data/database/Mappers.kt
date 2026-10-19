package com.obd2monitor.data.database

import com.obd2monitor.domain.model.ExpenseEntry
import com.obd2monitor.domain.model.ExpenseType
import com.obd2monitor.domain.model.FuelType
import com.obd2monitor.domain.model.GeoPoint
import com.obd2monitor.domain.model.MaintenanceEntry
import com.obd2monitor.domain.model.MaintenanceType
import com.obd2monitor.domain.model.MileageEntry
import com.obd2monitor.domain.model.OBDProtocol
import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.model.Trip
import com.obd2monitor.domain.model.VehicleProfile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun VehicleProfileEntity.toDomain(): VehicleProfile = VehicleProfile(
    id = id,
    name = name,
    make = make,
    model = model,
    year = year,
    engineType = engineType,
    displacementL = displacementL,
    fuelType = runCatching { FuelType.valueOf(fuelType) }.getOrDefault(FuelType.PETROL),
    tankCapacity = tankCapacity,
    fuelPrice = fuelPrice,
    normalCoolantTempC = coolantTempMin..coolantTempMax,
    normalRpm = rpmMin..rpmMax,
    idleRpm = idleRpmMin..idleRpmMax,
    normalBatteryV = batteryVMin..batteryVMax,
    obdProtocol = runCatching { OBDProtocol.valueOf(protocol) }.getOrDefault(OBDProtocol.AUTO),
    supportedPids = supportedPidsCsv.split(",").filter { it.isNotBlank() },
    maxTorqueNm = maxTorqueNm,
    maxPowerKw = maxPowerKw,
    vin = vin,
    licensePlate = licensePlate,
)

fun VehicleProfile.toEntity(active: Boolean = false): VehicleProfileEntity = VehicleProfileEntity(
    id = id,
    name = name,
    make = make,
    model = model,
    year = year,
    engineType = engineType,
    displacementL = displacementL,
    fuelType = fuelType.name,
    tankCapacity = tankCapacity,
    fuelPrice = fuelPrice,
    coolantTempMin = normalCoolantTempC.first,
    coolantTempMax = normalCoolantTempC.last,
    rpmMin = normalRpm.first,
    rpmMax = normalRpm.last,
    idleRpmMin = idleRpm.first,
    idleRpmMax = idleRpm.last,
    batteryVMin = normalBatteryV.start,
    batteryVMax = normalBatteryV.endInclusive,
    protocol = obdProtocol.name,
    supportedPidsCsv = supportedPids.joinToString(","),
    isActive = active,
    maxTorqueNm = maxTorqueNm,
    maxPowerKw = maxPowerKw,
    vin = vin,
    licensePlate = licensePlate,
)

fun TripEntity.toDomain(): Trip = Trip(
    id = id,
    vehicleProfileId = vehicleProfileId,
    startTime = startTime,
    endTime = endTime,
    distanceKm = distanceKm,
    avgSpeedKmh = avgSpeedKmh,
    maxSpeedKmh = maxSpeedKmh,
    fuelUsedLiters = fuelUsedLiters,
    avgFuelConsumption = avgFuelConsumption,
    maxCoolantTempC = maxCoolantTemp,
    maxRpm = maxRpm,
    routePoints = runCatching { json.decodeFromString<List<GeoPoint>>(routePointsJson) }
        .getOrDefault(emptyList()),
    pausedMs = pausedMs,
)

fun Trip.toEntity(): TripEntity = TripEntity(
    id = id,
    vehicleProfileId = vehicleProfileId,
    startTime = startTime,
    endTime = endTime,
    distanceKm = distanceKm,
    avgSpeedKmh = avgSpeedKmh,
    maxSpeedKmh = maxSpeedKmh,
    fuelUsedLiters = fuelUsedLiters,
    avgFuelConsumption = avgFuelConsumption,
    maxCoolantTemp = maxCoolantTempC,
    maxRpm = maxRpm,
    routePointsJson = json.encodeToString(routePoints),
    pausedMs = pausedMs,
)

private fun Float.scaled(): Int = Math.round(this * OBDReadingEntity.SCALE)
private fun Int.unscaled(): Float = this / OBDReadingEntity.SCALE

/** [tripNo] — номер поездки [OBDReading.tripId] (TripEntity.no). */
fun OBDReading.toEntity(tripNo: Long): OBDReadingEntity = OBDReadingEntity(
    tripNo = tripNo,
    timestamp = timestamp,
    speedKmh = speedKmh.scaled(),
    rpm = rpm.scaled(),
    coolantTempC = coolantTempC.scaled(),
    throttlePercent = throttlePercent.scaled(),
    fuelLevelPercent = fuelLevelPercent.scaled(),
    mafGs = mafGs.scaled(),
    instantFuelLPer100 = instantFuelLPer100.scaled(),
    batteryVoltage = batteryVoltage.scaled(),
    engineLoadPercent = engineLoadPercent.scaled(),
    intakeTempC = intakeTempC.scaled(),
    mapKpa = mapKpa.scaled(),
    ignitionTimingDeg = ignitionTimingDeg.scaled(),
    oilTempC = oilTempC.scaled(),
    shortFuelTrim1Pct = shortFuelTrim1Pct.scaled(),
    longFuelTrim1Pct = longFuelTrim1Pct.scaled(),
    distanceMilKm = distanceMilKm.scaled(),
    distanceSinceClearKm = distanceSinceClearKm.scaled(),
    latitude = latitude,
    longitude = longitude,
)

fun MileageEntryEntity.toDomain(): MileageEntry = MileageEntry(
    id = id,
    vehicleProfileId = vehicleProfileId,
    date = date,
    odometerKm = odometerKm,
    note = note,
)

fun MileageEntry.toEntity(): MileageEntryEntity = MileageEntryEntity(
    id = id,
    vehicleProfileId = vehicleProfileId,
    date = date,
    odometerKm = odometerKm,
    note = note,
)

fun ExpenseEntryEntity.toDomain(): ExpenseEntry = ExpenseEntry(
    id = id,
    vehicleProfileId = vehicleProfileId,
    date = date,
    type = ExpenseType.fromName(type),
    description = description,
    amountRub = amountRub,
    odometerKm = odometerKm,
    fuelLiters = fuelLiters,
    fullTank = fullTank,
)

fun ExpenseEntry.toEntity(): ExpenseEntryEntity = ExpenseEntryEntity(
    id = id,
    vehicleProfileId = vehicleProfileId,
    date = date,
    type = type.name,
    description = description,
    amountRub = amountRub,
    odometerKm = odometerKm,
    fuelLiters = fuelLiters,
    fullTank = fullTank,
)

fun MaintenanceEntryEntity.toDomain(): MaintenanceEntry = MaintenanceEntry(
    id = id,
    vehicleProfileId = vehicleProfileId,
    type = MaintenanceType.fromName(type),
    date = date,
    odometerKm = odometerKm,
    intervalKm = intervalKm,
    note = note,
)

fun MaintenanceEntry.toEntity(): MaintenanceEntryEntity = MaintenanceEntryEntity(
    id = id,
    vehicleProfileId = vehicleProfileId,
    type = type.name,
    date = date,
    odometerKm = odometerKm,
    intervalKm = intervalKm,
    note = note,
)

/** [tripId] — UUID поездки с номером [OBDReadingEntity.tripNo]. */
fun OBDReadingEntity.toDomain(tripId: String): OBDReading = OBDReading(
    tripId = tripId,
    timestamp = timestamp,
    speedKmh = speedKmh.unscaled(),
    rpm = rpm.unscaled(),
    coolantTempC = coolantTempC.unscaled(),
    throttlePercent = throttlePercent.unscaled(),
    fuelLevelPercent = fuelLevelPercent.unscaled(),
    mafGs = mafGs.unscaled(),
    instantFuelLPer100 = instantFuelLPer100.unscaled(),
    batteryVoltage = batteryVoltage.unscaled(),
    engineLoadPercent = engineLoadPercent.unscaled(),
    intakeTempC = intakeTempC.unscaled(),
    mapKpa = mapKpa.unscaled(),
    ignitionTimingDeg = ignitionTimingDeg.unscaled(),
    oilTempC = oilTempC.unscaled(),
    shortFuelTrim1Pct = shortFuelTrim1Pct.unscaled(),
    longFuelTrim1Pct = longFuelTrim1Pct.unscaled(),
    distanceMilKm = distanceMilKm.unscaled(),
    distanceSinceClearKm = distanceSinceClearKm.unscaled(),
    latitude = latitude,
    longitude = longitude,
)
