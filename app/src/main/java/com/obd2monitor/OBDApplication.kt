package com.obd2monitor

import android.app.Application
import com.obd2monitor.data.database.AppDatabase
import com.obd2monitor.data.database.Seeder
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
import javax.inject.Inject

@HiltAndroidApp
class OBDApplication : Application() {

    @Inject lateinit var db: AppDatabase
    @Inject lateinit var autoSession: AutoSessionManager

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
        // Бэкап БД на каждый запуск — страховка от случайной destructive-миграции
        // и от собственных ошибок при правке схемы. Хранится в app-external
        // (Android/data/<pkg>/files/db_backups), пользователь может вытащить через
        // файловый менеджер. Без runtime-permission, работает на Android 8+.
        appScope.launch { backupDatabase() }
    }

    /** Копируем obd2_monitor.db + -wal/-shm в внешнюю папку app, оставляем последние
     *  MAX_BACKUPS файлов. Безопасно вызывать даже когда Room ещё открывает БД —
     *  SQLite допускает чтение файла во время работы (WAL mode). Если копирование
     *  упало — глотаем (бэкап best-effort, не должен валить старт app). */
    private fun backupDatabase() {
        runCatching {
            val src = getDatabasePath(AppDatabase.NAME) ?: return
            if (!src.exists() || src.length() == 0L) return
            val dir = File(getExternalFilesDir(null), "db_backups").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "obd2_monitor_$stamp.db").also { dst ->
                src.copyTo(dst, overwrite = true)
            }
            // Ротация — оставляем последние MAX_BACKUPS, чтобы папка не пухла.
            val backups = dir.listFiles { f -> f.name.startsWith("obd2_monitor_") && f.name.endsWith(".db") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            backups.drop(MAX_BACKUPS).forEach { runCatching { it.delete() } }
        }
    }

    private companion object {
        const val MAX_BACKUPS = 10
    }
}
