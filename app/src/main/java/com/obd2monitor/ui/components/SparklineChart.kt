package com.obd2monitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.obd2monitor.ui.theme.ColorAccent
import com.obd2monitor.ui.theme.ColorTextSecondary

/**
 * Мини-график (sparkline) с градиентной заливкой и легкими засечками по краям и середине.
 */
@Composable
fun SparklineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = ColorAccent,
) {
    Canvas(modifier = modifier.height(32.dp)) {
        if (values.size < 2) return@Canvas
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).takeIf { it > 0.001f } ?: 1f
        val stepX = size.width / (values.size - 1f)
        // Лёгкие засечки на 0/50/100% по нижней оси — чтобы было видно, что график не пустой.
        val tick = ColorTextSecondary.copy(alpha = 0.35f)
        for (i in 0..4) {
            val x = size.width * i / 4f
            drawLine(
                color = tick,
                start = Offset(x, size.height - 1.dp.toPx()),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v - minV) / range * size.height
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(
            fill,
            brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.4f), Color.Transparent)),
        )
        drawPath(path, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
