package com.obd2monitor.domain.usecase

import com.obd2monitor.domain.model.DTCCode
import com.obd2monitor.domain.model.OBDData
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ConnectOBDUseCase @Inject constructor(
    private val obdRepo: OBDRepository,
) {
    suspend operator fun invoke(deviceAddress: String) = obdRepo.connect(deviceAddress)
}

class DisconnectOBDUseCase @Inject constructor(
    private val obdRepo: OBDRepository,
) {
    suspend operator fun invoke() = obdRepo.disconnect()
}

class ReadLiveDataUseCase @Inject constructor(
    private val obdRepo: OBDRepository,
) {
    operator fun invoke(): Flow<OBDData> = obdRepo.liveData
}

class ReadDTCCodesUseCase @Inject constructor(
    private val obdRepo: OBDRepository,
) {
    suspend operator fun invoke(): List<DTCCode> = obdRepo.readDTC()
}

class ClearDTCUseCase @Inject constructor(
    private val obdRepo: OBDRepository,
) {
    suspend operator fun invoke(): Boolean = obdRepo.clearDTC()
}

class StartTripUseCase @Inject constructor(
    private val tripRepo: TripRepository,
) {
    suspend operator fun invoke(vehicleProfileId: String): String =
        tripRepo.startTrip(vehicleProfileId)
}

class FinishTripUseCase @Inject constructor(
    private val tripRepo: TripRepository,
) {
    suspend operator fun invoke(tripId: String) = tripRepo.finishTrip(tripId)
}
