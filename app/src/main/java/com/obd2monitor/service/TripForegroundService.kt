package com.obd2monitor.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.obd2monitor.MainActivity
import com.obd2monitor.R
import com.obd2monitor.data.obd.TripMath
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.OBDData
import com.obd2monitor.domain.model.OBDReading
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.TripRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground Service записи поездки (ТЗ §4.4, §8).
 *
 * Архитектура:
 *  - кэширует последний снимок OBD-данных (если есть подключение к ELM327)
 *  - кэширует последнюю GPS-локацию (FusedLocationProviderClient, Play Services)
 *  - раз в WRITE_INTERVAL_MS пишет OBDReading в Room с тем что есть
 *
 * Это значит: даже без OBD-подключения GPS-маршрут будет записываться.
 * При паузе (TripController.paused=true) запись останавливается, но trip не закрывается.
 */
@AndroidEntryPoint
class TripForegroundService : Service() {

    @Inject lateinit var tripRepo: TripRepository
    @Inject lateinit var obdRepo: OBDRepository
    @Inject lateinit var controller: TripController
    @Inject lateinit var tracker: ActiveTripTracker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writerJob: Job? = null
    private var obdCacheJob: Job? = null
    private var gpsRetryJob: Job? = null
    private var notificationJob: Job? = null
    private var shownNotification: Pair<String, String>? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private val latestObd = MutableStateFlow(OBDData())
    private var lastLocation: Location? = null
    /** Скорость по последнему точному фиксу, км/ч, и момент фикса. Нужна GPS-сессиям
     *  и машинам, чей ЭБУ не отдаёт PID скорости. */
    @Volatile private var gpsSpeedKmh = 0f
    @Volatile private var gpsSpeedAt = 0L
    private var prevFix: Location? = null
    /** С какого момента GPS-сессия стоит на месте (для авто-паузы). */
    private var stationarySince: Long? = null
    /** true — requestLocationUpdates успешно вызван (GPS-подписка живёт). */
    @Volatile private var gpsSubscribed = false
    /** true — startForeground выполнен с типом LOCATION (а не dataSync-fallback).
     *  На Android 14+ FGS без типа location получает координаты только пока
     *  приложение видимо — поэтому при появлении разрешения тип надо повысить. */
    @Volatile private var fgsHasLocationType = false
    /** true — startForeground уже выполнен (см. start()). */
    private var isForeground = false
    /** Сессия, в которую пишет writer; null — сервис ждёт подключения адаптера. */
    @Volatile private var activeTripId: String? = null
    /**
     * Сглаживание GPS-дрожания на стоянке:
     *  - принимаем только точки с accuracy <= MAX_ACCURACY_M (если accuracy известен);
     *  - игнорируем «прыжки» < MIN_MOVE_M от предыдущей точки.
     * Без этого LocationManager на низкой скорости пишет клубки точек по 5–15 м.
     */
    /** Момент последнего колбэка от LocationManager — любого, даже отфильтрованного по
     *  точности/движению. Это health-маркер подписки для gps-watchdog'а: пока колбэки
     *  идут, подписка жива (даже когда стоим и точки не сохраняются). */
    @Volatile private var lastFixAt = 0L
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val now = System.currentTimeMillis()
            lastFixAt = now
            val accuracyOk = !loc.hasAccuracy() || loc.accuracy <= MAX_ACCURACY_M
            if (!accuracyOk) return
            gpsSpeedKmh = speedOf(loc)
            gpsSpeedAt = now
            prevFix = loc
            controller.onGpsFix(now)
            val prev = lastLocation
            val moved = prev == null || prev.distanceTo(loc) >= MIN_MOVE_M
            if (moved) lastLocation = loc
        }
    }

    companion object {
        const val ACTION_START = "com.obd2monitor.trip.START"
        const val ACTION_STOP = "com.obd2monitor.trip.STOP"
        const val CHANNEL_ID = "trip_channel"
        const val NOTIFICATION_ID = 1001
        private const val WRITE_INTERVAL_MS = 500L  // как часто пишем reading в Room
        private const val GPS_WRITE_INTERVAL_MS = 1000L
        private const val MIN_MOVE_M = 7f            // фильтр GPS-дрожания
        private const val MAX_ACCURACY_M = 25f       // отбрасываем «дикие» точки
        private const val GPS_INTERVAL_MS = 2000L    // период запроса фиксов у fused-провайдера
        /** Подписка на GPS есть, но колбэков нет дольше этого → пересоздаём подписку.
         *  Лечит «пустую» первую подписку (холодный GPS-чип не шлёт фиксы / FGS стартовал
         *  как dataSync до выдачи разрешения), из-за которой маршрут не писался до полного
         *  перезапуска сессии. С запасом больше периода фиксов (2 c). */
        private const val GPS_STALE_MS = 20_000L
        /** Фикс старше этого — скорость по GPS считаем неизвестной (0). */
        private const val GPS_SPEED_MAX_AGE_MS = 6_000L
        /** Ниже — дрожание координат на месте, а не движение. */
        private const val GPS_MIN_SPEED_KMH = 2.5f
        /** Данные OBD старше этого считаем устаревшими и в замер не пишем. */
        private const val OBD_FRESH_MS = 5_000L
        /** GPS-сессия, простоявшая столько без движения, уходит на паузу (и снова
         *  пишется, как только машина поедет) — иначе забытая запись шла бы сутками. */
        private const val GPS_IDLE_PAUSE_MS = 5 * 60_000L
        private const val GPS_RESUME_SPEED_KMH = 8f
    }

    /** Скорость фикса: доплеровская от GPS-чипа, иначе по смещению от прошлого фикса. */
    private fun speedOf(loc: Location): Float {
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else {
            val p = prevFix
            val dtS = p?.let { (loc.time - it.time) / 1000f } ?: 0f
            if (p != null && dtS in 0.5f..10f) p.distanceTo(loc) / dtS * 3.6f else 0f
        }
        return if (!kmh.isFinite() || kmh < GPS_MIN_SPEED_KMH) 0f else kmh
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    private fun start() {
        ensureChannel()
        // На Android 14+ FGS-тип "location" требует ACCESS_FINE_LOCATION,
        // иначе startForeground() кидает SecurityException и сессия не пишется.
        // Если разрешения нет — стартуем как dataSync, чтобы продолжать запись OBD-readings
        // без координат. Иначе при отказе от GPS в истории сессии всё было по нулям.
        //
        // Повторный START (сервис уже поднят на время подключения, теперь открылась
        // сессия) приходит, как правило, из фона: startForeground() там может кинуть
        // исключение, а stopSelf() убил бы работающую запись. Уже в foreground — только
        // обновляем текст уведомления.
        if (isForeground) {
            refreshNotification()
        } else {
            val hasLocationPerm = hasFineLocation()
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (hasLocationPerm) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
            var asLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasLocationPerm
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(notificationText()),
                    fgsType,
                )
            }.recoverCatching {
                // Старт без экрана (после перезагрузки телефона, см. BootReceiver): тип
                // location из фона не разрешён без фонового доступа к геопозиции. Пишем
                // OBD как dataSync; до location тип поднимет gpsRetryJob, когда
                // приложение откроют.
                if (fgsType != ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) throw it
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(notificationText()),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                asLocation = false
            }.onFailure { stopSelf(); return }
            isForeground = true
            fgsHasLocationType = asLocation
        }
        lastFixAt = System.currentTimeMillis()  // даём первой подписке окно GPS_STALE_MS до проверки
        startGps()
        // GPS-watchdog на всю сессию. На ПЕРВОМ запуске сервис стартует параллельно с
        // диалогом runtime-разрешений (MainActivity делает auto-connect, не дожидаясь
        // ответа пользователя) — startGps() выше молча выходит без ACCESS_FINE_LOCATION.
        // Прежний цикл выходил, как только requestLocationUpdates был ВЫЗВАН
        // (gpsSubscribed=true) — но если первая подписка оказывалась «пустой» (холодный
        // GPS-чип не шлёт колбэки, или FGS стартовал как dataSync до выдачи разрешения),
        // маршрут оставался пустым до конца сессии: помогал только полный перезапуск
        // сессии. Теперь цикл живёт всю сессию: поднимает тип FGS до location при
        // появлении разрешения, подписывается если ещё не подписан, и ПЕРЕСОЗДАЁТ
        // подписку, если колбэков нет дольше GPS_STALE_MS. Это авто-эквивалент
        // «переподключиться с нуля», только для GPS.
        gpsRetryJob?.cancel()
        gpsRetryJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                if (!fgsHasLocationType && hasFineLocation()) upgradeToLocationFgs()
                when {
                    !gpsSubscribed -> startGps()
                    System.currentTimeMillis() - lastFixAt > GPS_STALE_MS -> restartGps()
                }
            }
        }
        // НЕ вызываем controller.reset() — он сбросил бы paused=false и сломал бы
        // усыновление осиротевшей сессии (onTripAdopted ставит паузу ДО старта
        // сервиса, а reset() тут же её снял бы). Состоянием паузы владеют
        // AutoSessionManager и TripController.

        // Уведомление следует за состоянием сессии (подготовка / запись / пауза).
        notificationJob?.cancel()
        notificationJob = scope.launch {
            while (isActive) {
                delay(2_000L)
                refreshNotification()
            }
        }

        // Кэш последнего OBD-снимка (не блокируется отсутствием подключения)
        obdCacheJob?.cancel()
        obdCacheJob = obdRepo.liveData.onEach { latestObd.value = it }.launchIn(scope)

        // Периодическая запись reading'ов.
        // Ждём появления активного trip'а (AutoSessionManager создаёт его параллельно со
        // стартом сервиса — на момент start() observeActiveTrip может ещё не успеть
        // эмитнуть). С firstOrNull он вернул бы null и writer молча вышел бы → записи
        // не велись бы пока сессия не пересоздастся; поэтому ждём filterNotNull().first().
        writerJob?.cancel()
        writerJob = scope.launch {
            // Сервис, поднятый на время подключения, стоит здесь, пока не откроется сессия.
            val active = tripRepo.observeActiveTrip().filterNotNull().first()
            activeTripId = active.id
            refreshNotification()
            runCatching { tracker.attach(active.id) }
            while (isActive) {
                val now = System.currentTimeMillis()
                val status = obdRepo.connectionStatus.value
                // OBD-снимок пишем, только пока он свежий: после обрыва в latestObd
                // остаётся последнее значение, и GPS-сессия иначе тянула бы его дальше.
                val obdFresh = status.state == ConnectionState.CONNECTED &&
                    status.lastSnapshotHadData &&
                    now - (status.lastSnapshotAt ?: 0L) < OBD_FRESH_MS
                val obd = if (obdFresh) latestObd.value else OBDData()
                val gpsSpeed = if (now - gpsSpeedAt < GPS_SPEED_MAX_AGE_MS) gpsSpeedKmh else 0f
                if (controller.gpsMode.value && !obdFresh) updateGpsIdlePause(now, gpsSpeed)
                else updateGpsFallback(now, gpsSpeed, obdFresh)
                if (!controller.paused.value) {
                    val loc = lastLocation
                    // Пишем замер, если есть хоть что-то: свежие данные ЭБУ или координаты.
                    val hasObd = obd.speedKmh != null || obd.rpm != null ||
                        obd.coolantTempC != null || obd.engineLoadPercent != null ||
                        obd.batteryVoltage != null || obd.throttlePercent != null ||
                        obd.mapKpa != null || obd.intakeTempC != null
                    if (hasObd || loc != null) {
                        val reading = OBDReading(
                            tripId = active.id,
                            timestamp = now,
                            // Скорость ЭБУ точнее на малых скоростях; GPS — запасной источник.
                            speedKmh = obd.speedKmh ?: gpsSpeed,
                            rpm = (obd.rpm ?: 0).toFloat(),
                            coolantTempC = (obd.coolantTempC ?: 0).toFloat(),
                            throttlePercent = obd.throttlePercent ?: 0f,
                            fuelLevelPercent = obd.fuelLevelPercent ?: 0f,
                            mafGs = obd.mafGs ?: 0f,
                            instantFuelLPer100 = obd.instantFuelLPer100 ?: 0f,
                            batteryVoltage = obd.batteryVoltage ?: 0f,
                            engineLoadPercent = obd.engineLoadPercent ?: 0f,
                            intakeTempC = (obd.intakeTempC ?: 0).toFloat(),
                            mapKpa = (obd.mapKpa ?: 0).toFloat(),
                            ignitionTimingDeg = obd.ignitionTimingDeg ?: 0f,
                            oilTempC = (obd.oilTempC ?: 0).toFloat(),
                            shortFuelTrim1Pct = obd.shortFuelTrim1Pct ?: 0f,
                            longFuelTrim1Pct = obd.longFuelTrim1Pct ?: 0f,
                            distanceMilKm = (obd.distanceMilKm ?: 0).toFloat(),
                            distanceSinceClearKm = (obd.distanceSinceClearKm ?: 0).toFloat(),
                            latitude = loc?.latitude,
                            longitude = loc?.longitude,
                        )
                        tripRepo.appendReading(reading)
                        runCatching { tracker.onReading(reading) }
                    }
                }
                // Без адаптера данные приходят только с GPS (фикс раз в 2 c) — чаще писать незачем.
                delay(if (obdFresh) WRITE_INTERVAL_MS else GPS_WRITE_INTERVAL_MS)
            }
        }
    }

    /** Авто-пауза GPS-сессии на долгой стоянке и снятие паузы, когда машина поехала. */
    private fun updateGpsIdlePause(now: Long, gpsSpeed: Float) {
        if (gpsSpeed >= GPS_RESUME_SPEED_KMH) {
            stationarySince = null
            controller.setManualPause(false)
            if (controller.paused.value) controller.setPaused(false)
            return
        }
        if (gpsSpeed >= TripMath.MOVING_THRESHOLD_KMH) {
            stationarySince = null
            return
        }
        val since = stationarySince ?: now.also { stationarySince = it }
        if (now - since >= GPS_IDLE_PAUSE_MS && !controller.paused.value) controller.setPaused(true)
    }

    /**
     * OBD-сессия без связи с адаптером: если машина поехала (по GPS), снимаем паузу и
     * пишем маршрут и скорость по спутникам, пока адаптер не вернётся. На стоянке
     * дольше GPS_IDLE_PAUSE_MS — снова пауза, как у GPS-сессии. Ручную паузу не трогаем.
     */
    private fun updateGpsFallback(now: Long, gpsSpeed: Float, obdFresh: Boolean) {
        if (controller.gpsMode.value) return
        if (obdFresh || controller.manualPause.value) {
            if (controller.gpsFallback.value) controller.setGpsFallback(false)
            stationarySince = null
            return
        }
        if (gpsSpeed >= GPS_RESUME_SPEED_KMH) {
            stationarySince = null
            if (!controller.gpsFallback.value) controller.setGpsFallback(true)
            if (controller.paused.value) controller.setPaused(false)
            return
        }
        if (!controller.gpsFallback.value) return
        if (gpsSpeed >= TripMath.MOVING_THRESHOLD_KMH) {
            stationarySince = null
            return
        }
        val since = stationarySince ?: now.also { stationarySince = it }
        if (now - since >= GPS_IDLE_PAUSE_MS) {
            controller.setGpsFallback(false)
            controller.setPaused(true)
        }
    }

    private fun stop() {
        writerJob?.cancel(); writerJob = null
        obdCacheJob?.cancel(); obdCacheJob = null
        gpsRetryJob?.cancel(); gpsRetryJob = null
        notificationJob?.cancel(); notificationJob = null
        stopGps()
        controller.reset()
        scope.launch { tracker.detach() }
        activeTripId = null
        shownNotification = null
        isForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notificationTitle(): String =
        if (activeTripId == null) "Подготовка к записи" else getString(R.string.trip_notification_title)

    /** Текст уведомления — то же состояние, что на вкладке «Сессия» (StatusCard). */
    private fun notificationText(): String {
        val st = obdRepo.connectionStatus.value
        val obdFlowing = st.state == ConnectionState.CONNECTED && st.lastSnapshotHadData
        return when {
            activeTripId == null -> "Подключение к адаптеру. Сессия начнётся сама."
            controller.manualPause.value -> "Пауза"
            controller.gpsMode.value ->
                if (controller.paused.value) "Пауза: стоянка" else "Запись маршрута по GPS"
            controller.gpsFallback.value && !controller.paused.value ->
                "Адаптер не на связи — маршрут пишется по GPS"
            st.state != ConnectionState.CONNECTED -> "Пауза: ожидание адаптера"
            controller.paused.value || !obdFlowing -> "Пауза: нет данных от блока управления"
            else -> "Запись поездки"
        }
    }

    /** Перерисовывает уведомление, только если текст изменился. */
    private fun refreshNotification() {
        val shown = notificationTitle() to notificationText()
        if (shown == shownNotification) return
        shownNotification = shown
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(notificationText()))
        }
    }

    private fun hasFineLocation(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /** Повышение типа FGS dataSync → location после выдачи разрешения. Допустимо
     *  только пока приложение видимо (на 14+ иначе кинет исключение) — поэтому
     *  runCatching и повтор на следующем тике gpsRetryJob. */
    private fun upgradeToLocationFgs() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { fgsHasLocationType = true; return }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(notificationText()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            fgsHasLocationType = true
        }
    }

    private fun startGps() {
        val client = fusedClient
            ?: LocationServices.getFusedLocationProviderClient(this).also { fusedClient = it }
        if (!hasFineLocation()) return
        try {
            // Затравка lastLocation последней известной точкой. Без неё на первом
            // подключении к ELM trip пишется ДО первого фикса с lat/lon=null, и карта
            // в истории пустая (на повторном подключении GPS уже «тёплый», фикс почти
            // мгновенный, поэтому затрагивается только первая поездка). Fused сам
            // выбирает лучший источник (GPS/сеть/сенсоры), так что отдельный fallback
            // на NETWORK_PROVIDER больше не нужен.
            if (lastLocation == null) {
                client.lastLocation.addOnSuccessListener { seed ->
                    if (lastLocation == null && seed != null) lastLocation = seed
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
                .setMinUpdateIntervalMillis(GPS_INTERVAL_MS)
                // minDistance = 0: дельту по движению фильтруем в callback (MIN_MOVE_M).
                // Здесь 0, чтобы колбэки шли регулярно даже на стоянке — так gps-watchdog
                // отличает «подписка жива, но стоим» от «подписка мёртвая» (см. lastFixAt).
                .setMinUpdateDistanceMeters(0f)
                .build()
            client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            gpsSubscribed = true
            lastFixAt = System.currentTimeMillis()
        } catch (_: SecurityException) {}
    }

    /** Пересоздаёт GPS-подписку, НЕ теряя уже накопленный маршрут (lastLocation не
     *  обнуляем — startGps повторно «затравит» его только если он null). Вызывает
     *  gps-watchdog, когда подписка есть, но колбэки пропали. */
    private fun restartGps() {
        runCatching { fusedClient?.removeLocationUpdates(locationCallback) }
        gpsSubscribed = false
        startGps()
    }

    private fun stopGps() {
        runCatching { fusedClient?.removeLocationUpdates(locationCallback) }
        fusedClient = null
        lastLocation = null
        prevFix = null
        gpsSpeedKmh = 0f
        stationarySince = null
        gpsSubscribed = false
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.trip_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.trip_notification_channel_desc)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(notificationTitle())
        .setContentText(text)
        .setSmallIcon(R.mipmap.ic_launcher_foreground)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .build()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
