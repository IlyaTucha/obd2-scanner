package com.obd2monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
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
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.ui.theme.ColorCardGray
import com.obd2monitor.ui.theme.ColorError
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.obd2monitor.ui.theme.ColorWarning

/**
 * Подробная диагностика подключения. Показывается, когда что-то идёт не так:
 *  - подключение в процессе/ошибка → цветной баннер с описанием;
 *  - подключение успешно, но ECU не отвечает на PID → подсказка как чинить;
 *  - всё хорошо → ничего не показываем (компактно).
 */
@Composable
fun ConnectionDiagnostics(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
) {
    val info = analyze(status) ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ColorCardGray)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(info.icon, contentDescription = null, tint = info.color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(info.title, color = info.color, style = MaterialTheme.typography.labelMedium)
            Text(info.detail, color = ColorTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class DiagInfo(val title: String, val detail: String, val color: Color, val icon: ImageVector)

private fun analyze(s: ConnectionStatus): DiagInfo? = when (s.state) {
    ConnectionState.DISCONNECTED -> null
    ConnectionState.CONNECTING -> DiagInfo(
        title = "Подключение к адаптеру",
        detail = "Открывается Bluetooth-соединение с ${s.deviceAddress ?: "адаптером"}.",
        color = ColorWarning,
        icon = Icons.Outlined.HourglassEmpty,
    )
    ConnectionState.INITIALIZING -> DiagInfo(
        title = "Связь с блоком управления",
        detail = "Адаптер подбирает протокол и запрашивает список поддерживаемых параметров. " +
            "Обычно это занимает до 10 секунд, на старых автомобилях — до 30. Зажигание должно быть включено.",
        color = ColorWarning,
        icon = Icons.Outlined.HourglassEmpty,
    )
    ConnectionState.ERROR -> DiagInfo(
        title = "Нет связи с адаптером",
        // Системные сообщения Android приходят по-английски («read failed, socket might
        // closed…») — показываем только наши, понятные пользователю.
        detail = (s.errorMessage?.takeIf { msg -> msg.any { it in 'а'..'я' || it in 'А'..'Я' } }?.let { "$it. " } ?: "") +
            "Проверьте, что адаптер вставлен в OBD-разъём, зажигание включено, а адаптер " +
            "сопряжён с телефоном в настройках Bluetooth. Приложение повторит попытку автоматически.",
        color = ColorError,
        icon = Icons.Outlined.ErrorOutline,
    )
    ConnectionState.CONNECTED -> when {
        // Адаптер подключён, но ЭБУ не отдал ни одного PID на 0100 — типично при
        // выключенном зажигании или если адаптер не смог поднять шину.
        s.supportedPidsCount == 0 -> {
            val raw = s.initRawResponse?.take(200)?.replace("\r", " ")?.replace("\n", " ")
            val reason = when {
                raw == null -> null
                raw.contains("BUS INIT", ignoreCase = true) ->
                    "Блок управления не ответил на инициализацию K-line (BUS INIT)."
                raw.contains("UNABLE", ignoreCase = true) ->
                    "Адаптер перебрал все протоколы, но блок управления не ответил (UNABLE TO CONNECT)."
                raw.contains("STOPPED", ignoreCase = true) ->
                    "Адаптер не успевает обрабатывать команды (STOPPED)."
                raw.contains("SEARCHING", ignoreCase = true) ->
                    "Адаптер не нашёл протокол за отведённое время (SEARCHING)."
                else -> null
            }
            DiagInfo(
                title = "Блок управления не отвечает",
                detail = listOfNotNull(
                    reason,
                    "Что можно сделать: включить зажигание (или завести двигатель), " +
                        "выбрать протокол в профиле автомобиля вместо автоматического, " +
                        "вынуть адаптер из разъёма на 10 секунд и подключиться снова.",
                    raw?.takeIf { it.isNotBlank() }?.let { "Ответ адаптера: «$it»." },
                ).joinToString(" "),
                color = ColorWarning,
                icon = Icons.Outlined.ErrorOutline,
            )
        }
        s.lastSnapshotAt != null && !s.lastSnapshotHadData -> DiagInfo(
            title = "Блок управления не передаёт данные",
            detail = "Связь установлена (параметров: ${s.supportedPidsCount}), но на запросы " +
                "скорости, оборотов и температуры приходит «NO DATA». Многие параметры " +
                "доступны только при работающем двигателе.",
            color = ColorWarning,
            icon = Icons.Outlined.Info,
        )
        else -> null
    }
}
