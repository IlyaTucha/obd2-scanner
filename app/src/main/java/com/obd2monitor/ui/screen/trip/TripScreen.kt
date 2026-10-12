package com.obd2monitor.ui.screen.trip

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.Trip
import com.obd2monitor.service.TripController
import com.obd2monitor.ui.components.AdaptiveGrid
import com.obd2monitor.ui.components.MaxContentWidth
import com.obd2monitor.ui.components.ScreenColumn
import com.obd2monitor.ui.components.YandexMapView
import com.obd2monitor.ui.components.isWideLayout
import com.obd2monitor.ui.components.mapHeight
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorCardGray
import com.obd2monitor.ui.theme.ColorDialogScrim
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorOnPrimary
import com.obd2monitor.ui.theme.ColorPrimary
import com.obd2monitor.ui.theme.ColorSuccess
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.obd2monitor.ui.theme.ColorWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Через сколько без GPS-фикса показывать «ожидание сигнала GPS». */
private const val GPS_WAIT_HINT_MS = 15_000L

/** Текущая сессия: статус записи, показатели, живой маршрут. */
@Composable
fun TripActiveScreen(
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: TripViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeTrip = state.activeTrip
    var confirmFinish by remember { mutableStateOf(false) }
    val startGps = rememberGpsStarter(onStart = viewModel::startGpsTrip)
    val wide = isWideLayout()

    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Сессия", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenHistory) {
                Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("История")
            }
        }

        if (activeTrip == null) {
            IdleCard(
                isConnected = state.connection.state == ConnectionState.CONNECTED,
                onStartGps = startGps,
                onOpenSettings = onOpenSettings,
            )
            return@ScreenColumn
        }

        StatusCard(state = state, onFinish = { confirmFinish = true })
        val stats = buildStats(state)
        if (wide) {
            // В горизонтали — показатели слева, карта справа.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroStats(state)
                    AdaptiveGrid(stats, minCellWidth = 140.dp) { s, m -> LiveStatTile(s, m) }
                }
                RouteMap(state, Modifier.weight(1f).height(mapHeight(320.dp)))
            }
        } else {
            HeroStats(state)
            AdaptiveGrid(stats, minCellWidth = 140.dp) { s, m -> LiveStatTile(s, m) }
            RouteMap(state, Modifier.fillMaxWidth().height(mapHeight(240.dp)))
        }
        Text(
            "Подробный отчёт с графиками появится в «Истории» после завершения сессии.",
            color = ColorTextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            containerColor = ColorDialogScrim,
            title = { Text("Завершить сессию?") },
            text = {
                Text(
                    if (state.connection.state == ConnectionState.DISCONNECTED) {
                        "Запись остановится, сессия сохранится в истории."
                    } else {
                        "Запись остановится, сессия сохранится в истории. Адаптер будет отключён — " +
                            "новая сессия начнётся при следующем подключении."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    viewModel.finish()
                }) { Text("Завершить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) { Text("Отмена") }
            },
        )
    }
}

/**
 * Старт записи по GPS с проверкой разрешения на геолокацию и включённой геолокации.
 * Разрешение запрашивается здесь же, если пользователь отказал при первом запуске.
 */
@Composable
private fun rememberGpsStarter(onStart: () -> Unit): () -> Unit {
    val context = LocalContext.current
    fun locationEnabled(): Boolean = runCatching {
        LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))
    }.getOrDefault(true)
    fun startChecked() {
        if (!locationEnabled()) {
            Toast.makeText(context, "Включите геолокацию в настройках телефона", Toast.LENGTH_LONG).show()
        }
        onStart()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startChecked()
        else Toast.makeText(context, "Для записи по GPS нужен доступ к точной геолокации", Toast.LENGTH_LONG).show()
    }
    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startChecked()
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

@Composable
private fun IdleCard(isConnected: Boolean, onStartGps: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ColorCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isConnected) {
            Text("Сессия запускается…", style = MaterialTheme.typography.titleMedium)
            Text(
                "Адаптер подключён, запись начнётся через несколько секунд.",
                color = ColorTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        Text("Сессия не идёт", style = MaterialTheme.typography.titleMedium)
        Text(
            "Запись начинается автоматически при подключении OBD-адаптера. " +
                "Без адаптера можно записать маршрут по GPS — пробег, скорость и время в пути.",
            color = ColorTextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStartGps,
                colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary, contentColor = ColorOnPrimary),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Запись по GPS")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Text("Подключить адаптер")
            }
        }
    }
}

@Composable
private fun StatusCard(state: TripUiState, onFinish: () -> Unit) {
    val connected = state.connection.state == ConnectionState.CONNECTED
    val obdFlowing = connected && state.connection.lastSnapshotHadData
    val now = System.currentTimeMillis()
    @Suppress("UNUSED_EXPRESSION") state.durationMs // тик раз в 500 мс — пересчёт «ожидания GPS»
    val waitingGps = state.gpsMode && !obdFlowing &&
        (state.lastGpsFixAt == null || now - state.lastGpsFixAt > GPS_WAIT_HINT_MS)
    val paused = if (state.gpsMode) state.paused else state.paused || !obdFlowing

    val (color, title, hint) = when {
        state.gpsMode && paused -> Triple(ColorWarning, "Пауза: стоянка",
            "Запись возобновится автоматически, когда автомобиль поедет.")
        waitingGps -> Triple(ColorWarning, "Ожидание сигнала GPS",
            "Под открытым небом сигнал обычно появляется в течение минуты.")
        state.gpsMode -> Triple(ColorSuccess, "Идёт запись",
            if (obdFlowing) "Источники: GPS и OBD-адаптер." else "Источник: GPS. Данные двигателя появятся при подключении адаптера.")
        !connected -> Triple(ColorWarning, "Ожидание адаптера",
            "Связь с адаптером потеряна. Запись продолжится в эту же сессию после переподключения.")
        paused -> Triple(ColorWarning, "Пауза: нет данных",
            "Адаптер подключён, но блок управления не передаёт данные. Запись продолжится автоматически.")
        else -> Triple(ColorSuccess, "Идёт запись",
            "При обрыве связи запись встаёт на паузу и продолжается после переподключения.")
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ColorCard)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(title, color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (state.gpsMode) "GPS" else "OBD", color = ColorTextSecondary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text(formatHms(state.durationMs), style = MaterialTheme.typography.displaySmall)
        Text(
            if (state.pausedMs >= 1000L) "в пути · на паузе ${formatHms(state.pausedMs)}" else "в пути",
            color = ColorTextSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(10.dp))
        Text(hint, color = ColorTextSecondary, style = MaterialTheme.typography.bodySmall)
        // Обратный отсчёт до авто-завершения долгой паузы.
        val pauseSince = state.pauseSinceMs
        if (paused && pauseSince != null) {
            val remainingMs = (TripController.AUTO_FINISH_PAUSE_MS - (now - pauseSince)).coerceAtLeast(0L)
            Text(
                "Если запись не возобновится, сессия завершится автоматически через ${formatPauseRemaining(remainingMs)}.",
                color = ColorTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Завершить")
        }
    }
}

/** Три главных цифры сессии крупно. */
@Composable
private fun HeroStats(state: TripUiState) {
    val connected = state.connection.state == ConnectionState.CONNECTED
    val speedNow = state.live.speedKmh?.takeIf { connected } ?: state.stats.lastSpeedKmh
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ColorCardGray)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        HeroValue("Пробег", "%.2f".format(state.stats.distanceKm), "км")
        HeroValue("Скорость", "%.0f".format(speedNow), "км/ч")
        HeroValue("Средняя", "%.0f".format(state.stats.avgMovingSpeedKmh), "км/ч")
    }
}

@Composable
private fun HeroValue(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = ColorTextSecondary, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineLarge)
        Text(unit, color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RouteMap(state: TripUiState, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(ColorCard)) {
        YandexMapView(
            points = state.stats.route,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private data class LiveStat(val icon: ImageVector, val title: String, val value: String, val unit: String)

/** Плитки показателей. Данные двигателя — только когда адаптер на связи и ЭБУ их отдаёт. */
private fun buildStats(state: TripUiState): List<LiveStat> = buildList {
    val s = state.stats
    add(LiveStat(Icons.Outlined.Speed, "Макс. скорость", "%.0f".format(s.maxSpeedKmh), "км/ч"))
    add(LiveStat(Icons.Outlined.Timer, "В движении", formatHms(s.movingMs), ""))
    val live = state.live
    if (state.connection.state != ConnectionState.CONNECTED || !state.connection.lastSnapshotHadData) return@buildList
    if (s.fuelLiters > 0.001f) add(LiveStat(Icons.Outlined.LocalGasStation, "Топливо за сессию", "%.2f".format(s.fuelLiters), "л"))
    // Расход: на ходу — л/100 км, на стоянке — л/ч (как бортовой компьютер).
    if ((live.speedKmh ?: 0f) > 5f && live.instantFuelLPer100 != null) {
        add(LiveStat(Icons.Outlined.LocalGasStation, "Расход сейчас", "%.1f".format(live.instantFuelLPer100), "л/100 км"))
    } else if (live.instantFuelLPerH != null) {
        add(LiveStat(Icons.Outlined.LocalGasStation, "Расход сейчас", "%.1f".format(live.instantFuelLPerH), "л/ч"))
    }
    live.fuelLevelPercent?.let { add(LiveStat(Icons.Outlined.LocalGasStation, "Топливо в баке", "%.0f".format(it), "%")) }
    live.rpm?.let { add(LiveStat(Icons.AutoMirrored.Outlined.RotateRight, "Обороты", it.toString(), "об/мин")) }
    live.engineLoadPercent?.let { add(LiveStat(Icons.Outlined.Memory, "Нагрузка", "%.0f".format(it), "%")) }
    live.throttlePercent?.let { add(LiveStat(Icons.Outlined.Tune, "Дроссель", "%.0f".format(it), "%")) }
    live.coolantTempC?.let { add(LiveStat(Icons.Outlined.Thermostat, "Охлаждающая жидкость", it.toString(), "°C")) }
    live.intakeTempC?.let { add(LiveStat(Icons.Outlined.Air, "Воздух на впуске", it.toString(), "°C")) }
    live.batteryVoltage?.let { add(LiveStat(Icons.Outlined.BatteryFull, "Бортсеть", "%.2f".format(it), "В")) }
}

@Composable
private fun LiveStatTile(stat: LiveStat, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCardGray)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(stat.icon, contentDescription = null, tint = ColorNeutral, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stat.title, color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stat.value, style = MaterialTheme.typography.titleLarge)
            if (stat.unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(stat.unit, color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** История завершённых сессий. Открывается как отдельный route со вкладки «Сессия». */
@Composable
fun TripHistoryScreen(
    onOpenSummary: (String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: TripViewModel = hiltViewModel(),
) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Trip?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val backupMsg by viewModel.backupMessage.collectAsStateWithLifecycle()
    LaunchedEffect(backupMsg) {
        val m = backupMsg ?: return@LaunchedEffect
        Toast.makeText(context, m, Toast.LENGTH_LONG).show()
        viewModel.consumeBackupMessage()
    }

    // Экспорт ТОЛЬКО сессий в JSON. Полная копия БД делается из скрытого меню настроек.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportTrips(uri) }
    // Импорт через подтверждение: сессии с совпадающими id перезапишутся, поэтому
    // после выбора файла сначала спрашиваем.
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingImport = uri }

    // Свайп слева направо (как системный жест «назад») возвращает на вкладку «Сессия».
    // draggable уживается с вертикальной прокруткой списка; порог 80 dp.
    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    var totalDragX by remember { mutableFloatStateOf(0f) }
    var consumed by remember { mutableStateOf(false) }
    val draggableState = rememberDraggableState { delta ->
        totalDragX += delta
        if (!consumed && totalDragX > swipeThresholdPx) {
            consumed = true
            onBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStarted = { totalDragX = 0f; consumed = false },
                onDragStopped = { totalDragX = 0f; consumed = false },
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Spacer(Modifier.width(4.dp))
                Text("История сессий", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Экспорт и импорт")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Экспорт сессий") },
                            onClick = {
                                menuOpen = false
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                exportLauncher.launch("obd2_trips_$stamp.json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Импорт сессий") },
                            onClick = {
                                menuOpen = false
                                importLauncher.launch(arrayOf("application/json", "*/*"))
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (trips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Сохранённых сессий пока нет", color = ColorTextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(trips, key = { it.id }) { trip ->
                        TripHistoryCard(
                            trip = trip,
                            onClick = { onOpenSummary(trip.id) },
                            onDelete = { pendingDelete = trip },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = ColorDialogScrim,
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTrip(trip.id)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
            title = { Text("Удалить сессию?") },
            text = {
                val date = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(trip.startTime))
                Text("$date, ${"%.1f".format(trip.distanceKm)} км. Восстановить сессию после удаления не получится.")
            },
        )
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = ColorDialogScrim,
            title = { Text("Импортировать сессии?") },
            text = { Text("Сессии из файла добавятся в историю. Сессии с теми же идентификаторами будут заменены.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importTrips(uri)
                    pendingImport = null
                }) { Text("Импортировать") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun TripHistoryCard(trip: Trip, onClick: () -> Unit, onDelete: () -> Unit) {
    val date = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(trip.startTime))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .clickable { onClick() }
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (trip.isActive) "$date · идёт запись" else date, style = MaterialTheme.typography.titleMedium)
            if (trip.pausedMs >= 1000L) {
                Text(
                    "В пути ${formatHms(trip.activeMs)} · на паузе ${formatHms(trip.pausedMs)}",
                    color = ColorTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconValue(Icons.Outlined.DirectionsCar, "%.1f км".format(trip.distanceKm))
                if (trip.fuelUsedLiters > 0f) IconValue(Icons.Outlined.LocalGasStation, "%.2f л".format(trip.fuelUsedLiters))
                IconValue(Icons.Outlined.Schedule, formatHms(trip.durationMs))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить сессию", tint = ColorTextSecondary)
        }
    }
}

@Composable
private fun IconValue(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = ColorNeutral, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Формат чч:мм:сс (с ведущими нулями). */
internal fun formatHms(ms: Long): String {
    val secs = ms / 1000
    return "%02d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)
}

/** Остаток до авто-завершения. Минуты округляем вверх: 9 мин 30 с — это «10 мин». */
internal fun formatPauseRemaining(ms: Long): String {
    if (ms <= 0L) return "несколько секунд"
    val totalMin = ((ms + 59_999L) / 60_000L).toInt()
    return when {
        totalMin >= 60 -> {
            val h = totalMin / 60
            val m = totalMin % 60
            if (m == 0) "$h ч" else "$h ч $m мин"
        }
        else -> "$totalMin мин"
    }
}
