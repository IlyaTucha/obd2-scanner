package com.obd2monitor.di

import android.content.Context
import com.obd2monitor.data.bluetooth.BluetoothObdConnection
import com.obd2monitor.data.bluetooth.ELM327Protocol
import com.obd2monitor.data.database.AppDatabase
import com.obd2monitor.data.database.CarLogDao
import com.obd2monitor.data.database.DTCCodeDao
import com.obd2monitor.data.database.TripDao
import com.obd2monitor.data.database.VehicleProfileDao
import com.obd2monitor.data.obd.PIDRepository
import com.obd2monitor.data.repository.CarLogRepositoryImpl
import com.obd2monitor.data.repository.DTCRepositoryImpl
import com.obd2monitor.data.repository.OBDRepositoryImpl
import com.obd2monitor.data.repository.TripRepositoryImpl
import com.obd2monitor.data.repository.VehicleProfileRepositoryImpl
import com.obd2monitor.domain.repository.CarLogRepository
import com.obd2monitor.domain.repository.DTCRepository
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDb(@ApplicationContext context: Context): AppDatabase = AppDatabase.build(context)

    @Provides fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()
    @Provides fun provideVehicleDao(db: AppDatabase): VehicleProfileDao = db.vehicleProfileDao()
    @Provides fun provideDtcDao(db: AppDatabase): DTCCodeDao = db.dtcCodeDao()
    @Provides fun provideCarLogDao(db: AppDatabase): CarLogDao = db.carLogDao()
}

@Module
@InstallIn(SingletonComponent::class)
object BluetoothModule {

    @Provides @Singleton
    fun providePidRepository(): PIDRepository = PIDRepository()

    @Provides @Singleton
    fun provideElm(connection: BluetoothObdConnection): ELM327Protocol = ELM327Protocol(connection)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindObdRepository(impl: OBDRepositoryImpl): OBDRepository

    @Binds @Singleton
    abstract fun bindTripRepository(impl: TripRepositoryImpl): TripRepository

    @Binds @Singleton
    abstract fun bindVehicleRepository(impl: VehicleProfileRepositoryImpl): VehicleProfileRepository

    @Binds @Singleton
    abstract fun bindDtcRepository(impl: DTCRepositoryImpl): DTCRepository

    @Binds @Singleton
    abstract fun bindCarLogRepository(impl: CarLogRepositoryImpl): CarLogRepository
}
