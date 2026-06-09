package com.obd2monitor.ui.screen.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.OilBarrel
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2monitor.ui.components.ConnectionBar
import com.obd2monitor.ui.components.ConnectionDiagnostics
import com.obd2monitor.ui.components.DataCard
import com.obd2monitor.ui.components.GaugeWidget
import com.obd2monitor.ui.theme.ColorAccent
import com.obd2monitor.ui.theme.ColorError
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorPrimary
import com.obd2monitor.ui.theme.ColorSuccess
import com.obd2monitor.ui.theme.ColorWarning

@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenDetail: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val data = state.data

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionBar(
            status = state.connection,
            activeProfileName = state.activeProfile?.name,
            onSettingsClick = onOpenSettings,
            onConnectClick = onOpenSettings,
            onReconnectClick = { viewModel.reconnect() },
        )
        ConnectionDiagnostics(status = state.connection)

        // Hero: ОБОРОТЫ + СКОРОСТЬ + СРЕДНИЙ РАСХОД — одинаковая ширина (weight=1) →
        // одинаковая высота через aspectRatio внутри каждого GaugeWidget.
        // Третий hero — СРЕДНИЙ расход за активную сессию (не мгновенный): литры за сессию /
        // дистанцию × 100. Это та же формула что Trip.avgFuelConsumption в отчёте сессии —
        // т.е. водитель видит на дашборде ровно ту цифру, которая попадёт в «Средний» отчёта.
        // Шкала 0..20 л/100км — типичный диапазон средней по поездке (8-15 город).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Top,
        ) {
            GaugeWidget(
                value = data.rpm?.toFloat() ?: 0f,
                minValue = 0f,
                maxValue = (state.activeProfile?.normalRpm?.last ?: 7000).toFloat(),
                label = "ОБОРОТЫ",
                unit = "об/мин",
                modifier = Modifier.weight(1f),
                accentColor = ColorWarning,
                icon = Icons.Outlined.RotateRight,
                formatter = { "%.0f".format(it) },
                onClick = { onOpenDetail("rpm") },
            )
            GaugeWidget(
                value = data.speedKmh ?: 0f,
                minValue = 0f,
                maxValue = 200f,
                label = "СКОРОСТЬ",
                unit = "км/ч",
                modifier = Modifier.weight(1f),
                accentColor = ColorAccent,
                icon = Icons.Filled.DirectionsCar,
                onClick = { onOpenDetail("speed") },
            )
            GaugeWidget(
                value = state.tripAvgConsumption ?: 0f,
                minValue = 0f,
                maxValue = 20f,
                label = "СРЕДНИЙ",
                unit = "л/100км",
                modifier = Modifier.weight(1f),
                accentColor = ColorPrimary,
                icon = Icons.Outlined.LocalGasStation,
                formatter = { "%.1f".format(it) },
                onClick = { onOpenDetail("fuel") },
            )
        }

        // Сетка карточек 2 колонки. IntrinsicSize.Min — карточки в строке выровнены по высоте.
        val cards = buildCardData(state)
        cards.chunked(2).forEach { rowCards ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowCards.forEach { card ->
                    DataCard(
                        icon = card.icon,
                        title = card.title,
                        value = card.value,
                        unit = card.unit,
                        history = state.histories[card.key]?.values.orEmpty(),
                        valueColor = card.valueColor,
                        accent = ColorNeutral,
                        onClick = { onOpenDetail(card.key) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowCards.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        // отступ под плавающим нав-баром
        Spacer(Modifier.height(96.dp))
    }
}

private data class CardSpec(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val value: String,
    val unit: String,
    val valueColor: Color,
)

private fun buildCardData(state: DashboardUiState): List<CardSpec> {
    val data = state.data
    val profile = state.activeProfile
    val coolantColor = profile?.normalCoolantTempC?.let { range ->
        data.coolantTempC?.let { c ->
            when {
                c in range -> ColorSuccess
                c < range.first -> ColorNeutral
                else -> ColorError
            }
        }
    } ?: Color.White
    val batteryColor = profile?.normalBatteryV?.let { range ->
        data.batteryVoltage?.let { v ->
            when {
                v in range -> ColorSuccess
                v < range.start -> ColorWarning
                else -> ColorError
            }
        }
    } ?: Color.White
    val list = mutableListOf<CardSpec>()

    // ── TOP: критичные сырые PID — то, на что водитель смотрит постоянно ─────
    list += listOf(
        CardSpec("coolant", Icons.Outlined.Thermostat, "Темп. ОЖ",
            data.coolantTempC?.let { "$it" } ?: "—", "°C", coolantColor),
        CardSpec("battery", Icons.Outlined.BatteryFull, "Бортсеть",
            data.batteryVoltage?.let { "%.2f".format(it) } ?: "—", "В", batteryColor),
        CardSpec("load", Icons.Outlined.Memory, "Усилие двигателя",
            data.engineLoadPercent?.let { "%.0f".format(it) } ?: "—", "%", Color.White),
        CardSpec("throttle", Icons.Outlined.Tune, "Дроссель",
            data.throttlePercent?.let { "%.1f".format(it) } ?: "—", "%", Color.White),
    )

    if (data.warmupPct != null || state.histories["warmup"]?.values?.isNotEmpty() == true) {
        // Карточка прогрева актуальна только пока двигатель ХОЛОДНЫЙ.
        list += CardSpec("warmup", Icons.Outlined.Whatshot, "Прогрев",
            data.warmupPct?.let { "%.0f".format(it) } ?: "—", "%", if ((data.warmupPct ?: 0f) >= 99f) ColorSuccess else ColorWarning)
    }

    // ── Расчётные метрики ────────────────────────────────────────────────────
    if (data.powerKw != null || state.histories["power"]?.values?.isNotEmpty() == true) {
        val hp = data.powerKw?.let { it * 1.35962f }
        list += CardSpec("power", Icons.Outlined.Bolt, "Мощность",
            hp?.let { "%.0f".format(it) } ?: "—", "л.с. · ${data.powerKw?.let { "%.0f".format(it) } ?: "—"} кВт", ColorAccent)
    }
    if (data.torqueNm != null || state.histories["torque"]?.values?.isNotEmpty() == true) {
        list += CardSpec("torque", Icons.Outlined.Settings, "Крутящий момент",
            data.torqueNm?.let { "%.0f".format(it) } ?: "—", "Нм", Color.White)
    }
    if (state.rangeKm != null || state.histories["range"]?.values?.isNotEmpty() == true) {
        list += CardSpec("range", Icons.Outlined.Speed, "Запас хода",
            state.rangeKm?.let { "%.0f".format(it) } ?: "—", "км", Color.White)
    }
    if (state.tripCostRub != null || state.histories["tripcost"]?.values?.isNotEmpty() == true) {
        list += CardSpec("tripcost", Icons.Outlined.Payments, "Сессия · стоимость",
            state.tripCostRub?.let { "%.0f".format(it) } ?: "—", "₽", Color.White)
    }
    if (state.costPer100Rub != null || state.histories["cost100"]?.values?.isNotEmpty() == true) {
        list += CardSpec("cost100", Icons.Outlined.Payments, "Цена / 100 км",
            state.costPer100Rub?.let { "%.0f".format(it) } ?: "—", "₽", Color.White)
    }
    if (kotlin.math.abs(state.accelMs2) > 0.05f || state.histories["accel"]?.values?.any { kotlin.math.abs(it) > 0.05f } == true) {
        val g = state.accelMs2 / 9.81f
        val color = if (state.accelMs2 >= 0f) ColorAccent else ColorWarning
        list += CardSpec("accel", Icons.Outlined.Speed, "Ускорение",
            "%+.1f".format(state.accelMs2), "м/с² · %+.2fg".format(g), color)
    }
    if (data.vacuumKpa != null || state.histories["vacuum"]?.values?.isNotEmpty() == true) {
        list += CardSpec("vacuum", Icons.Outlined.Compress, "Вакуум впуска",
            data.vacuumKpa?.let { "%.0f".format(it) } ?: "—", "кПа", Color.White)
    }

    // ── Остальные сырые PID ──────────────────────────────────────────────────
    // Карточка «Расход в час» — мгновенный расход в л/ч (имеет смысл всегда,
    // включая стоянку — это холостой расход двигателя). Старая карточка «Расход»
    // (мгновенный л/100км) удалена: hero «СРЕДНИЙ» уже показывает расход в л/100км
    // (среднее за сессию) и ведёт на тот же детальный экран key="fuel", поэтому
    // две карточки на одну страницу — лишний дубль.
    val fuelLPerH = data.instantFuelLPerH?.let { "%.1f".format(it) } ?: "—"
    list += listOf(
        CardSpec("fuelh", Icons.Outlined.LocalGasStation, "Расход в час", fuelLPerH, "л/ч", Color.White),
        CardSpec("map", Icons.Outlined.Compress, "Давление впуска",
            data.mapKpa?.toString() ?: "—", "кПа", Color.White),
        CardSpec("intake", Icons.Outlined.Air, "Темп. впуска",
            data.intakeTempC?.let { "$it" } ?: "—", "°C", Color.White),
        CardSpec("timing", Icons.Outlined.Timer, "Угол зажигания",
            data.ignitionTimingDeg?.let { "%.1f".format(it) } ?: "—", "°", Color.White),
    )
    if (data.fuelLevelPercent != null || state.histories["fuellevel"]?.values?.isNotEmpty() == true) {
        list += CardSpec("fuellevel", Icons.Outlined.Opacity, "Уровень бака",
            data.fuelLevelPercent?.let { "%.0f".format(it) } ?: "—", "%", Color.White)
    }
    if (data.oilTempC != null || state.histories["oil"]?.values?.isNotEmpty() == true) {
        list += CardSpec("oil", Icons.Outlined.OilBarrel, "Темп. масла",
            data.oilTempC?.let { "$it" } ?: "—", "°C", Color.White)
    }
    if (data.shortFuelTrim1Pct != null || state.histories["stft"]?.values?.isNotEmpty() == true) {
        list += CardSpec("stft", Icons.Outlined.WaterDrop, "Краткая коррекция",
            data.shortFuelTrim1Pct?.let { "%+.1f".format(it) } ?: "—", "%", Color.White)
    }
    if (data.longFuelTrim1Pct != null || state.histories["ltft"]?.values?.isNotEmpty() == true) {
        list += CardSpec("ltft", Icons.Outlined.WaterDrop, "Долгая коррекция",
            data.longFuelTrim1Pct?.let { "%+.1f".format(it) } ?: "—", "%", Color.White)
    }
    if ((data.distanceMilKm != null && data.distanceMilKm > 0) || state.histories["distmil"]?.values?.any { it > 0f } == true) {
        list += CardSpec("distmil", Icons.Outlined.Route, "Пробег с Check Engine",
            data.distanceMilKm?.let { "$it" } ?: "—", "км", ColorWarning)
    }
    if (data.distanceSinceClearKm != null || state.histories["distclear"]?.values?.isNotEmpty() == true) {
        list += CardSpec("distclear", Icons.Outlined.Route, "Пробег после сброса",
            data.distanceSinceClearKm?.let { "$it" } ?: "—", "км", Color.White)
    }
    if (data.ambientTempC != null || state.histories["ambient"]?.values?.isNotEmpty() == true) {
        list += CardSpec("ambient", Icons.Outlined.AcUnit, "Темп. за бортом",
            data.ambientTempC?.let { "$it" } ?: "—", "°C", Color.White)
    }
    if (data.mafGs != null || state.histories["maf"]?.values?.isNotEmpty() == true) {
        list += CardSpec("maf", Icons.Outlined.Air, "MAF",
            data.mafGs?.let { "%.1f".format(it) } ?: "—", "г/с", Color.White)
    }

    // ── В самом конце: индикатор cut-off. Появляется только когда форсунки реально
    // отключены (load<8 + rpm>idle+300 + speed>15 + throttle<5). Открывает детальный
    // график engine load — именно по нему детектируется накат: load% падает к нулю.
    if (data.fuelCutoffActive) {
        list += CardSpec("load", Icons.Outlined.Eco, "Накат · форсунки off",
            "ON", "load ${data.engineLoadPercent?.let { "%.0f".format(it) } ?: "?"}%", ColorSuccess)
    }
    return list
}
