package com.obd2monitor.ui.screen.trip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2monitor.domain.model.MetricCatalog
import com.obd2monitor.ui.components.LineChart
import com.obd2monitor.ui.components.NormLine
import com.obd2monitor.ui.components.YandexMapView
import com.obd2monitor.ui.components.mapHeight
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.Color
import com.obd2monitor.ui.components.StatBox
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.obd2monitor.ui.theme.ColorWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Список остановок поездки: «19:07 — 19:09 · 2 мин 10 с». */
@Composable
private fun StopList(stops: List<com.obd2monitor.data.obd.TripMath.Stop>, onClick: (Long) -> Unit) {
    val tf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        stops.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().clickable { onClick(s.startMs) }.padding(vertical = 2.dp)) {
                Text("${i + 1}.", color = ColorTextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(32.dp))
                Text(
                    "${tf.format(Date(s.startMs))} — ${tf.format(Date(s.endMs))}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(formatShortDuration(s.durationMs), style = MaterialTheme.typography.bodyMedium, color = ColorTextSecondary)
            }
        }
    }
}

/** Детальный экран параметра ИЗ ИСТОРИИ ПОЕЗДКИ — данные из БД (полная серия), а не live. */
@Composable
fun TripParameterDetailScreen(
    onBack: () -> Unit,
    viewModel: TripParameterDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val key = viewModel.metricKey
    val info = MetricCatalog.forKey(key, state.profile)

    // Сброс выделения точки на графике по тапу вне chart-области.
    var chartResetTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Момент, который показываем на карте: выбранная точка графика или остановка.
    var mapMoment by remember { mutableStateOf<MapMoment?>(null) }
    val onPoint: (Int?) -> Unit = { idx ->
        mapMoment = idx?.let { i ->
            val t = state.times.getOrNull(i) ?: return@let null
            val v = state.series.getOrNull(i)
            val value = when {
                v == null -> null
                key == "stops" -> stopLabel(v)
                else -> "%.1f %s".format(v, info.unit).trim()
            }
            MapMoment(t, value)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .pointerInput(Unit) {
                detectTapGestures { chartResetTick++ }
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text(info.title, style = MaterialTheme.typography.headlineSmall)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ColorCard)
                .padding(8.dp),
        ) {
            if (key == "stops") {
                LineChart(
                    values = state.series,
                    color = ColorNeutral,
                    resetSelectionKey = chartResetTick,
                    pointTimes = state.times,
                    gridLines = 1,
                    valueFormatter = ::stopLabel,
                    onPointSelected = onPoint,
                )
            } else {
                LineChart(
                    values = state.series,
                    color = ColorNeutral,
                    resetSelectionKey = chartResetTick,
                    pointTimes = state.times,
                    onPointSelected = onPoint,
                )
            }
        }
        val customStats = state.stats
        StatBox(
            items = customStats ?: listOfNotNull(
                "Мин." to (state.min?.let { "%.1f".format(it) } ?: "—"),
                "Сред." to (state.avg?.let { "%.1f".format(it) } ?: "—"),
                state.movingAvg?.let { "Сред. на ходу" to "%.1f".format(it) },
                "Макс." to (state.max?.let { "%.1f".format(it) } ?: "—"),
            ),
        )
        if (state.stops.isNotEmpty()) {
            StopList(state.stops) { start ->
                val s = state.stops.first { it.startMs == start }
                mapMoment = MapMoment(start, "стоял ${formatShortDuration(s.durationMs)}")
            }
        }
        if (state.movingAvg != null) {
            Text(MOVING_AVG_HINT, color = ColorTextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        // Та же раскрывающаяся секция «Что это?», что и на дашборде.
        var expanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ColorCard)
                .clickable { expanded = !expanded }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = ColorNeutral,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Что это?",
                    style = MaterialTheme.typography.titleSmall,
                    color = ColorNeutral,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = ColorTextSecondary,
                )
            }
            Text(info.brief, style = MaterialTheme.typography.bodyMedium)
            if (info.normalRange.isNotBlank()) {
                NormLine(MetricCatalog.normLabel(state.profile), info.normalRange)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(4.dp))
                    Text(info.detailed, style = MaterialTheme.typography.bodyMedium)
                    info.warning?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = ColorWarning,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = ColorWarning,
                            )
                        }
                    }
                }
            }
        }
        // Место под панель карты, чтобы она не закрывала низ страницы.
        Spacer(Modifier.height(if (mapMoment != null) mapHeight(280.dp) + 24.dp else 24.dp))
    }

    mapMoment?.let { moment ->
        MomentMapPanel(
            moment = moment,
            route = state.route,
            location = state.locationAt(moment.timeMs),
            onClose = { mapMoment = null; chartResetTick++ },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }
}

/** Момент поездки для карты: время и подпись значения. */
private data class MapMoment(val timeMs: Long, val value: String?)

/**
 * Панель снизу: маршрут поездки и красная точка там, где машина была в выбранный
 * момент. Фон полупрозрачный; сама карта — нет: MapKit рисует её на отдельной
 * GL-поверхности, прозрачность к ней не применяется. График над панелью остаётся
 * доступным: тап по другой точке переносит маркер.
 */
@Composable
private fun MomentMapPanel(
    moment: MapMoment,
    route: List<com.obd2monitor.domain.model.GeoPoint>,
    location: com.obd2monitor.domain.model.GeoPoint?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard.copy(alpha = 0.88f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(tf.format(Date(moment.timeMs)), moment.value).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть карту", tint = ColorTextSecondary)
            }
        }
        if (location == null) {
            Text(
                if (route.isEmpty()) "Маршрут этой поездки не записан." else "В этот момент не было сигнала GPS.",
                color = ColorTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(mapHeight(240.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                YandexMapView(points = route, highlight = location, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
