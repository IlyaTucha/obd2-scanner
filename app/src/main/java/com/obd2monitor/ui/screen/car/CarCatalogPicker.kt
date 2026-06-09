package com.obd2monitor.ui.screen.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obd2monitor.data.catalog.CarCatalog
import com.obd2monitor.data.catalog.CatalogEntry
import com.obd2monitor.ui.theme.ColorDialogScrim
import com.obd2monitor.ui.theme.ColorTextSecondary

/**
 * Каскадный picker «марка → модель → поколение → двигатель» по [CarCatalog].
 *
 * Идея: 4 выпадающих списка идут по цепочке, каждый следующий зависит от
 * выбранного значения предыдущего. Если юзер меняет, скажем, модель — поля
 * ниже сбрасываются, иначе можно получить несуществующую комбинацию.
 *
 * Зачем не один большой список «всё-в-одном»: 70+ комбинаций неудобно искать
 * глазами. Цепочка из 4 коротких списков быстрее: на каждом шаге доступно
 * 5–15 вариантов. И каталог наглядно показывает структуру (Lada → Granta →
 * 2018-2025 → «1.6 11186 8кл»).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarCatalogPickerDialog(
    catalog: CarCatalog,
    onDismiss: () -> Unit,
    onPick: (CatalogEntry) -> Unit,
) {
    var make by remember { mutableStateOf<String?>(null) }
    var model by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableStateOf<String?>(null) }
    var engine by remember { mutableStateOf<CatalogEntry?>(null) }

    val makes = remember(catalog) { catalog.makes() }
    val models = remember(catalog, make) { make?.let { catalog.models(it) }.orEmpty() }
    val generations = remember(catalog, make, model) {
        if (make != null && model != null) catalog.generations(make!!, model!!) else emptyList()
    }
    val engines = remember(catalog, make, model, generation) {
        if (make != null && model != null && generation != null)
            catalog.engines(make!!, model!!, generation!!) else emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ColorDialogScrim,
        title = { Text("Выбор из каталога") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Если в каталоге нет нужной комбинации — закрой диалог и заполни поля вручную.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ColorTextSecondary,
                )
                CascadeDropdown(
                    label = "Марка",
                    selected = make,
                    options = makes,
                    optionLabel = { it },
                    onSelect = {
                        make = it
                        // Сбрасываем нижестоящие — комбинация изменилась.
                        model = null
                        generation = null
                        engine = null
                    },
                )
                CascadeDropdown(
                    label = "Модель",
                    selected = model,
                    options = models,
                    optionLabel = { it },
                    enabled = make != null,
                    onSelect = {
                        model = it
                        generation = null
                        engine = null
                    },
                )
                CascadeDropdown(
                    label = "Поколение",
                    selected = generation,
                    options = generations,
                    optionLabel = { it },
                    enabled = model != null,
                    onSelect = {
                        generation = it
                        engine = null
                    },
                )
                CascadeDropdown(
                    label = "Двигатель",
                    selected = engine?.let { engineLabel(it) },
                    options = engines,
                    optionLabel = ::engineLabel,
                    enabled = generation != null,
                    onSelect = { engine = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { engine?.let { onPick(it) } },
                enabled = engine != null,
            ) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun engineLabel(entry: CatalogEntry): String {
    // Показываем л.с. — в РФ привычная единица; в каталоге храним кВт,
    // потому что физические формулы (мощность через MAF, торк через power×9549/rpm)
    // считаются в SI. Конверсия 1 кВт = 1.35962 л.с.
    val hp = (entry.powerKw * 1.35962f).toInt()
    return "${entry.engine} · $hp л.с."
}

/**
 * Один шаг каскада — выпадающий список. Это тонкая обёртка над
 * [ExposedDropdownMenuBox] с muted-стилем поля. Хранит локальное состояние
 * expanded — нет смысла поднимать его наружу, потому что для каждого
 * шага оно независимое.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> CascadeDropdown(
    label: String,
    selected: String?,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val canExpand = enabled && options.isNotEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded && canExpand,
        onExpandedChange = { if (canExpand) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = {
                Text(
                    if (canExpand) "выбрать…" else "сначала заполни выше",
                    color = ColorTextSecondary.copy(alpha = 0.5f),
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && canExpand) },
            colors = mutedTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded && canExpand,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(optionLabel(opt)) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}
