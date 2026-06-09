package com.obd2monitor.domain.repository

import com.obd2monitor.domain.model.VehicleProfile
import kotlinx.coroutines.flow.Flow

interface VehicleProfileRepository {
    fun observeAll(): Flow<List<VehicleProfile>>
    fun observeActive(): Flow<VehicleProfile?>
    suspend fun setActive(profileId: String)
    suspend fun upsert(profile: VehicleProfile)
    suspend fun delete(profileId: String)
    suspend fun getById(profileId: String): VehicleProfile?
}
