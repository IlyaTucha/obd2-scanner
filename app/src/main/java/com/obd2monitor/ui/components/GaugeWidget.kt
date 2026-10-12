package com.obd2monitor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.obd2monitor.ui.theme.ColorPrimary
import com.obd2monitor.ui.theme.ColorTextPrimary
import com.obd2monitor.ui.theme.ColorTextSecondary

/**
 * Круговой gauge в стиле HUD. Угол развёртки 270°.
 * Layout: подпись (иконка + текст) НАД квадратом тахометра, внутри только число + единицы.
 */
@Composable
fun GaugeWidget(
    value: Float,
    minValue: Float,
    maxValue: Float,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    formatter: (Float) -> String = { "%.0f".format(it) },
    accentColor: Color = ColorPrimary,
    trackColor: Color = ColorTextSecondary.copy(alpha = 0.25f),
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val maxDial = minOf(190.dp, (LocalConfiguration.current.screenHeightDp * 0.32f).dp)
    val clamped = value.coerceIn(minValue, maxValue)
    val fraction = if (maxValue - minValue > 0f) (clamped - minValue) / (maxValue - minValue) else 0f
    val animFraction by animateFloatAsState(targetValue = fraction, label = "gauge")

    Column(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Подпись (иконка + текст) — НАД тахометром, прижата близко к нему.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ColorTextSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = ColorTextSecondary, maxLines = 1)
        }
        // Сам тахометр: квадрат с дугой и числом по центру.
        Box(
            // В горизонтали колонка широкая, а экран низкий — прибор ограничен и по
            // ширине, и по трети высоты экрана, чтобы под ним были видны карточки.
            modifier = Modifier.widthIn(max = maxDial).fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            // Равномерный padding со всех сторон → дуга остаётся круглой (не овалом).
            Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                val sweep = 270f
                val start = 135f
                val strokeWidth = 14.dp.toPx()
                val ringSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                drawArc(
                    color = trackColor,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(listOf(accentColor.copy(alpha = 0.0f), accentColor)),
                    startAngle = start,
                    sweepAngle = sweep * animFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                val center = Offset(size.width / 2f, size.height / 2f)
                val tickCount = 9
                val rOuter = (size.minDimension / 2f) - strokeWidth / 2f
                val rInner = rOuter - 8.dp.toPx()
                for (i in 0..tickCount) {
                    val angleDeg = start + sweep * i / tickCount
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val sx = center.x + rInner * kotlin.math.cos(rad).toFloat()
                    val sy = center.y + rInner * kotlin.math.sin(rad).toFloat()
                    val ex = center.x + rOuter * kotlin.math.cos(rad).toFloat()
                    val ey = center.y + rOuter * kotlin.math.sin(rad).toFloat()
                    drawLine(ColorTextSecondary.copy(alpha = 0.5f), Offset(sx, sy), Offset(ex, ey), 2.dp.toPx())
                }
            }
            // Число — строго в геометрическом центре Box. align(Center) ставит центр текста
            // на центр Box без учёта unit, как при Column-стэке (где центр посередине между ними).
            Text(
                formatter(clamped),
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.displaySmall,
                color = ColorTextPrimary,
            )
            // Единицы измерения — под числом.
            Text(
                unit,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 26.dp),
                style = MaterialTheme.typography.labelSmall,
                color = ColorTextSecondary,
            )
        }
    }
}
