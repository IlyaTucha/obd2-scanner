package com.obd2monitor.domain.usecase

import com.obd2monitor.domain.model.DtcScanResult
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.TripRepository
import javax.inject.Inject

class ScanDtcUseCase @Inject constructor(
    private val obdRepo: OBDRepository,
) {
    suspend operator fun invoke(): DtcScanResult = obdRepo.scanDtc()
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
