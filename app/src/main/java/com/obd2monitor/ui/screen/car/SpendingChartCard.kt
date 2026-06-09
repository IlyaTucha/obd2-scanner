package com.obd2monitor.ui.screen.car

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.obd2monitor.domain.model.ExpenseEntry
import com.obd2monitor.ui.components.LineChart
import com.obd2monitor.ui.theme.ColorAccent
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorTextSecondary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Период агрегации для графика трат. Каждый — фиксированное окно бакетов:
 *   - DAY: последние 30 дней (bar per day)
 *   - WEEK: последние 12 недель
 *   - MONTH: последние 12 месяцев
 *   - YEAR: с самого первого расхода до сегодня
 *
 * Окно фиксировано чтобы график был ВСЕГДА читаемым: 30 точек по дням, 12 по
 * неделям и месяцам — это лимит, в котором подписи на X-оси не наезжают друг
 * на друга. Юзер хочет «сколько потрачено за день/неделю/месяц/год» — это
 * именно один график с переключателем периода, без отдельных экранов.
 */
internal enum class SpendingPeriod(val label: String) {
    DAY("День"),
    WEEK("Неделя"),
    MONTH("Месяц"),
    YEAR("Год"),
}

private data class Bucket(val label: String, val value: Float)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpendingChartCard(expenses: List<ExpenseEntry>) {
    var period by remember { mutableStateOf(SpendingPeriod.MONTH) }
    // По умолчанию свёрнуто: график занимает 180.dp + чипы + строка-итог
    // (всего ~280 dp) — это серьёзно увеличивает прокрутку секции «Авто».
    // Юзер раскрывает по необходимости. State per-composable, сбрасывается
    // при уходе с экрана — это OK, не критично долго хранить.
    var expanded by remember { mutableStateOf(false) }
    // Счётчик для сброса выделенной точки графика — инкрементим при тапе
    // ВНЕ графика (по фону карточки / заголовку / чипам). LineChart получает
    // это значение как resetSelectionKey и реагирует через LaunchedEffect.
    var chartResetTick by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            // Outer-tap detector: ловит таппы по всей карточке. Если тап
            // попал на сам график — детектор графика (вложенный pointerInput)
            // выиграет, наш обработчик не вызовется. Если тап был на фоне
            // карточки/полях вокруг графика — сюда придёт событие, и мы
            // увеличиваем chartResetTick → выделение в LineChart снимается.
            .pointerInput(Unit) {
                detectTapGestures { chartResetTick++ }
            },
    ) {
        // Заголовок-полоса: тап по всему ряду переключает expanded. Иконка
        // справа — стандартный ExpandMore/ExpandLess (тот же паттерн, что в
        // CollapsibleCard в Настройках).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Динамика трат",
                style = MaterialTheme.typography.titleMedium,
                color = ColorNeutral,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                tint = ColorTextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Переключатель периода — Row из FilterChip'ов. 4 коротких
                // ярлыка влезают в одну строку даже на узких экранах.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SpendingPeriod.values().forEach { opt ->
                        FilterChip(
                            selected = opt == period,
                            onClick = { period = opt },
                            label = { Text(opt.label, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ColorNeutral.copy(alpha = 0.25f),
                                selectedLabelColor = Color.White,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                val buckets = remember(expenses, period) { aggregate(expenses, period) }
                if (buckets.all { it.value == 0f }) {
                    Text(
                        "Нет данных за выбранный период.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ColorTextSecondary,
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    ) {
                        LineChart(
                            values = buckets.map { it.value },
                            color = ColorAccent,
                            valueFormatter = { "%,.0f".format(it).replace(',', ' ') },
                            xAxisLabels = buckets.map { it.label },
                            resetSelectionKey = chartResetTick,
                        )
                    }
                    val total = buckets.sumOf { it.value.toDouble() }.toFloat()
                    val nonZero = buckets.count { it.value > 0f }.coerceAtLeast(1)
                    val avg = total / nonZero
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Итого: ${"%,.0f".format(total).replace(',', ' ')} ₽",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "среднее ${"%,.0f".format(avg).replace(',', ' ')} ₽ за ${period.label.lowercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ColorTextSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Группирует расходы по бакетам выбранного периода. Возвращает упорядоченный
 * по времени список «label + сумма». Бакеты без расходов остаются с value=0 —
 * это важно, чтобы график рисовал «провалы» (нулевые дни/недели), а не сжимал
 * данные подряд.
 */
private fun aggregate(expenses: List<ExpenseEntry>, period: SpendingPeriod): List<Bucket> {
    if (expenses.isEmpty()) return emptyList()
    val nowCal = Calendar.getInstance()
    return when (period) {
        SpendingPeriod.DAY -> aggregateRange(
            expenses = expenses,
            count = 30,
            // truncate каждое значение к началу суток; шаг — добавление 1 дня.
            truncate = { c ->
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            },
            step = { c -> c.add(Calendar.DAY_OF_YEAR, 1) },
            label = { c -> SimpleDateFormat("dd.MM", Locale.getDefault()).format(c.time) },
            now = nowCal,
        )
        SpendingPeriod.WEEK -> aggregateRange(
            expenses = expenses,
            count = 12,
            // Начало недели = понедельник. Calendar.FIRST_DAY_OF_WEEK по
            // умолчанию воскресенье в US-локали; форсируем понедельник, как
            // принято в РФ — иначе бакеты «перепрыгивают» с воскресенья на
            // понедельник и юзер видит ерунду.
            truncate = { c ->
                c.firstDayOfWeek = Calendar.MONDAY
                c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            },
            step = { c -> c.add(Calendar.WEEK_OF_YEAR, 1) },
            label = { c -> SimpleDateFormat("dd.MM", Locale.getDefault()).format(c.time) },
            now = nowCal,
        )
        SpendingPeriod.MONTH -> aggregateRange(
            expenses = expenses,
            count = 12,
            truncate = { c ->
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            },
            step = { c -> c.add(Calendar.MONTH, 1) },
            label = { c -> SimpleDateFormat("LLL yy", Locale.getDefault()).format(c.time) },
            now = nowCal,
        )
        SpendingPeriod.YEAR -> {
            // Для года diapason adaptive: от года самого раннего расхода до
            // текущего. Если расходов ещё мало (один год) — будет один бакет.
            val earliest = expenses.minOf { it.date }
            val earliestCal = Calendar.getInstance().apply { timeInMillis = earliest }
            val years = (nowCal.get(Calendar.YEAR) - earliestCal.get(Calendar.YEAR) + 1).coerceAtLeast(1)
            aggregateRange(
                expenses = expenses,
                count = years,
                truncate = { c ->
                    c.set(Calendar.DAY_OF_YEAR, 1)
                    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                },
                step = { c -> c.add(Calendar.YEAR, 1) },
                label = { c -> SimpleDateFormat("yyyy", Locale.getDefault()).format(c.time) },
                now = nowCal,
            )
        }
    }
}

/**
 * Общий алгоритм аггрегации в фиксированное число бакетов, заканчивающихся
 * текущим временем. Идём от ([count]-1) шагов назад до сегодня, строим
 * массив бакетов; для каждого расхода находим бакет по нормированному
 * времени (`truncate`) и добавляем сумму.
 */
private fun aggregateRange(
    expenses: List<ExpenseEntry>,
    count: Int,
    truncate: (Calendar) -> Unit,
    step: (Calendar) -> Unit,
    label: (Calendar) -> String,
    now: Calendar,
): List<Bucket> {
    val current = (now.clone() as Calendar).also(truncate)
    val stepField = getStepField(step)
    // Идём от current на (count-1) шагов назад, набирая бакеты. Префикс add(0)
    // в список — чтобы итоговый порядок был от старого к новому (хронологически).
    val cursor = current.clone() as Calendar
    val bucketCals = mutableListOf<Calendar>()
    repeat(count) {
        bucketCals.add(0, cursor.clone() as Calendar)
        cursor.add(stepField, -1)
    }
    val sums = FloatArray(count)
    // Для эффективности — линейный проход по expenses, бинарный поиск по
    // bucketCals не нужен (count ≤ 30, expenses обычно < 1000).
    expenses.forEach { exp ->
        val expCal = Calendar.getInstance().apply { timeInMillis = exp.date }
        truncate(expCal)
        val idx = bucketCals.indexOfFirst { it.timeInMillis == expCal.timeInMillis }
        if (idx >= 0) sums[idx] += exp.amountRub
    }
    return bucketCals.mapIndexed { i, c -> Bucket(label(c), sums[i]) }
}

/**
 * Хак: чтобы откатываться, нужно знать какое поле Calendar инкрементирует step.
 * Step — это lambda, в неё нельзя посмотреть. Делаем тестовый прогон: создаём
 * фиктивный календарь, инкрементируем step'ом, смотрим какое поле изменилось.
 *
 * Это однократная определялка для текущего period — не критично по
 * производительности, и позволяет не дублировать enum «какое поле сдвигать».
 */
private fun getStepField(step: (Calendar) -> Unit): Int {
    val a = Calendar.getInstance().apply { timeInMillis = 0 }
    val b = a.clone() as Calendar
    step(b)
    val deltaMs = b.timeInMillis - a.timeInMillis
    return when {
        deltaMs >= 365L * 24 * 60 * 60 * 1000 -> Calendar.YEAR
        deltaMs >= 28L * 24 * 60 * 60 * 1000 -> Calendar.MONTH
        deltaMs >= 7L * 24 * 60 * 60 * 1000 -> Calendar.WEEK_OF_YEAR
        else -> Calendar.DAY_OF_YEAR
    }
}
