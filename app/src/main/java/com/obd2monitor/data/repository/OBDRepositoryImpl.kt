package com.obd2monitor.data.repository

import com.obd2monitor.data.bluetooth.BluetoothObdConnection
import com.obd2monitor.data.bluetooth.ELM327Protocol
import com.obd2monitor.data.obd.FuelCalculator
import com.obd2monitor.data.obd.OBDCommand
import com.obd2monitor.data.obd.OBDParser
import com.obd2monitor.data.obd.PIDRepository
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.domain.model.DTCStatus
import com.obd2monitor.domain.model.DtcScanResult
import com.obd2monitor.domain.model.EcuInfo
import com.obd2monitor.domain.model.FreezeFrame
import com.obd2monitor.domain.model.OBDData
import com.obd2monitor.domain.model.OBDProtocol
import com.obd2monitor.domain.repository.DTCRepository
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OBDRepositoryImpl @Inject constructor(
    private val bt: BluetoothObdConnection,
    private val elm: ELM327Protocol,
    private val pids: PIDRepository,
    private val vehicleRepo: VehicleProfileRepository,
    private val dtcRepo: DTCRepository,
) : OBDRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioMutex = Mutex()

    private val _status = MutableStateFlow(ConnectionStatus())
    private val _liveData = MutableSharedFlow<OBDData>(replay = 1, extraBufferCapacity = 16)
    private var pollJob: Job? = null
    private var recoveryJob: Job? = null

    // Антиспайк скорости (см. sanitizeSpeed). Поля защищены ioMutex —
    // readSnapshot/startPolling всегда работают под ним или последовательно.
    private var speedAccepted: Int? = null
    private var speedAcceptedAt: Long = 0L
    private var speedPending: Int? = null

    // Последний полный снимок — нужен чтобы "медленные" PID не обнулялись между
    // быстрыми циклами. Каждый fast-цикл копируем сюда обновлённые горячие PID,
    // slow-группа (одна за цикл, см. SLOW_GROUPS) — обновляет остальное.
    @Volatile
    private var lastSnapshot: OBDData = OBDData()
    private val cacheLock = Any()

    /** Суффикс «1» к Mode 01: ELM отдаёт ответ сразу после первого ЭБУ, а не ждёт
     *  таймаут на случай второго. Без него каждый PID стоит лишние 100–200 мс.
     *  Включается, только если адаптер правильно ответил на пробный запрос
     *  (см. probeSingleResponse) — часть клонов на такую команду отвечает «?». */
    @Volatile
    private var pidSuffix = ""

    private fun pid(command: String) = command + pidSuffix

    // "Медленные" PID — температуры, бортсеть, бак, одометры, trims, oil, ambient —
    // меняются редко. Читаем их группами по очереди: каждый цикл — одну группу из
    // SLOW_GROUPS, чтобы цикл оставался коротким и скорость с оборотами обновлялись
    // равномерно.
    private companion object {
        const val SLOW_GROUPS = 5
        /** slowGroup для первого снимка: читаем все медленные PID разом. */
        const val ALL_GROUPS = -1
        /** Пауза между снимками. Сам снимок (5 «горячих» PID) занимает 100–300 мс,
         *  так что итоговая частота 3–7 Гц; пауза лишь даёт адаптеру передышку. */
        const val POLL_PAUSE_MS = 50L
        /** Mode 03/07/0A на K-line и при нескольких ЭБУ отвечают дольше обычного PID. */
        const val DTC_READ_TIMEOUT_MS = 8_000L

        // ── Антиспайк скорости ──
        /** Максимальное физически правдоподобное изменение скорости, км/ч за секунду.
         *  ~1g экстренного торможения ≈ 35 км/ч/с; разгон у Accent втрое меньше. */
        const val MAX_SPEED_DELTA_KMH_PER_S = 35f
        /** Нижний пол окна при очень частом опросе — чтобы не резать честные значения. */
        const val MIN_SPEED_DELTA_KMH = 10f
        /** Два подряд «выброса», расходящихся не более чем на столько, — это не выброс,
         *  а реальное резкое изменение: принимаем. */
        const val SPEED_CONFIRM_TOLERANCE_KMH = 20
        /** Выше этого — заведомый мусор с шины (0xFF=255 режется ещё в парсере). */
        const val MAX_PLAUSIBLE_SPEED_KMH = 220
    }

    /**
     * Фильтр одиночных пиков скорости («190 или 255 км/ч на ровном месте»).
     * ELM-клоны изредка отдают мусорный байт (рассинхрон буфера после таймаута,
     * потерянный байт на ISO9141/KWP), который проходит парсер как валидный ответ
     * `41 0D BE` → в истории и на графике вырастает пик.
     *
     * Правило: значение принимается, если оно достижимо от последнего принятого
     * (≤ MAX_SPEED_DELTA_KMH_PER_S × прошедшее время), ИНАЧЕ — откладывается и
     * принимается только если СЛЕДУЮЩИЙ снимок его подтвердит (±CONFIRM_TOLERANCE).
     * Честное резкое изменение проходит с лагом в один цикл (~0.3–0.5 c), одиночный
     * мусорный байт — никогда. Первое значение сессии тоже требует подтверждения:
     * мусор любит проскакивать именно в начале, пока шина «шумит» после init.
     * Пока выброс не подтверждён, наружу отдаётся последнее принятое значение.
     */
    private fun sanitizeSpeed(raw: Int?): Int? {
        val v = raw ?: return null
        val now = System.currentTimeMillis()
        if (v > MAX_PLAUSIBLE_SPEED_KMH) return speedAccepted
        fun accept(): Int { speedAccepted = v; speedAcceptedAt = now; speedPending = null; return v }
        val pending = speedPending
        val confirmed = pending != null && kotlin.math.abs(v - pending) <= SPEED_CONFIRM_TOLERANCE_KMH
        val last = speedAccepted ?: return if (confirmed) accept() else { speedPending = v; null }
        // Окно растёт с паузой между принятыми значениями: после разрыва связи
        // первое же значение пройдёт без подтверждения.
        val dtS = ((now - speedAcceptedAt) / 1000f).coerceAtLeast(0.05f)
        val maxDelta = (MAX_SPEED_DELTA_KMH_PER_S * dtS).coerceAtLeast(MIN_SPEED_DELTA_KMH)
        if (kotlin.math.abs(v - last) <= maxDelta) return accept()
        // Сбой старшего бита: 17 км/ч приходит как 0x91 = 145, и так несколько секунд
        // подряд — подтверждение такой выброс не отсеивает. Если без старшего бита
        // значение достижимо, а с ним нет, — это та же скорость с битой единицей.
        val masked = v and 0x7F
        if (v > 0x7F && kotlin.math.abs(masked - last) <= maxDelta) {
            speedAccepted = masked; speedAcceptedAt = now; speedPending = null
            return masked
        }
        if (confirmed) return accept()
        speedPending = v
        return last
    }

    override val connectionStatus: StateFlow<ConnectionStatus> get() = _status.asStateFlow()
    override val liveData: SharedFlow<OBDData> get() = _liveData.asSharedFlow()

    override suspend fun connect(deviceAddress: String) {
        // Идемпотентность: уже подключены к этому адресу и поллинг жив → ничего не
        // трогаем. Повторный вызов не должен сносить рабочий pollJob и сокет ради
        // холодного handshake заново. Activity на повороте/смене темы НЕ пересоздаётся
        // (configChanges в манифесте), так что это подстраховка от повторного коннекта
        // по уже живому соединению — повторный тап «Подключить» в настройках, редкое
        // пересоздание Activity при живом процессе («Не сохранять действия»). Чинить
        // живое, но залипшее соединение — задача watchdog'а в startPolling, не connect().
        if (pollJob?.isActive == true &&
            _status.value.state == ConnectionState.CONNECTED &&
            _status.value.deviceAddress == deviceAddress
        ) return
        // Отменяем фоновые задачи и поллинг перед новым подключением, чтобы повторный
        // вызов connect() не шёл поверх живого pollJob: два потока делили бы один сокет
        // и ломали init. Старый/ghost-сокет закрывает establishConnection на первой попытке.
        recoveryJob?.cancel(); recoveryJob = null
        pollJob?.cancel(); pollJob = null
        _status.value = ConnectionStatus(state = ConnectionState.CONNECTING, deviceAddress = deviceAddress)
        val preferred = vehicleRepo.observeActive().firstOrNull()?.obdProtocol
        try {
            // Единая процедура установки: несколько попыток, каждый ретрай — на свежем
            // сокете (см. establishConnection). Это делает «холодный» первый коннект
            // таким же надёжным, как реконнекты с паузы в течение дня.
            val init = establishConnection(deviceAddress, preferred)
            pids.set(init.supportedPids)
            _status.value = ConnectionStatus(
                state = ConnectionState.CONNECTED,
                deviceAddress = deviceAddress,
                elmVersion = init.version,
                protocol = init.protocol,
                supportedPidsCount = init.supportedPids.size,
                initRawResponse = init.lastRawResponse.takeIf { it.isNotBlank() },
            )
            startPolling()
        } catch (t: Throwable) {
            // Сохраняем deviceAddress — чтобы кнопка «Переподключить» в UI осталась
            // активной даже при провале handshake. CONNECTED при пустой маске не
            // объявляем (иначе откроется trip, встанет на «Паузу», карта пишется пустой).
            // scheduleRecovery() продолжит повторы свежим сокетом в фоне.
            _status.value = ConnectionStatus(
                state = ConnectionState.ERROR,
                deviceAddress = deviceAddress,
                errorMessage = t.message,
            )
            try { bt.close() } catch (_: Throwable) {}
            scheduleRecovery()
        }
    }

    override suspend fun disconnect() {
        recoveryJob?.cancel(); recoveryJob = null
        pollJob?.cancel(); pollJob = null
        bt.close()
        pids.clear()
        _status.value = ConnectionStatus(state = ConnectionState.DISCONNECTED)
    }

    /**
     * Авто-переподключение после ошибки (ТЗ §13): 3 попытки с шагом 5 c, дальше —
     * раз в 30 c, пока статус ERROR и адрес устройства известен.
     *
     * Закрывает дыры, до которых не дотягивается watchdog внутри pollJob:
     *  - первый connect() упал (типично: ELM ещё грузится после включения зажигания,
     *    auto-connect на старте приложения ловит таймаут) → pollJob так и не стартовал,
     *    и без этого цикла восстанавливать связь больше некому — сессия висела бы на паузе;
     *  - ручной/watchdog reconnect() упал → pollJob уже отменён, восстанавливать некому.
     *
     * Пока pollJob жив — не вмешиваемся: его двухступенчатый stale-watchdog сам чинит
     * связь, и параллельные попытки только дрались бы за сокет. Явный disconnect()
     * переводит статус в DISCONNECTED → цикл завершается.
     */
    private fun scheduleRecovery() {
        if (recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
            var attempt = 0
            while (isActive) {
                delay(if (attempt < 3) 5_000L else 30_000L)
                val st = _status.value
                if (st.state != ConnectionState.ERROR || st.deviceAddress == null) break
                if (pollJob?.isActive == true) break
                attempt++
                reconnect()
            }
        }
    }

    /**
     * Переподключение без полного DISCONNECT. Закрывает RFCOMM-сокет, открывает заново,
     * прогоняет ELM init и продолжает поллинг. Состояние:
     *   CONNECTED → CONNECTING → INITIALIZING → CONNECTED
     * Минуя DISCONNECTED — это специально, чтобы AutoSessionManager НЕ закрыл trip.
     *
     * Используется и кнопкой «Переподключить» в UI, и внутренним watchdog'ом (когда
     * ECU молчит >30 c И BT-сокет в неконсистентном состоянии).
     */
    override suspend fun reconnect() {
        val addr = _status.value.deviceAddress ?: return
        // Сохраняем профиль/версию ELM в статусе на время переподключения, чтобы UI
        // не «моргал» полем имени устройства.
        val prev = _status.value
        pollJob?.cancel(); pollJob = null
        _status.value = prev.copy(state = ConnectionState.CONNECTING, errorMessage = null)
        val preferred = vehicleRepo.observeActive().firstOrNull()?.obdProtocol
        try {
            // Та же процедура, что и при первом connect(), но с settleFirst: reconnect()
            // вызывается всегда ПОСЛЕ проблемы (watchdog, кнопка «Переподключить»,
            // авто-recovery после сбоя), когда адаптер заведомо «подвис». Поэтому даже
            // первую попытку начинаем с полного отдыха сокета (close → пауза → clear) —
            // ровно то, что делает ручное «Откл.»+пауза+«Подкл.», которое всегда срабатывает.
            val init = establishConnection(addr, preferred, settleFirst = true)
            pids.set(init.supportedPids)
            _status.value = ConnectionStatus(
                state = ConnectionState.CONNECTED,
                deviceAddress = addr,
                deviceName = prev.deviceName,
                elmVersion = init.version.ifBlank { prev.elmVersion },
                protocol = init.protocol,
                supportedPidsCount = init.supportedPids.size,
                initRawResponse = init.lastRawResponse.takeIf { it.isNotBlank() } ?: prev.initRawResponse,
            )
            startPolling()
        } catch (t: Throwable) {
            _status.value = _status.value.copy(state = ConnectionState.ERROR, errorMessage = t.message)
            try { bt.close() } catch (_: Throwable) {}
            scheduleRecovery()
        }
    }

    /**
     * Единая процедура установки связи для connect() и reconnect(): до [attempts]
     * попыток, КАЖДЫЙ ретрай — на свежем сокете (close → пауза → open → ELM
     * handshake). Принимаем только при непустой PID-маске (см. handshakeOrThrow).
     *
     * Зачем так. Дешёвый ELM-клон гарантированно «мусорит» на ПЕРВОМ сокете после
     * подачи питания: первая команда теряет байты, echo не выключается, и 0100
     * возвращает мусор → пустая маска. Реконнекты с паузы в течение дня потому и
     * работают всегда, что идут на уже прогретый адаптер И пересоздают сокет. Здесь
     * мы воспроизводим тот же сценарий с самого первого подключения: если первая
     * попытка не дала маску — отбрасываем сокет и пробуем заново на свежем, пока не
     * поднимется (или не исчерпаем попытки). «Тёплый» адаптер поднимается на первой
     * же попытке без задержки; «холодный» — со второй (как при ручном переподключении).
     */
    private suspend fun establishConnection(
        addr: String,
        preferred: OBDProtocol?,
        settleFirst: Boolean = false,
        attempts: Int = 3,
    ): ELM327Protocol.InitResult {
        var last: Throwable? = null
        repeat(attempts) { i ->
            try {
                // Первую попытку при «холодном» connect() начинаем с лёгкого закрытия
                // возможного старого/ghost-сокета (без паузы — тёплый адаптер не
                // замедляем). При settleFirst (recovery/reconnect, адаптер заведомо
                // подвис) и на всех ретраях идём через resetSocketState(): close +
                // пауза 1200 мс на освобождение RFCOMM-канала + сброс PID-маски и
                // кэшей — это и есть «отбросить сокет и переподключиться начисто».
                if (i == 0 && !settleFirst) ioMutex.withLock { runCatching { bt.close() } }
                else resetSocketState()
                bt.connect(addr)
                return handshakeOrThrow(preferred)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IOException("Не удалось установить связь с адаптером")
    }

    /**
     * Прогоняет ELM-handshake и возвращает результат. БРОСАЕТ, если ECU не отдал ни
     * одного поддерживаемого PID: пустая маска означает, что протокол не залочен (echo
     * не выключился / шина не поднялась — типично для «холодного» клона), и объявлять
     * CONNECTED в этом состоянии нельзя — поллинг будет вечно молчать, trip встанет на
     * «Паузу», карта запишется пустой. Тот же инвариант соблюдает hard-reconnect ветка
     * watchdog'а.
     */
    private suspend fun handshakeOrThrow(preferred: OBDProtocol?): ELM327Protocol.InitResult {
        _status.value = _status.value.copy(state = ConnectionState.INITIALIZING)
        val init = elm.initialize(preferredProtocol = preferred)
        if (init.supportedPids.isEmpty()) {
            throw IOException("Блок управления не ответил на запрос параметров")
        }
        return init
    }

    /**
     * Полный сброс сокета и кэшей перед повторной попыткой handshake. Пауза 1200 мс —
     * чтобы Android RFCOMM успел освободить канал (без неё новый connect() цепляется к
     * мёртвому сокету). Эквивалент того, что делает ручное «Переподключить».
     */
    private suspend fun resetSocketState() {
        ioMutex.withLock {
            runCatching { bt.close() }
            delay(1200L)
            pids.clear()
            synchronized(cacheLock) { lastSnapshot = OBDData() }
            speedAccepted = null; speedPending = null
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val activeProfile = vehicleRepo.observeActive().firstOrNull()
            val displacementL = activeProfile?.displacementL ?: 1.4f
            val fuel = activeProfile?.fuelType ?: com.obd2monitor.domain.model.FuelType.PETROL
            val maxTorqueNm = (activeProfile?.maxTorqueNm ?: 130).toFloat()
            val maxPowerKw = (activeProfile?.maxPowerKw ?: 70).toFloat()
            val idleTopRpm = (activeProfile?.idleRpm?.last ?: 900).toFloat()
            val coolantTargetC = (activeProfile?.normalCoolantTempC?.first ?: 85).toFloat()
            // Сколько идущих подряд снимков без данных переживаем до повторного init.
            // На скорости 5 PID/c × ~10 c → 50 пустых снимков. Делаем порог по времени:
            // если последний снимок с данными был >30 c назад — пробуем re-handshake.
            var lastDataAt = System.currentTimeMillis()
            var lastReinitAt = 0L
            // Сколько подряд попыток восстановления уже сделали БЕЗ возврата данных.
            // Обнуляется как только от ECU снова пришёл хоть один свежий hot-PID.
            // Нужен для эскалации light → hard reconnect (см. watchdog ниже).
            var staleRecoveries = 0
            var cycle = 0
            // Обнуляем кэш на старте опроса, чтобы старые значения с прошлого подключения
            // не «протекали» в новый снимок.
            synchronized(cacheLock) { lastSnapshot = OBDData() }
            speedAccepted = null; speedPending = null
            probeSingleResponse()
            while (isActive) {
                val snapshot = readSnapshot(
                    displacementL = displacementL,
                    fuel = fuel,
                    maxTorqueNm = maxTorqueNm,
                    maxPowerKw = maxPowerKw,
                    idleTopRpm = idleTopRpm,
                    coolantTargetC = coolantTargetC,
                    // Первый цикл читает всё сразу, чтобы плитки не стояли с «—».
                    slowGroup = if (cycle == 0) ALL_GROUPS else cycle % SLOW_GROUPS,
                )
                _liveData.tryEmit(snapshot)
                cycle++
                // hasData = были ли в ЭТОМ цикле свежие байты от ECU. Проверяем ТОЛЬКО
                // hot-tier PID'ы (speed/rpm/throttle/load/mapKpa) — они читаются в КАЖДОМ
                // цикле и НЕ берутся из кэша, так что null означает «ECU молчит».
                //
                // Slow-tier PID (coolant/intake/fuelLvl/battery/oil …) для hasData не
                // годятся: они подтягиваются из кэша (readSnapshot: `?: cached.X`) и после
                // первой удачной slow-итерации всегда не-null, поэтому маскировали бы
                // «ghost connection» (BT-сокет ещё рапортует подключение, а от ECU уже
                // ничего). По hot-tier разрыв виден сразу.
                val hasData = snapshot.speedKmh != null || snapshot.rpm != null ||
                    snapshot.throttlePercent != null || snapshot.engineLoadPercent != null ||
                    snapshot.mapKpa != null
                val now = System.currentTimeMillis()
                if (hasData) {
                    lastDataAt = now
                    // Данные пошли → цикл восстановления завершён, сбрасываем эскалацию,
                    // чтобы следующий разрыв снова начинался с дешёвого light-пути.
                    staleRecoveries = 0
                }
                _status.value = _status.value.copy(
                    lastSnapshotAt = now,
                    lastSnapshotHadData = hasData,
                )
                // Авто-восстановление связи. Двухступенчатый watchdog с эскалацией:
                //  1) Лёгкий путь — ECU молчит >10 c, но BT-сокет ещё жив. Шлём только
                //     ELM init (ATZ/ATE0/ATSP*). Часто помогает когда ECU «уснул» при
                //     заглушенном двигателе, а ELM остался в сессии. Пробуем 1 раз.
                //  2) Тяжёлый путь — закрываем сокет, ставим CONNECTING, открываем
                //     заново, init. Срабатывает когда сокет уже мёртв (отошли от машины /
                //     ELM перезагрузился) ИЛИ когда лёгкий путь уже отработал, а данные
                //     так и не пошли (см. эскалацию по staleRecoveries ниже — главное
                //     лекарство от «ghost connection» после перезапуска двигателя).
                //     Состояние НЕ переходит в DISCONNECTED, поэтому AutoSessionManager
                //     оставляет trip живым (только пауза), история сессии не обрывается.
                // Кулдаун 15 c — баланс между скоростью реакции на разрыв и
                // нагрузкой/риском зациклиться на неработающем адаптере.
                val stale = (now - lastDataAt) > 10_000L
                val cooldownPassed = (now - lastReinitAt) > 15_000L
                if (stale && cooldownPassed) {
                    lastReinitAt = now
                    staleRecoveries++
                    // Эскалация. Light-путь (только ELM init, без пересоздания сокета)
                    // пробуем лишь на ПЕРВОЙ попытке восстановления. На следующих
                    // попытках принудительно полностью пересоздаём RFCOMM-сокет, даже
                    // когда bt.isConnected ещё рапортует true.
                    //
                    // Это лечит «ghost connection» после перезапуска двигателя: ELM327
                    // питается от OBD-разъёма, при рестарте мотора уходит в ребут, а
                    // Android RFCOMM часто ещё показывает socket.isConnected == true.
                    // Light re-init на таком сокете молча падает в IOException/NO DATA,
                    // не пересоздав его — поэтому нужен принудительный hard-путь.
                    // Пустая PID-маска — явный признак что init не удался: не тратим
                    // цикл на бесполезный light re-init, сразу пересоздаём сокет.
                    val hardReconnect = !bt.isConnected || staleRecoveries > 1 || pids.all().isEmpty()
                    if (hardReconnect) {
                        // Полное переподключение сокета. Не вызываем `reconnect()` —
                        // он бы отменил текущий pollJob (то есть нас самих).
                        // Эквивалент ручного disconnect→connect: очищаем PID-маску и
                        // snapshot-кэш ДО init, чтобы старые «протекающие» данные не
                        // маскировали реальное молчание ECU после рестарта мотора.
                        val addr = _status.value.deviceAddress
                        if (addr != null) {
                            val prev = _status.value
                            _status.value = prev.copy(state = ConnectionState.CONNECTING, errorMessage = null)
                            runCatching {
                                ioMutex.withLock {
                                    runCatching { bt.close() }
                                    // Дать Android RFCOMM освободить канал. Без этой паузы
                                    // на части устройств новый connect() цепляется к тому же
                                    // мёртвому сокету, и ELM остаётся в подвешенном состоянии.
                                    delay(1200L)
                                    pids.clear()
                                    synchronized(cacheLock) { lastSnapshot = OBDData() }
                                    speedAccepted = null; speedPending = null
                                    bt.connect(addr)
                                    _status.value = _status.value.copy(state = ConnectionState.INITIALIZING)
                                    val res = elm.initialize(preferredProtocol = activeProfile?.obdProtocol)
                                    if (res.supportedPids.isNotEmpty()) {
                                        pids.set(res.supportedPids)
                                        _status.value = _status.value.copy(
                                            state = ConnectionState.CONNECTED,
                                            elmVersion = res.version.ifBlank { prev.elmVersion },
                                            protocol = res.protocol,
                                            supportedPidsCount = res.supportedPids.size,
                                            initRawResponse = res.lastRawResponse.takeIf { it.isNotBlank() } ?: prev.initRawResponse,
                                        )
                                    } else {
                                        // ECU так и не отдал PID-маску → не объявляем CONNECTED
                                        // (иначе UI считает связь живой, но реальные PID-запросы
                                        // молчат — это и есть «ECU отвечает, но без данных»,
                                        // которое держится до ручного дисконнекта). Оставляем
                                        // INITIALIZING и форсируем следующую попытку быстрее
                                        // нормального cooldown'а.
                                        _status.value = _status.value.copy(
                                            state = ConnectionState.INITIALIZING,
                                            initRawResponse = res.lastRawResponse.takeIf { it.isNotBlank() } ?: prev.initRawResponse,
                                        )
                                        // Откатываем cooldown → следующая итерация watchdog'а
                                        // снова попробует hard reconnect через stale-окно.
                                        lastReinitAt = 0L
                                    }
                                }
                            }.onFailure {
                                _status.value = _status.value.copy(
                                    state = ConnectionState.ERROR,
                                    errorMessage = it.message,
                                )
                            }
                        }
                    } else {
                        runCatching {
                            ioMutex.withLock {
                                val res = elm.initialize(preferredProtocol = activeProfile?.obdProtocol)
                                if (res.supportedPids.isNotEmpty()) {
                                    pids.set(res.supportedPids)
                                    synchronized(cacheLock) { lastSnapshot = OBDData() }
                                    _status.value = _status.value.copy(
                                        elmVersion = res.version.ifBlank { _status.value.elmVersion },
                                        protocol = res.protocol,
                                        supportedPidsCount = res.supportedPids.size,
                                        initRawResponse = res.lastRawResponse.takeIf { it.isNotBlank() }
                                            ?: _status.value.initRawResponse,
                                    )
                                }
                            }
                        }
                    }
                    // После любой попытки восстановления даём 15-секундный льготный
                    // период следующим (чтобы счётчик stale не сработал сразу же).
                    lastDataAt = System.currentTimeMillis()
                }
                delay(POLL_PAUSE_MS)
            }
        }
    }

    /**
     * Тиринг поллинга:
     *  - "Горячие" PID (RPM, скорость, дроссель, нагрузка, MAP, MAF, fuel rate) — каждый цикл.
     *  - "Медленные" PID (температуры, бортсеть, бак, одометры, trims, oil,
     *    ambient, timing) — по группам, одна группа `slowGroup` за цикл
     *    (ALL_GROUPS — все сразу).
     * Между чтениями своей группы медленные значения берутся из кэша lastSnapshot, чтобы UI
     * показывал последнее известное значение (а не "—" или 0). Расчётные метрики
     * (мощность, момент, вакуум, прогрев, CO2, cut-off) считаются здесь же.
     */
    private suspend fun readSnapshot(
        displacementL: Float,
        fuel: com.obd2monitor.domain.model.FuelType,
        maxTorqueNm: Float,
        maxPowerKw: Float,
        idleTopRpm: Float,
        coolantTargetC: Float,
        slowGroup: Int,
    ): OBDData = ioMutex.withLock {
        val cached = synchronized(cacheLock) { lastSnapshot }

        // ── HOT TIER (каждый цикл) ────────────────────────────────────────────
        val speed = sanitizeSpeed(ifSupported("0D") { OBDParser.parseSpeed(bt.sendRaw(pid(OBDCommand.SPEED.command))) })
        val rpm = ifSupported("0C") { OBDParser.parseRpm(bt.sendRaw(pid(OBDCommand.RPM.command))) }
        val throttle = ifSupported("11") { OBDParser.parseThrottle(bt.sendRaw(pid(OBDCommand.THROTTLE.command))) }
        val load = ifSupported("04") { OBDParser.parseEngineLoad(bt.sendRaw(pid(OBDCommand.ENGINE_LOAD.command))) }
        val mapKpa = ifSupported("0B") { OBDParser.parseMapPressure(bt.sendRaw(pid(OBDCommand.INTAKE_MAP.command))) }
        // MAF и PID 5E — основа мгновенного расхода и мощности, поэтому тоже каждый цикл.
        // Если ЭБУ их не отдаёт (Accent MC), ifSupported пропускает запрос даром.
        val maf = ifSupported("10") { OBDParser.parseMaf(bt.sendRaw(pid(OBDCommand.MAF.command))) }
        val fuelRateLh = ifSupported("5E") { OBDParser.parseFuelRateLPerH(bt.sendRaw(pid(OBDCommand.FUEL_RATE.command))) }

        // ── SLOW TIER (одна группа за цикл) ───────────────────────────────────
        // Если slow-чтение разово не вернуло значение — оставляем кэшированное,
        // чтобы UI не моргал из-за единичного NO DATA от шины. Полный «обнул» произойдёт
        // только после disconnect (см. startPolling: lastSnapshot = OBDData()).
        fun due(group: Int) = slowGroup == ALL_GROUPS || slowGroup == group
        val coolant = slowPid(due(0), "05", cached.coolantTempC) { OBDParser.parseCoolantTemp(bt.sendRaw(pid(OBDCommand.COOLANT_TEMP.command))) }
        val intake = slowPid(due(0), "0F", cached.intakeTempC) { OBDParser.parseIntakeTemp(bt.sendRaw(pid(OBDCommand.INTAKE_TEMP.command))) }
        // Бортсеть: сначала PID 0142 (если ECU отдаёт), иначе ATRV — оно работает всегда
        // на стороне самого адаптера. На Hyundai Accent MC PID 42 обычно отсутствует.
        val battery = if (due(1)) {
            (ifSupported("42") {
                OBDParser.parseBatteryVoltage(bt.sendRaw(pid(OBDCommand.BATTERY_VOLTAGE.command)))
            } ?: runCatching {
                OBDParser.parseAtRv(bt.sendRaw(com.obd2monitor.data.obd.ELMAtCommand.READ_VOLTAGE))
            }.getOrNull()) ?: cached.batteryVoltage
        } else cached.batteryVoltage
        val timing = slowPid(due(1), "0E", cached.ignitionTimingDeg) { OBDParser.parseTimingAdvance(bt.sendRaw(pid(OBDCommand.TIMING_ADVANCE.command))) }
        val fuelLvl = slowPid(due(2), "2F", cached.fuelLevelPercent) { OBDParser.parseFuelLevel(bt.sendRaw(pid(OBDCommand.FUEL_LEVEL.command))) }
        val oilTemp = slowPid(due(2), "5C", cached.oilTempC) { OBDParser.parseOilTemp(bt.sendRaw(pid(OBDCommand.OIL_TEMP.command))) }
        val stft1 = slowPid(due(3), "06", cached.shortFuelTrim1Pct) { OBDParser.parseShortFuelTrim1(bt.sendRaw(pid("0106"))) }
        val ltft1 = slowPid(due(3), "07", cached.longFuelTrim1Pct) { OBDParser.parseLongFuelTrim1(bt.sendRaw(pid("0107"))) }
        val distMil = slowPid(due(4), "21", cached.distanceMilKm) { OBDParser.parseDistanceWithMil(bt.sendRaw(pid(OBDCommand.DISTANCE_WITH_MIL.command))) }
        val distClear = slowPid(due(4), "31", cached.distanceSinceClearKm) { OBDParser.parseDistanceSinceClear(bt.sendRaw(pid(OBDCommand.DISTANCE_SINCE_CLEAR.command))) }
        val ambient = slowPid(due(4), "46", cached.ambientTempC) { OBDParser.parseAmbientTemp(bt.sendRaw(pid(OBDCommand.AMBIENT_TEMP.command))) }

        // Расход: предпочитаем PID 5E, иначе MAF, иначе MAP+RPM. Intake между чтениями
        // его группы берём из кэша — расход пересчитывается каждый цикл, чтобы график
        // не "ступенчатил".
        val instantLh = when {
            fuelRateLh != null && fuelRateLh > 0f -> fuelRateLh
            maf != null && maf > 0f -> FuelCalculator.fromMAF(maf, fuel)
            mapKpa != null && rpm != null && intake != null ->
                FuelCalculator.fromMAP(mapKpa.toFloat(), rpm.toFloat(), intake.toFloat(), displacementL, fuel)
            else -> null
        }
        val lPer100 = instantLh?.let { FuelCalculator.toLPer100Km(it, speed?.toFloat() ?: 0f) }

        // ── Расчётные метрики ────────────────────────────────────────────────
        // Мощность: предпочитаем MAF (физика), иначе load% × maxPower (приближение).
        // MAF: P_kW ≈ MAF(г/с) × η_thermal × Q_petrol / AFR
        //   = MAF × 0.30 × 43000(Дж/г) / 14.7 / 1000 ≈ MAF × 0.88
        val powerKw: Float? = when {
            maf != null && maf > 0f -> (maf * 0.88f).coerceIn(0f, maxPowerKw * 1.1f)
            load != null && rpm != null && rpm > 0 -> {
                // load% — это процент от максимального момента при текущих оборотах.
                // Power = Torque × RPM / 9549.
                val tq = load / 100f * maxTorqueNm
                (tq * rpm / 9549f).coerceIn(0f, maxPowerKw * 1.1f)
            }
            else -> null
        }
        // Момент = P × 9549 / RPM
        val torqueNm: Float? = if (powerKw != null && rpm != null && rpm > 200) {
            (powerKw * 9549f / rpm).coerceIn(0f, maxTorqueNm * 1.2f)
        } else null
        // Вакуум: 101 - MAP (для атмосферных авто хорошее приближение, без датчика 0133).
        val vacuumKpa: Float? = mapKpa?.let { (101f - it).coerceAtLeast(0f) }
        // Прогрев: линейно от 20°C до coolantTargetC. >= цели → 100%.
        val warmupPct: Float? = coolant?.let { c ->
            ((c - 20f) / (coolantTargetC - 20f) * 100f).coerceIn(0f, 100f)
        }
        // Cut-off: ECU отключил форсунки. Признак, который работает на NA-моторах без
        // PID 5E (Accent его не отдаёт): engine load% почти ноль — двигатель не выдаёт
        // мощность, его крутят колёса. Плюс мы ДВИЖЕМСЯ с приличной скоростью на передаче
        // (rpm выше холостых) и педаль практически отпущена. Условие engine load < 8%
        // ключевое: на накате он 0..5%, на тяге всегда >15% — оно отсекает медленную
        // езду с лёгким нажатием газа (там throttle тоже мал, но топливо подаётся).
        val cutoff = (load != null && load < 8f) &&
            (rpm != null && rpm.toFloat() > idleTopRpm + 300f) &&
            (speed != null && speed > 15) &&
            (throttle != null && throttle < 5f)

        val snapshot = OBDData(
            speedKmh = speed?.toFloat(),
            rpm = rpm,
            coolantTempC = coolant,
            intakeTempC = intake,
            mafGs = maf,
            mapKpa = mapKpa,
            throttlePercent = throttle,
            engineLoadPercent = load,
            fuelLevelPercent = fuelLvl,
            batteryVoltage = battery,
            ignitionTimingDeg = timing,
            instantFuelLPer100 = lPer100,
            instantFuelLPerH = instantLh,
            oilTempC = oilTemp,
            shortFuelTrim1Pct = stft1,
            longFuelTrim1Pct = ltft1,
            distanceMilKm = distMil,
            distanceSinceClearKm = distClear,
            ambientTempC = ambient,
            powerKw = powerKw,
            torqueNm = torqueNm,
            vacuumKpa = vacuumKpa,
            warmupPct = warmupPct,
            fuelCutoffActive = cutoff,
        )
        synchronized(cacheLock) { lastSnapshot = snapshot }
        snapshot
    }

    /** Медленный PID: читаем, только если подошла его группа, иначе — значение из кэша. */
    private inline fun <T> slowPid(due: Boolean, pid: String, cached: T?, block: () -> T?): T? =
        if (due) ifSupported(pid, block) ?: cached else cached

    /**
     * Пробный запрос оборотов с суффиксом «1» (ждать один ответ). Валидный ответ —
     * включаем суффикс для всего Mode 01; «?», NO DATA или ошибка — опрашиваем без
     * суффикса. Проверяется на каждом подключении: адаптер могли сменить.
     */
    private suspend fun probeSingleResponse() = ioMutex.withLock {
        pidSuffix = ""
        val ok = runCatching { OBDParser.parseRpm(bt.sendRaw(OBDCommand.RPM.command + "1")) }.getOrNull() != null
        if (ok) pidSuffix = "1"
    }

    private inline fun <T> ifSupported(pid: String, block: () -> T?): T? {
        // Если кэш PID-маски пуст, считаем что любой PID может быть запрошен (best-effort).
        if (pids.all().isNotEmpty() && !pids.isSupported(pid)) return null
        return runCatching { block() }.getOrNull()
    }

    override suspend fun scanDtc(): DtcScanResult {
        // Без живой связи все запросы молча падали, и экран показывал «ошибок нет».
        if (_status.value.state != ConnectionState.CONNECTED) {
            throw IOException("Адаптер не подключён")
        }
        return ioMutex.withLock {
            // Каждый запрос — отдельно: часть ЭБУ не поддерживает Mode 07/0A и отвечает
            // «NO DATA» или «?», это не повод терять ответ на Mode 03.
            suspend fun read(cmd: OBDCommand, mode: Int): List<String>? = runCatching {
                val raw = bt.sendRaw(cmd.command, timeoutMs = DTC_READ_TIMEOUT_MS)
                if (raw.isBlank() || OBDParser.cleanBytes(raw).none { it == mode + 0x40 }) {
                    // Ответ без заголовка режима: «NO DATA» — кодов нет, «?»/мусор — не поддерживается.
                    if (raw.contains("NO DATA", ignoreCase = true)) emptyList() else null
                } else OBDParser.parseDTCList(raw, mode)
            }.getOrNull()

            val confirmed = read(OBDCommand.READ_DTC, 0x03)
            val pending = read(OBDCommand.READ_PENDING_DTC, 0x07)
            val permanent = read(OBDCommand.READ_PERMANENT_DTC, 0x0A)
            val answered = listOf(confirmed, pending, permanent).count { it != null }
            if (answered == 0) throw IOException("Блок управления не ответил на запрос ошибок")

            val monitor = runCatching { OBDParser.parseMonitorStatus(bt.sendRaw("0101")) }.getOrNull()
            val freeze = runCatching { readFreezeFrame() }.getOrNull()

            val all = (confirmed.orEmpty() + pending.orEmpty() + permanent.orEmpty()).distinct()
            val described = dtcRepo.describeAll(all).map { c ->
                val status = when (c.code) {
                    in permanent.orEmpty() -> DTCStatus.PERMANENT
                    in confirmed.orEmpty() -> DTCStatus.CONFIRMED
                    in pending.orEmpty() -> DTCStatus.PENDING
                    else -> DTCStatus.CONFIRMED
                }
                c.copy(status = status)
            }
            DtcScanResult(
                codes = described,
                monitorStatus = monitor,
                freezeFrame = freeze,
                answeredRequests = answered,
            )
        }
    }

    /** Стоп-кадр (Mode 02, кадр 0): основные параметры в момент фиксации ошибки. */
    private suspend fun readFreezeFrame(): FreezeFrame? {
        val dtc = OBDParser.parseFreezeFrameDtc(bt.sendRaw("020200")) ?: return null
        suspend fun data(pid: Int): List<Int>? = runCatching {
            OBDParser.freezePayload(bt.sendRaw("02%02X00".format(pid)), pid)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        val values = mutableListOf<Pair<String, String>>()
        data(0x0C)?.takeIf { it.size >= 2 }?.let { values += "Обороты" to "${(it[0] * 256 + it[1]) / 4} об/мин" }
        data(0x0D)?.let { values += "Скорость" to "${it[0]} км/ч" }
        data(0x05)?.let { values += "Антифриз" to "${it[0] - 40} °C" }
        data(0x04)?.let { values += "Нагрузка" to "%.0f %%".format(it[0] * 100f / 255f) }
        data(0x0B)?.let { values += "Давление впуска" to "${it[0]} кПа" }
        data(0x06)?.let { values += "Краткая коррекция" to "%+.1f %%".format((it[0] - 128) * 100f / 128f) }
        data(0x07)?.let { values += "Долгая коррекция" to "%+.1f %%".format((it[0] - 128) * 100f / 128f) }
        return FreezeFrame(dtc, values)
    }

    override suspend fun clearDTC(): Boolean {
        if (_status.value.state != ConnectionState.CONNECTED) return false
        return ioMutex.withLock {
            // Успех — только явный ответ «44». На «?» или NO DATA блок код не стёр
            // (часто — двигатель заведён или ЭБУ требует зажигание без запуска мотора).
            runCatching {
                OBDParser.cleanBytes(bt.sendRaw(OBDCommand.CLEAR_DTC.command, timeoutMs = DTC_READ_TIMEOUT_MS)).contains(0x44)
            }.getOrDefault(false)
        }
    }

    override suspend fun readEcuInfo(): EcuInfo = ioMutex.withLock {
        // Эти PID может не отдавать ECU старого Hyundai/Lada — поэтому wrap в runCatching.
        val vin = runCatching {
            OBDParser.parseMode09Ascii(bt.sendRaw(OBDCommand.VIN.command), expectedPid = 0x02)
        }.getOrNull()
        val cal = runCatching {
            OBDParser.parseMode09Ascii(bt.sendRaw(OBDCommand.CALIBRATION_ID.command), expectedPid = 0x04)
        }.getOrNull()
        val name = runCatching {
            OBDParser.parseMode09Ascii(bt.sendRaw(OBDCommand.ECU_NAME.command), expectedPid = 0x0A)
        }.getOrNull()
        val status = _status.value
        EcuInfo(
            vin = vin,
            calibrationId = cal,
            ecuName = name,
            protocol = status.protocol,
            elmVersion = status.elmVersion,
            supportedPids = pids.all(),
        )
    }
}
