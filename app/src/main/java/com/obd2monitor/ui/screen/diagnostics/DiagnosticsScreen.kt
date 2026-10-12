package com.obd2monitor.ui.screen.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2monitor.domain.model.DTCCode
import com.obd2monitor.domain.model.DTCStatus
import com.obd2monitor.domain.model.FreezeFrame
import com.obd2monitor.domain.model.MonitorStatus
import com.obd2monitor.ui.components.ScreenColumn
import com.obd2monitor.ui.components.SectionHeader
import com.obd2monitor.ui.screen.car.mutedTextFieldColors
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorDialogScrim
import com.obd2monitor.ui.theme.ColorError
import com.obd2monitor.ui.theme.ColorOnPrimary
import com.obd2monitor.ui.theme.ColorPrimary
import com.obd2monitor.ui.theme.ColorSuccess
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.obd2monitor.ui.theme.ColorWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var confirmClear by remember { mutableStateOf(false) }
    val busy = state.isScanning || state.isClearing

    ScreenColumn {
        Column {
            Text("Ошибки", style = MaterialTheme.typography.headlineSmall)
            Text(
                state.lastScanAt?.let { "Последняя проверка в ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))}" }
                    ?: "Чтение кодов неисправностей блока управления двигателем",
                color = ColorTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (!state.connected) {
            InfoCard(
                icon = Icons.Outlined.Info,
                color = ColorTextSecondary,
                text = "Адаптер не подключён. Подключите его в настройках, чтобы прочитать или сбросить ошибки. " +
                    "Расшифровать код можно и без адаптера — ниже.",
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = viewModel::scan,
                enabled = state.connected && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary, contentColor = ColorOnPrimary),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                if (state.isScanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ColorOnPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Проверка…")
                } else Text("Проверить")
            }
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = state.connected && !busy,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text(if (state.isClearing) "Сброс…" else "Сбросить") }
        }
        state.message?.let { Text(it, color = ColorWarning, style = MaterialTheme.typography.bodySmall) }

        val result = state.result
        if (result != null && !state.isScanning) {
            SummaryCard(result.codes, result.monitorStatus)
            if (result.codes.isNotEmpty()) {
                SectionHeader("Коды неисправностей")
                result.codes.forEach { code ->
                    DTCCard(
                        code = code,
                        expanded = expanded[code.code] == true,
                        onToggle = { expanded[code.code] = expanded[code.code] != true },
                    )
                }
            }
            result.freezeFrame?.let { FreezeFrameCard(it) }
            result.monitorStatus?.takeIf { it.monitors.isNotEmpty() }?.let { ReadinessCard(it) }
        }

        LookupCard(lookup = state.lookup, onLookup = viewModel::lookup)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = ColorDialogScrim,
            title = { Text("Сбросить ошибки?") },
            text = {
                Text(
                    "Коды и стоп-кадр будут удалены из памяти блока управления, лампа Check Engine погаснет. " +
                        "Мониторы самодиагностики тоже сбросятся и станут «не готовы» на несколько поездок — " +
                        "до этого автомобиль может не пройти проверку на техосмотре. " +
                        "Если неисправность не устранена, код появится снова. " +
                        "Сброс выполняют при включённом зажигании и заглушенном двигателе.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clear()
                }) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun InfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ColorTextSecondary)
    }
}

/** Итог проверки: Check Engine и сколько кодов какого типа найдено. */
@Composable
private fun SummaryCard(codes: List<DTCCode>, monitor: MonitorStatus?) {
    val ok = codes.isEmpty()
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                contentDescription = null,
                tint = if (ok) ColorSuccess else ColorWarning,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (ok) "Ошибок не найдено" else "Найдено кодов: ${codes.size}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (monitor != null) {
            Text(
                if (monitor.milOn) "Лампа Check Engine горит" else "Лампа Check Engine не горит",
                color = if (monitor.milOn) ColorWarning else ColorTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val byStatus = DTCStatus.values().mapNotNull { s -> codes.count { it.status == s }.takeIf { it > 0 }?.let { s to it } }
        if (byStatus.isNotEmpty()) {
            Text(
                byStatus.joinToString(" · ") { (s, n) -> "${s.russianName.lowercase()}: $n" },
                color = ColorTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Проверяется блок управления двигателем и коробкой передач по стандарту OBD-II. " +
                "Ошибки подушек безопасности, ABS и кузовных блоков доступны только в сервисных сканерах марки.",
            color = ColorTextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DTCCard(code: DTCCode, expanded: Boolean, onToggle: () -> Unit) {
    val accent = if (code.isCritical) ColorError else ColorWarning
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ColorCard)
            .clickable(onClick = onToggle)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (code.isCritical) Icons.Outlined.ErrorOutline else Icons.Outlined.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(code.code, color = accent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(code.status.russianName, color = ColorTextSecondary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(code.description, style = MaterialTheme.typography.bodyMedium)
        if (code.isCritical) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Неисправность влияет на безопасность или работу двигателя — диагностику лучше не откладывать.",
                color = ColorError,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(code.status.hint, color = ColorTextSecondary, style = MaterialTheme.typography.bodySmall)
                CodeDetails(code)
            }
        }
        if (!expanded && (code.possibleCauses.isNotEmpty() || code.recommendedActions.isNotEmpty())) {
            Spacer(Modifier.height(4.dp))
            Text("Причины и что проверить", color = ColorTextSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CodeDetails(code: DTCCode) {
    if (code.possibleCauses.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("Возможные причины", color = ColorTextSecondary, style = MaterialTheme.typography.labelMedium)
        code.possibleCauses.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
    }
    if (code.recommendedActions.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("Что проверить", color = ColorTextSecondary, style = MaterialTheme.typography.labelMedium)
        code.recommendedActions.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FreezeFrameCard(frame: FreezeFrame) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = ColorTextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Стоп-кадр по коду ${frame.dtc}", style = MaterialTheme.typography.titleSmall)
        }
        Text(
            "Условия работы двигателя в момент, когда блок записал ошибку.",
            color = ColorTextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        frame.values.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(label, color = ColorTextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Готовность мониторов самодиагностики (то, что смотрят на техосмотре). */
@Composable
private fun ReadinessCard(status: MonitorStatus) {
    val notReady = status.monitors.count { !it.complete }
    Card {
        Text("Готовность самодиагностики", style = MaterialTheme.typography.titleSmall)
        Text(
            if (notReady == 0) "Все проверки систем пройдены."
            else "Не завершено проверок: $notReady. После сброса ошибок или отключения аккумулятора " +
                "проверки проходят заново во время обычных поездок — обычно за несколько дней.",
            color = ColorTextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        status.monitors.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (m.complete) ColorSuccess else ColorWarning),
                )
                Spacer(Modifier.width(10.dp))
                Text(m.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    if (m.complete) "готов" else "не готов",
                    color = if (m.complete) ColorSuccess else ColorWarning,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Расшифровка любого кода по справочнику, без подключения к машине. */
@Composable
private fun LookupCard(lookup: DTCCode?, onLookup: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val valid = DTCCode.isValidCode(text.trim().uppercase())
    Card {
        Text("Расшифровка кода", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                text = v.filter { it.isLetterOrDigit() }.uppercase().take(5)
                onLookup(text)
            },
            placeholder = { Text("Например, P0420", color = ColorTextSecondary) },
            singleLine = true,
            isError = text.length == 5 && !valid,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onLookup(text) }),
            colors = mutedTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            text.length == 5 && !valid -> Text(
                "Код состоит из буквы P, C, B или U и четырёх символов, например P0301.",
                color = ColorTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            lookup != null && lookup.code == text -> {
                Text(lookup.description, style = MaterialTheme.typography.bodyMedium)
                CodeDetails(lookup)
            }
        }
    }
}
