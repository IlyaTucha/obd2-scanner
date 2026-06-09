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
        title = "Подключение к ELM327…",
        detail = "Открываю Bluetooth-сокет к ${s.deviceAddress ?: "адаптеру"}.",
        color = ColorWarning,
        icon = Icons.Outlined.HourglassEmpty,
    )
    ConnectionState.INITIALIZING -> DiagInfo(
        title = "Инициализация ECU…",
        detail = "Сбрасываю эхо, выбираю протокол, запрашиваю поддерживаемые PID. " +
            "Если затягивается > 10 c — проверьте, что зажигание включено.",
        color = ColorWarning,
        icon = Icons.Outlined.HourglassEmpty,
    )
    ConnectionState.ERROR -> DiagInfo(
        title = "Ошибка подключения",
        detail = s.errorMessage?.takeIf { it.isNotBlank() }
            ?: "Адаптер не отвечает. Проверьте: вставлен ли ELM327 в OBD-разъём, " +
                "включено ли зажигание, спарен ли адаптер в системе.",
        color = ColorError,
        icon = Icons.Outlined.ErrorOutline,
    )
    ConnectionState.CONNECTED -> when {
        // Адаптер подключен, но ECU не отдал ни одного PID на 0100 — типично при выключенном зажигании
        // или нестандартном протоколе.
        s.supportedPidsCount == 0 -> {
            val raw = s.initRawResponse?.take(200)?.replace("\r", " ")?.replace("\n", " ")
            val isBusInit = raw?.contains("BUS INIT", ignoreCase = true) == true
            val isUnable = raw?.contains("UNABLE", ignoreCase = true) == true
            val isStopped = raw?.contains("STOPPED", ignoreCase = true) == true
            // SEARCHING без последующих hex-байт = ELM так и не залочился на шину.
            // Технически отличается от UNABLE — там адаптер сдался, а тут просто
            // не дождались. На практике причина та же: нет ECU на шине.
            val isSearchingOnly = raw?.contains("SEARCHING", ignoreCase = true) == true && !isUnable
            val title = when {
                isBusInit -> "BUS INIT: ERROR — K-line не поднялся"
                isUnable -> "UNABLE TO CONNECT — ECU не отвечает"
                isStopped -> "STOPPED — рассинхрон с адаптером"
                isSearchingOnly -> "SEARCHING — ECU не ответил за время поиска"
                else -> "ECU не отвечает на запросы"
            }
            val detail = when {
                isBusInit -> "ELM пытается поднять K-line сессию (ISO 9141-2 / KWP slow init), но " +
                    "ECU не отвечает на 5-baud init-последовательность. Что проверить: " +
                    "1) Зажигание именно в положении ON (одна позиция до старта стартёра), " +
                    "не ACC и не «двигатель работает» при старте сессии — на старых Hyundai " +
                    "ECU отвечает на K-line только при определённой позиции ключа. " +
                    "2) Заведите двигатель — на холодном ECT < 5 °C ECU может не отвечать. " +
                    "3) Полностью выньте адаптер из OBD-разъёма на 10 секунд и вставьте обратно — " +
                    "ELM v1.5 клоны часто залипают в неинициализированном состоянии. " +
                    "4) В Torque подключение работает, потому что он делает init дольше — " +
                    "повторите подключение здесь 2–3 раза подряд. " +
                    "Сырой ответ ELM: «$raw»."
                isUnable -> "ELM перебрал все протоколы и не смог установить связь. Проверьте: " +
                    "адаптер физически в OBD-разъёме, зажигание ON, попробуйте перевключить " +
                    "адаптер. Сырой ответ: «$raw»."
                isStopped -> "ELM получает команды быстрее, чем успевает обрабатывать (ответ STOPPED). " +
                    "Это типично для ELM327 v1.5-клонов на низком Bluetooth-канале. Попробуйте: " +
                    "1) Откл. → Подкл. в настройках (запустит инициализацию заново с длинными " +
                    "паузами и закрытием предыдущей сессии — ATPC). " +
                    "2) Удалите Bluetooth-сопряжение в системных настройках Android и заново " +
                    "спарьте адаптер — иногда помогает сбросить кэш. " +
                    "3) Подключитесь к OpenDiag/Torque один раз — они оставляют ECU в активной " +
                    "сессии, после чего наше приложение цепляется без slow-init. " +
                    "Сырой ответ: «$raw»."
                isSearchingOnly -> "ELM начал поиск протокола (SEARCHING), но ECU так и не ответил за " +
                    "30 секунд. На K-line авто (Hyundai Accent 2008 G4EE) это самый частый сбой. Что проверить: " +
                    "1) Зажигание именно в положении ON (одна позиция до старта, не ACC). На холодном " +
                    "ECU без зажигания K-line молчит, и ELM крутит SEARCHING до таймаута. " +
                    "2) Заведите двигатель — некоторые Hyundai ECU поднимают K-line только при работающем моторе. " +
                    "3) В настройках выберите профиль авто (Hyundai Accent 1.4 / 1.6) — приложение " +
                    "сразу попробует KWP fast init вместо медленного auto. " +
                    "4) Выньте адаптер из OBD-разъёма на 10 секунд и вставьте обратно — " +
                    "ELM v1.5 клоны после неудачной сессии часто залипают и не выходят без аппаратного сброса. " +
                    "5) Подключитесь к OpenDiag/Torque — они держат init дольше; если они тоже " +
                    "не цепляются, проблема в адаптере или зажигании, не в приложении. " +
                    "Сырой ответ: «$raw»."
                else -> "Адаптер ${s.elmVersion ?: "ELM327"} подключён, но ECU не вернул " +
                    "поддерживаемые PID. " +
                    (if (!raw.isNullOrBlank()) "Сырой ответ: «$raw». " else "") +
                    "Включите зажигание в ON, переподключите адаптер."
            }
            DiagInfo(
                title = title,
                detail = detail,
                color = ColorWarning,
                icon = Icons.Outlined.ErrorOutline,
            )
        }
        s.lastSnapshotAt != null && !s.lastSnapshotHadData -> DiagInfo(
            title = "ECU отвечает, но без данных",
            detail = "Поддерживаемых PID: ${s.supportedPidsCount}. На стандартные запросы " +
                "(скорость / обороты / температура) пришло «NO DATA». Заведите двигатель — " +
                "большинство PID активны только при работающем моторе.",
            color = ColorWarning,
            icon = Icons.Outlined.Info,
        )
        else -> null
    }
}
