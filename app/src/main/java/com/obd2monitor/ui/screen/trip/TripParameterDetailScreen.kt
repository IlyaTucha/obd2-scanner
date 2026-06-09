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
import com.obd2monitor.ui.components.StatBox
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.obd2monitor.ui.theme.ColorWarning

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
            LineChart(
                values = state.series,
                color = ColorNeutral,
                resetSelectionKey = chartResetTick,
            )
        }
        StatBox(
            items = listOf(
                "Мин" to (state.min?.let { "%.1f".format(it) } ?: "—"),
                "Сред" to (state.avg?.let { "%.1f".format(it) } ?: "—"),
                "Макс" to (state.max?.let { "%.1f".format(it) } ?: "—"),
            ),
        )

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
                val withUnit = if (info.unit.isNotBlank()) "${info.normalRange} ${info.unit}" else info.normalRange
                Text(
                    "Норма: $withUnit",
                    style = MaterialTheme.typography.labelMedium,
                    color = ColorTextSecondary,
                )
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
        Spacer(Modifier.height(24.dp))
    }
}
