package com.obd2monitor.service

import com.obd2monitor.data.obd.FuelCalculator
import com.obd2monitor.data.obd.ReadingMetrics
import com.obd2monitor.data.obd.TripMath
import com.obd2monitor.domain.model.GeoPoint
import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Сводка активной сессии, накопленная по мере записи замеров. */
data class LiveTripStats(
    val tripId: String? = null,
    val distanceKm: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    /** Скорость в последнем записанном замере (ЭБУ или GPS), км/ч. */
    val lastSpeedKmh: Float = 0f,
    val movingMs: Long = 0L,
    val movingDistanceKm: Float = 0f,
    val fuelLiters: Float = 0f,
    /** Маршрут без «клубков» на стоянке (точки ближе MIN_MOVE_M отброшены). */
    val route: List<GeoPoint> = emptyList(),
    /** Ускорение по последним 5 замерам, м/с². */
    val accelMs2: Float = 0f,
    val readingsCount: Int = 0,
) {
    val avgMovingSpeedKmh: Float
        get() = if (movingMs > 0L) movingDistanceKm / (movingMs / 3_600_000f) else 0f

    /** Средний расход за сессию, л/100км (как Trip.avgFuelConsumption). null — пока мало данных. */
    val avgConsumption: Float?
        get() = if (distanceKm >= 0.1f && fuelLiters > 0.001f) fuelLiters / distanceKm * 100f else null
}

/**
 * Инкрементальная статистика активной поездки.
 *
 * Раньше «Главная» и «Сессия» подписывались на полный список замеров поездки: Room
 * перечитывал его из БД на КАЖДУЮ вставку (2 раза в секунду), а экраны каждый раз
 * пересчитывали пробег/расход/маршрут с нуля — на поездке в 40 тыс. замеров это
 * заметная нагрузка на процессор и батарею. Теперь замеры один раз дочитываются из
 * БД при старте/усыновлении сессии, дальше сервис записи передаёт сюда каждый новый.
 */
@Singleton
class ActiveTripTracker @Inject constructor(
    private val tripRepo: TripRepository,
    private val vehicleRepo: VehicleProfileRepository,
) {
    private val _stats = MutableStateFlow(LiveTripStats())
    val stats: StateFlow<LiveTripStats> = _stats.asStateFlow()

    private val lock = Mutex()
    private var tripId: String? = null
    private var profile: VehicleProfile? = null
    private var acc = LiveTripStats()
    private var prev: OBDReading? = null
    private var prevFuelRateLh = 0f
    private val recent = ArrayDeque<OBDReading>()
    private val route = ArrayList<GeoPoint>()
    private var routePublishedAt = 0L

    /** Привязка к поездке: подтягиваем уже записанные замеры (после рестарта процесса). */
    suspend fun attach(id: String) = lock.withLock {
        if (tripId == id) return@withLock
        resetLocked()
        tripId = id
        profile = tripRepo.getTrip(id)?.vehicleProfileId?.let { vehicleRepo.getById(it) }
        acc = LiveTripStats(tripId = id)
        tripRepo.readingsFor(id).forEach { accumulate(it) }
        publish(force = true)
    }

    suspend fun onReading(r: OBDReading) {
        if (tripId != r.tripId) attach(r.tripId)
        lock.withLock {
            if (tripId != r.tripId) return@withLock
            accumulate(r)
            publish(force = false)
        }
    }

    suspend fun detach() = lock.withLock { resetLocked() }

    private fun resetLocked() {
        tripId = null
        profile = null
        acc = LiveTripStats()
        prev = null
        prevFuelRateLh = 0f
        recent.clear()
        route.clear()
        routePublishedAt = 0L
        _stats.value = LiveTripStats()
    }

    private fun accumulate(r: OBDReading) {
        val fuelRate = ReadingMetrics.fuelRateLh(r, profile)
        val p = prev
        if (p != null) {
            val h = TripMath.segmentHours(p.timestamp, r.timestamp)
            if (h > 0f) {
                val avgSpd = (p.speedKmh + r.speedKmh) / 2f
                val seg = avgSpd * h
                var moving = acc.movingMs
                var movingKm = acc.movingDistanceKm
                if (avgSpd >= TripMath.MOVING_THRESHOLD_KMH) {
                    moving += r.timestamp - p.timestamp
                    movingKm += seg
                }
                // Та же формула, что FuelCalculator.integrateTotalLiters для одного отрезка.
                val liters = FuelCalculator.integrateTotalLiters(
                    timestamps = longArrayOf(p.timestamp, r.timestamp),
                    consumptionLPer100 = floatArrayOf(p.instantFuelLPer100, r.instantFuelLPer100),
                    speedsKmh = floatArrayOf(p.speedKmh, r.speedKmh),
                    fuelRateLPerHour = floatArrayOf(prevFuelRateLh, fuelRate),
                )
                acc = acc.copy(
                    distanceKm = acc.distanceKm + seg,
                    movingMs = moving,
                    movingDistanceKm = movingKm,
                    fuelLiters = acc.fuelLiters + liters,
                )
            }
        }
        recent.addLast(r)
        while (recent.size > 5) recent.removeFirst()
        val accel = if (recent.size >= 2) TripMath.accelAt(recent, 0, recent.size - 1) else 0f
        acc = acc.copy(
            maxSpeedKmh = maxOf(acc.maxSpeedKmh, r.speedKmh),
            lastSpeedKmh = r.speedKmh,
            accelMs2 = accel,
            readingsCount = acc.readingsCount + 1,
        )
        val lat = r.latitude
        val lon = r.longitude
        if (lat != null && lon != null) {
            val last = route.lastOrNull()
            if (last == null || haversineMeters(last.latitude, last.longitude, lat, lon) >= MIN_MOVE_M) {
                route += GeoPoint(lat, lon, r.timestamp)
            }
        }
        prev = r
        prevFuelRateLh = fuelRate
    }

    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        // Маршрут копируем не чаще раза в ROUTE_PUBLISH_MS: карта всё равно
        // перерисовывает линию целиком, а копия списка на каждый замер не нужна.
        val routeSnapshot = if (force || now - routePublishedAt >= ROUTE_PUBLISH_MS || acc.route.isEmpty() && route.isNotEmpty()) {
            routePublishedAt = now
            route.toList()
        } else acc.route
        acc = acc.copy(route = routeSnapshot)
        _stats.value = acc
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 6_371_000.0 * acos((1 - 2 * a).coerceIn(-1.0, 1.0))
    }

    private companion object {
        const val MIN_MOVE_M = 8.0
        const val ROUTE_PUBLISH_MS = 3_000L
    }
}
