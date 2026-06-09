package com.obd2monitor.ui.screen.trip

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.Trip
import com.obd2monitor.ui.components.YandexMapView
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorCardGray
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorSuccess
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.obd2monitor.ui.theme.ColorWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BOTTOM_BAR_PAD = 96.dp

/** Текущая (live) сессия. Открывается свайпом из «Главной» направо. */
@Composable
fun TripActiveScreen(
    onOpenHistory: () -> Unit = {},
    viewModel: TripViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isConnected = state.connection.state == ConnectionState.CONNECTED
    val activeTrip = state.activeTrip
    var confirmFinish by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            if (activeTrip == null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (isConnected) "Идёт инициализация…" else "Нет активной сессии",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Сессия записывается автоматически после подключения к ELM327. " +
                        "Откройте «Настройки» → выберите Bluetooth-устройство, чтобы начать запись.",
                    color = ColorTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // Длительность и флаг паузы живут в TripController (singleton) — переход на
                // вкладку «Сессии» больше не сбрасывает счётчик, а на паузе он замораживается.
                val durationMs = state.durationMs
                val isPaused = state.paused || !isConnected || !state.connection.lastSnapshotHadData
                val statusColor = when {
                    !isConnected -> ColorWarning
                    isPaused -> ColorWarning
                    else -> ColorSuccess
                }
                val statusText = when {
                    !isConnected -> "ОЖИДАНИЕ ПОДКЛЮЧЕНИЯ"
                    isPaused -> "ПАУЗА — НЕТ ДАННЫХ ОТ ECU"
                    else -> "ЗАПИСЬ ИДЁТ"
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = ColorCard),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.RadioButtonChecked,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                statusText,
                                color = statusColor,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatHms(durationMs),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // Разбивка по времени: «в пути» — счётчик из TripController (тикает
                        // только когда paused=false), «на паузе» — pausedMs. Сумма ≈
                        // фактическое время от начала сессии (с поправкой на flush в БД).
                        if (state.pausedMs >= 1000L) {
                            Text(
                                "В пути ${formatHms(durationMs)} · на паузе ${formatHms(state.pausedMs)}",
                                color = ColorTextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            if (isPaused) "Запись приостановлена — ELM подключён, но ECU не отдаёт " +
                                "данные. Сессия продолжится автоматически как только поток восстановится."
                            else "Запись остановится автоматически при отключении ELM327.",
                            color = ColorTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        // Обратный отсчёт до авто-завершения сессии. Зависимость от
                        // state.pausedMs — это «тик», который форсирует recompose раз в
                        // ~500 мс пока пауза идёт (TripController инкрементирует pausedMs).
                        val pauseSince = state.pauseSinceMs
                        if (isPaused && pauseSince != null) {
                            @Suppress("UNUSED_EXPRESSION") state.pausedMs  // recompose-тик
                            val remainingMs = (com.obd2monitor.service.TripController.AUTO_FINISH_PAUSE_MS -
                                (System.currentTimeMillis() - pauseSince)).coerceAtLeast(0L)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Сессия будет завершена автоматически через ${formatPauseRemaining(remainingMs)}, если запись не возобновится.",
                                color = ColorTextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                val tripStats = listOf(
                    LiveStat(Icons.Outlined.DirectionsCar, "Пробег", "%.2f".format(state.distanceKm), "км"),
                    LiveStat(Icons.Outlined.Speed, "Тек. скорость", "%.0f".format(state.live.speedKmh ?: 0f), "км/ч"),
                    LiveStat(Icons.Outlined.Speed, "Макс. скорость", "%.0f".format(state.maxSpeed), "км/ч"),
                    LiveStat(Icons.Outlined.Speed, "Сред. скорость", "%.0f".format(state.avgMovingSpeed), "км/ч"),
                    run {
                        // Расход: на ходу — л/100км (clamp 50), на стоянке — л/ч (как штатный БК).
                        val movingNow = (state.live.speedKmh ?: 0f) > 5f
                        if (movingNow) {
                            LiveStat(Icons.Outlined.LocalGasStation, "Расход",
                                "%.1f".format(com.obd2monitor.data.obd.FuelCalculator.displayConsumption(state.live.instantFuelLPer100) ?: 0f),
                                "л/100км")
                        } else {
                            LiveStat(Icons.Outlined.LocalGasStation, "Расход",
                                "%.1f".format(state.live.instantFuelLPerH ?: 0f),
                                "л/ч")
                        }
                    },
                    LiveStat(Icons.Outlined.LocalGasStation, "Бак", state.live.fuelLevelPercent?.let { "%.0f".format(it) } ?: "—", "%"),
                    LiveStat(Icons.Outlined.RotateRight, "Обороты", state.live.rpm?.toString() ?: "—", "об/мин"),
                    LiveStat(Icons.Outlined.Memory, "Усилие", state.live.engineLoadPercent?.let { "%.0f".format(it) } ?: "—", "%"),
                    LiveStat(Icons.Outlined.Tune, "Дроссель", state.live.throttlePercent?.let { "%.0f".format(it) } ?: "—", "%"),
                    LiveStat(Icons.Outlined.Thermostat, "Темп. ОЖ", state.live.coolantTempC?.toString() ?: "—", "°C"),
                    LiveStat(Icons.Outlined.Air, "Темп. впуска", state.live.intakeTempC?.toString() ?: "—", "°C"),
                    LiveStat(Icons.Outlined.BatteryFull, "Бортсеть", state.live.batteryVoltage?.let { "%.2f".format(it) } ?: "—", "В"),
                )
                tripStats.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowItems.forEach { item ->
                            LiveStatTile(item, modifier = Modifier.weight(1f))
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ColorCard),
                ) {
                    YandexMapView(points = state.routePoints, modifier = Modifier.fillMaxSize())
                }
                Text(
                    "Подробный отчёт сессии (графики, мин/сред/макс) будет доступен после её завершения по кнопке «История» внизу экрана.",
                    color = ColorTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
        }
        // Отступ под нижнюю панель навигации + место под FAB-кнопку «История»
        // (висит поверх скролла, но не должна перекрывать последнюю секцию контента).
        Spacer(Modifier.height(BOTTOM_BAR_PAD + 56.dp))
    }
        // Sticky-кнопка «История» — над нижней панелью навигации, чуть правее центра.
        ExtendedFloatingActionButton(
            onClick = onOpenHistory,
            icon = { Icon(Icons.Outlined.History, contentDescription = null) },
            text = { Text("История") },
            containerColor = ColorCard,
            contentColor = ColorNeutral,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = BOTTOM_BAR_PAD),
        )
        // Слева — «Завершить»: разрывает соединение с ELM, AutoSessionManager закроет trip.
        if (isConnected || activeTrip != null) {
            ExtendedFloatingActionButton(
                onClick = { confirmFinish = true },
                icon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                text = { Text("Завершить") },
                containerColor = ColorCard,
                contentColor = ColorWarning,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = BOTTOM_BAR_PAD),
            )
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
            title = { Text("Завершить сессию?") },
            text = {
                Text(
                    "Соединение с ELM327 будет разорвано, текущая поездка сохранится в истории. " +
                        "Чтобы продолжить запись — подключитесь к адаптеру заново.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    viewModel.disconnect()
                }) { Text("Завершить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) { Text("Отмена") }
            },
        )
    }
}

private data class LiveStat(val icon: ImageVector, val title: String, val value: String, val unit: String)

@Composable
private fun LiveStatTile(stat: LiveStat, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ColorCardGray)
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(stat.icon, contentDescription = null, tint = ColorNeutral, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stat.title, color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stat.value, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(4.dp))
                Text(stat.unit, color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** История завершённых сессий. Открывается как отдельный route из TripActiveScreen. */
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

    // Тосты для результатов экспорта/импорта. Snackbar бы тоже подошёл, но в этом
    // экране нет Scaffold — Toast проще и не требует обвязки.
    val backupMsg by viewModel.backupMessage.collectAsStateWithLifecycle()
    LaunchedEffect(backupMsg) {
        val m = backupMsg ?: return@LaunchedEffect
        Toast.makeText(context, m, Toast.LENGTH_LONG).show()
        viewModel.consumeBackupMessage()
    }

    // SAF: экспорт ТОЛЬКО поездок в JSON. Полный бэкап БД делается из настроек
    // (скрытое меню по 4 тапам) — там используются sqlite-файлы.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportTrips(uri)
    }
    // Импорт через подтверждение: после выбора файла НЕ запускаем сразу, а кладём
    // URI в pendingImport и показываем диалог. Без подтверждения юзер легко может
    // случайно выбрать не тот файл — и поездки с совпадающими id молча перезапишутся.
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingImport = uri
    }

    // Свайп слева направо (как системный back-жест Android) = выход назад.
    // Используем Modifier.draggable вместо detectHorizontalDragGestures — он
    // играет в одну команду с nested-scroll LazyColumn (вертикальный скролл
    // списка не глушит горизонтальный жест). Порог 80dp; флаг consumed
    // блокирует повторный onBack в одном жесте.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 80.dp.toPx() }
    var totalDragX by remember { mutableStateOf(0f) }
    var consumed by remember { mutableStateOf(false) }
    val draggableState = rememberDraggableState { delta ->
        totalDragX += delta
        if (!consumed && totalDragX > swipeThresholdPx) {
            consumed = true
            onBack()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStarted = { totalDragX = 0f; consumed = false },
                onDragStopped = { totalDragX = 0f; consumed = false },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "История поездок",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            // Шестерёнка справа: экспорт / импорт БД. См. DatabaseBackupManager —
            // формат raw SQLite, после импорта приложение перезапускается.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Меню истории")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
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
                Text("Сессий ещё нет", style = MaterialTheme.typography.bodyLarge)
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

    pendingDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
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
                Text("$date — ${"%.1f".format(trip.distanceKm)} км. Действие нельзя отменить.")
            },
        )
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
            title = { Text("Импортировать сессии?") },
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
    Card(
        colors = CardDefaults.cardColors(containerColor = ColorCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(date, style = MaterialTheme.typography.titleMedium)
            // Время в пути / на паузе. Если пауз не было (или незначительные <1 c) —
            // строку про паузу скрываем, чтобы не загромождать карточку.
            if (trip.pausedMs >= 1000L) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "В пути ${formatHms(trip.activeMs)} · на паузе ${formatHms(trip.pausedMs)}",
                    color = ColorTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                IconValue(Icons.Outlined.DirectionsCar, "%.1f км".format(trip.distanceKm))
                IconValue(Icons.Outlined.LocalGasStation, "%.2f л".format(trip.fuelUsedLiters))
                IconValue(Icons.Outlined.Schedule, formatHms(trip.durationMs))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Удалить",
                color = ColorWarning,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDelete() }
                    .padding(6.dp),
                style = MaterialTheme.typography.labelMedium,
            )
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

/** Формат hh:mm:ss всегда (включая ведущие нули). */
internal fun formatHms(ms: Long): String {
    val secs = ms / 1000
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/** Грубый формат «остаток до авто-завершения». Минуты округляем вверх — иначе при
 *  9 минутах и 30 секундах показывалось бы «9 мин», что психологически меньше реальности. */
internal fun formatPauseRemaining(ms: Long): String {
    if (ms <= 0L) return "несколько секунд"
    val totalMin = ((ms + 59_999L) / 60_000L).toInt()
    return when {
        totalMin >= 60 -> {
            val h = totalMin / 60
            val m = totalMin % 60
            if (m == 0) "${h} ч" else "${h} ч ${m} мин"
        }
        totalMin >= 2 -> "${totalMin} мин"
        else -> "1 мин"
    }
}
