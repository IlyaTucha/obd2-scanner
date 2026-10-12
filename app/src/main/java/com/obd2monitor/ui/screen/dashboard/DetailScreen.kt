package com.obd2monitor.ui.screen.dashboard

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.obd2monitor.domain.model.MetricCatalog
import com.obd2monitor.ui.components.LineChart
import com.obd2monitor.ui.components.ScreenColumn
import com.obd2monitor.ui.components.StatBox
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.obd2monitor.ui.theme.ColorWarning

@Composable
fun ParameterDetailScreen(
    metricKey: String,
    onBack: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Полная история за сессию: из БД, обновляется раз в несколько секунд, пока
    // экран открыт (раньше пересчитывалась целиком на каждом новом снимке OBD).
    val series by produceState(initialValue = state.histories[metricKey]?.values.orEmpty(), metricKey) {
        while (true) {
            value = viewModel.sessionSeries(metricKey)
            delay(5_000)
        }
    }
    val info = MetricCatalog.forKey(metricKey, state.activeProfile)
    val current = currentValue(metricKey, state)
    val min = series.minOrNull()
    val max = series.maxOrNull()
    // Для расхода (л/100км) арифметическое среднее по мгновенным значениям
    // даёт ерунду: при низких скоростях мгновенный расход взлетает до сотен
    // л/100км («стоял на месте — потратил столько-то на 100 км»), и обычное
    // среднее не отражает реальный расход. На дашборде в hero показывается
    // правильное значение — литры за сессию / дистанцию × 100. Здесь тоже
    // отдаём его (совпадает с Trip.avgFuelConsumption в отчёте сессии).
    val avg = if (metricKey == "fuel") state.tripAvgConsumption
        else series.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    // Сброс выделения точки на LineChart при тапе ВНЕ chart-области.
    // Инкрементируется во внешнем pointerInput → проксируется в LineChart как
    // resetSelectionKey.
    var chartResetTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    ScreenColumn(
        modifier = Modifier.pointerInput(Unit) { detectTapGestures { chartResetTick++ } },
        bottomPadding = 24.dp,
        spacing = 14.dp,
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
            LineChart(
                values = series,
                color = ColorNeutral,
                zeroCentered = metricKey == "accel",
                resetSelectionKey = chartResetTick,
            )
        }
        StatBox(
            items = listOf(
                "Сейч." to (current?.let { "%.1f".format(it) } ?: "—"),
                "Мин" to (min?.let { "%.1f".format(it) } ?: "—"),
                "Макс" to (max?.let { "%.1f".format(it) } ?: "—"),
                "Сред" to (avg?.let { "%.1f".format(it) } ?: "—"),
            ),
        )

        // Раскрывающаяся секция «Что это?»: краткая всегда видна, детали — по тапу.
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
            // Краткая — всегда
            Text(info.brief, style = MaterialTheme.typography.bodyMedium)
            if (info.normalRange.isNotBlank()) {
                val withUnit = if (info.unit.isNotBlank()) "${info.normalRange} ${info.unit}" else info.normalRange
                Text(
                    "Норма: $withUnit",
                    style = MaterialTheme.typography.labelMedium,
                    color = ColorTextSecondary,
                )
            }
            // Подробная — раскрывается
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

        Spacer(Modifier.height(24.dp))
    }
}

private fun currentValue(key: String, state: DashboardUiState): Float? = when (key) {
    "speed" -> state.data.speedKmh
    "rpm" -> state.data.rpm?.toFloat()
    "coolant" -> state.data.coolantTempC?.toFloat()
    "intake" -> state.data.intakeTempC?.toFloat()
    "battery" -> state.data.batteryVoltage
    "throttle" -> state.data.throttlePercent
    "load" -> state.data.engineLoadPercent
    "fuel" -> com.obd2monitor.data.obd.FuelCalculator.displayConsumption(state.data.instantFuelLPer100)
    "fuelh" -> state.data.instantFuelLPerH
    "map" -> state.data.mapKpa?.toFloat()
    "timing" -> state.data.ignitionTimingDeg
    "oil" -> state.data.oilTempC?.toFloat()
    "stft" -> state.data.shortFuelTrim1Pct
    "ltft" -> state.data.longFuelTrim1Pct
    "fuellevel" -> state.data.fuelLevelPercent
    "distmil" -> state.data.distanceMilKm?.toFloat()
    "distclear" -> state.data.distanceSinceClearKm?.toFloat()
    "ambient" -> state.data.ambientTempC?.toFloat()
    "maf" -> state.data.mafGs
    "power" -> state.data.powerKw?.let { it * com.obd2monitor.data.obd.ReadingMetrics.KW_TO_HP }
    "torque" -> state.data.torqueNm
    "vacuum" -> state.data.vacuumKpa
    "warmup" -> state.data.warmupPct
    "range" -> state.rangeKm
    "tripcost" -> state.tripCostRub
    "cost100" -> state.costPer100Rub
    "accel" -> state.accelMs2
    "cutoff" -> if (state.data.fuelCutoffActive) 1f else 0f
    else -> null
}
