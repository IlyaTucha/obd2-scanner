package com.obd2monitor.data.repository

import com.obd2monitor.data.bluetooth.BluetoothObdConnection
import com.obd2monitor.data.bluetooth.ELM327Protocol
import com.obd2monitor.data.obd.FuelCalculator
import com.obd2monitor.data.obd.OBDCommand
import com.obd2monitor.data.obd.OBDParser
import com.obd2monitor.data.obd.PIDRepository
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.domain.model.DTCCode
import com.obd2monitor.domain.model.EcuInfo
import com.obd2monitor.domain.model.OBDData
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
    private val _pollIntervalMs = MutableStateFlow(50L)
    private var pollJob: Job? = null
    private var recoveryJob: Job? = null

    // Антиспайк скорости (см. sanitizeSpeed). Поля защищены ioMutex —
    // readSnapshot/startPolling всегда работают под ним или последовательно.
    private var speedAccepted: Int? = null
    private var speedAcceptedAt: Long = 0L
    private var speedPending: Int? = null

    // Последний полный снимок — нужен чтобы "медленные" PID не обнулялись между
    // быстрыми циклами. Каждый fast-цикл копируем сюда обновлённые горячие PID,
    // slow-цикл (раз в SLOW_EVERY) — обновляет всё остальное.
    @Volatile
    private var lastSnapshot: OBDData = OBDData()
    private val cacheLock = Any()

    // Каждый N-й цикл читаем "медленные" PID — температуры, бортсеть, бак,
    // одометры, trims, oil, баро, ambient. Они меняются редко, и держать их
    // синхронно с RPM/throttle — терять отзывчивость по педали газа.
    private companion object {
        const val SLOW_EVERY = 5

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
        if (kotlin.math.abs(v - last) <= maxDelta || confirmed) return accept()
        speedPending = v
        return last
    }

    override val connectionStatus: StateFlow<ConnectionStatus> get() = _status.asStateFlow()
    override val liveData: SharedFlow<OBDData> get() = _liveData.asSharedFlow()

    override suspend fun connect(deviceAddress: String) {
        _status.value = ConnectionStatus(state = ConnectionState.CONNECTING, deviceAddress = deviceAddress)
        try {
            bt.connect(deviceAddress)
            _status.value = _status.value.copy(state = ConnectionState.INITIALIZING)
            val activeProfile = vehicleRepo.observeActive().firstOrNull()
            val init = elm.initialize(preferredProtocol = activeProfile?.obdProtocol)
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
            // активной даже если первый handshake провалился (типично с ELM v1.5
            // клонами: первая попытка ATZ ловит мусор, вторая проходит).
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
     *    и раньше НИКТО больше не пробовал — сессия оставалась на паузе навсегда;
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
        ioMutex.withLock {
            runCatching { bt.close() }
        }
        try {
            bt.connect(addr)
            _status.value = _status.value.copy(state = ConnectionState.INITIALIZING)
            val activeProfile = vehicleRepo.observeActive().firstOrNull()
            val init = elm.initialize(preferredProtocol = activeProfile?.obdProtocol)
            pids.set(init.supportedPids)
            _status.value = ConnectionStatus(
                state = ConnectionState.CONNECTED,
                deviceAddress = addr,
                deviceName = prev.deviceName,
                elmVersion = init.version.ifBlank { prev.elmVersion },
                protocol = init.protocol ?: prev.protocol,
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

    override suspend fun setPollInterval(intervalMs: Long) {
        _pollIntervalMs.value = intervalMs.coerceIn(50L, 5000L)
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
            while (isActive) {
                val isSlowTick = (cycle % SLOW_EVERY == 0)
                val snapshot = readSnapshot(
                    displacementL = displacementL,
                    fuel = fuel,
                    maxTorqueNm = maxTorqueNm,
                    maxPowerKw = maxPowerKw,
                    idleTopRpm = idleTopRpm,
                    coolantTargetC = coolantTargetC,
                    includeSlow = isSlowTick,
                )
                _liveData.tryEmit(snapshot)
                cycle++
                // hasData = были ли в ЭТОМ цикле свежие байты от ECU. Проверяем ТОЛЬКО
                // hot-tier PID'ы (speed/rpm/throttle/load/mapKpa) — они читаются в КАЖДОМ
                // цикле и НЕ берутся из кэша, так что null означает «ECU молчит».
                //
                // Раньше тут было `snapshot.coolantTempC != null` — а coolant/intake/
                // fuelLvl/battery/oil и т.д. подтягиваются из кэша (см. readSnapshot:
                // `?: cached.X`), и после первой удачной slow-итерации они всегда
                // не-null. Поэтому hasData оставался true даже при «ghost connection»:
                // BT-стек ещё думал что мы подключены (машина рядом, питание ELM упало,
                // вы отошли — RFCOMM не сразу падает), фактически от ECU ничего, а
                // watchdog в AutoSessionManager продолжал считать связь живой и
                // продолжал писать в trip нули. Теперь — только свежие hot-tier.
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
                    // пробуем лишь на ПЕРВОЙ попытке восстановления. Если после неё
                    // данные так и не пошли — на всех следующих попытках принудительно
                    // полностью пересоздаём RFCOMM-сокет, даже когда bt.isConnected ещё
                    // рапортует true.
                    //
                    // Это и есть лечение «при перезапуске машины пишет NO DATA и не
                    // переподключается»: ELM327 питается от OBD-разъёма, при рестарте
                    // двигателя адаптер уходит в ребут. Android RFCOMM при этом часто
                    // ещё показывает socket.isConnected == true («ghost connection») —
                    // и старый код вечно крутил light re-init, который молча падал в
                    // IOException/NO DATA, так и не пересоздав сокет.
                    val hardReconnect = !bt.isConnected || staleRecoveries > 1
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
                                            protocol = res.protocol ?: prev.protocol,
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
                delay(_pollIntervalMs.value)
            }
        }
    }

    /**
     * Тиринг поллинга:
     *  - "Горячие" PID (RPM, скорость, дроссель, нагрузка, MAP) — каждый цикл.
     *  - "Медленные" PID (температуры, бортсеть, бак, одометры, trims, oil,
     *    ambient, MAF, fuel rate, timing) — раз в SLOW_EVERY циклов.
     * Между slow-циклами медленные значения берутся из кэша lastSnapshot, чтобы UI
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
        includeSlow: Boolean,
    ): OBDData = ioMutex.withLock {
        val cached = synchronized(cacheLock) { lastSnapshot }

        // ── HOT TIER (каждый цикл) ────────────────────────────────────────────
        val speed = sanitizeSpeed(ifSupported("0D") { OBDParser.parseSpeed(bt.sendRaw(OBDCommand.SPEED.command)) })
        val rpm = ifSupported("0C") { OBDParser.parseRpm(bt.sendRaw(OBDCommand.RPM.command)) }
        val throttle = ifSupported("11") { OBDParser.parseThrottle(bt.sendRaw(OBDCommand.THROTTLE.command)) }
        val load = ifSupported("04") { OBDParser.parseEngineLoad(bt.sendRaw(OBDCommand.ENGINE_LOAD.command)) }
        val mapKpa = ifSupported("0B") { OBDParser.parseMapPressure(bt.sendRaw(OBDCommand.INTAKE_MAP.command)) }

        // ── SLOW TIER (каждые SLOW_EVERY циклов) ──────────────────────────────
        val intake: Int?
        val maf: Float?
        val fuelLvl: Float?
        val battery: Float?
        val timing: Float?
        val fuelRateLh: Float?
        val oilTemp: Int?
        val coolant: Int?
        val stft1: Float?
        val ltft1: Float?
        val distMil: Int?
        val distClear: Int?
        val ambient: Int?
        if (includeSlow) {
            // Если slow-чтение разово не вернуло значение — оставляем кэшированное,
            // чтобы UI не моргал из-за единичного NO DATA от шины. Полный «обнул» произойдёт
            // только после disconnect (см. startPolling: lastSnapshot = OBDData()).
            coolant = ifSupported("05") { OBDParser.parseCoolantTemp(bt.sendRaw(OBDCommand.COOLANT_TEMP.command)) } ?: cached.coolantTempC
            intake = ifSupported("0F") { OBDParser.parseIntakeTemp(bt.sendRaw(OBDCommand.INTAKE_TEMP.command)) } ?: cached.intakeTempC
            maf = ifSupported("10") { OBDParser.parseMaf(bt.sendRaw(OBDCommand.MAF.command)) } ?: cached.mafGs
            fuelLvl = ifSupported("2F") { OBDParser.parseFuelLevel(bt.sendRaw(OBDCommand.FUEL_LEVEL.command)) } ?: cached.fuelLevelPercent
            // Бортсеть: сначала PID 0142 (если ECU отдаёт), иначе ATRV — оно работает всегда
            // на стороне самого адаптера. На Hyundai Accent MC PID 42 обычно отсутствует.
            battery = (ifSupported("42") {
                OBDParser.parseBatteryVoltage(bt.sendRaw(OBDCommand.BATTERY_VOLTAGE.command))
            } ?: runCatching {
                OBDParser.parseAtRv(bt.sendRaw(com.obd2monitor.data.obd.ELMAtCommand.READ_VOLTAGE))
            }.getOrNull()) ?: cached.batteryVoltage
            timing = ifSupported("0E") { OBDParser.parseTimingAdvance(bt.sendRaw(OBDCommand.TIMING_ADVANCE.command)) } ?: cached.ignitionTimingDeg
            fuelRateLh = ifSupported("5E") { OBDParser.parseFuelRateLPerH(bt.sendRaw(OBDCommand.FUEL_RATE.command)) }
            oilTemp = ifSupported("5C") { OBDParser.parseOilTemp(bt.sendRaw(OBDCommand.OIL_TEMP.command)) } ?: cached.oilTempC
            stft1 = ifSupported("06") { OBDParser.parseShortFuelTrim1(bt.sendRaw("0106")) } ?: cached.shortFuelTrim1Pct
            ltft1 = ifSupported("07") { OBDParser.parseLongFuelTrim1(bt.sendRaw("0107")) } ?: cached.longFuelTrim1Pct
            distMil = ifSupported("21") { OBDParser.parseDistanceWithMil(bt.sendRaw(OBDCommand.DISTANCE_WITH_MIL.command)) } ?: cached.distanceMilKm
            distClear = ifSupported("31") { OBDParser.parseDistanceSinceClear(bt.sendRaw(OBDCommand.DISTANCE_SINCE_CLEAR.command)) } ?: cached.distanceSinceClearKm
            ambient = ifSupported("46") { OBDParser.parseAmbientTemp(bt.sendRaw(OBDCommand.AMBIENT_TEMP.command)) } ?: cached.ambientTempC
        } else {
            // Между slow-тиками подставляем кэш, чтобы UI не моргал.
            coolant = cached.coolantTempC
            intake = cached.intakeTempC
            maf = cached.mafGs
            fuelLvl = cached.fuelLevelPercent
            battery = cached.batteryVoltage
            timing = cached.ignitionTimingDeg
            fuelRateLh = null  // пересчитаем расход через свежий MAF/MAP, см. ниже
            oilTemp = cached.oilTempC
            stft1 = cached.shortFuelTrim1Pct
            ltft1 = cached.longFuelTrim1Pct
            distMil = cached.distanceMilKm
            distClear = cached.distanceSinceClearKm
            ambient = cached.ambientTempC
        }

        // Расход: предпочитаем PID 5E (если был свежий из slow-тика), иначе MAF, иначе
        // MAP+RPM. Между slow-тиками MAP/RPM свежие, intake берём из кэша — расход
        // пересчитывается каждый цикл, чтобы график не "ступенчатил".
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
        // Cut-off: ECU отключил форсунки. Признак, который реально работает на NA-моторах
        // без PID 5E (Accent его не отдаёт): engine load% почти ноль — двигатель не выдаёт
        // мощность, его крутят колёса. Плюс мы ДВИЖЕМСЯ с приличной скоростью на передаче
        // (rpm выше холостых) и педаль практически отпущена.
        //
        // Прежняя эвристика (throttle<2 + rpm>idle+100 + speed>5) ложно срабатывала на
        // медленной езде с лёгким нажатием — формально все 3 пункта совпадают, но топливо
        // подаётся. Добавляем engine load < 8% — на накате он 0..5%, на тяге всегда >15%.
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

    private inline fun <T> ifSupported(pid: String, block: () -> T?): T? {
        // Если кэш PID-маски пуст, считаем что любой PID может быть запрошен (best-effort).
        if (pids.all().isNotEmpty() && !pids.isSupported(pid)) return null
        return runCatching { block() }.getOrNull()
    }

    override suspend fun readDTC(): List<DTCCode> = ioMutex.withLock {
        val confirmed = runCatching {
            OBDParser.parseDTCList(bt.sendRaw(OBDCommand.READ_DTC.command), mode = 3)
        }.getOrDefault(emptyList())
        val pending = runCatching {
            OBDParser.parseDTCList(bt.sendRaw(OBDCommand.READ_PENDING_DTC.command), mode = 7)
        }.getOrDefault(emptyList())
        val permanent = runCatching {
            OBDParser.parseDTCList(bt.sendRaw(OBDCommand.READ_PERMANENT_DTC.command), mode = 10)
        }.getOrDefault(emptyList())
        val all = (confirmed + pending + permanent).distinct()
        val described = dtcRepo.describeAll(all)
        // Прокидываем статус
        described.map { c ->
            val status = when {
                permanent.contains(c.code) -> com.obd2monitor.domain.model.DTCStatus.PERMANENT
                confirmed.contains(c.code) -> com.obd2monitor.domain.model.DTCStatus.CONFIRMED
                pending.contains(c.code) -> com.obd2monitor.domain.model.DTCStatus.PENDING
                else -> com.obd2monitor.domain.model.DTCStatus.CONFIRMED
            }
            c.copy(status = status)
        }
    }

    override suspend fun clearDTC(): Boolean = ioMutex.withLock {
        runCatching { bt.sendRaw(OBDCommand.CLEAR_DTC.command) }.isSuccess
    }

    override suspend fun supportedPids(): List<String> = pids.all()

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
