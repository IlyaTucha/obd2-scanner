package com.obd2monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorError
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorSuccess
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.obd2monitor.ui.theme.ColorWarning

/** Цвет и подпись состояния подключения — одни и те же на всех экранах. */
fun connectionLabel(state: ConnectionState): Pair<Color, String> = when (state) {
    ConnectionState.CONNECTED -> ColorSuccess to "Подключено"
    ConnectionState.CONNECTING -> ColorWarning to "Подключение"
    ConnectionState.INITIALIZING -> ColorWarning to "Инициализация"
    ConnectionState.ERROR -> ColorError to "Нет связи"
    ConnectionState.DISCONNECTED -> ColorTextSecondary to "Не подключено"
}

/**
 * Шапка «Главной»: активный профиль автомобиля, адаптер и протокол, статус связи,
 * кнопки переподключения и настроек.
 */
@Composable
fun ConnectionBar(
    status: ConnectionStatus,
    activeProfileName: String?,
    onSettingsClick: () -> Unit,
    onReconnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (color, label) = connectionLabel(status.state)
    // Переподключение доступно, когда адрес адаптера известен. Во время подключения
    // и инициализации кнопка неактивна, чтобы повторные нажатия не сбивали handshake.
    val canReconnect = status.deviceAddress != null &&
        status.state != ConnectionState.CONNECTING &&
        status.state != ConnectionState.INITIALIZING
    // Подробности — только при подключении (версия адаптера и протокол); в остальных
    // состояниях статус слева уже всё говорит, вторая подпись была бы повтором.
    val details = if (status.state == ConnectionState.CONNECTED) {
        listOfNotNull(status.elmVersion?.takeIf { it.isNotBlank() } ?: "ELM327", status.protocol?.displayName)
            .joinToString(" · ")
    } else null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ColorCard)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                activeProfileName ?: "Профиль автомобиля не выбран",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                if (details != null) {
                    Text(
                        "  ·  $details",
                        style = MaterialTheme.typography.bodySmall,
                        color = ColorTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            IconButton(onClick = onReconnectClick, enabled = canReconnect) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Переподключить",
                    tint = if (canReconnect) ColorNeutral else ColorTextSecondary.copy(alpha = 0.4f),
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Outlined.Settings, contentDescription = "Настройки", tint = ColorTextSecondary)
            }
        }
    }
}
