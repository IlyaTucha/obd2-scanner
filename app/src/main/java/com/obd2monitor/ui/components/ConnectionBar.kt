package com.obd2monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorError
import com.obd2monitor.ui.theme.ColorNeutral
import com.obd2monitor.ui.theme.ColorSuccess
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.obd2monitor.ui.theme.ColorWarning

@Composable
fun ConnectionBar(
    status: ConnectionStatus,
    activeProfileName: String?,
    onSettingsClick: () -> Unit,
    onConnectClick: () -> Unit,
    onReconnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (color, label) = when (status.state) {
        ConnectionState.CONNECTED -> ColorSuccess to "ON-LINE"
        ConnectionState.CONNECTING -> ColorWarning to "ПОДКЛ…"
        ConnectionState.INITIALIZING -> ColorWarning to "INIT"
        ConnectionState.ERROR -> ColorError to "ОШИБКА"
        ConnectionState.DISCONNECTED -> ColorTextSecondary to "OFF"
    }
    // Кнопка переподключения доступна, когда есть сохранённый адрес устройства
    // (то есть мы уже хоть раз подключались). Это касается и состояния ERROR/OFF —
    // оттуда тоже хочется быстро дёрнуть «повторить», не идя в настройки.
    // Во время CONNECTING/INITIALIZING — блокируем, чтобы юзер не отменил активный
    // handshake серией повторных нажатий.
    val canReconnect = status.deviceAddress != null &&
        status.state != ConnectionState.CONNECTING &&
        status.state != ConnectionState.INITIALIZING
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ColorCard)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "Переподключить",
            tint = if (canReconnect) ColorNeutral else ColorTextSecondary,
            modifier = Modifier
                .size(18.dp)
                .let { if (canReconnect) it.clickable { onReconnectClick() } else it },
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            status.elmVersion?.takeIf { it.isNotBlank() } ?: "ELM327",
            style = MaterialTheme.typography.bodySmall,
            color = ColorTextSecondary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (activeProfileName != null) {
                Text(
                    activeProfileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = ColorNeutral,
                    modifier = Modifier
                        .clickable { onConnectClick() }
                        .padding(end = 8.dp),
                )
            }
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                tint = ColorTextSecondary,
                modifier = Modifier.clickable { onSettingsClick() },
            )
        }
    }
}
