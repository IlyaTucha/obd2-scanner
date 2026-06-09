package com.obd2monitor.data.repository

import com.obd2monitor.data.database.TripDao
import com.obd2monitor.data.database.VehicleProfileDao
import com.obd2monitor.data.database.toDomain
import com.obd2monitor.data.database.toEntity
import com.obd2monitor.data.obd.FuelCalculator
import com.obd2monitor.domain.model.FuelType
import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.model.Trip
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Singleton
class TripRepositoryImpl @Inject constructor(
    private val dao: TripDao,
    private val vehicleDao: VehicleProfileDao,
) : TripRepository {

    override fun observeTrips(): Flow<List<Trip>> = dao.observeAll().map { it.map { e -> e.toDomain() } }
    override fun observeActiveTrip(): Flow<Trip?> = dao.observeActive().map { it?.toDomain() }
    override suspend fun getActiveTripOnce(): Trip? = dao.getActiveOnce()?.toDomain()
    override suspend fun lastReadingTimestamp(tripId: String): Long? = dao.lastReadingTimestamp(tripId)
    override suspend fun getTrip(id: String): Trip? = dao.getById(id)?.toDomain()

    override suspend fun addPausedMs(tripId: String, deltaMs: Long) {
        if (deltaMs <= 0L) return
        dao.addPausedMs(tripId, deltaMs)
    }

    override suspend fun startTrip(vehicleProfileId: String): String {
        val id = UUID.randomUUID().toString()
        val trip = Trip(
            id = id,
            vehicleProfileId = vehicleProfileId,
            startTime = System.currentTimeMillis(),
            endTime = null,
            distanceKm = 0f,
            avgSpeedKmh = 0f,
            maxSpeedKmh = 0f,
            fuelUsedLiters = 0f,
            avgFuelConsumption = 0f,
            maxCoolantTempC = 0,
            maxRpm = 0,
            routePoints = emptyList(),
        )
        dao.insert(trip.toEntity())
        return id
    }

    override suspend fun appendReading(reading: OBDReading) {
        dao.insertReading(reading.toEntity())
    }

    override suspend fun finishTrip(tripId: String) {
        val current = dao.getById(tripId) ?: return
        val readings = dao.readingsFor(tripId)
        val profile = vehicleDao.getById(current.vehicleProfileId)?.toDomain()
        if (readings.isEmpty()) {
            dao.update(current.copy(endTime = System.currentTimeMillis()))
            return
        }
        // Расстояние: интегрируем скорость * dt
        var distance = 0f
        var maxSpeed = 0f
        var maxRpm = 0
        var maxCoolant = 0
        for (i in 1 until readings.size) {
            val dtH = (readings[i].timestamp - readings[i - 1].timestamp) / 3600000f
            val avgSpd = (readings[i].speedKmh + readings[i - 1].speedKmh) / 2f
            distance += avgSpd * dtH
            maxSpeed = max(maxSpeed, readings[i].speedKmh)
            maxRpm = max(maxRpm, readings[i].rpm.toInt())
            maxCoolant = max(maxCoolant, readings[i].coolantTempC.toInt())
        }
        val totalLiters = FuelCalculator.integrateTotalLiters(
            timestamps = readings.map { it.timestamp }.toLongArray(),
            consumptionLPer100 = readings.map { it.instantFuelLPer100 }.toFloatArray(),
            speedsKmh = readings.map { it.speedKmh }.toFloatArray(),
            fuelRateLPerHour = readings.map { fuelRateLhOf(it.toDomain(), profile) }.toFloatArray(),
        )
        val durationH = (System.currentTimeMillis() - current.startTime) / 3600000f
        val avgSpeed = if (durationH > 0f) distance / durationH else 0f
        val avgCons = if (distance > 0.05f) totalLiters / distance * 100f else 0f
        dao.update(
            current.copy(
                endTime = System.currentTimeMillis(),
                distanceKm = distance,
                avgSpeedKmh = avgSpeed,
                maxSpeedKmh = maxSpeed,
                fuelUsedLiters = totalLiters,
                avgFuelConsumption = avgCons,
                maxCoolantTemp = maxCoolant,
                maxRpm = maxRpm,
            )
        )
    }

    override suspend fun deleteTrip(tripId: String) {
        dao.deleteReadings(tripId)
        dao.delete(tripId)
    }

    override suspend fun readingsFor(tripId: String): List<OBDReading> =
        dao.readingsFor(tripId).map { it.toDomain() }

    override fun observeReadings(tripId: String) =
        dao.observeReadings(tripId).map { list -> list.map { it.toDomain() } }

    /** Гаверсинус — расстояние между двумя GPS-точками в км. Не используется напрямую тут, но полезно. */
    @Suppress("unused")
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * acos((1 - 2 * a).coerceIn(-1.0, 1.0)) / 2
        return (R * c).toFloat()
    }

    private fun fuelRateLhOf(reading: OBDReading, profile: VehicleProfile?): Float {
        val fuel = profile?.fuelType ?: FuelType.PETROL
        val displacementL = profile?.displacementL ?: 1.5f
        return when {
            reading.mafGs > 0f -> FuelCalculator.fromMAF(reading.mafGs, fuel)
            reading.mapKpa > 0f && reading.rpm > 0f && reading.intakeTempC != 0f ->
                FuelCalculator.fromMAP(reading.mapKpa, reading.rpm, reading.intakeTempC, displacementL, fuel)
            reading.rpm > 0f -> FuelCalculator.estimateIdleLitersPerHour(displacementL, reading.rpm)
            else -> 0f
        }
    }
}
