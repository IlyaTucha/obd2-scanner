package com.obd2monitor.ui.screen.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.OilBarrel
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2monitor.data.obd.ReadingMetrics
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.ui.components.AdaptiveGrid
import com.obd2monitor.ui.components.ConnectionBar
import com.obd2monitor.ui.components.ConnectionDiagnostics
import com.obd2monitor.ui.components.DataCard
import com.obd2monitor.ui.components.GaugeWidget
import com.obd2monitor.ui.components.ScreenColumn
import com.obd2monitor.ui.components.SectionHeader
import com.obd2monitor.ui.theme.ColorAccent
import com.obd2monitor.ui.theme.ColorError
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorPrimary
import com.obd2monitor.ui.theme.ColorSuccess
import com.obd2monitor.ui.theme.ColorTextPrimary
import com.obd2monitor.ui.theme.ColorWarning

@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenDetail: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val data = state.data

    ScreenColumn {
        ConnectionBar(
            status = state.connection,
            activeProfileName = state.activeProfile?.name,
            onSettingsClick = onOpenSettings,
            onReconnectClick = { viewModel.reconnect() },
        )
        ConnectionDiagnostics(status = state.connection)

        // Три прибора одинаковой ширины. Третий — СРЕДНИЙ расход за активную сессию
        // (литры / пробег × 100) — ровно та цифра, что попадёт в отчёт сессии.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            GaugeWidget(
                value = data.rpm?.toFloat() ?: 0f,
                minValue = 0f,
                maxValue = (state.activeProfile?.normalRpm?.last ?: 7000).toFloat(),
                label = "Обороты",
                unit = "об/мин",
                modifier = Modifier.weight(1f),
                accentColor = ColorWarning,
                icon = Icons.AutoMirrored.Outlined.RotateRight,
                formatter = { "%.0f".format(it) },
                onClick = { onOpenDetail("rpm") },
            )
            GaugeWidget(
                value = data.speedKmh ?: 0f,
                minValue = 0f,
                maxValue = 200f,
                label = "Скорость",
                unit = "км/ч",
                modifier = Modifier.weight(1f),
                accentColor = ColorAccent,
                icon = Icons.Outlined.DirectionsCar,
                onClick = { onOpenDetail("speed") },
            )
            GaugeWidget(
                value = state.tripAvgConsumption ?: 0f,
                minValue = 0f,
                maxValue = 20f,
                label = "Ср. расход",
                unit = "л/100 км",
                modifier = Modifier.weight(1f),
                accentColor = ColorPrimary,
                icon = Icons.Outlined.LocalGasStation,
                formatter = { "%.1f".format(it) },
                onClick = { onOpenDetail("fuel") },
            )
        }

        val connected = state.connection.state == ConnectionState.CONNECTED
        buildCardGroups(state).forEach { (title, cards) ->
            if (cards.isEmpty()) return@forEach
            SectionHeader(title)
            AdaptiveGrid(cards) { card, modifier ->
                DataCard(
                    icon = card.icon,
                    title = card.title,
                    value = card.value,
                    unit = card.unit,
                    history = state.histories[card.historyKey]?.values.orEmpty(),
                    valueColor = if (connected) card.valueColor else ColorTextPrimary.copy(alpha = 0.6f),
                    onClick = { onOpenDetail(card.key) },
                    modifier = modifier,
                )
            }
        }
    }
}

private data class CardSpec(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val value: String,
    val unit: String,
    val valueColor: Color = ColorTextPrimary,
    val historyKey: String = key,
)

/** Карточки «Главной», разложенные по группам. Карточка расчётного или
 *  необязательного параметра появляется, только когда у неё есть значение. */
private fun buildCardGroups(state: DashboardUiState): List<Pair<String, List<CardSpec>>> {
    val data = state.data
    val profile = state.activeProfile
    fun has(key: String, v: Any?) = v != null || state.histories[key]?.values?.isNotEmpty() == true
    fun f0(v: Float?) = v?.let { "%.0f".format(it) } ?: "—"
    fun f1(v: Float?) = v?.let { "%.1f".format(it) } ?: "—"

    val coolantColor = profile?.normalCoolantTempC?.let { range ->
        data.coolantTempC?.let { c ->
            when {
                c in range -> ColorSuccess
                c < range.first -> ColorNeutral
                else -> ColorError
            }
        }
    } ?: ColorTextPrimary
    val batteryColor = profile?.normalBatteryV?.let { range ->
        data.batteryVoltage?.let { v ->
            when {
                v in range -> ColorSuccess
                v < range.start -> ColorWarning
                else -> ColorError
            }
        }
    } ?: ColorTextPrimary

    val engine = buildList {
        add(CardSpec("coolant", Icons.Outlined.Thermostat, "Охлаждающая жидкость",
            data.coolantTempC?.toString() ?: "—", "°C", coolantColor))
        add(CardSpec("battery", Icons.Outlined.BatteryFull, "Бортсеть", data.batteryVoltage?.let { "%.2f".format(it) } ?: "—", "В", batteryColor))
        add(CardSpec("load", Icons.Outlined.Memory, "Нагрузка", f0(data.engineLoadPercent), "%"))
        add(CardSpec("throttle", Icons.Outlined.Tune, "Дроссель", f1(data.throttlePercent), "%"))
        // Прогрев актуален, пока двигатель холодный.
        if (has("warmup", data.warmupPct)) add(CardSpec("warmup", Icons.Outlined.Whatshot, "Прогрев", f0(data.warmupPct), "%",
            if ((data.warmupPct ?: 0f) >= 99f) ColorSuccess else ColorWarning))
        if (has("oil", data.oilTempC)) add(CardSpec("oil", Icons.Outlined.OilBarrel, "Масло", data.oilTempC?.toString() ?: "—", "°C"))
        add(CardSpec("intake", Icons.Outlined.Air, "Воздух на впуске", data.intakeTempC?.toString() ?: "—", "°C"))
        add(CardSpec("timing", Icons.Outlined.Timer, "Угол зажигания", f1(data.ignitionTimingDeg), "°"))
        if (has("ambient", data.ambientTempC)) add(CardSpec("ambient", Icons.Outlined.AcUnit, "За бортом", data.ambientTempC?.toString() ?: "—", "°C"))
    }

    val fuel = buildList {
        add(CardSpec("fuelh", Icons.Outlined.LocalGasStation, "Расход сейчас", f1(data.instantFuelLPerH), "л/ч"))
        if (has("fuellevel", data.fuelLevelPercent)) add(CardSpec("fuellevel", Icons.Outlined.Opacity, "Топливо в баке", f0(data.fuelLevelPercent), "%"))
        if (has("range", state.rangeKm)) add(CardSpec("range", Icons.Outlined.Route, "Запас хода", f0(state.rangeKm), "км"))
        if (has("tripcost", state.tripCostRub)) add(CardSpec("tripcost", Icons.Outlined.Payments, "Стоимость сессии", f0(state.tripCostRub), "₽"))
        if (has("cost100", state.costPer100Rub)) add(CardSpec("cost100", Icons.Outlined.Payments, "Цена 100 км", f0(state.costPer100Rub), "₽"))
        if (has("stft", data.shortFuelTrim1Pct)) add(CardSpec("stft", Icons.Outlined.WaterDrop, "Краткая коррекция",
            data.shortFuelTrim1Pct?.let { "%+.1f".format(it) } ?: "—", "%"))
        if (has("ltft", data.longFuelTrim1Pct)) add(CardSpec("ltft", Icons.Outlined.WaterDrop, "Долгая коррекция",
            data.longFuelTrim1Pct?.let { "%+.1f".format(it) } ?: "—", "%"))
    }

    val dynamics = buildList {
        if (has("power", data.powerKw)) add(CardSpec("power", Icons.Outlined.Bolt, "Мощность",
            f0(data.powerKw?.let { it * ReadingMetrics.KW_TO_HP }), "л.с."))
        if (has("torque", data.torqueNm)) add(CardSpec("torque", Icons.Outlined.Settings, "Крутящий момент", f0(data.torqueNm), "Н·м"))
        if (kotlin.math.abs(state.accelMs2) > 0.05f || state.histories["accel"]?.values?.any { kotlin.math.abs(it) > 0.05f } == true) {
            add(CardSpec("accel", Icons.Outlined.Speed, "Ускорение", "%+.1f".format(state.accelMs2), "м/с²",
                if (state.accelMs2 >= 0f) ColorAccent else ColorWarning))
        }
        add(CardSpec("map", Icons.Outlined.Compress, "Давление впуска", data.mapKpa?.toString() ?: "—", "кПа"))
        if (has("vacuum", data.vacuumKpa)) add(CardSpec("vacuum", Icons.Outlined.Compress, "Разрежение", f0(data.vacuumKpa), "кПа"))
        if (has("maf", data.mafGs)) add(CardSpec("maf", Icons.Outlined.Air, "Расход воздуха", f1(data.mafGs), "г/с"))
        // Накат без подачи топлива: форсунки отключены (нагрузка < 8 %, обороты выше
        // холостых, скорость > 15 км/ч, дроссель закрыт). Открывает график нагрузки.
        if (data.fuelCutoffActive) add(CardSpec("load", Icons.Outlined.Eco, "Накат без топлива", "Да",
            "", ColorSuccess, historyKey = "cutoff"))
    }

    val diagnostics = buildList {
        if ((data.distanceMilKm ?: 0) > 0 || state.histories["distmil"]?.values?.any { it > 0f } == true) {
            add(CardSpec("distmil", Icons.Outlined.Route, "Пробег с Check Engine", data.distanceMilKm?.toString() ?: "—", "км", ColorWarning))
        }
        if (has("distclear", data.distanceSinceClearKm)) add(CardSpec("distclear", Icons.Outlined.Route, "Пробег после сброса ошибок",
            data.distanceSinceClearKm?.toString() ?: "—", "км"))
    }

    return listOf(
        "Двигатель" to engine,
        "Топливо" to fuel,
        "Динамика" to dynamics,
        "Пробег" to diagnostics,
    )
}
