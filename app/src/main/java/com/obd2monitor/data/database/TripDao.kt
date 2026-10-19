package com.obd2monitor.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.obd2monitor.domain.model.TripFuelRow
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun observeAll(): Flow<List<TripEntity>>

    /** Снимок всех поездок без подписки — для экспорта в JSON. */
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    suspend fun getAllOnce(): List<TripEntity>

    /** Итоги завершённых поездок с расчётом топлива (без маршрута и показаний). */
    @Query(
        "SELECT startTime, distanceKm, fuelUsedLiters FROM trips " +
            "WHERE vehicleProfileId = :profileId AND endTime IS NOT NULL AND fuelUsedLiters > 0 AND distanceKm > 0"
    )
    fun observeFuelTotals(profileId: String): Flow<List<TripFuelRow>>

    @Query("SELECT * FROM trips WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    fun observeActive(): Flow<TripEntity?>

    /** Snapshot (без подписки) самой свежей активной поездки — нужен на старте app,
     *  чтобы усыновить «осиротевшую» сессию после force-stop'а. */
    @Query("SELECT * FROM trips WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveOnce(): TripEntity?

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: String): TripEntity?

    /** Инкрементируем накопленную паузу. Атомарно — TripController может вызывать
     *  параллельно с обычными update'ами. */
    @Query("UPDATE trips SET pausedMs = pausedMs + :deltaMs WHERE id = :tripId")
    suspend fun addPausedMs(tripId: String, deltaMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trip: TripEntity)

    @Query("SELECT no FROM trips WHERE id = :id")
    suspend fun noOf(id: String): Long?

    @Query("SELECT COALESCE(MAX(no), 0) FROM trips")
    suspend fun maxNo(): Long

    /** Новая поездка со следующим свободным номером. */
    @Transaction
    suspend fun insertNumbered(trip: TripEntity): TripEntity {
        val numbered = trip.copy(no = maxNo() + 1)
        insert(numbered)
        return numbered
    }

    @Update
    suspend fun update(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReading(reading: OBDReadingEntity)

    /** Пакетная вставка — для импорта бэкапа сессий. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReadings(readings: List<OBDReadingEntity>)

    @Query(
        "SELECT * FROM obd_readings WHERE tripNo = (SELECT no FROM trips WHERE id = :tripId) " +
            "ORDER BY timestamp ASC"
    )
    suspend fun readingsFor(tripId: String): List<OBDReadingEntity>

    @Query("SELECT MAX(timestamp) FROM obd_readings WHERE tripNo = (SELECT no FROM trips WHERE id = :tripId)")
    suspend fun lastReadingTimestamp(tripId: String): Long?

    /** Завершённые поездки, у которых конец позже последнего замера больше чем на
     *  [gapMs]: время окончания включает хвост паузы. */
    @Query(
        "SELECT * FROM trips t WHERE t.endTime IS NOT NULL AND " +
            "t.endTime - (SELECT MAX(r.timestamp) FROM obd_readings r WHERE r.tripNo = t.no) > :gapMs"
    )
    suspend fun finishedWithTrailingGap(gapMs: Long): List<TripEntity>

    /**
     * Показания без сессии (сессия удалена раньше, чем сервис дописал последние
     * точки, — бывало в старых версиях). Нигде не видны и не экспортируются.
     */
    @Query("DELETE FROM obd_readings WHERE tripNo NOT IN (SELECT no FROM trips)")
    suspend fun deleteOrphanReadings(): Int

    @Query("DELETE FROM obd_readings WHERE tripNo = (SELECT no FROM trips WHERE id = :tripId)")
    suspend fun deleteReadings(tripId: String)
}
