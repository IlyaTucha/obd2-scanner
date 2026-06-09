package com.obd2monitor.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CarLogDao {

    // --- Mileage ---

    @Query("SELECT * FROM mileage_entries WHERE vehicleProfileId = :profileId ORDER BY date ASC")
    fun observeMileage(profileId: String): Flow<List<MileageEntryEntity>>

    @Query("SELECT * FROM mileage_entries WHERE vehicleProfileId = :profileId ORDER BY date DESC LIMIT 1")
    suspend fun lastMileage(profileId: String): MileageEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMileage(entry: MileageEntryEntity)

    @Query("DELETE FROM mileage_entries WHERE id = :id")
    suspend fun deleteMileage(id: String)

    // --- Expenses ---

    @Query("SELECT * FROM expense_entries WHERE vehicleProfileId = :profileId ORDER BY date DESC")
    fun observeExpenses(profileId: String): Flow<List<ExpenseEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExpense(entry: ExpenseEntryEntity)

    @Query("DELETE FROM expense_entries WHERE id = :id")
    suspend fun deleteExpense(id: String)

    // --- Maintenance ---

    @Query("SELECT * FROM maintenance_entries WHERE vehicleProfileId = :profileId ORDER BY date DESC")
    fun observeMaintenance(profileId: String): Flow<List<MaintenanceEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMaintenance(entry: MaintenanceEntryEntity)

    @Query("DELETE FROM maintenance_entries WHERE id = :id")
    suspend fun deleteMaintenance(id: String)

    // --- Bulk delete для импорта (replace-режим) ---

    @Query("DELETE FROM mileage_entries WHERE vehicleProfileId = :profileId")
    suspend fun deleteAllMileage(profileId: String)

    @Query("DELETE FROM expense_entries WHERE vehicleProfileId = :profileId")
    suspend fun deleteAllExpenses(profileId: String)

    @Query("DELETE FROM maintenance_entries WHERE vehicleProfileId = :profileId")
    suspend fun deleteAllMaintenance(profileId: String)
}
