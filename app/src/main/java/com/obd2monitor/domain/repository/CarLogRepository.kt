package com.obd2monitor.domain.repository

import com.obd2monitor.domain.model.ExpenseEntry
import com.obd2monitor.domain.model.MaintenanceEntry
import com.obd2monitor.domain.model.MileageEntry
import kotlinx.coroutines.flow.Flow

interface CarLogRepository {
    fun observeMileage(profileId: String): Flow<List<MileageEntry>>
    suspend fun lastMileage(profileId: String): MileageEntry?
    suspend fun upsertMileage(entry: MileageEntry)
    suspend fun deleteMileage(id: String)

    fun observeExpenses(profileId: String): Flow<List<ExpenseEntry>>
    suspend fun upsertExpense(entry: ExpenseEntry)
    suspend fun deleteExpense(id: String)

    fun observeMaintenance(profileId: String): Flow<List<MaintenanceEntry>>
    suspend fun upsertMaintenance(entry: MaintenanceEntry)
    suspend fun deleteMaintenance(id: String)

    /** Полная очистка журналов профиля — используется при import-режиме «replace». */
    suspend fun deleteAllForProfile(profileId: String)
}
