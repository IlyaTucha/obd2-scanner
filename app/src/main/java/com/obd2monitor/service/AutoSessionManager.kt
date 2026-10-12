package com.obd2monitor.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.obd2monitor.data.preferences.UserPreferences
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import com.obd2monitor.domain.usecase.FinishTripUseCase
import com.obd2monitor.domain.usecase.StartTripUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Жизненный цикл сессий:
 *  - при подключении к OBD сессия запускается сама;
 *  - кнопкой «Запись по GPS» сессию можно начать и без адаптера — маршрут, скорость
 *    и пробег пишутся по спутникам, а если адаптер подключится, его данные добавятся
 *    в ту же сессию;
 *  - закрывается сессия только явным «Завершить» (или после 8 ч непрерывной паузы);
 *  - обрыв связи с OBD-сессией ставит её на паузу, GPS-сессия продолжает запись.
 */
@Singleton
class AutoSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val obdRepo: OBDRepository,
    private val vehicleRepo: VehicleProfileRepository,
    private val tripRepo: TripRepository,
    private val startTrip: StartTripUseCase,
    private val finishTrip: FinishTripUseCase,
    private val controller: TripController,
    private val tracker: ActiveTripTracker,
    private val prefs: UserPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Все переходы открыта/закрыта сессия идут под этим мьютексом: слушатель статуса,
     *  кнопки UI и watchdog'и иначе могли бы одновременно открыть две сессии или
     *  дважды закрыть одну. */
    private val tripMutex = Mutex()
    private var lastState: ConnectionState = ConnectionState.DISCONNECTED
    private var currentTripId: String? = null
    private var started = false

    companion object {
        /** Если за этот интервал не пришёл ни один валидный snapshot — считаем разрыв и ставим паузу. */
        private const val STALE_DATA_THRESHOLD_MS = 10_000L
        /** Брошенная сессия старше этого — закрывается автоматически на старте app,
         *  чтобы не «вечный trip» в БД при долгом простое. Синхронизирована с
         *  [TripController.AUTO_FINISH_PAUSE_MS] — это одно правило, проявленное в двух
         *  местах (на старте app и runtime-watchdog'ом). */
        private val ORPHAN_MAX_AGE_MS = TripController.AUTO_FINISH_PAUSE_MS
    }

    fun start() {
        if (started) return
        started = true
        // Сначала усыновление осиротевшей сессии, потом подписка на connectionStatus:
        // иначе первое же CONNECTED (auto-connect в MainActivity идёт параллельно)
        // открыло бы новую сессию, а старая осталась бы в БД незакрытой.
        scope.launch {
            runCatching { tripMutex.withLock { adoptOrphanIfAny() } }
            obdRepo.connectionStatus.onEach { status ->
                val state = status.state
                try {
                    tripMutex.withLock { onConnectionState(state) }
                } catch (_: Throwable) {
                    // Не валим приложение из-за гонки — попробуем при следующем переходе.
                }
                lastState = state
            }.launchIn(scope)
        }

        // Watchdog «частичного разрыва»: CONNECTED, но данные не идут >10 c — пауза.
        // GPS-сессию не трогаем: она пишется и без ЭБУ (паузу ей ставит сервис на стоянке).
        scope.launch {
            while (isActive) {
                delay(2000)
                if (currentTripId == null || controller.gpsMode.value) continue
                val s = obdRepo.connectionStatus.value
                if (s.state != ConnectionState.CONNECTED) continue
                val now = System.currentTimeMillis()
                val ageMs = s.lastSnapshotAt?.let { now - it } ?: Long.MAX_VALUE
                val stale = ageMs > STALE_DATA_THRESHOLD_MS || !s.lastSnapshotHadData
                if (stale && !controller.paused.value) {
                    controller.setPaused(true)
                } else if (!stale && controller.paused.value) {
                    controller.setPaused(false)
                }
            }
        }

        // Watchdog авто-завершения: сессия в непрерывной паузе дольше 8 ч закрывается,
        // чтобы пользователь не вернулся через сутки к давно мёртвой «активной» сессии.
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (currentTripId == null) continue
                val pauseStart = controller.pauseSinceMs.value ?: continue
                if (System.currentTimeMillis() - pauseStart >= ORPHAN_MAX_AGE_MS) {
                    runCatching { tripMutex.withLock { closeTripLocked() } }
                }
            }
        }
    }

    /** Ручной старт записи по GPS (кнопка на вкладке «Сессия»). */
    suspend fun startGpsTrip() = tripMutex.withLock {
        if (currentTripId != null) return@withLock
        val profile = vehicleRepo.observeActive().firstOrNull() ?: return@withLock
        val tripId = startTrip(profile.id)
        currentTripId = tripId
        prefs.setGpsTripId(tripId)
        controller.onTripStarted(tripId, gpsMode = true)
        startService()
    }

    /**
     * «Завершить» на вкладке «Сессия». Закрывает сессию в любом состоянии — и при
     * подключённом адаптере, и в GPS-режиме, и когда сессия висит на паузе без связи
     * (раньше в последнем случае кнопка ничего не делала: разрыв, которого ждал
     * слушатель статуса, уже давно случился). Адаптер отключаем, иначе следующий же
     * снимок CONNECTED тут же открыл бы новую сессию.
     */
    suspend fun finishByUser() {
        controller.requestFinish()
        if (obdRepo.connectionStatus.value.state != ConnectionState.DISCONNECTED) {
            obdRepo.disconnect()
        }
        tripMutex.withLock {
            closeTripLocked()
            controller.consumeFinishRequest()
        }
    }

    private suspend fun onConnectionState(state: ConnectionState) {
        when {
            // Переход → CONNECTED: открываем новую сессию (если еще нет открытой).
            state == ConnectionState.CONNECTED && currentTripId == null -> {
                val profile = vehicleRepo.observeActive().firstOrNull() ?: return
                val tripId = startTrip(profile.id)
                currentTripId = tripId
                controller.onTripStarted(tripId)
                startService()
            }
            // Переход → DISCONNECTED/ERROR с открытой сессией. Закрываем её ТОЛЬКО по
            // явному «Завершить»/«Откл.» (флаг в TripController). Иначе это обрыв:
            // вышли из машины, заглушили двигатель — OBD-сессия встаёт на паузу и
            // продолжится в ту же запись после переподключения. GPS-сессия пишется дальше.
            (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) &&
                currentTripId != null -> {
                if (controller.consumeFinishRequest()) {
                    closeTripLocked()
                } else if (!controller.gpsMode.value) {
                    controller.setPaused(true)
                }
            }
            // Возврат CONNECTED при уже открытой сессии (только на переходе, не на каждую
            // emission — иначе гонялись бы с watchdog'ом, который ставит паузу по данным).
            state == ConnectionState.CONNECTED &&
                currentTripId != null &&
                lastState != ConnectionState.CONNECTED -> {
                controller.setPaused(false)
            }
            else -> Unit
        }
    }

    /** Закрытие текущей сессии. Вызывать только под [tripMutex]; повторный вызов — no-op. */
    private suspend fun closeTripLocked() {
        val id = currentTripId ?: return
        currentTripId = null
        controller.onTripFinished()
        stopService()
        tracker.detach()
        prefs.setGpsTripId(null)
        finishTrip(id)
    }

    /** Усыновление осиротевшей сессии: если в БД есть trip с endTime=null, делаем его
     *  активным в памяти, иначе следующий коннект создаст новый trip и старый зависнет.
     *  Слишком старый (> 8 ч) — закрываем сразу, чтобы не считать простой «паузой». */
    private suspend fun adoptOrphanIfAny() {
        val orphan = tripRepo.getActiveTripOnce() ?: return
        val gpsTripId = prefs.gpsTripId.first()
        val now = System.currentTimeMillis()
        val ageMs = now - orphan.startTime
        if (ageMs > ORPHAN_MAX_AGE_MS) {
            finishTrip(orphan.id)
            prefs.setGpsTripId(null)
            return
        }
        // Простой между последней записью и текущим моментом — это время, когда
        // приложение было убито. Записей не было — засчитываем как паузу, иначе UI
        // покажет «активный» интервал, в котором на самом деле записано 0 точек.
        val lastReadingTs = tripRepo.lastReadingTimestamp(orphan.id)
        val downtimeMs = lastReadingTs?.let { (now - it).coerceAtLeast(0L) } ?: ageMs
        if (downtimeMs > 1_000L) tripRepo.addPausedMs(orphan.id, downtimeMs)
        val adopted = tripRepo.getActiveTripOnce()
        currentTripId = orphan.id
        controller.onTripAdopted(
            tripId = orphan.id,
            startTime = orphan.startTime,
            pausedMsSoFar = adopted?.pausedMs ?: orphan.pausedMs,
            gpsMode = orphan.id == gpsTripId,
        )
        startService()
    }

    private fun startService() {
        val intent = Intent(context, TripForegroundService::class.java).apply {
            action = TripForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopService() {
        val intent = Intent(context, TripForegroundService::class.java).apply {
            action = TripForegroundService.ACTION_STOP
        }
        runCatching { context.startService(intent) }
    }
}
