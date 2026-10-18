package com.obd2monitor.ui.screen.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.obd2monitor.domain.model.GeoPoint
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2monitor.ui.components.LineChart
import com.obd2monitor.ui.components.MaxContentWidth
import com.obd2monitor.ui.components.mapHeight
import com.obd2monitor.ui.components.YandexMapView
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TripSummaryScreen(
    onBack: () -> Unit,
    onOpenParam: (String) -> Unit = {},
    viewModel: TripSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val trip = state.trip
    var confirmDelete by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var captureBounds by remember { mutableStateOf<Rect?>(null) }
    var screenshotMode by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { captureBounds = it.boundsInRoot() }
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Icon(Icons.Filled.Flag, contentDescription = null, tint = ColorNeutral, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Отчёт сессии", style = MaterialTheme.typography.headlineSmall)
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Загрузка…")
            }
            return
        }
        if (trip == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Сессия не найдена")
            }
            return
        }

        val df = SimpleDateFormat("dd MMMM yyyy · HH:mm", Locale.getDefault())
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val period = "${df.format(Date(trip.startTime))} — ${trip.endTime?.let { tf.format(Date(it)) } ?: "сейчас"}"
        // Подробная разбивка времени: «всего» = startTime → endTime, «на паузе» —
        // накоплено TripController'ом, «в пути» = всего − пауза. Без разбивки один
        // trip.durationMs (всего) вводил бы в заблуждение, если поездка долго стояла
        // на паузе из-за разрывов OBD.
        val timeBreakdown = "Всего ${formatHms(trip.durationMs)} · в пути ${formatHms(trip.activeMs)} · на паузе ${formatHms(trip.pausedMs)}"

        Column(
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(period, color = ColorTextSecondary, style = MaterialTheme.typography.bodyMedium)
            Text(timeBreakdown, color = ColorTextSecondary, style = MaterialTheme.typography.bodySmall)

            // Маршрут
            if (state.routePoints.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(mapHeight(260.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(ColorCard),
                ) {
                    if (screenshotMode) {
                        RoutePreview(points = state.routePoints, modifier = Modifier.fillMaxSize())
                    } else {
                        YandexMapView(points = state.routePoints, modifier = Modifier.fillMaxSize())
                    }
                }
            } else {
                Text(
                    "Маршрут не записан: не было доступа к геолокации или сигнала GPS.",
                    color = ColorTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Итоги одной карточкой: пробег, макс. скорость, топливо. Время — в строке
            // под датой, средняя скорость, остановки и ускорения — в карточках ниже.
            TotalsCard(
                distanceKm = trip.distanceKm,
                maxSpeedKmh = trip.maxSpeedKmh,
                fuelLiters = trip.fuelUsedLiters,
                avgConsumption = trip.avgFuelConsumption,
                tripCostRub = state.tripCostRub,
            )

            // Параметры — мин/сред/макс + кривая по каждому
            if (state.parameters.isNotEmpty()) {
                Text(
                    "Параметры за сессию",
                    style = MaterialTheme.typography.labelMedium,
                    color = ColorNeutral,
                )
                state.parameters.forEach { p ->
                    ParameterCard(p, onClick = { onOpenParam(p.key) })
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            exportMessage = if (withContext(Dispatchers.IO) { saveTripTextReport(context, state) } != null) {
                                "Текстовый отчёт сохранён в Downloads/OBD2"
                            } else {
                                "Не удалось сохранить текстовый отчёт"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Экспорт TXT") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            screenshotMode = true
                            // Yandex MapView рисуется через OpenGL-surface и в скриншот
                            // через view.draw(canvas) попадает чёрной (тайлы рендерятся
                            // на отдельном surface, не на canvas-окна). Поэтому на время
                            // скриншота переключаемся на RoutePreview — Canvas-полилинию,
                            // которая корректно сохраняется. Одного кадра
                            // (withFrameNanos { }) здесь мало: AndroidView не успевает
                            // убраться, и на скриншот попадает чёрный surface.
                            // 250 мс хватает чтобы:
                            //   1) Compose снёс AndroidView и положил RoutePreview;
                            //   2) Canvas нарисовал свой полилайн;
                            //   3) View дерево перерисовалось до начала захвата.
                            repeat(3) { withFrameNanos { } }
                            delay(250)
                            exportMessage = try {
                                if (saveTripScreenScreenshot(context, view, scrollState, state, captureBounds) != null) {
                                    "Скриншот отчёта сохранён в Downloads/OBD2"
                                } else {
                                    "Не удалось сохранить скриншот"
                                }
                            } finally {
                                screenshotMode = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Скриншот") }
            }
            exportMessage?.let {
                Text(it, color = ColorTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Удалить") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = com.obd2monitor.ui.theme.ColorDialogScrim,
            title = { Text("Удалить сессию?") },
            text = { Text("Запись поездки и все её показатели будут удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onDone = onBack)
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun RoutePreview(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(ColorCard)) {
        if (points.isEmpty()) return@Canvas
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val latSpan = (maxLat - minLat).takeIf { it > 0.000001 } ?: 0.000001
        val lonSpan = (maxLon - minLon).takeIf { it > 0.000001 } ?: 0.000001
        val padding = 28f
        val plotW = (size.width - padding * 2f).coerceAtLeast(1f)
        val plotH = (size.height - padding * 2f).coerceAtLeast(1f)

        // Сохраняем аспект: на 1° широты на экране уходит столько же пикселей,
        // сколько на 1° долготы, скорректированной на cos(средней широты)
        // (на наших широтах 1° долготы примерно cos(lat) × 111 км). Иначе
        // короткий и широкий маршрут «сплющивается» в горизонтальную полосу,
        // а длинный и узкий — в вертикальную: лат и лон масштабировались
        // независимо и вытягивали bbox под форму канваса. Теперь маршрут
        // выглядит как на бумажной карте — просто отдалён до нужного зума.
        val midLat = (minLat + maxLat) / 2.0
        val lonScale = kotlin.math.cos(Math.toRadians(midLat)).coerceAtLeast(0.1)
        val effLonSpan = lonSpan * lonScale
        val scale = kotlin.math.min(plotW / effLonSpan, plotH / latSpan).toFloat()
        val routeWPx = (effLonSpan * scale).toFloat()
        val routeHPx = (latSpan * scale).toFloat()
        val offsetX = padding + (plotW - routeWPx) / 2f
        val offsetY = padding + (plotH - routeHPx) / 2f

        fun x(lon: Double): Float = offsetX + ((lon - minLon) * lonScale * scale).toFloat()
        fun y(lat: Double): Float = size.height - offsetY - ((lat - minLat) * scale).toFloat()
        val path = Path()
        points.forEachIndexed { index, point ->
            val px = x(point.longitude)
            val py = y(point.latitude)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, ColorNeutral, style = Stroke(width = 6f))
        val start = points.first()
        val finish = points.last()
        drawCircle(com.obd2monitor.ui.theme.ColorSuccess, radius = 9f, center = androidx.compose.ui.geometry.Offset(x(start.longitude), y(start.latitude)))
        drawCircle(com.obd2monitor.ui.theme.ColorPrimary, radius = 9f, center = androidx.compose.ui.geometry.Offset(x(finish.longitude), y(finish.latitude)))
    }
}

@Composable
private fun ParameterCard(p: ParamSummary, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(p.unit, style = MaterialTheme.typography.labelSmall, color = ColorTextSecondary)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                val stats = p.stats
                if (stats != null) {
                    stats.forEach { (label, value) -> StatTriple(label, value) }
                } else {
                    StatTriple("Мин.", "%.1f".format(p.min))
                    StatTriple("Сред.", "%.1f".format(p.avg))
                    p.movingAvg?.let { StatTriple("Сред. на ходу", "%.1f".format(it)) }
                    StatTriple("Макс.", "%.1f".format(p.max))
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(4.dp),
            ) {
                if (p.key == "stops") {
                    LineChart(
                        values = p.series, color = ColorNeutral, pointTimes = p.times,
                        gridLines = 1, valueFormatter = ::stopLabel,
                    )
                } else {
                    LineChart(values = p.series, color = ColorNeutral, zeroCentered = p.key == "accel", pointTimes = p.times)
                }
            }
        }
    }
}

/** Подпись оси и подсказки графика остановок: ряд 0/1. */
internal fun stopLabel(v: Float): String = if (v >= 0.5f) "стоял" else "ехал"

@Composable
private fun StatTriple(label: String, value: String) {
    Column {
        Text(label, color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Итоги поездки сеткой в две колонки: путь и макс. скорость, затем топливо.
 * У сессии, записанной только по GPS, данных о топливе нет — строки топлива скрыты.
 * Стоимость — только когда в профиле задана цена топлива (Настройки → Цена топлива).
 */
@Composable
private fun TotalsCard(
    distanceKm: Float,
    maxSpeedKmh: Float,
    fuelLiters: Float,
    avgConsumption: Float,
    tripCostRub: Float?,
) {
    val rows = buildList {
        add(("Путь" to "%.1f км".format(distanceKm)) to ("Макс. скорость" to "%.0f км/ч".format(maxSpeedKmh)))
        if (fuelLiters > 0f) {
            add(("Топливо" to "%.2f л".format(fuelLiters)) to ("Средний расход" to "%.1f л/100 км".format(avgConsumption)))
            if (tripCostRub != null) add(("Стоимость" to "%.0f ₽".format(tripCostRub)) to null)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { (left, right) ->
            Row {
                TotalsCell(left, Modifier.weight(1f))
                if (right != null) TotalsCell(right, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TotalsCell(item: Pair<String, String>, modifier: Modifier) {
    Column(modifier) {
        Text(item.first, color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(item.second, style = MaterialTheme.typography.headlineSmall)
    }
}

