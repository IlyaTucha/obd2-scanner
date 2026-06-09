package com.obd2monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorTextSecondary

@Composable
fun StatBox(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ColorCard)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = ColorTextSecondary)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
