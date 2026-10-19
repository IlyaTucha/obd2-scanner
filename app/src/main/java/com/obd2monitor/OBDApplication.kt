package com.obd2monitor

import android.app.Application
import com.obd2monitor.data.database.AppDatabase
import com.obd2monitor.data.database.Seeder
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.service.AutoSessionManager
import com.yandex.mapkit.MapKitFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import javax.inject.Inject

@HiltAndroidApp
class OBDApplication : Application() {

    @Inject lateinit var db: AppDatabase
    @Inject lateinit var autoSession: AutoSessionManager
    @Inject lateinit var tripRepo: TripRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.YANDEX_API_KEY.isNotBlank()) {
            MapKitFactory.setApiKey(BuildConfig.YANDEX_API_KEY)
        }
        MapKitFactory.initialize(this)

        appScope.launch { Seeder.seedIfEmpty(db) }
        // Авто-сессии: подписываемся на состояние подключения OBD и открываем/закрываем сессию автоматически.
        autoSession.start()
        // Ежедневный бэкап БД — страховка от неудачной миграции и ошибок при правке
        // схемы. Хранится в Android/data/<pkg>/files/db_backups, его можно забрать
        // файловым менеджером или по USB. Разрешений не требует.
        appScope.launch {
            backupDatabase()
            // После бэкапа — чтобы удалённое осталось хотя бы в копии.
            runCatching { db.tripDao().deleteOrphanReadings() }
            runCatching { tripRepo.trimTrailingPauses() }
        }
    }

    /**
     * Копия obd2_monitor.db во внешнюю папку приложения, не чаще раза в сутки;
     * хранятся последние MAX_BACKUPS копий (то есть за последние дни, а не за
     * последние запуски — раньше десять перезапусков за вечер вытесняли все
     * вчерашние копии). Перед копированием журнал WAL вливается в основной файл:
     * без этого последние записи оставались в -wal и в копию не попадали.
     * Копия сжата gzip (в разы меньше базы); для восстановления её распаковывают
     * в .db. Пишется во временный файл, чтобы оборванная запись не вытеснила
     * целую копию. Ошибки глотаем — бэкап best-effort и не должен ломать запуск.
     */
    private fun backupDatabase() {
        runCatching {
            val src = getDatabasePath(AppDatabase.NAME) ?: return
            if (!src.exists() || src.length() == 0L) return
            val dir = File(getExternalFilesDir(null), "db_backups").apply { mkdirs() }
            val backups = dir.listFiles { f ->
                f.name.startsWith("obd2_monitor_") && (f.name.endsWith(".db") || f.name.endsWith(".db.gz"))
            }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            if (backups.any { it.name.startsWith("obd2_monitor_$today") }) return
            runCatching {
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(dir, "obd2_monitor_$stamp.db.gz")
            val tmp = File(dir, "${out.name}.tmp")
            src.inputStream().use { ins -> GZIPOutputStream(tmp.outputStream().buffered()).use { ins.copyTo(it) } }
            if (!tmp.renameTo(out)) return
            (listOf(out) + backups)
                .drop(MAX_BACKUPS)
                .forEach { runCatching { it.delete() } }
        }
    }

    private companion object {
        const val MAX_BACKUPS = 3
    }
}
