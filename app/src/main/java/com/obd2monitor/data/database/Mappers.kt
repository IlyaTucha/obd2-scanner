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

fun OBDReading.toEntity(): OBDReadingEntity = OBDReadingEntity(
    tripId = tripId,
    timestamp = timestamp,
    speedKmh = speedKmh,
    rpm = rpm,
    coolantTempC = coolantTempC,
    throttlePercent = throttlePercent,
    fuelLevelPercent = fuelLevelPercent,
    mafGs = mafGs,
    instantFuelLPer100 = instantFuelLPer100,
    batteryVoltage = batteryVoltage,
    engineLoadPercent = engineLoadPercent,
    intakeTempC = intakeTempC,
    mapKpa = mapKpa,
    ignitionTimingDeg = ignitionTimingDeg,
    oilTempC = oilTempC,
    shortFuelTrim1Pct = shortFuelTrim1Pct,
    longFuelTrim1Pct = longFuelTrim1Pct,
    distanceMilKm = distanceMilKm,
    distanceSinceClearKm = distanceSinceClearKm,
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
)

fun ExpenseEntry.toEntity(): ExpenseEntryEntity = ExpenseEntryEntity(
    id = id,
    vehicleProfileId = vehicleProfileId,
    date = date,
    type = type.name,
    description = description,
    amountRub = amountRub,
    odometerKm = odometerKm,
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

fun OBDReadingEntity.toDomain(): OBDReading = OBDReading(
    tripId = tripId,
    timestamp = timestamp,
    speedKmh = speedKmh,
    rpm = rpm,
    coolantTempC = coolantTempC,
    throttlePercent = throttlePercent,
    fuelLevelPercent = fuelLevelPercent,
    mafGs = mafGs,
    instantFuelLPer100 = instantFuelLPer100,
    batteryVoltage = batteryVoltage,
    engineLoadPercent = engineLoadPercent,
    intakeTempC = intakeTempC,
    mapKpa = mapKpa,
    ignitionTimingDeg = ignitionTimingDeg,
    oilTempC = oilTempC,
    shortFuelTrim1Pct = shortFuelTrim1Pct,
    longFuelTrim1Pct = longFuelTrim1Pct,
    distanceMilKm = distanceMilKm,
    distanceSinceClearKm = distanceSinceClearKm,
    latitude = latitude,
    longitude = longitude,
)
