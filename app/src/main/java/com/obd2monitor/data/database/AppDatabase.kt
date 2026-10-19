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
    version = 7,
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

        /**
         * v6 → v7: замеры ссылаются на поездку номером trips.no вместо UUID, показания —
         * целые в сотых долях (см. OBDReadingEntity). База становится вдвое меньше.
         * Номера раздаются по времени старта. Замеры без поездки (их и так чистит
         * deleteOrphanReadings) не переносятся.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE trips ADD COLUMN no INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    "UPDATE trips SET no = (SELECT COUNT(*) FROM trips t WHERE t.startTime < trips.startTime " +
                        "OR (t.startTime = trips.startTime AND t.id <= trips.id))"
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trips_no` ON `trips` (`no`)")
                database.execSQL(
                    "CREATE TABLE `obd_readings_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tripNo` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`speedKmh` INTEGER NOT NULL, `rpm` INTEGER NOT NULL, `coolantTempC` INTEGER NOT NULL, `throttlePercent` INTEGER NOT NULL, `fuelLevelPercent` INTEGER NOT NULL, `mafGs` INTEGER NOT NULL, `instantFuelLPer100` INTEGER NOT NULL, `batteryVoltage` INTEGER NOT NULL, `engineLoadPercent` INTEGER NOT NULL, `intakeTempC` INTEGER NOT NULL, `mapKpa` INTEGER NOT NULL, `ignitionTimingDeg` INTEGER NOT NULL, `oilTempC` INTEGER NOT NULL, `shortFuelTrim1Pct` INTEGER NOT NULL, `longFuelTrim1Pct` INTEGER NOT NULL, `distanceMilKm` INTEGER NOT NULL, `distanceSinceClearKm` INTEGER NOT NULL, " +
                        "`latitude` REAL, `longitude` REAL)"
                )
                database.execSQL(
                    "INSERT INTO obd_readings_new SELECT r.id, t.no, r.timestamp, " +
                        "CAST(ROUND(r.speedKmh * 100) AS INTEGER), CAST(ROUND(r.rpm * 100) AS INTEGER), CAST(ROUND(r.coolantTempC * 100) AS INTEGER), CAST(ROUND(r.throttlePercent * 100) AS INTEGER), CAST(ROUND(r.fuelLevelPercent * 100) AS INTEGER), CAST(ROUND(r.mafGs * 100) AS INTEGER), CAST(ROUND(r.instantFuelLPer100 * 100) AS INTEGER), CAST(ROUND(r.batteryVoltage * 100) AS INTEGER), CAST(ROUND(r.engineLoadPercent * 100) AS INTEGER), CAST(ROUND(r.intakeTempC * 100) AS INTEGER), CAST(ROUND(r.mapKpa * 100) AS INTEGER), CAST(ROUND(r.ignitionTimingDeg * 100) AS INTEGER), CAST(ROUND(r.oilTempC * 100) AS INTEGER), CAST(ROUND(r.shortFuelTrim1Pct * 100) AS INTEGER), CAST(ROUND(r.longFuelTrim1Pct * 100) AS INTEGER), CAST(ROUND(r.distanceMilKm * 100) AS INTEGER), CAST(ROUND(r.distanceSinceClearKm * 100) AS INTEGER), " +
                        "r.latitude, r.longitude FROM obd_readings r JOIN trips t ON t.id = r.tripId"
                )
                database.execSQL("DROP TABLE obd_readings")
                database.execSQL("ALTER TABLE obd_readings_new RENAME TO obd_readings")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_obd_readings_tripNo` ON `obd_readings` (`tripNo`)")
            }
        }

        /** Свободных страниц больше этой доли файла — сжимаем его (VACUUM). Сам SQLite
         *  файл не уменьшает: после миграции 6 → 7 и удаления поездок место остаётся
         *  за базой пустыми страницами. */
        private const val VACUUM_FREE_SHARE = 0.25

        private val compactOnOpen = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                runCatching {
                    val pages = db.longPragma("page_count")
                    val free = db.longPragma("freelist_count")
                    if (pages > 0 && free > pages * VACUUM_FREE_SHARE) db.execSQL("VACUUM")
                }
            }
        }

        private fun SupportSQLiteDatabase.longPragma(name: String): Long =
            query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else 0L }

        fun build(context: Context): AppDatabase = Room
            .databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .addCallback(compactOnOpen)
            // На случай если в будущем какой-то апгрейд приедет без миграции — НЕ хотим
            // молча терять историю. Лучше упасть с понятным исключением и заставить
            // меня написать миграцию (так же как было бы при destructiveMigration=off
            // по умолчанию). Если когда-то понадобится откатить версию назад —
            // подключим .fallbackToDestructiveMigrationOnDowngrade().
            .build()
    }
}
