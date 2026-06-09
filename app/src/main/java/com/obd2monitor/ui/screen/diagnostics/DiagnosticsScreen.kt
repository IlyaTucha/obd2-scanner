package com.obd2monitor.ui.screen.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2monitor.domain.model.DTCCode
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorError
import com.obd2monitor.ui.theme.ColorPrimary
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

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("ДИАГНОСТИКА", style = MaterialTheme.typography.headlineMedium)
        }
        state.lastScanAt?.let {
            Text("Последняя проверка: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))}",
                color = ColorTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = viewModel::scan,
                enabled = !state.isScanning,
                colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text(if (state.isScanning) "СКАНИРУЕМ…" else "СКАНИРОВАТЬ") }
            OutlinedButton(
                onClick = viewModel::clear,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("СБРОСИТЬ") }
        }
        state.message?.let {
            Text(it, color = ColorWarning, style = MaterialTheme.typography.bodySmall)
        }
        if (state.lastScanAt != null && !state.isScanning) {
            SubsystemHealthCard(codes = state.codes)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
        ) {
            items(state.codes, key = { it.code }) { code ->
                DTCCard(
                    code = code,
                    expanded = expanded[code.code] == true,
                    onToggle = { expanded[code.code] = !(expanded[code.code] ?: false) },
                )
            }
        }
    }
}

@Composable
private fun SubsystemHealthCard(codes: List<DTCCode>) {
    val groups = listOf(
        SubsystemGroup("Двигатель / трансмиссия", 'P'),
        SubsystemGroup("Кузов (Body)", 'B'),
        SubsystemGroup("Шасси (Chassis)", 'C'),
        SubsystemGroup("Сеть / шина (Network)", 'U'),
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = ColorCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "СТАТУС ПОДСИСТЕМ",
                style = MaterialTheme.typography.labelMedium,
                color = ColorTextSecondary,
            )
            groups.forEach { g ->
                val n = codes.count { it.code.firstOrNull()?.uppercaseChar() == g.prefix }
                SubsystemRow(label = g.label, errorCount = n)
            }
        }
    }
}

private data class SubsystemGroup(val label: String, val prefix: Char)

@Composable
private fun SubsystemRow(label: String, errorCount: Int) {
    val ok = errorCount == 0
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
            contentDescription = null,
            tint = if (ok) com.obd2monitor.ui.theme.ColorSuccess else ColorWarning,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (ok) "OK" else "$errorCount ошибок",
            color = if (ok) com.obd2monitor.ui.theme.ColorSuccess else ColorWarning,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun DTCCard(code: DTCCode, expanded: Boolean, onToggle: () -> Unit) {
    val accent = if (code.isCritical) ColorError else ColorWarning
    val icon = if (code.isCritical) Icons.Outlined.Error else Icons.Outlined.Warning
    Card(
        colors = CardDefaults.cardColors(containerColor = ColorCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(code.code, color = accent, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Text(code.status.russianName, color = ColorTextSecondary,
                    style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(code.description, style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                if (code.possibleCauses.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Возможные причины:", color = ColorTextSecondary,
                        style = MaterialTheme.typography.labelMedium)
                    code.possibleCauses.forEach { Text("  • $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (code.recommendedActions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Рекомендации:", color = ColorTextSecondary,
                        style = MaterialTheme.typography.labelMedium)
                    code.recommendedActions.forEach { Text("  • $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
