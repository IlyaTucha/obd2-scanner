package com.obd2monitor.data.repository

import com.obd2monitor.data.database.TripDao
import com.obd2monitor.data.database.TripEntity
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class TripRepositoryImpl @Inject constructor(
    private val dao: TripDao,
    private val vehicleDao: VehicleProfileDao,
) : TripRepository {

    /** Номера поездок (TripEntity.no) для записи замеров: сервис пишет их по паре в
     *  секунду, искать номер в БД на каждый замер незачем. */
    private val tripNos = ConcurrentHashMap<String, Long>()

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

    /**
     * Простой нельзя просто прибавить к pausedMs: до гибели процесса TripController
     * уже успел записать в БД часть этой же паузы (сессия встала на паузу без данных,
     * потом телефон перезагрузился). Двойной учёт съедал время «в пути», и таймер
     * после перезапуска показывал меньше, чем было. Поэтому пауза зажимается между
     * разрывами, видными по замерам (вместе с хвостом до [now]), и всем временем без
     * записи — как и при завершении (см. endAndPause).
     */
    override suspend fun pausedMsOnAdopt(tripId: String, now: Long): Long {
        val trip = dao.getById(tripId) ?: return 0L
        val readings = dao.readingsFor(tripId).map { it.toDomain(tripId) }
        val paused = if (readings.isEmpty()) {
            (now - trip.startTime).coerceAtLeast(0L)
        } else {
            val tailMs = (now - readings.last().timestamp).coerceAtLeast(0L)
            val minPause = TripMath.recordedGapsMs(readings, trip.startTime) +
                (if (tailMs > TripMath.MAX_GAP_MS) tailMs else 0L)
            val maxPause = (now - trip.startTime - TripMath.recordedSpanMs(readings)).coerceAtLeast(minPause)
            (trip.pausedMs + tailMs).coerceIn(minPause, maxPause)
        }
        dao.update(trip.copy(pausedMs = paused))
        return paused
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
        tripNos[id] = dao.insertNumbered(trip.toEntity()).no
        return id
    }

    override suspend fun appendReading(reading: OBDReading) {
        // Поездку уже удалили — замер некуда привязать.
        val no = tripNos[reading.tripId] ?: dao.noOf(reading.tripId)?.also { tripNos[reading.tripId] = it } ?: return
        dao.insertReading(reading.toEntity(no))
    }

    override suspend fun finishTrip(tripId: String) {
        val current = dao.getById(tripId) ?: return
        val readings = dao.readingsFor(tripId).map { it.toDomain(tripId) }
        val profile = vehicleDao.getById(current.vehicleProfileId)?.toDomain()
        val now = System.currentTimeMillis()
        if (readings.isEmpty()) {
            dao.update(current.copy(endTime = now))
            return
        }
        val (endTime, pausedMs) = endAndPause(current, readings, finishedAt = now)
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
        // Средняя — по времени в пути (без пауз), как и плитка «В пути» в отчёте.
        val activeH = ((endTime - current.startTime - pausedMs).coerceAtLeast(0L)) / 3_600_000f
        val avgSpeed = if (activeH > 0f) distance / activeH else 0f
        val avgCons = if (distance > 0.05f) totalLiters / distance * 100f else 0f
        dao.update(
            current.copy(
                endTime = endTime,
                pausedMs = pausedMs,
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

    /** Меняет только время окончания, паузу и среднюю скорость: пробег и топливо
     *  поездки остаются такими, какими их посчитали при завершении. */
    override suspend fun trimTrailingPauses() {
        dao.finishedWithTrailingGap(TripMath.MAX_GAP_MS).forEach { trip ->
            val finishedAt = trip.endTime ?: return@forEach
            val readings = dao.readingsFor(trip.id).map { it.toDomain(trip.id) }
            if (readings.isEmpty()) return@forEach
            val (endTime, pausedMs) = endAndPause(trip, readings, finishedAt)
            val activeH = ((endTime - trip.startTime - pausedMs).coerceAtLeast(0L)) / 3_600_000f
            dao.update(
                trip.copy(
                    endTime = endTime,
                    pausedMs = pausedMs,
                    avgSpeedKmh = if (activeH > 0f) trip.distanceKm / activeH else trip.avgSpeedKmh,
                )
            )
        }
    }

    /**
     * Конец поездки и итоговая пауза при завершении в [finishedAt]. Если после
     * последнего замера шла пауза (заглушили двигатель, отключился адаптер, сессия
     * закрылась сама через AUTO_FINISH_PAUSE_MS), поездка кончается на последнем
     * замере, а этот хвост вычитается из pausedMs: «Всего» не включает стоянку после
     * приезда.
     *
     * Пауза зажата между разрывами, видными по самим замерам (хвост мог попасть в
     * pausedMs не целиком, если приложение было убито), и временем без записи — она
     * не может быть больше, иначе «В пути» уходит в минус.
     */
    private fun endAndPause(trip: TripEntity, readings: List<OBDReading>, finishedAt: Long): Pair<Long, Long> {
        val lastTs = readings.last().timestamp
        val tailMs = finishedAt - lastTs
        val trimTail = tailMs > TripMath.MAX_GAP_MS
        val endTime = if (trimTail) lastTs else finishedAt
        val minPause = TripMath.recordedGapsMs(readings, trip.startTime)
        val maxPause = (endTime - trip.startTime - TripMath.recordedSpanMs(readings)).coerceAtLeast(minPause)
        val paused = (if (trimTail) trip.pausedMs - tailMs else trip.pausedMs).coerceIn(minPause, maxPause)
        return endTime to paused
    }

    override suspend fun deleteTrip(tripId: String) {
        dao.deleteReadings(tripId)
        dao.delete(tripId)
        tripNos.remove(tripId)
    }

    override suspend fun readingsFor(tripId: String): List<OBDReading> =
        dao.readingsFor(tripId).map { it.toDomain(tripId) }
}
