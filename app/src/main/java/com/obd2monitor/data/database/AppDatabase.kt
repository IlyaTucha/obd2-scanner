package com.obd2monitor.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TripEntity::class,
        OBDReadingEntity::class,
        VehicleProfileEntity::class,
        DTCCodeEntity::class,
        MileageEntryEntity::class,
        ExpenseEntryEntity::class,
        MaintenanceEntryEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun vehicleProfileDao(): VehicleProfileDao
    abstract fun dtcCodeDao(): DTCCodeDao
    abstract fun carLogDao(): CarLogDao

    companion object {
        const val NAME = "obd2_monitor.db"

        /** v4 → v5: добавили TripEntity.pausedMs для учёта времени на паузе. Чистая
         *  ALTER-миграция, чтобы не терять историю при апгрейде. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE trips ADD COLUMN pausedMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 → v6: литры и отметка «полный бак» у заправок — для расчёта расхода
         *  по чекам. Чистая ALTER-миграция, существующие записи не трогаются. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE expense_entries ADD COLUMN fuelLiters REAL")
                database.execSQL("ALTER TABLE expense_entries ADD COLUMN fullTank INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): AppDatabase = Room
            .databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            // На случай если в будущем какой-то апгрейд приедет без миграции — НЕ хотим
            // молча терять историю. Лучше упасть с понятным исключением и заставить
            // меня написать миграцию (так же как было бы при destructiveMigration=off
            // по умолчанию). Если когда-то понадобится откатить версию назад —
            // подключим .fallbackToDestructiveMigrationOnDowngrade().
            .build()
    }
}
