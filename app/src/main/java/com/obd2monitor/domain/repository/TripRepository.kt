package com.obd2monitor.domain.repository

import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.model.Trip
import com.obd2monitor.domain.model.TripFuelRow
import kotlinx.coroutines.flow.Flow

interface TripRepository {
    fun observeTrips(): Flow<List<Trip>>
    fun observeActiveTrip(): Flow<Trip?>
    /** Итоги завершённых поездок профиля с расчётом топлива — для сравнения с чеками. */
    fun observeFuelTotals(profileId: String): Flow<List<TripFuelRow>>
    /** Снимок активной поездки на момент вызова (без подписки). */
    suspend fun getActiveTripOnce(): Trip?
    /** Timestamp последней записанной точки данных по поездке. Используется при
     *  усыновлении осиротевшей сессии — простой между last reading и now засчитывается
     *  в pausedMs. */
    suspend fun lastReadingTimestamp(tripId: String): Long?
    suspend fun getTrip(id: String): Trip?
    suspend fun startTrip(vehicleProfileId: String): String
    suspend fun appendReading(reading: OBDReading)
    suspend fun finishTrip(tripId: String)
    suspend fun deleteTrip(tripId: String)
    /** Инкрементальное накопление паузы — пишется TripController'ом раз в ~5 с. */
    suspend fun addPausedMs(tripId: String, deltaMs: Long)
    suspend fun readingsFor(tripId: String): List<OBDReading>
}
