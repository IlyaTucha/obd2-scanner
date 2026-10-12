package com.obd2monitor.ui.screen.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.EcuInfo
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.ui.components.ConnectionDiagnostics
import com.obd2monitor.ui.components.ScreenColumn
import com.obd2monitor.ui.screen.car.CarSection
import com.obd2monitor.ui.screen.car.mutedTextFieldColors
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorPrimary
import com.obd2monitor.ui.theme.ColorTextSecondary

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    /** Открыто ли скрытое меню резервных копий. Триггерится извне — навигация
     *  отсчитывает 6 тапов по табу «Настройки» (см. AppNavigation). Дефолт false
     *  оставлен для тестового вызова экрана без обвязки. */
    showBackupMenu: Boolean = false,
    onDismissBackupMenu: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val conn by viewModel.connection.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshDevices() }

    val isConnected = conn.state == ConnectionState.CONNECTED
    // «Подключение» по умолчанию свёрнуто всегда. Юзер раскроет вручную если
    // понадобится сменить устройство — это редкая операция, незачем занимать
    // экран постоянно.
    var connectionExpanded by remember { mutableStateOf(false) }
    var profilesExpanded by remember { mutableStateOf(false) }
    var fuelPriceExpanded by remember { mutableStateOf(false) }

    ScreenColumn {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall)

        // Диагностика подключения вверху — самая частая проблема.
        ConnectionDiagnostics(status = conn)

        CollapsibleCard(
            title = "Подключение",
            subtitle = if (isConnected) "Подключено · ${conn.deviceAddress ?: ""}" else "Не подключено",
            expanded = connectionExpanded,
            onToggle = { connectionExpanded = !connectionExpanded },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Подключаться автоматически", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "При запуске приложения — к последнему адаптеру",
                        color = ColorTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.autoConnect, onCheckedChange = viewModel::setAutoConnect)
            }
            ConnectionBody(
                isConnected = isConnected,
                connectingAddress = conn.deviceAddress?.takeIf {
                    conn.state == ConnectionState.CONNECTING ||
                        conn.state == ConnectionState.INITIALIZING
                },
                connectingStage = when (conn.state) {
                    ConnectionState.CONNECTING -> "Подключение…"
                    ConnectionState.INITIALIZING -> "Связь с блоком управления…"
                    else -> null
                },
                currentAddress = conn.deviceAddress,
                devices = state.pairedDevices,
                onRefresh = viewModel::refreshDevices,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
            )
        }

        if (isConnected) {
            SectionTitle("Блок управления двигателем")
            EcuSection(
                info = state.ecuInfo,
                loading = state.ecuLoading,
                onRefresh = viewModel::loadEcuInfo,
            )
        }

        CollapsibleCard(
            title = "Профиль автомобиля",
            subtitle = activeProfile?.name ?: "Не выбран",
            expanded = profilesExpanded,
            onToggle = { profilesExpanded = !profilesExpanded },
        ) {
            VehicleProfilesBody(
                profiles = profiles,
                active = activeProfile,
                onSelect = viewModel::setActiveProfile,
            )
        }

        val fuelPrice = activeProfile?.fuelPrice ?: 0f
        CollapsibleCard(
            title = "Цена топлива",
            subtitle = if (fuelPrice > 0f) "%.2f ₽/л".format(fuelPrice).replace(",00", "") else "Не указана",
            expanded = fuelPriceExpanded,
            onToggle = { fuelPriceExpanded = !fuelPriceExpanded },
        ) {
            FuelPriceSection(currentRub = fuelPrice, onSave = viewModel::setFuelPrice)
        }

        // Секция «Авто» — VIN/гос.номер, пробег, обслуживание, расходы.
        // Живёт внизу настроек, а не отдельной вкладкой.
        Spacer(Modifier.height(8.dp))
        CarSection()

        // Скрытое меню резервных копий открывается через 6 тапов по табу «Настройки»
        // в нижней панели навигации (см. AppNavigation). Сюда передаётся флаг —
        // SettingsScreen лишь рендерит диалог.
        BackupMenuHost(
            viewModel = viewModel,
            showMenu = showBackupMenu,
            onDismissMenu = onDismissBackupMenu,
        )
    }
}

@Composable
private fun BackupMenuHost(
    viewModel: SettingsViewModel,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
) {
    val context = LocalContext.current

    val backupMsg by viewModel.backupMessage.collectAsStateWithLifecycle()
    LaunchedEffect(backupMsg) {
        val m = backupMsg ?: return@LaunchedEffect
        Toast.makeText(context, m, Toast.LENGTH_LONG).show()
        viewModel.consumeBackupMessage()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) viewModel.exportFullDatabase(uri) }
    // Импорт через второе подтверждение — деструктивная операция: вся БД сносится.
    // Первое окно — выбор экспорт/импорт; второе — финальное «уверены?».
    var pendingFullImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingFullImport = uri }

    if (showMenu) {
        AlertDialog(
            onDismissRequest = onDismissMenu,
            containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
            title = { Text("Резервная копия данных") },
            text = {
                Text(
                    "Полная копия всех данных: сессии, профили автомобилей, пробег, " +
                        "расходы и обслуживание. Формат — файл базы SQLite (.db). " +
                        "При восстановлении текущие данные заменяются данными из файла, " +
                        "после чего приложение перезапускается.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismissMenu()
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    exportLauncher.launch("obd2_monitor_full_$stamp.db")
                }) { Text("Сохранить копию") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onDismissMenu()
                    importLauncher.launch(arrayOf("*/*"))
                }) { Text("Восстановить") }
            },
        )
    }

    // Финальное подтверждение перед деструктивным импортом — показывается ПОСЛЕ
    // выбора файла через SAF, чтобы юзер видел: «да, я только что выбрал X,
    // и сейчас оно затрёт всё».
    pendingFullImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingFullImport = null },
            containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
            title = { Text("Восстановить данные из файла?") },
            text = {
                Text(
                    "Текущие данные будут заменены данными из выбранного файла. " +
                        "Всё, что было добавлено после создания этой копии, будет потеряно. " +
                        "Приложение перезапустится сразу после восстановления.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = uri
                    pendingFullImport = null
                    viewModel.importFullDatabase(u)
                }) { Text("Восстановить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingFullImport = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    // Тот же стиль и отступ, что у заголовков секций «Авто» (CarScreen.SectionHeader).
    com.obd2monitor.ui.screen.car.SectionTitleText(title, Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun CollapsibleCard(
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ColorCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = ColorNeutral)
                    if (!subtitle.isNullOrBlank()) {
                        // Одна строка: свёрнутые карточки одной высоты независимо от длины текста.
                        Text(
                            subtitle,
                            color = ColorTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    tint = ColorTextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ConnectionBody(
    isConnected: Boolean,
    connectingAddress: String?,
    connectingStage: String?,
    currentAddress: String?,
    devices: List<DeviceInfo>,
    onRefresh: () -> Unit,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column {
        Row {
            Text(
                if (isConnected) "Подключено: $currentAddress" else "Не подключено",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (isConnected) {
                OutlinedButton(onClick = onDisconnect) { Text("Отключить") }
            } else {
                OutlinedButton(onClick = onRefresh) { Text("Обновить") }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = ColorTextSecondary.copy(alpha = 0.2f))
        if (devices.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Сопряжённых устройств нет. Выполните сопряжение адаптера в настройках Bluetooth телефона и нажмите «Обновить».",
                color = ColorTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            devices.forEach { dev ->
                val isThisConnecting = dev.address == connectingAddress
                val isThisActive = isConnected && dev.address == currentAddress
                // Пока идёт подключение — блокируем повторные клики, чтобы пользователь
                // не запустил вторую попытку поверх первой (это сломает RFCOMM-сокет).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = connectingAddress == null && !isConnected) {
                            onConnect(dev.address, dev.name)
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dev.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            dev.address,
                            color = ColorTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    when {
                        isThisConnecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = ColorPrimary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                connectingStage ?: "Подключение…",
                                color = ColorPrimary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        isThisActive -> Text(
                            "Подключено",
                            color = com.obd2monitor.ui.theme.ColorSuccess,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        else -> Text(
                            "Подключить",
                            color = ColorNeutral,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EcuSection(info: EcuInfo?, loading: Boolean, onRefresh: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ColorCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (info == null && loading) {
                Text("Чтение данных блока управления…", color = ColorTextSecondary)
            } else if (info == null) {
                Text(
                    "Не удалось получить данные блока управления. Запрос паспорта (Mode 09) " +
                        "поддерживают не все блоки — на многих автомобилях до 2008 года VIN не передаётся.",
                    color = ColorTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onRefresh) { Text("Повторить") }
            } else {
                EcuRow("VIN", info.vin ?: "не передаётся")
                EcuRow("Блок управления", info.ecuName ?: "не передаётся")
                EcuRow("Калибровка", info.calibrationId ?: "не передаётся")
                EcuRow("Протокол", info.protocol?.displayName ?: "—")
                EcuRow("Адаптер", info.elmVersion ?: "—")
                EcuRow(
                    "Поддерживаемых PID",
                    if (info.supportedPids.isEmpty()) "—"
                    else "${info.supportedPids.size}: ${info.supportedPids.joinToString(", ")}",
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onRefresh) {
                    Text(if (loading) "Обновление…" else "Обновить")
                }
            }
        }
    }
}

@Composable
private fun EcuRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = ColorTextSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Composable
private fun VehicleProfilesBody(
    profiles: List<VehicleProfile>,
    active: VehicleProfile?,
    onSelect: (String) -> Unit,
) {
    Column {
        profiles.forEach { p ->
            val isActive = p.id == active?.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(p.id) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isActive) "✓ " else "  ",
                    color = if (isActive) ColorNeutral else ColorTextSecondary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${p.engineType} · ${p.fuelType.russianName} · бак ${p.tankCapacity.toInt()}л",
                        color = ColorTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun FuelPriceSection(currentRub: Float, onSave: (Float) -> Unit) {
    // Локальное состояние ввода — сохраняем по нажатию «Сохранить» или потере фокуса.
    // Используем ремембер по ключу currentRub, чтобы при смене активного профиля
    // поле перерисовалось с актуальной ценой.
    var text by remember(currentRub) {
        mutableStateOf(if (currentRub > 0f) "%.2f".format(currentRub).trimEnd('0').trimEnd('.', ',') else "")
    }
    val parsed = text.replace(',', '.').toFloatOrNull()
    val isValid = parsed != null && parsed >= 0f
    val isDirty = parsed != null && kotlin.math.abs(parsed - currentRub) > 0.001f

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Цена литра топлива для активного профиля. По ней считаются стоимость сессии и цена 100 км на «Главной».",
            color = ColorTextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { newText ->
                    // Только цифры и одна точка/запятая; не больше 6 символов (например "999.99").
                    val filtered = newText.filter { it.isDigit() || it == '.' || it == ',' }
                    text = filtered.take(7)
                },
                label = { Text("₽ за литр", color = ColorTextSecondary) },
                singleLine = true,
                isError = text.isNotEmpty() && !isValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                // Единая палитра muted-полей — та же что и во всех диалогах
                // секции «Авто» (см. mutedTextFieldColors в CarScreen.kt).
                // Чтобы поля выглядели одинаково «приглушённо» по всему
                // приложению, без отдельного стиля у настроек.
                colors = mutedTextFieldColors(),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { parsed?.let { onSave(it) } },
                enabled = isValid && isDirty,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorPrimary,
                    contentColor = com.obd2monitor.ui.theme.ColorOnPrimary,
                    disabledContainerColor = ColorCard,
                    disabledContentColor = ColorTextSecondary,
                ),
            ) { Text("Сохранить") }
        }
        if (currentRub > 0f) {
            Text(
                "Сохранено: %.2f ₽/л".format(currentRub),
                color = ColorTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            Text(
                "Цена не задана — карточки стоимости на «Главной» скрыты.",
                color = ColorTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

