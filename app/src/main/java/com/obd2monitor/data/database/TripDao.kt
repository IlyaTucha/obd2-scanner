package com.obd2monitor.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun observeAll(): Flow<List<TripEntity>>

    /** Снимок всех поездок без подписки — для экспорта в JSON. */
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    suspend fun getAllOnce(): List<TripEntity>

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

    @Update
    suspend fun update(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReading(reading: OBDReadingEntity)

    @Query("SELECT * FROM obd_readings WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun readingsFor(tripId: String): List<OBDReadingEntity>

    @Query("SELECT MAX(timestamp) FROM obd_readings WHERE tripId = :tripId")
    suspend fun lastReadingTimestamp(tripId: String): Long?

    @Query("DELETE FROM obd_readings WHERE tripId = :tripId")
    suspend fun deleteReadings(tripId: String)
}
