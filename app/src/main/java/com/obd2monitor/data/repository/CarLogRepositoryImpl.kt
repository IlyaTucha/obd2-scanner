package com.obd2monitor.data.repository

import com.obd2monitor.data.database.CarLogDao
import com.obd2monitor.data.database.toDomain
import com.obd2monitor.data.database.toEntity
import com.obd2monitor.domain.model.ExpenseEntry
import com.obd2monitor.domain.model.MaintenanceEntry
import com.obd2monitor.domain.model.MileageEntry
import com.obd2monitor.domain.repository.CarLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CarLogRepositoryImpl @Inject constructor(
    private val dao: CarLogDao,
) : CarLogRepository {

    override fun observeMileage(profileId: String): Flow<List<MileageEntry>> =
        dao.observeMileage(profileId).map { list -> list.map { it.toDomain() } }

    override suspend fun lastMileage(profileId: String): MileageEntry? =
        dao.lastMileage(profileId)?.toDomain()

    override suspend fun upsertMileage(entry: MileageEntry) = dao.upsertMileage(entry.toEntity())
    override suspend fun deleteMileage(id: String) = dao.deleteMileage(id)

    override fun observeExpenses(profileId: String): Flow<List<ExpenseEntry>> =
        dao.observeExpenses(profileId).map { list -> list.map { it.toDomain() } }

    override suspend fun upsertExpense(entry: ExpenseEntry) = dao.upsertExpense(entry.toEntity())
    override suspend fun deleteExpense(id: String) = dao.deleteExpense(id)

    override fun observeMaintenance(profileId: String): Flow<List<MaintenanceEntry>> =
        dao.observeMaintenance(profileId).map { list -> list.map { it.toDomain() } }

    override suspend fun upsertMaintenance(entry: MaintenanceEntry) = dao.upsertMaintenance(entry.toEntity())
    override suspend fun deleteMaintenance(id: String) = dao.deleteMaintenance(id)

    override suspend fun deleteAllForProfile(profileId: String) {
        dao.deleteAllMileage(profileId)
        dao.deleteAllExpenses(profileId)
        dao.deleteAllMaintenance(profileId)
    }
}
