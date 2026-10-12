package com.obd2monitor.data.database

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Контейнер JSON-бэкапа только поездок: trips + obd_readings. Версия для миграций
 *  формата на будущее (например, если поменяется набор полей в OBDReadingEntity). */
@Serializable
data class TripBackupPayload(
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val trips: List<TripEntity> = emptyList(),
    val readings: List<OBDReadingEntity> = emptyList(),
)

/**
 * Экспорт и импорт основной БД приложения. Формат — сырой SQLite-файл (.db).
 *
 * Зачем raw .db, а не JSON: все таблицы, индексы и связи копируются 1:1 без
 * писанины маппинга, который придётся синхронизировать при каждой смене схемы.
 * Плюс получившийся файл можно открыть в DB Browser for SQLite на ПК.
 *
 * WAL-сложность: Room по умолчанию использует WAL — последние транзакции могут
 * лежать в `-wal`/`-shm`, не в основном файле. Перед копированием делаем
 * `wal_checkpoint(TRUNCATE)`, чтобы влить хвост в основной файл и забрать
 * полный снимок. Сами `-wal`/`-shm` НЕ копируем — после checkpoint'а они пусты.
 *
 * Импорт: не подменяем файл прямо под Room (Room держит хэндлы, всё развалится).
 * Кладём входной файл в pending-слот, затем приложение перезапускает процесс —
 * на следующем onCreate AppDatabase подцепит уже новый файл.
 */
@Singleton
class DatabaseBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val tripDao: TripDao,
) {
    private val pendingImportName = "$DB_NAME.pending_import"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ── Только поездки (trips + obd_readings) в JSON ───────────────────────────
    // Используется в разделе «История сессий». Полный бэкап (со всеми таблицами:
    // профили, пробег, расходы, ТО) — это exportFullDbTo/stageFullDbImport ниже.

    /**
     * Экспорт только поездок и их readings. Возвращает количество поездок.
     *
     * Формат v2 — построчный: первая строка — [TripBackupPayload] со списком поездок
     * (без readings), дальше по одной строке на каждый reading. Показаний сотни тысяч:
     * собрать их в одну JSON-строку (как в v1) — сотни мегабайт в памяти и падение.
     * Здесь в памяти одновременно только показания одной поездки.
     */
    suspend fun exportTripsTo(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            // WAL-чекпойнт — иначе только что закрытая поездка может не попасть в snapshot.
            runCatching {
                db.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { it.moveToFirst() }
            }
            val trips = tripDao.getAllOnce()
            val header = TripBackupPayload(version = 2, exportedAt = System.currentTimeMillis(), trips = trips)
            context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { out ->
                out.write(json.encodeToString(header))
                out.newLine()
                trips.forEach { trip ->
                    tripDao.readingsFor(trip.id).forEach { r ->
                        out.write(json.encodeToString(r))
                        out.newLine()
                    }
                }
            } ?: error("Не удалось открыть поток на запись")
            trips.size
        }
    }

    /**
     * Импорт поездок. Поездка с тем же id заменяется целиком: её старые показания
     * удаляются перед записью новых (связи между таблицами нет, и без этого
     * повторный импорт задваивал бы показания). Понимает v1 (всё одной строкой)
     * и v2 (построчно). Возвращает количество импортированных поездок.
     */
    suspend fun importTripsFrom(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть поток на чтение")
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                val first = reader.readLine() ?: error("Файл пустой")
                val payload = runCatching { json.decodeFromString<TripBackupPayload>(first) }
                    .getOrElse { error("Файл не похож на бэкап сессий") }
                check(payload.version in 1..2) { "Неподдерживаемая версия бэкапа: ${payload.version}" }

                db.withTransaction {
                    payload.trips.forEach { trip ->
                        tripDao.deleteReadings(trip.id)
                        tripDao.insert(trip)
                    }
                }
                // Для readings обнуляем autogen-id: Room назначит новые без конфликтов.
                val readings = if (payload.version == 1) {
                    payload.readings.asSequence()
                } else {
                    reader.lineSequence().filter { it.isNotBlank() }
                        .map { json.decodeFromString<OBDReadingEntity>(it) }
                }
                readings.map { it.copy(id = 0L) }.chunked(IMPORT_BATCH).forEach { batch ->
                    db.withTransaction { tripDao.insertReadings(batch) }
                }
                payload.trips.size
            }
        }
    }

    // ── Полный бэкап БД (sqlite-файл) — для скрытого меню в настройках ──────

    suspend fun exportFullDbTo(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            // Принудительно сливаем WAL в основной файл — иначе экспорт может
            // отстать от состояния UI на несколько последних транзакций.
            runCatching {
                db.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { it.moveToFirst() }
            }
            val src = context.getDatabasePath(DB_NAME)
            check(src.exists() && src.length() > 0L) { "БД пустая, нечего экспортировать" }
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: error("Не удалось открыть поток на запись")
            src.length()
        }
    }

    /**
     * Кладём импортируемый файл в `pending_import`. Сам файл БД заменим перед
     * `Process.killProcess` — между моментом замены и рестартом не должно быть
     * никакой работы с Room, иначе он закэширует половину старых данных.
     */
    suspend fun stageFullDbImport(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val pending = File(context.getDatabasePath(DB_NAME).parentFile, pendingImportName)
            context.contentResolver.openInputStream(uri)?.use { ins ->
                pending.outputStream().use { ins.copyTo(it) }
            } ?: error("Не удалось открыть поток на чтение")
            check(pending.length() >= MIN_SQLITE_HEADER) {
                "Файл слишком маленький — не похож на SQLite"
            }
            // Валидируем magic-bytes («SQLite format 3\0»). Если кто-то скормит .txt
            // или PDF — лучше сразу сказать пользователю «не тот формат», чем
            // подсунуть это Room'у и упасть на старте.
            pending.inputStream().use { ins ->
                val header = ByteArray(SQLITE_MAGIC.size)
                ins.read(header)
                check(header.contentEquals(SQLITE_MAGIC)) { "Это не SQLite-файл" }
            }
            pending
        }
    }

    /**
     * Финализация импорта: закрываем Room, переименовываем pending → актуальный
     * obd2_monitor.db, чистим -wal/-shm (они от старой БД), убиваем процесс.
     * Android Application.onCreate стартует заново при следующем запуске —
     * Room откроет уже новый файл.
     */
    fun finalizeFullDbImportAndRestart() {
        runCatching { db.close() }
        val dbDir = context.getDatabasePath(DB_NAME).parentFile ?: return
        val main = File(dbDir, DB_NAME)
        val wal = File(dbDir, "$DB_NAME-wal")
        val shm = File(dbDir, "$DB_NAME-shm")
        val pending = File(dbDir, pendingImportName)
        if (!pending.exists()) return
        runCatching { if (main.exists()) main.delete() }
        runCatching { if (wal.exists()) wal.delete() }
        runCatching { if (shm.exists()) shm.delete() }
        runCatching { pending.renameTo(main) }
        // Убиваем процесс — Application пересоздастся, Room откроет новый файл.
        // Альтернатива (ProcessPhoenix или recreate UI) хуже: фрагменты Hilt-графа
        // продолжают держать ссылки на closed RoomDatabase → NPE.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        private const val DB_NAME = AppDatabase.NAME
        private const val MIN_SQLITE_HEADER = 100L  // официальный заголовок SQLite — 100 байт
        // 16-байтовая magic-последовательность из спеки SQLite: "SQLite format 3\0".
        // Записана как byteArrayOf, чтобы не зависеть от того как кодировщик
        // запишет embedded NUL в .kt-файл.
        /** Показаний на одну транзакцию при импорте. */
        private const val IMPORT_BATCH = 1000
        private val SQLITE_MAGIC: ByteArray = byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )
    }
}
