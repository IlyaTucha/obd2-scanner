package com.obd2monitor.data.repository

import com.obd2monitor.data.database.TripDao
import com.obd2monitor.data.database.VehicleProfileDao
import com.obd2monitor.data.database.toDomain
import com.obd2monitor.data.database.toEntity
import com.obd2monitor.data.obd.FuelCalculator
import com.obd2monitor.data.obd.ReadingMetrics
import com.obd2monitor.data.obd.TripMath
import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.model.Trip
import com.obd2monitor.domain.model.TripFuelRow
import com.obd2monitor.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class TripRepositoryImpl @Inject constructor(
    private val dao: TripDao,
    private val vehicleDao: VehicleProfileDao,
) : TripRepository {

    override fun observeTrips(): Flow<List<Trip>> = dao.observeAll().map { it.map { e -> e.toDomain() } }

    override fun observeFuelTotals(profileId: String): Flow<List<TripFuelRow>> = dao.observeFuelTotals(profileId)
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
        val readings = dao.readingsFor(tripId).map { it.toDomain() }
        val profile = vehicleDao.getById(current.vehicleProfileId)?.toDomain()
        if (readings.isEmpty()) {
            dao.update(current.copy(endTime = System.currentTimeMillis()))
            return
        }
        // Расстояние: интегрируем скорость × dt; разрывы записи (паузы) не считаем ездой.
        val distance = TripMath.distanceKm(readings)
        var maxSpeed = 0f
        var maxRpm = 0
        var maxCoolant = 0
        for (r in readings) {
            maxSpeed = max(maxSpeed, r.speedKmh)
            maxRpm = max(maxRpm, r.rpm.toInt())
            maxCoolant = max(maxCoolant, r.coolantTempC.toInt())
        }
        val totalLiters = FuelCalculator.integrateTotalLiters(
            timestamps = LongArray(readings.size) { readings[it].timestamp },
            consumptionLPer100 = FloatArray(readings.size) { readings[it].instantFuelLPer100 },
            speedsKmh = FloatArray(readings.size) { readings[it].speedKmh },
            fuelRateLPerHour = FloatArray(readings.size) { ReadingMetrics.fuelRateLh(readings[it], profile) },
        )
        val now = System.currentTimeMillis()
        // Средняя — по времени в пути (без пауз), как и плитка «В пути» в отчёте.
        val activeH = ((now - current.startTime - current.pausedMs).coerceAtLeast(0L)) / 3_600_000f
        val avgSpeed = if (activeH > 0f) distance / activeH else 0f
        val avgCons = if (distance > 0.05f) totalLiters / distance * 100f else 0f
        dao.update(
            current.copy(
                endTime = now,
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
}
