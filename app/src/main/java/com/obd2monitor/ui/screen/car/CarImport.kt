package com.obd2monitor.ui.screen.car

import android.content.Context
import android.net.Uri
import com.obd2monitor.domain.model.ExpenseEntry
import com.obd2monitor.domain.model.ExpenseType
import com.obd2monitor.domain.model.MaintenanceEntry
import com.obd2monitor.domain.model.MaintenanceType
import com.obd2monitor.domain.model.MileageEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Парсер отчёта по авто, формат — ровно тот же, который пишет
 * [saveCarReport] (см. CarExport.buildCarReportText). Цель: позволить пользователю
 * взять ранее экспортированный .txt с другого устройства и импортировать его обратно.
 *
 * Идея парсера простая и устойчивая к мелким человеческим правкам файла:
 *  - текст режется по «=== РАЗДЕЛ ===» (АВТОМОБИЛЬ / ПРОБЕГ / ОБСЛУЖИВАНИЕ / РАСХОДЫ);
 *  - внутри раздела построчно ищутся записи по регуляркам, не сходящиеся строки
 *    молча пропускаются (не падаем на «Период», «Полная история обслуживания:» и т.п.);
 *  - агрегаты («Итого», «По категориям», «Период», «Пробежено за период»,
 *    «Общее расстояние») игнорируются — они выводимы из импортированных записей.
 *
 * Возвращаем [CarImportResult] с готовыми domain-моделями: vehicleProfileId не
 * проставлен (его подставит CarViewModel.importCarReport — берёт из активного профиля).
 */
data class CarImportResult(
    val mileage: List<MileageEntry>,
    val expenses: List<ExpenseEntry>,
    val maintenance: List<MaintenanceEntry>,
    val vin: String?,
    val licensePlate: String?,
) {
    val total: Int get() = mileage.size + expenses.size + maintenance.size
}

private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
private val dateTimeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

/** Читает Uri как текст и парсит. null если файла нет или содержимое пустое. */
fun importCarReportFromUri(context: Context, uri: Uri): CarImportResult? {
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)
    }.getOrNull() ?: return null
    if (text.isBlank()) return null
    return parseCarReport(text)
}

fun parseCarReport(text: String): CarImportResult {
    // Делим на куски по заголовкам «=== X ===».
    val sections = splitBySections(text)

    val mileage = parseMileageSection(sections["ПРОБЕГ"].orEmpty())
    val maintenance = parseMaintenanceSection(sections["ОБСЛУЖИВАНИЕ"].orEmpty())
    val expenses = parseExpensesSection(sections["РАСХОДЫ"].orEmpty())
    val (vin, plate) = parseVehicleSection(sections["АВТОМОБИЛЬ"].orEmpty())
    return CarImportResult(mileage, expenses, maintenance, vin, plate)
}

private fun splitBySections(text: String): Map<String, String> {
    val out = mutableMapOf<String, StringBuilder>()
    var current: StringBuilder? = null
    // Заголовок раздела: «=== ИМЯ ===» либо «=== ИМЯ (N записей) ===» / «(N категорий)»
    // и т.п. — берём первое слово (АВТОМОБИЛЬ/ПРОБЕГ/ОБСЛУЖИВАНИЕ/РАСХОДЫ).
    val headerRegex = Regex("""^=+\s*([А-ЯЁ]+).*=+\s*$""")
    text.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        val m = headerRegex.matchEntire(line)
        if (m != null) {
            val name = m.groupValues[1]
            current = out.getOrPut(name) { StringBuilder() }
        } else {
            current?.appendLine(line)
        }
    }
    return out.mapValues { it.value.toString() }
}

private fun parseVehicleSection(body: String): Pair<String?, String?> {
    var vin: String? = null
    var plate: String? = null
    body.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("VIN:") -> vin = line.removePrefix("VIN:").trim().takeIf { it != "—" && it.isNotBlank() }
            line.startsWith("Гос. номер:") ->
                plate = line.removePrefix("Гос. номер:").trim().takeIf { it != "—" && it.isNotBlank() }
        }
    }
    return vin to plate
}

private fun parseMileageSection(body: String): List<MileageEntry> {
    // Строки формата «dd.MM.yyyy  87000 км» или «...  87000 км  — примечание».
    val regex = Regex("""^(\d{2}\.\d{2}\.\d{4}(?:\s+\d{2}:\d{2})?)\s+(\d+)\s*км\s*(?:[—–-]\s*(.*))?$""")
    val out = mutableListOf<MileageEntry>()
    body.lineSequence().forEach { raw ->
        val line = raw.trim()
        val m = regex.matchEntire(line) ?: return@forEach
        val date = parseDate(m.groupValues[1]) ?: return@forEach
        val odo = m.groupValues[2].toIntOrNull() ?: return@forEach
        val note = m.groupValues.getOrNull(3).orEmpty().trim()
        out += MileageEntry(
            vehicleProfileId = "", // подставится в CarViewModel
            date = date,
            odometerKm = odo,
            note = note,
        )
    }
    return out
}

private fun parseMaintenanceSection(body: String): List<MaintenanceEntry> {
    // Берём строки полной истории: «  dd.MM.yyyy  Моторное масло: 87000 км, заметка»
    // Заголовочные «• Тип [статус]» / «    Замена: ... → следующая ...» пропускаем,
    // т.к. они дублируют запись из полной истории.
    // Интервал пишется с версии, где его стали экспортировать; в старых файлах — нет.
    val regex = Regex("""^(\d{2}\.\d{2}\.\d{4}(?:\s+\d{2}:\d{2})?)\s+([^:]+?):\s*(\d+)\s*км(?:\s*·\s*интервал\s*(\d+)\s*км)?(?:\s*,\s*(.*))?$""")
    val out = mutableListOf<MaintenanceEntry>()
    body.lineSequence().forEach { raw ->
        val line = raw.trim()
        val m = regex.matchEntire(line) ?: return@forEach
        val date = parseDate(m.groupValues[1]) ?: return@forEach
        val typeName = m.groupValues[2].trim()
        val odo = m.groupValues[3].toIntOrNull() ?: return@forEach
        val interval = m.groupValues.getOrNull(4)?.toIntOrNull()
        val note = m.groupValues.getOrNull(5).orEmpty().trim()
        val type = MaintenanceType.values().firstOrNull { it.russianName == typeName }
            ?: MaintenanceType.OTHER
        out += MaintenanceEntry(
            vehicleProfileId = "",
            type = type,
            date = date,
            odometerKm = odo,
            intervalKm = interval ?: type.defaultIntervalKm,
            note = note,
        )
    }
    return out
}

private fun parseExpensesSection(body: String): List<ExpenseEntry> {
    // Формат: «  dd.MM.yyyy  Тип: SUMM ₽ · ODO км · L л · полный бак» (хвост опционален) + опц. следующая строка с описанием
    // (шесть пробелов отступа в экспорте).
    val regex = Regex("""^(\d{2}\.\d{2}\.\d{4}(?:\s+\d{2}:\d{2})?)\s+([^:]+?):\s*([\d.,]+)\s*₽(?:\s*·\s*(\d+)\s*км)?(?:\s*·\s*([\d.]+)\s*л)?(\s*·\s*полный бак)?\s*$""")
    val out = mutableListOf<ExpenseEntry>()
    val lines = body.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        val m = regex.matchEntire(line)
        if (m == null) { i++; continue }
        val date = parseDate(m.groupValues[1])
        if (date == null) { i++; continue }
        val typeName = m.groupValues[2].trim()
        val amount = m.groupValues[3].replace(',', '.').replace(" ", "").toFloatOrNull()
        if (amount == null) { i++; continue }
        val odo = m.groupValues.getOrNull(4)?.toIntOrNull()
        val liters = m.groupValues.getOrNull(5)?.toFloatOrNull()
        val fullTank = m.groupValues.getOrNull(6).orEmpty().isNotBlank()
        val type = ExpenseType.values().firstOrNull { it.russianName == typeName } ?: ExpenseType.OTHER

        // Описание идёт следующей строкой с большим отступом (>=4 пробела) и без даты.
        val next = lines.getOrNull(i + 1)?.let { if (it.startsWith("    ")) it.trim() else null }
        val description = next.orEmpty()

        out += ExpenseEntry(
            vehicleProfileId = "",
            date = date,
            type = type,
            description = description,
            amountRub = amount,
            odometerKm = odo,
            fuelLiters = liters,
            fullTank = fullTank,
        )
        i += if (next != null) 2 else 1
    }
    return out
}

/** «dd.MM.yyyy HH:mm» (новый формат) или «dd.MM.yyyy» (старые файлы). */
private fun parseDate(text: String): Long? = runCatching {
    val t = text.trim().replace(Regex("""\s+"""), " ")
    (if (t.length > 10) dateTimeFmt else dateFmt).parse(t)?.time
}.getOrNull()
