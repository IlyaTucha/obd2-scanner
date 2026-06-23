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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.LocalGasStation
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
import com.obd2monitor.ui.components.StatBox
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
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Icon(Icons.Filled.Flag, contentDescription = null, tint = ColorNeutral, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("ОТЧЁТ СЕССИИ", style = MaterialTheme.typography.headlineSmall)
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
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(period, color = ColorTextSecondary, style = MaterialTheme.typography.bodyMedium)
            Text(timeBreakdown, color = ColorTextSecondary, style = MaterialTheme.typography.bodySmall)

            // Маршрут
            if (state.routePoints.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
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
                    "GPS-точки не записаны (нет разрешения на геолокацию или GPS был выключен).",
                    color = ColorTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Сводка. В плитке «Время» — реальное время в пути (без пауз), чтобы
            // средняя скорость и время в пути были согласованы.
            StatBox(
                items = listOf(
                    "Путь" to "%.1f км".format(trip.distanceKm),
                    "В пути" to formatHms(trip.activeMs),
                    "Сред." to "%.0f км/ч".format(trip.avgSpeedKmh),
                ),
            )

            FuelCard(
                fuelLiters = trip.fuelUsedLiters,
                avgConsumption = trip.avgFuelConsumption,
                tripCostRub = state.tripCostRub,
            )

            // Расчётные показатели за сессию: пиковое ускорение, макс скорость.
            val extraStats = buildList {
                add("Макс." to "%.0f км/ч".format(trip.maxSpeedKmh))
                state.peakAccelMs2?.let {
                    add("Разгон" to "%+.1f м/с²".format(it))
                }
                state.peakBrakeMs2?.let {
                    add("Торм." to "%+.1f м/с²".format(it))
                }
            }
            if (extraStats.isNotEmpty()) {
                StatBox(items = extraStats)
            }

            // Параметры — мин/сред/макс + кривая по каждому
            if (state.parameters.isNotEmpty()) {
                Text(
                    "ПАРАМЕТРЫ ЗА СЕССИЮ",
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
                StatTriple("Мин", "%.1f".format(p.min))
                StatTriple("Сред", "%.1f".format(p.avg))
                StatTriple("Макс", "%.1f".format(p.max))
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
                LineChart(values = p.series, color = ColorNeutral, zeroCentered = p.key == "accel")
            }
        }
    }
}

@Composable
private fun StatTriple(label: String, value: String) {
    Column {
        Text(label, color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FuelCard(
    fuelLiters: Float,
    avgConsumption: Float,
    tripCostRub: Float?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.LocalGasStation,
                    contentDescription = null,
                    tint = ColorNeutral,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("ТОПЛИВО", color = ColorNeutral, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Потрачено", color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall)
                    Text("%.2f л".format(fuelLiters), style = MaterialTheme.typography.headlineSmall)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Средний", color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall)
                    Text("%.1f л/100км".format(avgConsumption), style = MaterialTheme.typography.headlineSmall)
                }
            }
            // Стоимость показывается только когда в профиле задана fuelPrice
            // (Настройки → Цена топлива).
            if (tripCostRub != null) {
                Spacer(Modifier.height(10.dp))
                Column {
                    Text("Стоимость", color = ColorTextSecondary, style = MaterialTheme.typography.labelSmall)
                    Text(
                        "%.0f ₽".format(tripCostRub),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }
    }
}

