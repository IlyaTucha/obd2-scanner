package com.obd2monitor.ui.screen.car

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2monitor.domain.model.ExpenseType
import com.obd2monitor.domain.model.MaintenanceType
import com.obd2monitor.domain.model.MileageEntry
import com.obd2monitor.ui.components.LineChart
import com.obd2monitor.ui.theme.ColorAccent
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorError
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorSuccess
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.obd2monitor.ui.theme.ColorWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

/**
 * Содержимое вкладки «Авто» — без собственного scroll-контейнера. Рендерится внутри
 * SettingsScreen (там уже есть verticalScroll), поэтому свой scroll сделал бы
 * вложенный скролл и упал бы рантайм-эксепшеном compose.
 */
@Composable
fun CarSection(viewModel: CarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showVehicleDialog by remember { mutableStateOf(false) }
    // Для каждой секции есть пара: isOpen + опционально editing-entry.
    // editing == null => диалог в режиме добавления (viewModel.addX);
    // editing != null => в режиме редактирования (viewModel.upsertX, id сохраняется,
    // плюс показывается кнопка «Удалить»).
    var showMileageDialog by remember { mutableStateOf(false) }
    var editingMileage by remember { mutableStateOf<MileageEntry?>(null) }
    var showExpenseDialog by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<com.obd2monitor.domain.model.ExpenseEntry?>(null) }
    var showMaintenanceDialog by remember { mutableStateOf(false) }
    var editingMaintenance by remember { mutableStateOf<com.obd2monitor.domain.model.MaintenanceEntry?>(null) }

    // Import: системный пикер документов отдаёт content:// Uri, парсер CarImport
    // читает текст и возвращает domain-модели; ViewModel сохраняет в БД.
    //
    // REPLACE-режим: импорт ЗАМЕЩАЕТ существующие записи активного профиля
    // (см. CarViewModel.importCarReport → carLogRepo.deleteAllForProfile). Поэтому
    // перед applying показываем confirm-диалог — иначе случайный тап = потеря
    // всех вручную накопленных пробега/расходов.
    var pendingImport by remember { mutableStateOf<CarImportResult?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = importCarReportFromUri(context, uri)
        if (result == null || result.total == 0) {
            Toast.makeText(context, "Файл пустой или не распознан", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        pendingImport = result
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitleText("Авто", Modifier.weight(1f).padding(start = 4.dp))
            // Кнопка импорта: открывает системный пикер документов (.txt) и
            // загружает ранее экспортированный отчёт того же формата
            // (см. CarImport.parseCarReport). Применяет к активному профилю.
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        // mimeType text/plain; некоторые файлменеджеры
                        // (например, GDrive) отдают «*/*», поэтому слегка
                        // расширяем фильтр.
                        importLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, tint = ColorNeutral, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Импорт", style = MaterialTheme.typography.labelMedium, color = ColorNeutral)
            }
            Spacer(Modifier.size(4.dp))
            // Кнопка экспорта: сохраняет отчёт в Downloads/OBD2/ через MediaStore
            // и сразу открывает системный share-чузер. Текст так же копируется в буфер.
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        val uri = saveCarReport(context, state)
                        if (uri != null) {
                            Toast.makeText(context, "Отчёт сохранён в Downloads/OBD2", Toast.LENGTH_SHORT).show()
                            shareCarReport(context, uri)
                        } else {
                            Toast.makeText(context, "Не удалось сохранить отчёт", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = ColorNeutral, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Экспорт", style = MaterialTheme.typography.labelMedium, color = ColorNeutral)
            }
        }

        VehicleCard(
            profile = state.profile,
            currentOdoKm = state.currentOdometerKm,
            onEdit = { showVehicleDialog = true },
        )

        SectionHeader("Расход топлива", hint = "приблизительно")
        FuelEconomyCard(economy = remember(state.expenses, state.tripFuel, state.mileage) { state.fuelEconomy })

        // Кликабелен ВЕСЬ заголовок (название + строка), не только иконка «+».
        // Карточки ниже сами не кликабельны — иначе тап по графику или чипу
        // открывал бы add-диалог (мешает разглядывать данные).
        SectionHeader("Пробег", onAdd = { editingMileage = null; showMileageDialog = true })
        MileageCard(
            entries = state.mileage,
            onEdit = { entry -> editingMileage = entry; showMileageDialog = true },
        )

        SectionHeader("Расходы", onAdd = { editingExpense = null; showExpenseDialog = true })
        ExpensesCard(
            state = state,
            onEdit = { entry -> editingExpense = entry; showExpenseDialog = true },
        )

        // График трат по периоду (день/неделя/месяц/год). Подключается ниже
        // карточки расходов — это «аналитика», производный взгляд на те же
        // данные, без отдельного раздела в навигации.
        if (state.expenses.isNotEmpty()) {
            SpendingChartCard(expenses = state.expenses)
        }

        SectionHeader("Обслуживание", onAdd = { editingMaintenance = null; showMaintenanceDialog = true })
        MaintenanceCard(
            statuses = state.maintenanceStatus,
            onEdit = { entry -> editingMaintenance = entry; showMaintenanceDialog = true },
        )
    }

    if (showVehicleDialog) {
        VehicleEditDialog(
            profile = state.profile,
            catalog = viewModel.catalog,
            decodeState = state.vinDecode,
            onDecode = viewModel::decodeVin,
            onDismiss = {
                viewModel.resetVinDecode()
                showVehicleDialog = false
            },
            onSave = { make, model, year, engine, displ, fuel, tank, vin, plate ->
                viewModel.saveVehicleFull(make, model, year, engine, displ, fuel, tank, vin, plate)
                showVehicleDialog = false
            },
        )
    }
    if (showMileageDialog) {
        MileageAddDialog(
            editing = editingMileage,
            suggestedOdo = state.currentOdometerKm.takeIf { it > 0 },
            onDismiss = { showMileageDialog = false; editingMileage = null },
            onSave = { date, odo, note ->
                val orig = editingMileage
                if (orig != null) {
                    viewModel.upsertMileage(orig.copy(date = date, odometerKm = odo, note = note))
                } else {
                    viewModel.addMileage(date, odo, note)
                }
                showMileageDialog = false
                editingMileage = null
            },
            onDelete = editingMileage?.let { e -> {
                viewModel.deleteMileage(e.id)
                showMileageDialog = false
                editingMileage = null
            } },
        )
    }
    if (showExpenseDialog) {
        ExpenseAddDialog(
            editing = editingExpense,
            odometerOn = state::odometerOn,
            onDismiss = { showExpenseDialog = false; editingExpense = null },
            onSave = { date, type, desc, amt, odo, liters, full ->
                val orig = editingExpense
                if (orig != null) {
                    val fuel = type == ExpenseType.FUEL
                    viewModel.upsertExpense(
                        orig.copy(
                            date = date, type = type, description = desc, amountRub = amt, odometerKm = odo,
                            fuelLiters = liters.takeIf { fuel }, fullTank = full && fuel,
                        )
                    )
                } else {
                    viewModel.addExpense(date, type, desc, amt, odo, liters, full)
                }
                showExpenseDialog = false
                editingExpense = null
            },
            onDelete = editingExpense?.let { e -> {
                viewModel.deleteExpense(e.id)
                showExpenseDialog = false
                editingExpense = null
            } },
        )
    }
    if (showMaintenanceDialog) {
        MaintenanceAddDialog(
            editing = editingMaintenance,
            suggestedOdo = state.currentOdometerKm.takeIf { it > 0 },
            onDismiss = { showMaintenanceDialog = false; editingMaintenance = null },
            onSave = { date, type, odo, interval, note ->
                val orig = editingMaintenance
                if (orig != null) {
                    viewModel.upsertMaintenance(
                        orig.copy(date = date, type = type, odometerKm = odo, intervalKm = interval, note = note)
                    )
                } else {
                    viewModel.addMaintenance(date, type, odo, interval, note)
                }
                showMaintenanceDialog = false
                editingMaintenance = null
            },
            onDelete = editingMaintenance?.let { e -> {
                viewModel.deleteMaintenance(e.id)
                showMaintenanceDialog = false
                editingMaintenance = null
            } },
        )
    }

    pendingImport?.let { res ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
            title = { Text("Заменить данные?") },
            text = {
                Text(
                    "Импорт заменит весь текущий журнал активного профиля " +
                        "(${state.mileage.size} записей пробега, ${state.expenses.size} расходов, " +
                        "${state.maintenance.size} обслуживания) на ${res.mileage.size} / " +
                        "${res.expenses.size} / ${res.maintenance.size} из файла. " +
                        "Действие необратимое.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toApply = res
                    pendingImport = null
                    viewModel.importCarReport(toApply) { saved ->
                        Toast.makeText(context, "Импортировано записей: $saved", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Заменить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Отмена") }
            },
        )
    }
}

/**
 * Заголовок секции — один стиль на всех секциях «Настроек» и «Авто».
 * С [onAdd] кликабелен ВЕСЬ ряд (включая текст), а не только иконка «+».
 * [hint] — бледная приписка рядом с названием.
 */
@Composable
private fun SectionHeader(title: String, hint: String? = null, onAdd: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onAdd != null) Modifier.clickable(onClick = onAdd) else Modifier)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionTitleText(title, Modifier.weight(1f), hint)
        if (onAdd != null) {
            Icon(Icons.Filled.Add, contentDescription = "Добавить", tint = ColorNeutral, modifier = Modifier.size(22.dp))
        }
    }
}

/** Текст заголовка секции: titleMedium, приглушённо-светлый; [hint] — бледнее. */
@Composable
internal fun SectionTitleText(title: String, modifier: Modifier = Modifier, hint: String? = null) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ColorNeutral)
        if (hint != null) {
            Text(
                "  $hint",
                style = MaterialTheme.typography.bodySmall,
                color = ColorTextSecondary.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun VehicleCard(
    profile: com.obd2monitor.domain.model.VehicleProfile?,
    currentOdoKm: Int,
    onEdit: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .clickable(onClick = onEdit)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(profile?.name ?: "Профиль не выбран", style = MaterialTheme.typography.titleMedium)
        if (profile != null) {
            InfoLine("Марка / модель", "${profile.make} ${profile.model}")
            InfoLine("Год", profile.year.takeIf { it > 0 }?.toString() ?: "—")
            InfoLine("Двигатель", profile.engineType)
            InfoLine("VIN", profile.vin.ifBlank { "— (тапни чтобы ввести)" })
            InfoLine("Гос. номер", profile.licensePlate.ifBlank { "—" })
            InfoLine("Текущий пробег", if (currentOdoKm > 0) "$currentOdoKm км" else "—")
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ColorTextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MileageCard(
    entries: List<MileageEntry>,
    onEdit: (MileageEntry) -> Unit,
) {
    // По умолчанию виден только график и последняя запись; остальные
    // подгружаются страницами по «Показать ещё» (в журнале сотни записей).
    var shown by remember { mutableIntStateOf(1) }
    // Тап вне самого графика (по фону карточки / тексту итога / списку строк)
    // снимает выделение точки на LineChart. Внутренние pointerInput'ы графика
    // и EditableRow'ов перехватывают тапы на себя, поэтому outer-детектор
    // вызывается ТОЛЬКО для «пустых» зон карточки.
    var chartResetTick by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .pointerInput(Unit) {
                detectTapGestures { chartResetTick++ }
            }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (entries.size < 2) {
            Text(
                "Добавь минимум 2 записи пробега чтобы увидеть график динамики.",
                style = MaterialTheme.typography.bodySmall,
                color = ColorTextSecondary,
            )
        } else {
            val sorted = entries.sortedBy { it.date }
            val series = sorted.map { it.odometerKm.toFloat() }
            // Подписи X-оси — формат «dd.MM» из даты записи. Полный «dd.MM.yyyy»
            // не влез бы на 4 tick'а — на узких графиках лейблы наезжали бы
            // друг на друга.
            val xLabelFormat = SimpleDateFormat("dd.MM", Locale.getDefault())
            val xLabels = sorted.map { xLabelFormat.format(Date(it.date)) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(top = 4.dp),
            ) {
                LineChart(
                    values = series,
                    color = ColorAccent,
                    valueFormatter = { "%.0f".format(it) },
                    xAxisLabels = xLabels,
                    resetSelectionKey = chartResetTick,
                    pointNotes = sorted.map { it.note },
                )
            }
            val deltaKm = (sorted.last().odometerKm - sorted.first().odometerKm).coerceAtLeast(0)
            val days = ((sorted.last().date - sorted.first().date) / 86_400_000.0).coerceAtLeast(1.0)
            val perDay = deltaKm / days
            Text(
                "Общее расстояние за период: $deltaKm км · ${"%.0f".format(perDay)} км/день в среднем",
                style = MaterialTheme.typography.bodySmall,
                color = ColorTextSecondary,
            )
        }
        val sortedDesc = entries.sortedByDescending { it.date }
        sortedDesc.take(shown).forEach { entry ->
            // Каждая строка — кликабельная: тап открывает диалог редактирования
            // именно этой записи. Иконка удаления слева не нужна — удаление
            // доступно из диалога edit'а.
            EditableRow(onClick = { onEdit(entry) }) {
                Text("${entry.odometerKm} км", style = MaterialTheme.typography.bodyMedium)
                Text(
                    dateFormat.format(Date(entry.date)) + if (entry.note.isNotBlank()) "  · ${entry.note}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = ColorTextSecondary,
                )
            }
        }
        PagedControls(shown, entries.size, initial = 1, pageSize = LIST_PAGE, onShownChange = { shown = it })
        if (entries.isEmpty()) {
            Text(
                "Нет записей. Тап по заголовку «Пробег» — чтобы добавить пробег на любую дату.",
                style = MaterialTheme.typography.bodySmall,
                color = ColorTextSecondary,
            )
        }
    }
}

/**
 * Кликабельный ряд для отображения записи в журналах. Левая колонка — контент
 * (составляется содержимым). Иконки удаления намеренно нет — удаление делается
 * через edit-диалог, чтобы случайный тап по «корзинке» не уничтожал запись.
 */
@Composable
private fun EditableRow(
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) { content() }
    }
}

/** Сколько записей журнала подгружается за одно нажатие «Показать ещё». */
private const val LIST_PAGE = 20

/**
 * Постраничный показ длинного списка: «Показать ещё N» добавляет [pageSize] записей,
 * «Свернуть» возвращает к [initial]. Сотни записей не рисуются разом.
 */
@Composable
private fun PagedControls(
    shown: Int,
    total: Int,
    initial: Int,
    pageSize: Int,
    onShownChange: (Int) -> Unit,
) {
    if (total <= initial) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (shown < total) {
            val next = minOf(pageSize, total - shown)
            Text(
                if (next < total - shown) "Показать ещё $next из ${total - shown}" else "Показать оставшиеся $next",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onShownChange(shown + next) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = ColorNeutral,
            )
        }
        Spacer(Modifier.weight(1f))
        if (shown > initial) {
            Text(
                "Свернуть",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onShownChange(initial) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = ColorTextSecondary,
            )
        }
    }
}

/** Маленький toggle-link: «Подробнее» ↔ «Свернуть». */
@Composable
private fun ExpandToggle(
    expanded: Boolean,
    collapsedLabel: String,
    onToggle: () -> Unit,
    expandedLabel: String = "Свернуть",
) {
    Text(
        if (expanded) expandedLabel else collapsedLabel,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = ColorNeutral,
    )
}

@Composable
private fun MaintenanceCard(
    statuses: List<MaintenanceStatus>,
    onEdit: (com.obd2monitor.domain.model.MaintenanceEntry) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .padding(12.dp),
        // Небольшой спейсинг между записями: при большем (плюс IconButton 48dp,
        // задающий высоту строки) появляются визуально «большие пустые промежутки».
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (statuses.isEmpty()) {
            Text(
                "Добавь замены масел/фильтров — приложение будет следить когда менять следующий раз.",
                style = MaterialTheme.typography.bodySmall,
                color = ColorTextSecondary,
            )
        }
        statuses.forEach { st -> MaintenanceRow(st, onEdit) }
    }
}

@Composable
private fun MaintenanceRow(
    st: MaintenanceStatus,
    onEdit: (com.obd2monitor.domain.model.MaintenanceEntry) -> Unit,
) {
    val color = when (st.urgency) {
        MaintenanceStatus.Urgency.OVERDUE -> ColorError
        MaintenanceStatus.Urgency.SOON -> ColorWarning
        MaintenanceStatus.Urgency.OK -> ColorSuccess
    }
    val statusText = when (st.urgency) {
        MaintenanceStatus.Urgency.OVERDUE -> "просрочено ${-st.remainingKm} км"
        else -> "через ${st.remainingKm} км"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // Тап по всей строке открывает edit-диалог для этой записи —
            // оттуда же доступно удаление.
            .clickable { onEdit(st.entry) }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(st.entry.type.russianName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Замена ${st.entry.odometerKm} км (${dateFormat.format(Date(st.entry.date))}) · следующая ${st.entry.nextDueKm} км",
                style = MaterialTheme.typography.labelSmall,
                color = ColorTextSecondary,
            )
            Text(statusText, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun ExpensesCard(
    state: CarUiState,
    onEdit: (com.obd2monitor.domain.model.ExpenseEntry) -> Unit,
) {
    // По умолчанию виден только последний расход + сумма по категориям;
    // остальные подгружаются страницами по «Показать ещё».
    var shown by remember { mutableIntStateOf(1) }
    // Выбранная категория для drill-down модалки. null = модалка не показана;
    // при тапе на плашку «Топливо» / «Запчасти» / ... сюда кладётся тип, и
    // открывается отдельный AlertDialog со всеми записями этой категории.
    var drillType by remember { mutableStateOf<com.obd2monitor.domain.model.ExpenseType?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardValue(
            "${"%,.0f".format(state.totalExpenses).replace(',', ' ')} ₽",
            "за всё время",
            inline = true,
        )
        if (state.expensesByType.isNotEmpty()) {
            // Сетка 2 колонки одинаковой ширины. FlowRow паковал жадно слева направо
            // и оставлял пустые «дырки» справа когда длинный лейбл («Налог/штраф»)
            // переносил следующий чип на новую строку. Фиксированная сетка такого
            // эффекта не даёт — каждый ряд равномерно заполнен.
            val sorted = state.expensesByType.entries.sortedByDescending { it.value }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sorted.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (type, sum) ->
                            ExpenseSummaryCell(
                                type.russianName,
                                "${"%,.0f".format(sum).replace(',', ' ')} ₽",
                                onClick = { drillType = type },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Если в последнем ряду одна запись — добавляем пустой
                        // weight=1f spacer чтобы единственный чип не растянулся
                        // на всю ширину карточки.
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        val sortedExpenses = state.expenses  // dao уже отдаёт DESC по дате
        sortedExpenses.take(shown).forEach { exp ->
            EditableRow(onClick = { onEdit(exp) }) {
                Text(
                    "${exp.type.russianName} · ${"%,.0f".format(exp.amountRub).replace(',', ' ')} ₽",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val odoSuffix = exp.odometerKm?.let { "  · $it км" }.orEmpty()
                Text(
                    dateFormat.format(Date(exp.date)) + odoSuffix +
                        if (exp.description.isNotBlank()) "\n${exp.description}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = ColorTextSecondary,
                )
            }
        }
        PagedControls(shown, sortedExpenses.size, initial = 1, pageSize = LIST_PAGE, onShownChange = { shown = it })
        if (state.expenses.isEmpty()) {
            Text(
                "Нет расходов. Тап по заголовку «Расходы» — чтобы добавить ТО, бензин, запчасти и т.д.",
                style = MaterialTheme.typography.bodySmall,
                color = ColorTextSecondary,
            )
        }
    }

    drillType?.let { type ->
        CategoryExpensesDialog(
            type = type,
            expenses = state.expenses.filter { it.type == type },
            onDismiss = { drillType = null },
            onEdit = { entry ->
                // Закрываем drill-down модалку и сразу прокидываем тап «выше» —
                // в ExpensesCard.onEdit, который уже знает как открыть полноценный
                // edit-диалог записи. Это даёт удобный жест: тап по «Топливо» →
                // тап по нужной заправке → редактирование, ровно за 2 тапа.
                drillType = null
                onEdit(entry)
            },
        )
    }
}

/**
 * Главное значение карточки — одинаково во всех карточках «Авто»: крупное
 * значение и серая подпись под ним. [aside] — второе значение справа с подписью.
 */
@Composable
private fun CardValue(
    value: String,
    caption: String,
    aside: Pair<String, String>? = null,
    /** Подпись в одну строку со значением, а не под ним. */
    inline: Boolean = false,
) {
    if (inline) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(
                "  $caption",
                style = MaterialTheme.typography.bodySmall,
                color = ColorTextSecondary,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(caption, style = MaterialTheme.typography.bodySmall, color = ColorTextSecondary)
        }
        if (aside != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(aside.first, style = MaterialTheme.typography.titleMedium, color = ColorNeutral)
                Text(aside.second, style = MaterialTheme.typography.bodySmall, color = ColorTextSecondary)
            }
        }
    }
}

/**
 * Расход топлива по заправкам: литры из журнала расходов против пробега.
 * Справа — расчёт по датчикам OBD за тот же период, чтобы видеть, насколько
 * ему можно доверять.
 */
@Composable
private fun FuelEconomyCard(economy: com.obd2monitor.domain.model.FuelEconomy?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (economy == null) {
            Text(
                "Появится после нескольких заправок с литрами и пробегом.",
                style = MaterialTheme.typography.bodySmall,
                color = ColorTextSecondary,
            )
            return@Column
        }
        val obd = economy.overallObd?.let { o ->
            val diff = ((o.lPer100 / economy.overall.lPer100 - 1f) * 100f).roundToInt()
            val sign = when {
                diff > 0 -> "+$diff%"
                diff < 0 -> "−${-diff}%"
                else -> "0%"
            }
            "${lPer100(o.lPer100)} ($sign)" to "по датчикам OBD"
        }
        CardValue(
            "${lPer100(economy.overall.lPer100)} л/100 км",
            "за всё время · ${"%.0f".format(economy.overall.liters)} л · ${economy.overall.km} км",
            aside = obd,
        )
        Spacer(Modifier.height(4.dp))
        economy.lastTank?.let { t ->
            InfoLine("Последний полный бак, ${tankDates(t)}", "${lPer100(t.span.lPer100)} л/100 км")
        }
        economy.sinceLastFill?.let { s ->
            InfoLine(
                "С заправки ${shortDate.format(Date(s.date))}, ${s.km} км",
                "~${"%.0f".format(s.estimatedLiters)} л",
            )
        }

        var expanded by remember { mutableStateOf(false) }
        var tanksShown by remember { mutableIntStateOf(TANKS_PAGE) }
        if (expanded) {
            if (economy.tanks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Полные баки", style = MaterialTheme.typography.labelLarge, color = ColorNeutral)
                economy.tanks.asReversed().take(tanksShown).forEach { t ->
                    InfoLine(
                        "${tankDates(t)} · ${t.span.km} км · ${"%.0f".format(t.span.liters)} л",
                        "${lPer100(t.span.lPer100)} л/100 км",
                    )
                }
                PagedControls(tanksShown, economy.tanks.size, initial = TANKS_PAGE, pageSize = TANKS_PAGE) {
                    tanksShown = it
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Как считается", style = MaterialTheme.typography.labelLarge, color = ColorNeutral)
            FuelNote(
                "За всё время",
                "все залитые литры, кроме последней заправки, на пробег от первой заправки до последней.",
            )
            FuelNote(
                "Полный бак",
                "литры, залитые до полного, на пробег от предыдущей заправки до полного. " +
                    "Точность зависит от пробега, указанного у заправок.",
            )
            FuelNote(
                "С заправки",
                "пробег по журналу × средний расход за всё время. Сколько сгорело на самом деле, " +
                    "станет известно на следующей заправке.",
            )
        }
        ExpandToggle(
            expanded = expanded,
            collapsedLabel = "Подробнее",
            expandedLabel = "Скрыть подробности",
            onToggle = { expanded = !expanded },
        )
    }
}

private const val TANKS_PAGE = 10

private fun tankDates(t: com.obd2monitor.domain.model.FuelTank): String =
    "${shortDate.format(Date(t.fromDate))} – ${shortDate.format(Date(t.toDate))}"

/** Пояснение к расчёту: название строки светлее, объяснение — серым. */
@Composable
private fun FuelNote(title: String, text: String) {
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = ColorNeutral)) { append(title) }
            append(" — ")
            append(text)
        },
        style = MaterialTheme.typography.bodySmall,
        color = ColorTextSecondary,
    )
}

private val shortDate = SimpleDateFormat("dd.MM", Locale.getDefault())

private fun lPer100(v: Float): String = "%.1f".format(v)

/**
 * Модалка «Все расходы категории X». Показывает плоский список всех записей
 * выбранной [type], отсортированных от новых к старым. Тап по любой записи →
 * закрытие модалки + проброс наверх (открывает полноценный edit-диалог).
 *
 * Зачем: на главной карточке «Расходы» сводка по категориям — это плашки
 * «Топливо 16 327 ₽», «Запчасти 28 610 ₽» и т.д. Без drill-down видны только
 * итоговые суммы, не посмотреть «из чего конкретно складывается 16 тысяч на
 * топливо». Один тап по плашке открывает разбивку.
 */
@Composable
private fun CategoryExpensesDialog(
    type: com.obd2monitor.domain.model.ExpenseType,
    expenses: List<com.obd2monitor.domain.model.ExpenseEntry>,
    onDismiss: () -> Unit,
    onEdit: (com.obd2monitor.domain.model.ExpenseEntry) -> Unit,
) {
    val total = expenses.sumOf { it.amountRub.toDouble() }.toFloat()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
        title = {
            Column {
                Text(type.russianName)
                Text(
                    "${"%,.0f".format(total).replace(',', ' ')} ₽ · ${expenses.size} записей",
                    style = MaterialTheme.typography.labelMedium,
                    color = ColorTextSecondary,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (expenses.isEmpty()) {
                    Text(
                        "Нет записей в категории.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ColorTextSecondary,
                    )
                }
                expenses.sortedByDescending { it.date }.forEach { exp ->
                    EditableRow(onClick = { onEdit(exp) }) {
                        Text(
                            "${"%,.0f".format(exp.amountRub).replace(',', ' ')} ₽",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val odoSuffix = exp.odometerKm?.let { "  · $it км" }.orEmpty()
                        Text(
                            dateFormat.format(Date(exp.date)) + odoSuffix +
                                if (exp.description.isNotBlank()) "\n${exp.description}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = ColorTextSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/**
 * Ячейка-плашка для сетки сводки по категориям расходов: название (мелким) +
 * сумма (покрупнее). Фиксированный фон и единый внутренний паддинг — поэтому
 * соседние ячейки в Row выглядят как ровная сетка без зазоров «по содержимому».
 */
@Composable
private fun ExpenseSummaryCell(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ColorCard.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = ColorTextSecondary,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = ColorNeutral,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}


// ───── Диалоги ─────

/**
 * Универсальный контейнер для содержимого диалогов: внешний scroll позволяет
 * прокручивать содержимое когда клавиатура поджимает доступную высоту.
 *
 * windowSoftInputMode=adjustResize в манифесте сам сжимает окно активити под
 * IME, а AlertDialog адаптирует свою высоту — нижние поля при этом не уезжают
 * за край экрана, потому что внутреннее содержимое скроллится. Связка
 * decorFitsSystemWindows=false + imePadding() здесь не годится — она ломает
 * верстку (диалог рисуется поверх инсетов, нижние строки расходов и
 * обслуживания не видны).
 */
/**
 * Единая палитра для OutlinedTextField во ВСЕХ полях ввода приложения.
 *
 * С дефолтными Material3 colors контейнер прозрачный, граница ярко-белая, поле
 * визуально «дёргается» на тёмном фоне — и стиль полей расходится по секциям
 * (например поле «Цена топлива» в Настройках имеет свой набор muted-цветов).
 * Поэтому все OutlinedTextField приложения берут эту палитру: контейнер =
 * ColorCard, граница приглушённая (0.15 в покое, 0.4 в фокусе) — единый
 * «дымчатый» вид без ярких рамок.
 */
@Composable
internal fun mutedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = ColorCard,
    unfocusedContainerColor = ColorCard,
    disabledContainerColor = ColorCard,
    focusedBorderColor = ColorTextSecondary.copy(alpha = 0.4f),
    unfocusedBorderColor = ColorTextSecondary.copy(alpha = 0.15f),
    disabledBorderColor = ColorTextSecondary.copy(alpha = 0.1f),
    focusedLabelColor = ColorTextSecondary,
    unfocusedLabelColor = ColorTextSecondary.copy(alpha = 0.6f),
)

@Composable
private fun DialogBody(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/**
 * Диалог редактирования данных авто. Юзер может заполнить любую машину вручную:
 * марка / модель / год / двигатель / объём / топливо / бак — всё editable
 * (а не только VIN + гос. номер, с остальным из хардкод-пресетов/VIN-декодера).
 * VIN-декодер вспомогательный — успешный decode заполняет соответствующие поля,
 * после чего их можно поправить руками.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehicleEditDialog(
    profile: com.obd2monitor.domain.model.VehicleProfile?,
    catalog: com.obd2monitor.data.catalog.CarCatalog,
    decodeState: VinDecodeUiState,
    onDecode: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, String, Float, com.obd2monitor.domain.model.FuelType, Float, String, String) -> Unit,
) {
    var make by remember { mutableStateOf(profile?.make.orEmpty()) }
    var model by remember { mutableStateOf(profile?.model.orEmpty()) }
    var yearText by remember { mutableStateOf(profile?.year?.takeIf { it > 0 }?.toString().orEmpty()) }
    var engine by remember { mutableStateOf(profile?.engineType.orEmpty()) }
    var displText by remember { mutableStateOf(profile?.displacementL?.takeIf { it > 0f }?.let { "%.1f".format(it) }.orEmpty()) }
    var fuel by remember { mutableStateOf(profile?.fuelType ?: com.obd2monitor.domain.model.FuelType.PETROL) }
    var tankText by remember { mutableStateOf(profile?.tankCapacity?.takeIf { it > 0f }?.let { "%.0f".format(it) }.orEmpty()) }
    var vin by remember { mutableStateOf(profile?.vin.orEmpty()) }
    var plate by remember { mutableStateOf(profile?.licensePlate.orEmpty()) }

    // Каталог авто (марка→модель→поколение→двигатель). По тапу «Выбрать из
    // каталога» открывается вложенный AlertDialog, и при «Применить» данные
    // оттуда прорастают в поля выше — дальше юзер уже правит вручную.
    var showCatalogPicker by remember { mutableStateOf(false) }

    // Если в этом сеансе пришёл успешный VIN-декод — единоразово применяем его к
    // полям (юзеру не надо переписывать руками). Дальше юзер свободно правит.
    val decoded = (decodeState as? VinDecodeUiState.Done)?.result
    val appliedDecode = remember { mutableStateOf(false) }
    if (decoded != null && !appliedDecode.value && decoded.vin == vin) {
        decoded.make?.let { if (make.isBlank()) make = it }
        decoded.model?.let { if (model.isBlank()) model = it }
        decoded.year?.let { if (yearText.isBlank()) yearText = it.toString() }
        decoded.engineType?.let { if (engine.isBlank()) engine = it }
        decoded.displacementL?.let { if (displText.isBlank()) displText = "%.1f".format(it) }
        decoded.fuelType?.let { fuel = it }
        appliedDecode.value = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
        title = { Text("Данные автомобиля") },
        text = {
            DialogBody {
                // Кнопка-ссылка в самом верху: быстрый ввод через каталог.
                // Текст-кнопка, не Button — не агрессивно, не претендует на
                // primary action в диалоге (primary — «Сохранить» внизу).
                TextButton(
                    onClick = { showCatalogPicker = true },
                ) {
                    Text(
                        "Выбрать из каталога…",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                FieldGroup(title = "Марка") {
                    OutlinedTextField(
                        value = make,
                        onValueChange = { make = it.take(40) },
                        placeholder = { PlaceholderText("Марка") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Next,
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FieldGroup(title = "Модель") {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it.take(40) },
                        placeholder = { PlaceholderText("Модель") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Next,
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FieldGroup(title = "Год выпуска") {
                    OutlinedTextField(
                        value = yearText,
                        onValueChange = { yearText = it.filter { ch -> ch.isDigit() }.take(4) },
                        placeholder = { PlaceholderText("2008") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FieldGroup(title = "Двигатель") {
                    OutlinedTextField(
                        value = engine,
                        onValueChange = { engine = it.take(40) },
                        placeholder = { PlaceholderText("1.6 бензин") },
                        singleLine = true,
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FieldGroup(title = "Объём, литров") {
                    OutlinedTextField(
                        value = displText,
                        onValueChange = { displText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(4) },
                        placeholder = { PlaceholderText("1.6") },
                        suffix = { Text("л", color = ColorTextSecondary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FieldGroup(title = "Тип топлива") {
                    ChipGrid(
                        options = com.obd2monitor.domain.model.FuelType.values().toList(),
                        selected = fuel,
                        label = { it.russianName },
                        onSelect = { fuel = it },
                    )
                }
                FieldGroup(title = "Бак, литров") {
                    OutlinedTextField(
                        value = tankText,
                        onValueChange = { tankText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(4) },
                        placeholder = { PlaceholderText("50") },
                        suffix = { Text("л", color = ColorTextSecondary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FieldGroup(title = "VIN", hint = "17 символов, без I/O/Q. Помогает авто-заполнить поля выше.", optional = true) {
                    OutlinedTextField(
                        value = vin,
                        onValueChange = {
                            vin = it.uppercase().take(17)
                            appliedDecode.value = false
                        },
                        placeholder = { PlaceholderText("KMHCN41BP8U123456") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Next,
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onDecode(vin) },
                        enabled = vin.length == 17 && decodeState !is VinDecodeUiState.Loading,
                    ) {
                        Text(
                            if (decodeState is VinDecodeUiState.Loading) "Поиск…" else "Определить по VIN",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (decodeState is VinDecodeUiState.Loading) {
                        Spacer(Modifier.size(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ColorNeutral,
                        )
                    }
                }
                if (decodeState is VinDecodeUiState.Done) {
                    VinDecodeResultCard(decodeState.result)
                }
                FieldGroup(title = "Гос. номер", optional = true) {
                    OutlinedTextField(
                        value = plate,
                        onValueChange = { plate = it.uppercase().take(12) },
                        placeholder = { PlaceholderText("А123АА777") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done,
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val year = yearText.toIntOrNull() ?: 0
                val displ = displText.replace(',', '.').toFloatOrNull() ?: 0f
                val tank = tankText.replace(',', '.').toFloatOrNull() ?: 0f
                onSave(make, model, year, engine, displ, fuel, tank, vin, plate)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    // Каскадный picker — вложенный диалог поверх VehicleEditDialog. На «Применить»
    // прорастает выбранную запись каталога в editable-поля выше; сохранение
    // выполняется уже из основного диалога. Это даёт пользователю возможность
    // докрутить любое поле перед сохранением (например, поменять объём с 1.6 на 1.5).
    if (showCatalogPicker) {
        CarCatalogPickerDialog(
            catalog = catalog,
            onDismiss = { showCatalogPicker = false },
            onPick = { entry ->
                make = entry.make
                model = entry.model
                yearText = entry.yearFrom.toString()
                engine = entry.engine
                displText = "%.1f".format(entry.displacement)
                fuel = entry.fuel
                tankText = "%.0f".format(entry.tank)
                showCatalogPicker = false
            },
        )
    }
}

/** Карточка-превью с расшифрованными по VIN полями. Применяется при «Сохранить». */
@Composable
private fun VinDecodeResultCard(result: com.obd2monitor.data.vin.VinDecodeResult) {
    val parts = mutableListOf<String>()
    result.make?.let { parts += it }
    result.model?.let { parts += it }
    result.year?.let { parts += it.toString() }
    val title = parts.joinToString(" ").ifBlank { "Не удалось определить" }

    val details = buildList {
        result.engineType?.let { add("Двигатель: $it") }
        result.fuelType?.let { add("Топливо: ${it.russianName}") }
        result.maxPowerKw?.let { add("Мощность: ~${(it * 1.341f).toInt()} л.с.") }
    }
    val sourceLine = when {
        result.online -> "Источник: NHTSA vPIC (онлайн)"
        else -> "Источник: локальный fallback (по WMI и 10-му символу)"
    }
    val accent = if (result.hasAnyData) ColorSuccess else ColorWarning
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ColorCard)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
        details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = ColorTextSecondary) }
        Text(sourceLine, style = MaterialTheme.typography.labelSmall, color = ColorTextSecondary)
        result.errorMessage?.let { msg ->
            // Подсказка про неполноту данных — отдельно, цветом warning, чтобы не
            // сливалась с источником и не противоречила зелёному заголовку.
            Text(msg, style = MaterialTheme.typography.labelSmall, color = ColorWarning)
        }
        if (result.hasAnyData) {
            Text(
                "При сохранении эти поля применятся к профилю авто.",
                style = MaterialTheme.typography.labelSmall,
                color = ColorNeutral,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MileageAddDialog(
    editing: MileageEntry? = null,
    suggestedOdo: Int?,
    onDismiss: () -> Unit,
    onSave: (Long, Int, String) -> Unit,
    /** Если задан — внизу появляется кнопка «Удалить» (только в edit-режиме). */
    onDelete: (() -> Unit)? = null,
) {
    var odoText by remember { mutableStateOf((editing?.odometerKm ?: suggestedOdo)?.toString().orEmpty()) }
    var note by remember { mutableStateOf(editing?.note.orEmpty()) }
    var dateMs by remember { mutableStateOf(editing?.date ?: System.currentTimeMillis()) }
    // Явные FocusRequester'ы для перехода по «Далее». На числовой клавиатуре
    // FocusDirection.Next через дерево фокуса работает ненадёжно (часть IME
    // вообще не вызывает onNext, часть — вызывает, но геометрический поиск
    // не находит цель из-за вложенности FieldGroup → Column → DialogBody).
    // Явный requestFocus() надёжен независимо от типа клавиатуры.
    val focusManager = LocalFocusManager.current
    val odoFocus = remember { FocusRequester() }
    val noteFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
        title = { Text(if (editing == null) "Запись пробега" else "Изменить пробег") },
        text = {
            DialogBody {
                FieldGroup(title = "Дата") {
                    DateField(value = dateMs, onChange = { dateMs = it })
                }
                FieldGroup(title = "Пробег на одометре", hint = "число в километрах") {
                    OutlinedTextField(
                        value = odoText,
                        onValueChange = { odoText = it.filter { ch -> ch.isDigit() }.take(7) },
                        placeholder = { PlaceholderText("87000") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { noteFocus.requestFocus() },
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(odoFocus),
                    )
                }
                FieldGroup(title = "Комментарий", optional = true) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(80) },
                        placeholder = { PlaceholderText("Комментарий") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() },
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(noteFocus),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val odo = odoText.toIntOrNull() ?: return@TextButton
                onSave(dateMs, odo, note)
            }) { Text("Сохранить") }
        },
        dismissButton = {
            // В edit-режиме на месте «Отмены» рисуем две кнопки рядом:
            // «Удалить» (warning-color) + «Отмена». В add-режиме — только
            // «Отмена». Это даёт интуитивный путь удаления записи прямо
            // из ряда, без отдельного контекстного меню.
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Удалить", color = ColorWarning)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseAddDialog(
    editing: com.obd2monitor.domain.model.ExpenseEntry? = null,
    /** Пробег на дату по журналу пробега (null — журнал пуст). */
    odometerOn: (Long) -> Int?,
    onDismiss: () -> Unit,
    /** дата, категория, описание, сумма, пробег, литры, полный бак */
    onSave: (Long, ExpenseType, String, Float, Int?, Float?, Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var dateMs by remember { mutableStateOf(editing?.date ?: System.currentTimeMillis()) }
    // Новый расход: пробег берётся по журналу на дату и следует за сменой даты,
    // пока его не изменили вручную. У существующей записи — сохранённый пробег,
    // а значение по журналу предлагается подсказкой, если отличается.
    var odoText by remember {
        mutableStateOf((editing?.odometerKm ?: odometerOn(dateMs))?.toString().orEmpty())
    }
    var odoEdited by remember { mutableStateOf(editing != null) }
    val journalOdo = odometerOn(dateMs)
    var type by remember { mutableStateOf(editing?.type ?: ExpenseType.MAINTENANCE) }
    var description by remember { mutableStateOf(editing?.description.orEmpty()) }
    var amountText by remember {
        mutableStateOf(editing?.amountRub?.let { if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString() }.orEmpty())
    }
    // Литры у старых заправок подхватываются из описания («40л 92»).
    var litersText by remember { mutableStateOf(editing?.liters?.let { formatNumber(it) }.orEmpty()) }
    var fullTank by remember { mutableStateOf(editing?.fullTank ?: false) }
    val focusManager = LocalFocusManager.current
    val descriptionFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
        title = { Text(if (editing == null) "Новый расход" else "Изменить расход") },
        text = {
            DialogBody {
                FieldGroup(title = "Дата") {
                    DateField(value = dateMs, onChange = {
                        dateMs = it
                        if (!odoEdited) odoText = odometerOn(it)?.toString().orEmpty()
                    })
                }
                FieldGroup(title = "Пробег на одометре", optional = true) {
                    OutlinedTextField(
                        value = odoText,
                        onValueChange = {
                            odoText = it.filter { ch -> ch.isDigit() }.take(7)
                            odoEdited = true
                        },
                        placeholder = { PlaceholderText("87000") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (journalOdo != null && journalOdo.toString() != odoText) {
                        Text(
                            "По журналу на эту дату: $journalOdo км · подставить",
                            style = MaterialTheme.typography.labelSmall,
                            color = ColorAccent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { odoText = journalOdo.toString(); odoEdited = false }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                        )
                    }
                }
                FieldGroup(title = "Категория") {
                    ChipGrid(
                        options = ExpenseType.values().toList(),
                        selected = type,
                        label = { it.russianName },
                        onSelect = { type = it },
                    )
                }
                FieldGroup(title = "Сумма в рублях") {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(9) },
                        placeholder = { PlaceholderText("3500") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { descriptionFocus.requestFocus() },
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (type == ExpenseType.FUEL) {
                    FieldGroup(title = "Литры", hint = "для расчёта расхода по заправкам") {
                        OutlinedTextField(
                            value = litersText,
                            onValueChange = { litersText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) },
                            placeholder = { PlaceholderText("40") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { descriptionFocus.requestFocus() }),
                            colors = mutedTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val liters = litersText.replace(',', '.').toFloatOrNull()
                        val amount = amountText.replace(',', '.').toFloatOrNull()
                        if (liters != null && liters > 0f && amount != null && amount > 0f) {
                            Text(
                                "${"%.2f".format(amount / liters)} ₽ за литр",
                                style = MaterialTheme.typography.labelSmall,
                                color = ColorTextSecondary,
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { fullTank = !fullTank },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = fullTank, onCheckedChange = { fullTank = it })
                        Column {
                            Text("Полный бак", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "между двумя полными заправками расход считается точно",
                                style = MaterialTheme.typography.labelSmall,
                                color = ColorTextSecondary,
                            )
                        }
                    }
                }
                FieldGroup(title = "Описание", optional = true) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(500) },
                        placeholder = { PlaceholderText("Например, замена масла и фильтра") },
                        // singleLine: иначе длинное описание раскатывает поле
                        // на несколько строк и нижний край (с курсором) уезжает
                        // за пределы видимой области диалога. С однострочкой
                        // текст скроллится горизонтально внутри поля.
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() },
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(descriptionFocus),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amt = amountText.replace(',', '.').toFloatOrNull() ?: return@TextButton
                onSave(
                    dateMs, type, description, amt,
                    odoText.toIntOrNull()?.takeIf { it > 0 },
                    litersText.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f },
                    fullTank,
                )
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Удалить", color = ColorWarning)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaintenanceAddDialog(
    editing: com.obd2monitor.domain.model.MaintenanceEntry? = null,
    suggestedOdo: Int?,
    onDismiss: () -> Unit,
    onSave: (Long, MaintenanceType, Int, Int, String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var dateMs by remember { mutableStateOf(editing?.date ?: System.currentTimeMillis()) }
    var type by remember { mutableStateOf(editing?.type ?: MaintenanceType.ENGINE_OIL) }
    var odoText by remember { mutableStateOf((editing?.odometerKm ?: suggestedOdo)?.toString().orEmpty()) }
    var note by remember { mutableStateOf(editing?.note.orEmpty()) }
    // Интервал: при edit берём сохранённый intervalKm; при add — дефолт типа.
    // remember(type) переинициализирует поле при смене типа в чипах — для add.
    // Для edit-режима смена типа тоже сбросит интервал (это сейчас приемлемо).
    var intervalText by remember(type) {
        mutableStateOf((editing?.intervalKm?.takeIf { it > 0 } ?: type.defaultIntervalKm).toString())
    }
    val focusManager = LocalFocusManager.current
    val noteFocus = remember { FocusRequester() }
    val intervalFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
        title = { Text(if (editing == null) "Запись обслуживания" else "Изменить обслуживание") },
        text = {
            DialogBody {
                FieldGroup(title = "Дата замены") {
                    DateField(value = dateMs, onChange = { dateMs = it })
                }
                FieldGroup(title = "Что менял") {
                    ChipGrid(
                        options = MaintenanceType.values().toList(),
                        selected = type,
                        label = { it.russianName },
                        onSelect = { type = it },
                    )
                }
                // Интервал до следующей замены не показываем — берём дефолтный для
                // выбранного типа (10 000 км для масла, 30 000 для свечей и т.д.).
                //
                // Заголовок «Пробег на момент обслуживания» — снаружи, жирным
                // (как у других полей через FieldGroup). Внутри поля — пример
                // «87000» как placeholder + суффикс «км» справа.
                FieldGroup(title = "Пробег на момент обслуживания") {
                    OutlinedTextField(
                        value = odoText,
                        onValueChange = { odoText = it.filter { ch -> ch.isDigit() }.take(7) },
                        placeholder = { PlaceholderText("например, 87000") },
                        suffix = { Text("км", color = ColorTextSecondary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { noteFocus.requestFocus() },
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FieldGroup(title = "Где делал / деталь / комментарий", optional = true) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(120) },
                        placeholder = { PlaceholderText("Например, где обслуживались и что меняли") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { intervalFocus.requestFocus() },
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(noteFocus),
                    )
                }
                // Интервал до следующей замены. Дефолт берётся из
                // MaintenanceType.defaultIntervalKm (масло 10 000, свечи 30 000
                // и т.д.); юзер может скорректировать (например масло хочет
                // менять каждые 7 000 — синтетика, тяжёлые условия).
                //
                // Подсказка снизу проясняет ЧТО это число делает: через столько
                // приложение начнёт напоминать «пора менять» — без этого
                // поле выглядело бы как непонятная цифра.
                FieldGroup(title = "Интервал до следующей замены") {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter { ch -> ch.isDigit() }.take(7) },
                        placeholder = { PlaceholderText(type.defaultIntervalKm.toString()) },
                        suffix = { Text("км", color = ColorTextSecondary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() },
                        ),
                        colors = mutedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(intervalFocus),
                    )
                }
                val intervalShown = intervalText.toIntOrNull() ?: type.defaultIntervalKm
                Text(
                    "Типовой интервал для «${type.russianName}» — ${"%,d".format(type.defaultIntervalKm).replace(',', ' ')} км. " +
                        "Приложение начнёт показывать «скоро замена» за 1 500 км до отметки " +
                        "${"%,d".format(intervalShown).replace(',', ' ')} км после текущей записи.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ColorTextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val odo = odoText.toIntOrNull() ?: return@TextButton
                val interval = intervalText.toIntOrNull()?.takeIf { it > 0 } ?: type.defaultIntervalKm
                onSave(dateMs, type, odo, interval, note)
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Удалить", color = ColorWarning)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/**
 * Сильно приглушённый placeholder. Дефолт OutlinedTextField слишком яркий —
 * текст подсказки выглядит как уже введённое значение и сбивает с толку.
 * Тут берём `ColorTextSecondary` с alpha 0.5 — чётко читается как подсказка.
 */
@Composable
private fun PlaceholderText(text: String) {
    Text(
        text,
        color = ColorTextSecondary.copy(alpha = 0.5f),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Группа поля: жирный заголовок сверху + опциональная подсказка серым + сам контент
 * (input/чипы) снизу. Лейблы у входных полей не используются — заголовок наружу,
 * placeholder остаётся внутри как пример заполнения. Так понятнее на узких диалогах.
 */
@Composable
private fun FieldGroup(
    title: String,
    hint: String? = null,
    optional: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = ColorNeutral,
                fontWeight = FontWeight.SemiBold,
            )
            if (optional) {
                Spacer(Modifier.size(6.dp))
                Text(
                    "необязательно",
                    style = MaterialTheme.typography.labelSmall,
                    color = ColorTextSecondary,
                )
            }
        }
        if (!hint.isNullOrBlank()) {
            Text(hint, style = MaterialTheme.typography.labelSmall, color = ColorTextSecondary)
        }
        content()
    }
}

/**
 * Поле даты: показываем текущее значение и при тапе открываем DatePickerDialog
 * штатного Android. Без своего лейбла — заголовок секции рендерит FieldGroup сверху.
 *
 * Реализация: OutlinedTextField сам поглощает pointer-события (фокус/курсор),
 * поэтому `.clickable` прямо на нём не срабатывает. Поверх кладём прозрачный
 * Box с matchParentSize().clickable — он перехватывает тапы первым и открывает
 * штатный DatePickerDialog. Из-за readOnly = true внутреннее поле не открывает
 * клавиатуру.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(value: Long, onChange: (Long) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val openPicker = {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = value }
        android.app.DatePickerDialog(
            context,
            { _, year, month, day ->
                val c = java.util.Calendar.getInstance()
                c.set(year, month, day, 12, 0, 0)
                c.set(java.util.Calendar.MILLISECOND, 0)
                onChange(c.timeInMillis)
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        ).show()
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = dateFormat.format(Date(value)),
            onValueChange = {},
            readOnly = true,
            colors = mutedTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        // Прозрачный overlay перехватывает клики — без него
        // OutlinedTextField сам обрабатывает pointer-события (постановка
        // фокуса/курсора) и .clickable выше уровня не вызывается.
        // indication = null убирает ripple, чтобы клик по дате выглядел
        // как тап по полю, а не как кнопка.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { openPicker() },
                ),
        )
    }
}

/**
 * Сетка чипов 2 колонки с равной шириной. FlowRow здесь не подходит — он пакует
 * жадно слева направо, и из-за длинных лейблов («Налог/штраф», «Тормозные колодки
 * (перед)») в строке остаются пустые зазоры справа. Фиксированная сетка убирает
 * эти дырки: каждая ячейка ровно 50% ширины, чипы выравнены сеткой.
 *
 * При нечётном числе элементов последняя ячейка занимает половину, вторая половина —
 * прозрачный Spacer (иначе одиночный чип растянулся бы на всю строку).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipGrid(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        options.chunked(2).forEach { row ->
            // weight(1f) на каждом чипе → одинаковая ширина, ровно по 2 на строку.
            // fillMaxHeight() для выравнивания высоты здесь нельзя — в AlertDialog
            // с unbounded height он коллапсит чипы (рендерятся только 2 на всю
            // ширину). Без fillMaxHeight чипы сами растут по контенту, Row
            // автоматически выравнивает их по высоте максимального.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { opt ->
                    FilterChip(
                        selected = opt == selected,
                        onClick = { onSelect(opt) },
                        label = {
                            Text(
                                label(opt),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                lineHeight = 14.sp,
                            )
                        },
                        // leadingIcon = null — иначе при selected=true вылезает галочка
                        // и визуально все чипы кажутся «дёрганными».
                        leadingIcon = null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ColorNeutral.copy(alpha = 0.25f),
                            selectedLabelColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Число без лишних нулей: 40 → «40», 35.9 → «35.9». */
private fun formatNumber(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')
