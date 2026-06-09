package com.obd2monitor.ui.screen.car

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.obd2monitor.domain.model.VehicleProfile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Текстовый отчёт по машине: профиль (VIN/марка/двигатель/...),
 * пробег (история записей + дельта), обслуживание (история + статус «следующее на …»),
 * расходы (по категориям + полный список). Кладётся в Downloads/OBD2/ через MediaStore
 * (точно так же, как saveTripTextReport в trip/TripExport.kt) и одновременно копируется
 * в буфер обмена. Возвращает content:// URI готовый для Intent.ACTION_SEND.
 */
fun saveCarReport(context: Context, state: CarUiState): Uri? {
    val text = buildCarReportText(state)
    copyToClipboard(context, text)
    val name = exportFileName(state.profile)
    return writeExport(context, name, "text/plain") { stream ->
        stream.write(text.toByteArray(Charsets.UTF_8))
    }
}

/** Делает Intent share для уже сохранённого URI. Если URI null — кидаем текст в EXTRA_TEXT. */
fun shareCarReport(context: Context, uri: Uri?, fallbackText: String? = null) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        if (uri != null) {
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else if (fallbackText != null) {
            putExtra(Intent.EXTRA_TEXT, fallbackText)
        }
        putExtra(Intent.EXTRA_SUBJECT, "Отчёт по авто (OBD2 Monitor)")
    }
    val chooser = Intent.createChooser(intent, "Поделиться отчётом").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

/**
 * Экспорт = только ПОЛЬЗОВАТЕЛЬСКИЕ данные. Расчётные величины (общая дистанция,
 * статус «скоро/просрочено», суммы по категориям, итог) намеренно не пишутся:
 * после re-импорта приложение их пересчитает по тем же данным. Это ключевой
 * инвариант: export → import должен быть идемпотентен, никакого «вторичного»
 * мусора в БД не оседает.
 */
private fun buildCarReportText(state: CarUiState): String {
    val df = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    val profile = state.profile
    return buildString {
        appendLine("ОТЧЁТ ПО АВТОМОБИЛЮ")
        appendLine()

        // ─── Профиль ───
        appendLine("=== АВТОМОБИЛЬ ===")
        if (profile == null) {
            appendLine("Профиль не выбран")
        } else {
            appendLine("Название: ${profile.name}")
            appendLine("Марка / модель: ${profile.make} ${profile.model}")
            if (profile.year > 0) appendLine("Год: ${profile.year}")
            appendLine("Двигатель: ${profile.engineType}")
            appendLine("Топливо: ${profile.fuelType.russianName}")
            appendLine("Бак: ${profile.tankCapacity} л")
            if (profile.fuelPrice > 0f) appendLine("Цена топлива: ${"%.2f".format(profile.fuelPrice)} ₽/л")
            appendLine("VIN: ${profile.vin.ifBlank { "—" }}")
            appendLine("Гос. номер: ${profile.licensePlate.ifBlank { "—" }}")
        }
        appendLine()

        // ─── Пробег ─── только введённые записи, без агрегатов
        appendLine("=== ПРОБЕГ ===")
        state.mileage.sortedBy { it.date }.forEach { m ->
            val note = if (m.note.isNotBlank()) " — ${m.note}" else ""
            appendLine("${df.format(Date(m.date))}  ${m.odometerKm} км$note")
        }
        appendLine()

        // ─── Обслуживание ─── только введённые записи, без статусов «через N км»
        appendLine("=== ОБСЛУЖИВАНИЕ ===")
        state.maintenance.sortedByDescending { it.date }.forEach { m ->
            val noteSuffix = if (m.note.isNotBlank()) ", ${m.note}" else ""
            appendLine("  ${df.format(Date(m.date))}  ${m.type.russianName}: ${m.odometerKm} км$noteSuffix")
        }
        appendLine()

        // ─── Расходы ─── только введённые записи, без «По категориям» и «Итого»
        appendLine("=== РАСХОДЫ ===")
        state.expenses.sortedByDescending { it.date }.forEach { e ->
            val odo = e.odometerKm?.let { " · $it км" }.orEmpty()
            val desc = if (e.description.isNotBlank()) "\n      ${e.description}" else ""
            appendLine("  ${df.format(Date(e.date))}  ${e.type.russianName}: ${"%.0f".format(e.amountRub)} ₽$odo$desc")
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("OBD2 car report", text))
}

private fun writeExport(
    context: Context,
    name: String,
    mimeType: String,
    writer: (OutputStream) -> Unit,
): Uri? {
    // Поведение совпадает с TripExport.writeExport: на Q+ пишем в Downloads/OBD2 через
    // MediaStore (получаем shareable content URI без FileProvider), на старых API —
    // в getExternalFilesDir(Downloads) и file://-URI.
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OBD2")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use(writer)
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        uri
    } else {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val dir = File(root, "OBD2").apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use(writer)
        Uri.fromFile(file)
    }
}

private fun exportFileName(profile: VehicleProfile?): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val slug = profile
        ?.let { "${it.make}_${it.model}" }
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9_]+"), "_")
        ?.trim('_')
        ?.ifBlank { null }
        ?: "car"
    return "obd2_car_${slug}_$stamp.txt"
}
