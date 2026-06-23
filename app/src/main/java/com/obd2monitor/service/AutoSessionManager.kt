package com.obd2monitor.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Авто-сессии:
 *  - при подключении к OBD сессия запускается сама,
 *  - при отключении — закрывается,
 *  - при «частичном разрыве» (CONNECTED, но данные не приходят >10 c) — ставим на паузу
 *    через TripController.setPaused(true). Сессия остаётся открытой, запись OBD-readings
 *    приостанавливается до восстановления потока данных.
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        // Запускаем последовательно: сначала усыновление осиротевшей сессии (если есть),
        // потом подписка на connectionStatus. Это критично — если listener стартует
        // раньше, чем currentTripId выставлен в id осиротевшего trip'а, то на первое же
        // CONNECTED (MainActivity делает attemptAutoConnect параллельно) сработает ветка
        // «currentTripId == null → создать новый trip», и старый осиротевший останется
        // лежать в БД незакрытым.
        scope.launch {
            runCatching { adoptOrphanIfAny() }
            obdRepo.connectionStatus.onEach { status ->
                val state = status.state
                try {
                    when {
                        // Переход → CONNECTED: открываем новую сессию (если еще нет открытой).
                        state == ConnectionState.CONNECTED && currentTripId == null -> {
                            val profile = vehicleRepo.observeActive().firstOrNull()
                            if (profile != null) {
                                val tripId = startTrip(profile.id)
                                currentTripId = tripId
                                controller.onTripStarted(tripId)
                                startService()
                            }
                        }
                        // Переход → DISCONNECTED/ERROR с открытой сессией.
                        //
                        // Закрываем сессию ТОЛЬКО если пользователь явно нажал
                        // «Завершить» (controller.requestFinish() ставит флаг). Иначе —
                        // ставим на паузу и оставляем trip открытым: BT может оборваться
                        // потому что юзер вышел из машины / заглушил двигатель → когда
                        // вернётся и ELM снова подключится, запись продолжится в ту же
                        // сессию. Без этого любой разрыв закрывал бы trip, и продолжать
                        // поездку приходилось бы вручную каждый раз.
                        (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) &&
                            currentTripId != null -> {
                            if (controller.consumeFinishRequest()) {
                                val id = currentTripId
                                currentTripId = null
                                controller.onTripFinished()
                                stopService()
                                if (id != null) finishTrip(id)
                            } else {
                                controller.setPaused(true)
                            }
                        }
                        // Возврат CONNECTED при уже открытой сессии (только на переходе,
                        // не на каждую emission в CONNECTED — иначе бы тут гонялись с
                        // watchdog'ом ниже, который пауза/анпауза по lastSnapshotAt).
                        state == ConnectionState.CONNECTED &&
                            currentTripId != null &&
                            lastState != ConnectionState.CONNECTED -> {
                            controller.setPaused(false)
                        }
                        else -> Unit
                    }
                } catch (_: Throwable) {
                    // Не валим приложение из-за гонки — попробуем при следующем переходе.
                }
                lastState = state
            }.launchIn(scope)
        }

        // Watchdog «частичного разрыва»: смотрим на ConnectionStatus.lastSnapshotAt.
        // Когда CONNECTED, но данные не идут >10 c — пауза. Когда снова пошли — снимаем.
        scope.launch {
            while (isActive) {
                delay(2000)
                if (currentTripId == null) continue
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

        // Watchdog авто-завершения. Сессия в непрерывной паузе дольше ORPHAN_MAX_AGE_MS
        // (8 ч) → закрываем сами как полноценный finish (а не оставляем «висеть» до
        // следующего рестарта приложения). Это та же логика что adoption на старте,
        // только применяется в runtime — чтобы пользователь не вернулся через сутки и
        // не увидел давно мёртвую сессию активной.
        scope.launch {
            while (isActive) {
                delay(30_000)
                val tripId = currentTripId ?: continue
                val pauseStart = controller.pauseSinceMs.value ?: continue
                val pausedFor = System.currentTimeMillis() - pauseStart
                if (pausedFor >= ORPHAN_MAX_AGE_MS) {
                    runCatching {
                        currentTripId = null
                        controller.onTripFinished()
                        stopService()
                        finishTrip(tripId)
                    }
                }
            }
        }
    }

    /** Усыновление осиротевшей сессии: если в БД есть trip с endTime=null, делаем его
     *  активным в памяти, иначе следующий коннект создаст новый trip и старый зависнет.
     *  Слишком старый (>24 ч) — закрываем сразу, чтобы не считать сутки простоя «паузой». */
    private suspend fun adoptOrphanIfAny() {
        val orphan = tripRepo.getActiveTripOnce() ?: return
        val now = System.currentTimeMillis()
        val ageMs = now - orphan.startTime
        if (ageMs > ORPHAN_MAX_AGE_MS) {
            finishTrip(orphan.id)
            return
        }
        // Простой между последней записью и текущим моментом — это время, когда
        // приложение было убито. Записей не было, ECU вероятно не отвечал — поэтому
        // засчитываем как паузу. Иначе UI покажет 30-минутный «активный» интервал, в
        // котором на самом деле записано 0 точек.
        val lastReadingTs = tripRepo.lastReadingTimestamp(orphan.id)
        val downtimeMs = lastReadingTs?.let { (now - it).coerceAtLeast(0L) } ?: ageMs
        if (downtimeMs > 1_000L) tripRepo.addPausedMs(orphan.id, downtimeMs)
        val adopted = tripRepo.getActiveTripOnce()
        currentTripId = orphan.id
        controller.onTripAdopted(
            tripId = orphan.id,
            startTime = orphan.startTime,
            pausedMsSoFar = adopted?.pausedMs ?: orphan.pausedMs,
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
