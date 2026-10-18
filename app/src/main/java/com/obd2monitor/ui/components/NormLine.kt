package com.obd2monitor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obd2monitor.ui.theme.ColorTextPrimary
import com.obd2monitor.ui.theme.ColorTextSecondary

/**
 * Норма параметра: подпись «Норма для <машина>» серым, под ней сами цифры тем же
 * мелким кеглем, но жирным и белым — крупнее описания «Что это?» быть не должна.
 */
@Composable
fun NormLine(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ColorTextSecondary)
        Text(
            keepSegmentsWhole(value),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = ColorTextPrimary,
        )
    }
}

/** Перенос строки — только между частями « · »: внутри части пробелы неразрывные,
 *  а после «/» стоит запрет переноса, чтобы «об/мин» не разрывалось. */
private fun keepSegmentsWhole(value: String): String =
    value.split(" · ").joinToString(" · ") { it.replace(' ', '\u00A0').replace("/", "/\u2060") }
