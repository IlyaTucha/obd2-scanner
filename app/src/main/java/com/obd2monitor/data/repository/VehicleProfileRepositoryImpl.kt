package com.obd2monitor.data.repository

import com.obd2monitor.data.database.VehicleProfileDao
import com.obd2monitor.data.database.toDomain
import com.obd2monitor.data.database.toEntity
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.VehicleProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleProfileRepositoryImpl @Inject constructor(
    private val dao: VehicleProfileDao,
) : VehicleProfileRepository {
    override fun observeAll(): Flow<List<VehicleProfile>> = dao.observeAll().map { it.map { e -> e.toDomain() } }
    override fun observeActive(): Flow<VehicleProfile?> = dao.observeActive().map { it?.toDomain() }
    override suspend fun setActive(profileId: String) = dao.setActive(profileId)
    override suspend fun upsert(profile: VehicleProfile) {
        // Сохраняем текущий isActive — иначе апдейт активного профиля сбросил бы
        // флаг (toEntity() по умолчанию ставит active = false), и observeActive()
        // отдал бы null до следующего setActive().
        val existingActive = dao.getById(profile.id)?.isActive ?: false
        dao.upsert(profile.toEntity(active = existingActive))
    }
    override suspend fun delete(profileId: String) = dao.delete(profileId)
    override suspend fun getById(profileId: String): VehicleProfile? = dao.getById(profileId)?.toDomain()
}
