package com.obd2monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.obd2monitor.ui.theme.ColorCardGray
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorTextPrimary
import com.obd2monitor.ui.theme.ColorTextSecondary

/**
 * Карточка параметра: иконка, название, значение, sparkline.
 * Плоский серый фон, без градиента (см. правки UX).
 */
@Composable
fun DataCard(
    icon: ImageVector,
    title: String,
    value: String,
    unit: String,
    history: List<Float> = emptyList(),
    valueColor: Color = ColorTextPrimary,
    accent: Color = ColorNeutral,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(ColorCardGray)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = ColorTextSecondary,
                maxLines = 2,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineLarge, color = valueColor)
            Spacer(Modifier.width(6.dp))
            Text(unit, style = MaterialTheme.typography.labelSmall, color = ColorTextSecondary)
        }
        if (history.size > 1) {
            Spacer(Modifier.height(6.dp))
            SparklineChart(values = history, color = accent.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth())
        }
    }
}
