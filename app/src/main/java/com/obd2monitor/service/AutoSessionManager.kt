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
    /** Во время ручной паузы адаптер отключался (заглушили двигатель). Следующее
     *  подключение — это новый запуск, и ручная пауза снимается. Без этого флага её
     *  снимал бы и лёгкий реконнект при работающем двигателе (CONNECTED → … → CONNECTED). */
    private var manualPauseSawDisconnect = false
    /** С какого момента сервис запущен «впрок» на время подключения, когда сессии ещё нет.
     *  Запускаем его на нажатии «Подключить» / автоподключении, пока приложение на экране:
     *  из фона Android 12+ не даёт стартовать foreground-сервис, и раньше сессия,
     *  открытая после того как пользователь свернул приложение, оставалась без записи
     *  (а сам процесс без сервиса система замораживала, и подключение не доходило до конца). */
    private var standbySince: Long? = null

    companion object {
        /** Сколько держим сервис в ожидании адаптера, если подключиться так и не удалось. */
        private const val CONNECT_STANDBY_MAX_MS = 10 * 60_000L
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
            val adopted = runCatching { tripMutex.withLock { adoptOrphanIfAny() } }.getOrDefault(false)
            obdRepo.connectionStatus.onEach { status ->
                val state = status.state
                try {
                    tripMutex.withLock { onConnectionState(state) }
                } catch (_: Throwable) {
                    // Не валим приложение из-за гонки — попробуем при следующем переходе.
                }
                lastState = state
            }.launchIn(scope)
            // Ручную паузу снимают и не отсюда (сервис — когда GPS-сессия поехала):
            // флаг в настройках чистим здесь, в одном месте.
            controller.manualPause.onEach { manual ->
                if (!manual) {
                    manualPauseSawDisconnect = false
                    prefs.setManualPauseTripId(null)
                }
            }.launchIn(scope)
            // Уже после подписки на статус — чтобы переходы подключения не прошли мимо.
            if (adopted) connectToLastAdapter()
        }

        // Watchdog «частичного разрыва»: CONNECTED, но данные не идут >10 c — пауза.
        // GPS-сессию не трогаем: она пишется и без ЭБУ (паузу ей ставит сервис на стоянке).
        // Так же и OBD-сессию, которая сейчас пишется по GPS (адаптер отвалился на ходу).
        scope.launch {
            while (isActive) {
                delay(2000)
                if (currentTripId == null || controller.gpsMode.value || controller.gpsFallback.value) continue
                val s = obdRepo.connectionStatus.value
                if (s.state != ConnectionState.CONNECTED) continue
                val now = System.currentTimeMillis()
                val ageMs = s.lastSnapshotAt?.let { now - it } ?: Long.MAX_VALUE
                val stale = ageMs > STALE_DATA_THRESHOLD_MS || !s.lastSnapshotHadData
                if (stale && !controller.paused.value) {
                    controller.setPaused(true)
                } else if (!stale && controller.paused.value && !controller.manualPause.value) {
                    controller.setPaused(false)
                }
            }
        }

        // Watchdog авто-завершения: сессия в непрерывной автопаузе дольше 8 ч закрывается,
        // чтобы пользователь не вернулся через сутки к давно мёртвой «активной» сессии.
        // Ручную паузу не трогаем: её поставили намеренно (ночёвка, долгая стоянка).
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (currentTripId == null || controller.manualPause.value) continue
                val pauseStart = controller.pauseSinceMs.value ?: continue
                if (System.currentTimeMillis() - pauseStart >= ORPHAN_MAX_AGE_MS) {
                    runCatching { tripMutex.withLock { closeTripLocked() } }
                }
            }
        }

        // Watchdog ожидания адаптера: адаптер так и не подключился — снимаем сервис,
        // чтобы уведомление и GPS не висели без сессии.
        scope.launch {
            while (isActive) {
                delay(30_000)
                runCatching {
                    tripMutex.withLock {
                        val since = standbySince ?: return@withLock
                        if (currentTripId == null &&
                            System.currentTimeMillis() - since >= CONNECT_STANDBY_MAX_MS
                        ) stopStandbyLocked()
                    }
                }
            }
        }
    }

    /** Кнопка «Пауза»: запись останавливается без ограничения по времени. */
    suspend fun pauseByUser() = tripMutex.withLock {
        val id = currentTripId ?: return@withLock
        manualPauseSawDisconnect = obdRepo.connectionStatus.value.state != ConnectionState.CONNECTED
        controller.setManualPause(true)
        prefs.setManualPauseTripId(id)
    }

    /** Кнопка «Продолжить». Если адаптер не на связи, OBD-сессия остаётся на обычной
     *  автопаузе: её отсчёт до авто-завершения начинается заново, с этого момента. */
    suspend fun resumeByUser() = tripMutex.withLock {
        if (currentTripId == null) return@withLock
        controller.setManualPause(false)
        controller.setPaused(false)
        val s = obdRepo.connectionStatus.value
        val obdFlowing = s.state == ConnectionState.CONNECTED && s.lastSnapshotHadData
        if (!controller.gpsMode.value && !obdFlowing) controller.setPaused(true)
    }

    /** Ручной старт записи по GPS (кнопка на вкладке «Сессия»). */
    suspend fun startGpsTrip() = tripMutex.withLock {
        if (currentTripId != null) return@withLock
        val profile = vehicleRepo.observeActive().firstOrNull() ?: return@withLock
        val tripId = startTrip(profile.id)
        currentTripId = tripId
        BootReceiver.setEnabled(context, true)
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
                BootReceiver.setEnabled(context, true)
                controller.onTripStarted(tripId)
                // Сервис обычно уже работает с момента подключения и сам подхватит
                // сессию. Повторный старт из фона Android может запретить — не страшно.
                val wasStandby = standbySince != null
                standbySince = null
                if (wasStandby) runCatching { startService() } else startService()
            }
            // Начало подключения без открытой сессии: поднимаем сервис заранее, пока
            // приложение на экране (см. standbySince). Из фона (авто-recovery) старт
            // может не пройти — тогда всё как раньше.
            (state == ConnectionState.CONNECTING || state == ConnectionState.INITIALIZING) &&
                currentTripId == null && standbySince == null -> {
                runCatching { startService() }.onSuccess { standbySince = System.currentTimeMillis() }
            }
            // «Откл.» до того, как адаптер подключился, — сервис больше не нужен.
            state == ConnectionState.DISCONNECTED && currentTripId == null && standbySince != null ->
                stopStandbyLocked()
            // Переход → DISCONNECTED/ERROR с открытой сессией. Закрываем её ТОЛЬКО по
            // явному «Завершить»/«Откл.» (флаг в TripController). Иначе это обрыв:
            // вышли из машины, заглушили двигатель — OBD-сессия встаёт на паузу и
            // продолжится в ту же запись после переподключения. GPS-сессия пишется дальше.
            (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) &&
                currentTripId != null -> {
                if (controller.consumeFinishRequest()) {
                    closeTripLocked()
                } else {
                    if (controller.manualPause.value) manualPauseSawDisconnect = true
                    // Пока машина едет и пишется по GPS, неудачные попытки переподключения
                    // (CONNECTING → ERROR) паузу не ставят — её снимет/поставит сервис.
                    if (!controller.gpsMode.value && !controller.gpsFallback.value) controller.setPaused(true)
                }
            }
            // Возврат CONNECTED при уже открытой сессии (только на переходе, не на каждую
            // emission — иначе гонялись бы с watchdog'ом, который ставит паузу по данным).
            // Ручная пауза держится, пока адаптер не отключался: снимается только при
            // новом запуске двигателя.
            state == ConnectionState.CONNECTED &&
                currentTripId != null &&
                lastState != ConnectionState.CONNECTED -> {
                if (!controller.manualPause.value || manualPauseSawDisconnect) {
                    controller.setManualPause(false)
                    controller.setPaused(false)
                }
                controller.setGpsFallback(false)
            }
            else -> Unit
        }
    }

    /** Снятие сервиса, запущенного на время подключения. Только под [tripMutex]. */
    private fun stopStandbyLocked() {
        standbySince = null
        stopService()
    }

    /** Закрытие текущей сессии. Вызывать только под [tripMutex]; повторный вызов — no-op. */
    private suspend fun closeTripLocked() {
        val id = currentTripId ?: return
        currentTripId = null
        BootReceiver.setEnabled(context, false)
        standbySince = null
        controller.onTripFinished()
        stopService()
        tracker.detach()
        prefs.setGpsTripId(null)
        prefs.setManualPauseTripId(null)
        finishTrip(id)
    }

    /** Усыновление осиротевшей сессии: если в БД есть trip с endTime=null, делаем его
     *  активным в памяти, иначе следующий коннект создаст новый trip и старый зависнет.
     *  Без записей дольше 8 ч — закрываем сразу, чтобы не считать простой «паузой»;
     *  поездку на ручной паузе оставляем открытой сколько угодно. */
    private suspend fun adoptOrphanIfAny(): Boolean {
        val orphan = tripRepo.getActiveTripOnce()
        // Приёмник загрузки нужен, только пока есть открытая поездка (см. BootReceiver).
        // Здесь — и для поездок, открытых до появления приёмника.
        BootReceiver.setEnabled(context, orphan != null)
        if (orphan == null) return false
        val gpsTripId = prefs.gpsTripId.first()
        val manual = prefs.manualPauseTripId.first() == orphan.id
        val now = System.currentTimeMillis()
        val lastReadingTs = tripRepo.lastReadingTimestamp(orphan.id)
        if (!manual && now - (lastReadingTs ?: orphan.startTime) > ORPHAN_MAX_AGE_MS) {
            finishTrip(orphan.id)
            BootReceiver.setEnabled(context, false)
            prefs.setGpsTripId(null)
            prefs.setManualPauseTripId(null)
            return false
        }
        // Простой между последней записью и текущим моментом — это время, когда
        // приложение было убито. Записей не было — засчитываем как паузу, иначе UI
        // покажет «активный» интервал, в котором на самом деле записано 0 точек.
        val pausedMs = tripRepo.pausedMsOnAdopt(orphan.id, now)
        currentTripId = orphan.id
        controller.onTripAdopted(
            tripId = orphan.id,
            startTime = orphan.startTime,
            pausedMsSoFar = pausedMs,
            gpsMode = orphan.id == gpsTripId,
            manualPause = manual,
        )
        // Процесс перезапущен — адаптер заведомо отключён, следующее подключение
        // снимет ручную паузу.
        manualPauseSawDisconnect = manual
        // Процесс мог подняться сам, без экрана (после перезагрузки телефона — см.
        // BootReceiver): из фона Android может не дать стартовать foreground-сервису.
        // Сессию это не отменяет — сервис поднимется при открытии приложения.
        runCatching { startService() }
        return true
    }

    /**
     * Подключение к последнему адаптеру без открытого приложения, когда есть поездка,
     * ждущая продолжения. Раньше подключение запускала только MainActivity: телефон
     * перезагрузился на стоянке — и обратная дорога не писалась, пока приложение не
     * откроют. Адаптер сейчас без питания (двигатель заглушен) — не страшно:
     * OBDRepository повторяет попытки сам (scheduleRecovery), пока машину не заведут.
     */
    private suspend fun connectToLastAdapter() {
        if (!prefs.autoConnect.first()) return
        val addr = prefs.lastDeviceAddress.first()?.takeIf { it.isNotBlank() } ?: return
        if (obdRepo.connectionStatus.value.state != ConnectionState.DISCONNECTED) return
        runCatching { obdRepo.connect(addr) }
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
