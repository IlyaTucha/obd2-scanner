package com.obd2monitor.service

import com.obd2monitor.domain.repository.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Общий Singleton для координации поездки между ViewModel и Foreground Service.
 *
 * Хранит:
 *  - флаг паузы
 *  - накопленную длительность активной записи (durationMs), которая инкрементируется
 *    раз в 500 мс ТОЛЬКО когда paused == false. Это значение переживает уход с экрана
 *    и возвращается живым (хранится в singleton, а не в Composable remember{}).
 *  - id текущего trip'а — чтобы сбрасывать счётчик при старте новой сессии.
 *
 * Таким образом, переход на вкладку «Сессии» (история) больше не сбрасывает счётчик,
 * а на паузе он действительно замораживается.
 */
@Singleton
class TripController @Inject constructor(
    // Provider — TripRepository зависит от Room, который инициализируется в
    // Application.onCreate(). Singleton-граф разрулится без циклов только через
    // ленивую ссылку.
    private val tripRepoProvider: Provider<TripRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _pausedMs = MutableStateFlow(0L)
    /** Накопленная пауза за текущую сессию (in-memory + DB). */
    val pausedMs: StateFlow<Long> = _pausedMs.asStateFlow()

    private val _pauseSinceMs = MutableStateFlow<Long?>(null)
    /** Момент начала ТЕКУЩЕЙ непрерывной паузы (или null если запись активна).
     *  Используется UI для отображения «сессия будет завершена через Х мин», и
     *  AutoSessionManager-watchdog'ом для авто-finish после длительного простоя. */
    val pauseSinceMs: StateFlow<Long?> = _pauseSinceMs.asStateFlow()

    private val _gpsMode = MutableStateFlow(false)
    /** Сессия начата вручную кнопкой «Запись по GPS». В этом режиме обрыв OBD не
     *  ставит запись на паузу — маршрут, скорость и пробег пишутся по спутникам. */
    val gpsMode: StateFlow<Boolean> = _gpsMode.asStateFlow()

    private val _lastGpsFixAt = MutableStateFlow<Long?>(null)
    /** Время последнего принятого GPS-фикса — UI показывает «ожидание сигнала GPS». */
    val lastGpsFixAt: StateFlow<Long?> = _lastGpsFixAt.asStateFlow()

    private var currentTripId: String? = null
    // Несбросанный delta-pause, который ещё не записали в DB. Флашим раз в FLUSH_EVERY_MS,
    // чтобы не делать update раз в 500 мс.
    private var unflushedPauseMs: Long = 0L
    private var lastFlushAt: Long = 0L

    companion object {
        /** Сколько непрерывной паузы до авто-завершения сессии. Та же константа что
         *  AutoSessionManager.ORPHAN_MAX_AGE_MS — это два проявления одного правила:
         *  «сессия в простое >N часов считается законченной». UI использует её для
         *  отсчёта «осталось до авто-завершения». */
        const val AUTO_FINISH_PAUSE_MS = 8L * 60 * 60 * 1000  // 8 ч
        private const val FLUSH_EVERY_MS = 5000L
    }

    /**
     * Флаг «пользователь явно нажал Завершить». Взводится из UI ПЕРЕД вызовом
     * obdRepo.disconnect(); AutoSessionManager при переходе CONNECTED→DISCONNECTED
     * читает и сбрасывает его. true → закрыть trip; false → просто пауза.
     *
     * Зачем: BT-соединение может оборваться не только по воле пользователя
     * (отошёл от машины, заглушил двигатель — питание ELM пропало). В этих
     * случаях нельзя автоматически закрывать сессию — пользователь продолжит
     * запись, когда вернётся. Закрытие должно быть явным жестом.
     */
    private val _finishRequested = MutableStateFlow(false)

    fun requestFinish() { _finishRequested.value = true }
    fun consumeFinishRequest(): Boolean {
        val v = _finishRequested.value
        _finishRequested.value = false
        return v
    }

    init {
        scope.launch {
            var lastTick = System.currentTimeMillis()
            lastFlushAt = lastTick
            while (isActive) {
                delay(500)
                val now = System.currentTimeMillis()
                val delta = (now - lastTick).coerceAtLeast(0)
                lastTick = now
                val tripId = currentTripId
                if (tripId != null) {
                    if (_paused.value) {
                        _pausedMs.value = _pausedMs.value + delta
                        unflushedPauseMs += delta
                    } else {
                        _durationMs.value = _durationMs.value + delta
                    }
                    // Раз в FLUSH_EVERY_MS пишем накопленную паузу в БД, чтобы
                    // переживать force-stop без потери учёта пауз.
                    if (unflushedPauseMs > 0 && now - lastFlushAt >= FLUSH_EVERY_MS) {
                        val toFlush = unflushedPauseMs
                        unflushedPauseMs = 0L
                        lastFlushAt = now
                        runCatching { tripRepoProvider.get().addPausedMs(tripId, toFlush) }
                    }
                }
            }
        }
    }

    fun setPaused(value: Boolean) {
        val wasPaused = _paused.value
        _paused.value = value
        // Запоминаем момент входа в паузу один раз — повторные setPaused(true) не
        // обнуляют отсчёт, иначе watchdog никогда не триггерит auto-finish.
        if (value && !wasPaused) {
            _pauseSinceMs.value = System.currentTimeMillis()
        } else if (!value) {
            _pauseSinceMs.value = null
        }
    }

    fun onGpsFix(at: Long) { _lastGpsFixAt.value = at }

    /** Вызывать при старте новой сессии. Сбрасывает счётчик. */
    fun onTripStarted(tripId: String, gpsMode: Boolean = false) {
        currentTripId = tripId
        _gpsMode.value = gpsMode
        _lastGpsFixAt.value = null
        _durationMs.value = 0L
        _pausedMs.value = 0L
        unflushedPauseMs = 0L
        lastFlushAt = System.currentTimeMillis()
        _paused.value = false
        _pauseSinceMs.value = null
        _finishRequested.value = false
    }

    /** Усыновление осиротевшей сессии после force-stop'а. Подхватывает уже
     *  накопленные durationMs/pausedMs из БД, чтобы UI показывал корректное
     *  время (а не «00:00:00» сразу после рестарта приложения). */
    fun onTripAdopted(tripId: String, startTime: Long, pausedMsSoFar: Long, gpsMode: Boolean) {
        currentTripId = tripId
        _gpsMode.value = gpsMode
        _lastGpsFixAt.value = null
        val now = System.currentTimeMillis()
        val elapsed = (now - startTime).coerceAtLeast(0L)
        _pausedMs.value = pausedMsSoFar
        _durationMs.value = (elapsed - pausedMsSoFar).coerceAtLeast(0L)
        unflushedPauseMs = 0L
        lastFlushAt = now
        // Сессия начинается с паузы — записи нет до восстановления подключения
        // (GPS-сессию снимает с паузы сервис, как только машина поедет).
        _paused.value = true
        // Отсчёт «текущей» паузы стартуем с сейчас. Не с момента app-death, потому что
        // pausedMsSoFar уже содержит downtime (см. AutoSessionManager.adoptOrphanIfAny),
        // и иначе watchdog мгновенно бы триггерил auto-finish.
        _pauseSinceMs.value = now
        _finishRequested.value = false
    }

    /** Вызывать при завершении сессии. Останавливает счётчик. */
    fun onTripFinished() {
        val tripId = currentTripId
        val toFlush = unflushedPauseMs
        currentTripId = null
        unflushedPauseMs = 0L
        _gpsMode.value = false
        _lastGpsFixAt.value = null
        _paused.value = false
        _pauseSinceMs.value = null
        _finishRequested.value = false
        // Финальный flush, чтобы итоговое pausedMs было точным.
        if (tripId != null && toFlush > 0) {
            scope.launch { runCatching { tripRepoProvider.get().addPausedMs(tripId, toFlush) } }
        }
    }

    fun reset() {
        _paused.value = false
    }
}
