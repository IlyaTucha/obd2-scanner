package com.obd2monitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obd2monitor.ui.theme.ColorAccent
import com.obd2monitor.ui.theme.ColorTextSecondary

/**
 * Полноразмерный line chart с засечками по осям, подписями min/max/mid и градиентной заливкой.
 * Y-ось — числовые значения; X-ось — относительное время (от старта к концу серии).
 *
 * Жесты: двупальцевое pinch — зум по оси времени; однопальцевое перетаскивание —
 * pan; короткий тап — выбор ближайшей точки (показывается tooltip с её значением).
 *
 * Точки рисуются всегда (маленькие кружки на ломаной) — это помогает понять,
 * сколько именно samples в видимом окне, и куда тапать чтобы увидеть значение.
 *
 * Зум по Y не делается осознанно: точки могут быть очень разной природы
 * (RPM 700..6000 vs температура 80..95), и Y-зум только запутает. Состояние
 * жеста (zoom + offset + selectedIndex) хранится в remember{} — переход на
 * другой экран сбрасывает на полный вид без выделения.
 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = ColorAccent,
    gridLines: Int = 4,
    xTicks: Int = 4,
    zeroCentered: Boolean = false,
    valueFormatter: (Float) -> String = { "%.1f".format(it) },
    /**
     * Подписи для X-оси по индексам ряда. null = подписи как «%» (старое
     * поведение, нужно для большинства метрик где X — это «доля по времени»).
     * Когда передан непустой список (длина == values.size) — подписи берутся
     * из него: используется на графике пробега, где осмысленнее видеть даты.
     */
    xAxisLabels: List<String>? = null,
    /**
     * Если передан и его значение МЕНЯЕТСЯ — текущее выделение сбрасывается.
     * Используется родительским контейнером, чтобы дёрнуть «отпусти точку»
     * по тапу вне графика (родитель ловит outside-tap и инкрементирует
     * счётчик, передавая его как этот ключ).
     */
    resetSelectionKey: Any? = null,
) {
    val density = LocalDensity.current
    val labelPx = with(density) { 10.sp.toPx() }
    val tooltipTextPx = with(density) { 11.sp.toPx() }
    val leftPad = with(density) { 36.dp.toPx() }
    val bottomPad = with(density) { 18.dp.toPx() }
    val topPad = with(density) { 6.dp.toPx() }
    val rightPad = with(density) { 6.dp.toPx() }
    val labelColor = ColorTextSecondary.copy(alpha = 0.85f).toArgb()
    val gridColor = ColorTextSecondary.copy(alpha = 0.18f)
    val tickColor = ColorTextSecondary.copy(alpha = 0.55f)

    // Состояние pinch-zoom/pan. zoom=1 — полный вид; offsetFracX в долях от полной
    // ширины ряда (–0.5..+0.5). При zoom=1 offset зажимается в 0.
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetFracX by remember { mutableFloatStateOf(0f) }
    // Индекс выбранной точки В ВИДИМОМ ОКНЕ (не в полном values). -1 = ничего
    // не выбрано. Обнуляется при смене values или при повторном тапе по тому
    // же месту (toggle off). При зуме/pan не обнуляем — пользователь может
    // одной рукой держать выбор, другой подкрутить масштаб.
    var selectedWindowIdx by remember { mutableIntStateOf(-1) }
    // Внешний сброс: родитель меняет resetSelectionKey → выделение снимается.
    // Применяется чтобы по тапу за пределами графика тут же скрылся tooltip.
    LaunchedEffect(resetSelectionKey) { selectedWindowIdx = -1 }
    // Запоминаем размер канваса в пикселях из draw-блока — нужно для
    // tap-callback, чтобы оттуда найти ближайшую точку. Прямо в pointerInput
    // size.width есть, но геометрия plot (paddings) считается в draw, поэтому
    // передаём через mutableState.
    var canvasW by remember { mutableStateOf(0f) }
    var canvasH by remember { mutableStateOf(0f) }

    // Параметры видимого окна — пересчитываем дважды (в pointerInput для tap-hit
    // и в draw для рендера), поэтому выносим в lambda.
    fun computeWindow(): Triple<Int, Int, Int> {
        if (values.size < 2) return Triple(0, 0, 0)
        val total = values.size
        val visibleFrac = 1f / zoom
        val centerFrac = (0.5f + offsetFracX).coerceIn(visibleFrac / 2f, 1f - visibleFrac / 2f)
        val startFrac = (centerFrac - visibleFrac / 2f).coerceAtLeast(0f)
        val endFrac = (centerFrac + visibleFrac / 2f).coerceAtMost(1f)
        val startIdx = (startFrac * (total - 1)).toInt().coerceIn(0, total - 1)
        val endIdx = (endFrac * (total - 1)).toInt().coerceIn(startIdx + 1, total - 1)
        return Triple(startIdx, endIdx, total)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Тап — выбор ближайшей точки. Отдельный pointerInput от transform —
            // Compose сам разруливает: короткий tap → сюда, drag/pinch → ниже.
            .pointerInput(values.size) {
                detectTapGestures { offset ->
                    if (values.size < 2 || canvasW <= 0f) {
                        selectedWindowIdx = -1
                        return@detectTapGestures
                    }
                    val (startIdx, endIdx, _) = computeWindow()
                    val windowSize = endIdx - startIdx + 1
                    if (windowSize < 2) {
                        selectedWindowIdx = -1
                        return@detectTapGestures
                    }
                    val plotLeft = leftPad
                    val plotRight = canvasW - rightPad
                    val plotTopPx = topPad
                    val plotBottomPx = canvasH - bottomPad
                    // Тап в служебных зонах (Y-подписи слева, X-подписи снизу,
                    // верхний/правый отступы) — НЕ интерпретируем как выбор
                    // точки. Снимаем выделение и выходим. Это и есть «нажато
                    // не на сам график» внутри канваса.
                    if (offset.x < plotLeft || offset.x > plotRight ||
                        offset.y < plotTopPx || offset.y > plotBottomPx
                    ) {
                        selectedWindowIdx = -1
                        return@detectTapGestures
                    }
                    val plotW = plotRight - plotLeft
                    val stepX = plotW / (windowSize - 1f)
                    val relX = (offset.x - plotLeft).coerceIn(0f, plotW)
                    val idx = (relX / stepX).toInt().coerceIn(0, windowSize - 1)
                    // Toggle: повторный тап в ту же точку — снимает выделение.
                    selectedWindowIdx = if (selectedWindowIdx == idx) -1 else idx
                }
            }
            .pointerInput(values.size) {
                detectTransformGestures { _, panChange, gestureZoom, _ ->
                    val newZoom = (zoom * gestureZoom).coerceIn(1f, 8f)
                    val widthPx = size.width.coerceAtLeast(1).toFloat()
                    val deltaFrac = -panChange.x / widthPx / newZoom
                    val maxOff = 0.5f - 0.5f / newZoom
                    offsetFracX = (offsetFracX + deltaFrac).coerceIn(-maxOff, maxOff)
                    zoom = newZoom
                }
            },
    ) {
        canvasW = size.width
        canvasH = size.height
        if (values.size < 2) return@Canvas

        val (startIdx, endIdx, _) = computeWindow()
        val window = values.subList(startIdx, endIdx + 1)
        if (window.size < 2) return@Canvas

        val visibleFrac = 1f / zoom
        val centerFrac = (0.5f + offsetFracX).coerceIn(visibleFrac / 2f, 1f - visibleFrac / 2f)
        val startFrac = (centerFrac - visibleFrac / 2f).coerceAtLeast(0f)
        val endFrac = (centerFrac + visibleFrac / 2f).coerceAtMost(1f)

        val rawMin = window.min()
        val rawMax = window.max()
        val symmetricMax = maxOf(kotlin.math.abs(rawMin), kotlin.math.abs(rawMax)).takeIf { it > 0.001f } ?: 1f
        val minV = if (zeroCentered) -symmetricMax else rawMin
        val maxV = if (zeroCentered) symmetricMax else rawMax
        val range = (maxV - minV).takeIf { it > 0.001f } ?: 1f
        val plotLeft = leftPad
        val plotRight = size.width - rightPad
        val plotTop = topPad
        val plotBottom = size.height - bottomPad
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        // Используем явный конструктор, чтобы не конфликтовать с параметром
        // `color: Color` композабла внутри `apply`-блока (Kotlin резолвит `color = ...`
        // в outer val и выдаёт «Val cannot be reassigned»).
        val paint = android.graphics.Paint()
        paint.color = labelColor
        paint.textSize = labelPx
        paint.isAntiAlias = true

        // Горизонтальные линии сетки + подписи Y.
        val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
        for (i in 0..gridLines) {
            val frac = i.toFloat() / gridLines
            val y = plotBottom - plotH * frac
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = if (i == 0 || i == gridLines) null else dashed,
            )
            drawLine(
                color = tickColor,
                start = Offset(plotLeft - 3.dp.toPx(), y),
                end = Offset(plotLeft, y),
                strokeWidth = 1.dp.toPx(),
            )
            val v = minV + range * frac
            val label = valueFormatter(v)
            drawContext.canvas.nativeCanvas.drawText(
                label,
                plotLeft - 6.dp.toPx() - paint.measureText(label),
                y + labelPx / 3f,
                paint,
            )
        }
        if (zeroCentered) {
            val zeroY = plotBottom - (0f - minV) / range * plotH
            drawLine(
                color = tickColor.copy(alpha = 0.9f),
                start = Offset(plotLeft, zeroY),
                end = Offset(plotRight, zeroY),
                strokeWidth = 2.dp.toPx(),
            )
        }

        // Засечки по X — подписи показывают актуальный диапазон окна, не «0..100%».
        for (i in 0..xTicks) {
            val frac = i.toFloat() / xTicks
            val x = plotLeft + plotW * frac
            drawLine(
                color = tickColor,
                start = Offset(x, plotBottom),
                end = Offset(x, plotBottom + 4.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            val label = if (xAxisLabels != null && xAxisLabels.size == values.size && values.size >= 2) {
                val idxF = startFrac + (endFrac - startFrac) * frac
                val idx = (idxF * (values.size - 1)).toInt().coerceIn(0, values.size - 1)
                xAxisLabels[idx]
            } else {
                val pctOfTotal = (startFrac + (endFrac - startFrac) * frac) * 100f
                "${pctOfTotal.toInt()}%"
            }
            drawContext.canvas.nativeCanvas.drawText(
                label,
                x - paint.measureText(label) / 2f,
                plotBottom + 4.dp.toPx() + labelPx,
                paint,
            )
        }

        // ── Линия + заливка ──
        val stepX = plotW / (window.size - 1f)
        val path = Path()
        val fill = Path()
        val baseY = if (zeroCentered) plotBottom - (0f - minV) / range * plotH else plotBottom
        // Заодно собираем pixel-координаты всех точек — пригодятся для дотов
        // и для tooltip-маркера выбранной точки.
        val pointXs = FloatArray(window.size)
        val pointYs = FloatArray(window.size)
        window.forEachIndexed { i, v ->
            val x = plotLeft + i * stepX
            val y = plotBottom - (v - minV) / range * plotH
            pointXs[i] = x; pointYs[i] = y
            if (i == 0) {
                path.moveTo(x, y); fill.moveTo(x, baseY); fill.lineTo(x, y)
            } else {
                path.lineTo(x, y); fill.lineTo(x, y)
            }
        }
        fill.lineTo(plotLeft + (window.size - 1) * stepX, baseY); fill.close()
        drawPath(fill, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent)))
        drawPath(path, color = color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

        // ── Точки на линии ──
        // Радиус подбираем в зависимости от плотности samples в окне: если
        // точек много (>50), маленькие dots, чтобы не превратить ломаную в
        // «гусеницу». Если мало (≤20) — крупнее, чтобы по ним удобнее попадать
        // пальцем. На очень плотных рядах (>200) — вообще скрываем, иначе
        // график замазывается.
        val dotRadius = when {
            window.size > 200 -> 0f
            window.size > 50 -> 1.5.dp.toPx()
            window.size > 20 -> 2.dp.toPx()
            else -> 2.5.dp.toPx()
        }
        if (dotRadius > 0f) {
            for (i in window.indices) {
                drawCircle(
                    color = color,
                    radius = dotRadius,
                    center = Offset(pointXs[i], pointYs[i]),
                )
            }
        }

        // ── Tooltip для выбранной точки ──
        if (selectedWindowIdx in window.indices) {
            val px = pointXs[selectedWindowIdx]
            val py = pointYs[selectedWindowIdx]
            val v = window[selectedWindowIdx]
            // Выделяем точку крупным кругом + белой обводкой — отличается от
            // обычных dots сразу даже на ярком сегменте.
            drawCircle(color = color, radius = 5.dp.toPx(), center = Offset(px, py))
            drawCircle(
                color = Color.White, radius = 5.dp.toPx(),
                center = Offset(px, py),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            // Вертикальный гайд от выбранной точки вниз до оси — помогает
            // глазом соотнести с X-меткой.
            drawLine(
                color = ColorTextSecondary.copy(alpha = 0.35f),
                start = Offset(px, py),
                end = Offset(px, plotBottom),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
            )
            // Текст подсказки: значение + (если есть) подпись X для этой точки.
            val originalIdx = startIdx + selectedWindowIdx
            // Локальный val чтобы smart-cast прошёл внутри ?.takeIf: без него
            // Kotlin не уверен что параметр xAxisLabels не null между двумя
            // обращениями в цепочке.
            val labels = xAxisLabels
            val xCaption = if (labels != null && labels.size == values.size)
                labels.getOrNull(originalIdx) else null
            val text = if (xCaption != null) "${valueFormatter(v)} · $xCaption" else valueFormatter(v)
            // ВАЖНО: не использовать .apply { color = ... } здесь — Kotlin
            // резолвит `color` в outer-параметр LineChart (Color), а не в
            // Paint.color (Int). См. такой же приём строкой 119–121 для основного
            // paint. Поэтому конфигурируем через прямые присваивания на инстанс.
            val tooltipPaint = android.graphics.Paint()
            tooltipPaint.color = android.graphics.Color.WHITE
            tooltipPaint.textSize = tooltipTextPx
            tooltipPaint.isAntiAlias = true
            val padX = 6.dp.toPx()
            val padY = 4.dp.toPx()
            val textW = tooltipPaint.measureText(text)
            val boxW = textW + padX * 2
            val boxH = tooltipTextPx + padY * 2
            // Позиционируем подсказку ВЫШЕ точки. Если упирается в верх — снизу.
            // По X центрируем относительно точки, но не выпускаем за края plot.
            var boxLeft = (px - boxW / 2f).coerceIn(plotLeft, plotRight - boxW)
            var boxTop = py - boxH - 8.dp.toPx()
            if (boxTop < plotTop) boxTop = py + 8.dp.toPx()
            val bg = android.graphics.Paint()
            bg.color = android.graphics.Color.argb(220, 21, 22, 26)  // ColorDialogScrim-like
            bg.isAntiAlias = true
            val border = android.graphics.Paint()
            border.color = android.graphics.Color.argb(80, 138, 138, 154) // ColorTextSecondary
            border.isAntiAlias = true
            border.style = android.graphics.Paint.Style.STROKE
            border.strokeWidth = 1.dp.toPx()
            val rect = android.graphics.RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
            drawContext.canvas.nativeCanvas.drawRoundRect(rect, 6.dp.toPx(), 6.dp.toPx(), bg)
            drawContext.canvas.nativeCanvas.drawRoundRect(rect, 6.dp.toPx(), 6.dp.toPx(), border)
            drawContext.canvas.nativeCanvas.drawText(
                text,
                boxLeft + padX,
                boxTop + padY + tooltipTextPx - 2f,
                tooltipPaint,
            )
        }

        // Индикатор зума справа сверху.
        if (zoom > 1.01f) {
            paint.color = labelColor
            val zoomLabel = "${"%.1f".format(zoom)}×"
            drawContext.canvas.nativeCanvas.drawText(
                zoomLabel,
                plotRight - paint.measureText(zoomLabel) - 6f,
                plotTop + labelPx + 4f,
                paint,
            )
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt().coerceIn(0, 255),
    (red * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue * 255).toInt().coerceIn(0, 255),
)
