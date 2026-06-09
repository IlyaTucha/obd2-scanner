package com.obd2monitor.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleProfileDao {
    @Query("SELECT * FROM vehicle_profiles ORDER BY name")
    fun observeAll(): Flow<List<VehicleProfileEntity>>

    @Query("SELECT * FROM vehicle_profiles WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<VehicleProfileEntity?>

    @Query("SELECT * FROM vehicle_profiles WHERE id = :id")
    suspend fun getById(id: String): VehicleProfileEntity?

    @Query("SELECT * FROM vehicle_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveOnce(): VehicleProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: VehicleProfileEntity)

    @Query("DELETE FROM vehicle_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE vehicle_profiles SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE vehicle_profiles SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: String)

    @Transaction
    suspend fun setActive(id: String) {
        deactivateAll()
        activate(id)
    }

    @Query("SELECT COUNT(*) FROM vehicle_profiles")
    suspend fun count(): Int
}
