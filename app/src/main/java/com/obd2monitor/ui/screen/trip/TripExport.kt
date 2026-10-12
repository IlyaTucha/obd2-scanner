package com.obd2monitor.ui.screen.trip

import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.runtime.withFrameNanos
import com.obd2monitor.domain.model.Trip
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun saveTripTextReport(context: Context, state: TripSummaryUiState): Uri? {
    val text = buildTripReportText(state)
    copyTripTextReport(context, text)
    val name = exportFileName(state.trip, "txt")
    return writeExport(context, name, "text/plain") { stream ->
        stream.write(text.toByteArray(Charsets.UTF_8))
    }
}

suspend fun saveTripScreenScreenshot(
    context: Context,
    view: View,
    scrollState: ScrollState,
    state: TripSummaryUiState,
    captureBounds: ComposeRect? = null,
): Uri? {
    if (view.width <= 0 || view.height <= 0) return null
    val captureLeft = captureBounds?.left?.toInt()?.coerceIn(0, view.width - 1) ?: 0
    val captureTop = captureBounds?.top?.toInt()?.coerceIn(0, view.height - 1) ?: 0
    val maxCaptureWidth = (view.width - captureLeft).coerceAtLeast(1)
    val maxCaptureHeight = (view.height - captureTop).coerceAtLeast(1)
    val captureWidth = captureBounds?.width?.toInt()?.coerceIn(1, maxCaptureWidth) ?: maxCaptureWidth
    val captureHeight = captureBounds?.height?.toInt()?.coerceIn(1, maxCaptureHeight) ?: maxCaptureHeight
    val originalScroll = scrollState.value
    val viewportHeight = captureHeight
    val contentHeight = (scrollState.maxValue + viewportHeight).coerceAtLeast(viewportHeight)
    val bitmap = Bitmap.createBitmap(captureWidth, contentHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val offsets = buildList {
        var y = 0
        while (y < scrollState.maxValue) {
            add(y)
            y += viewportHeight
        }
        if (lastOrNull() != scrollState.maxValue) add(scrollState.maxValue)
    }.distinct()
    try {
        offsets.forEach { offset ->
            scrollState.scrollTo(offset)
            withFrameNanos { }
            val fullTile = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val fullTileCanvas = Canvas(fullTile)
            view.draw(fullTileCanvas)
            val tile = Bitmap.createBitmap(fullTile, captureLeft, captureTop, captureWidth, captureHeight)
            canvas.drawBitmap(tile, 0f, offset.toFloat(), null)
            fullTile.recycle()
            tile.recycle()
        }
    } finally {
        scrollState.scrollTo(originalScroll)
        withFrameNanos { }
    }
    val name = exportFileName(state.trip, "png").replace(".png", "_screen.png")
    return withContext(Dispatchers.IO) {
        writeExport(context, name, "image/png") { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }.also {
        bitmap.recycle()
    }
}

private fun copyTripTextReport(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("OBD2 report", text))
}

private fun buildTripReportText(state: TripSummaryUiState): String {
    val trip = state.trip ?: return "Сессия не найдена"
    val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
    val end = trip.endTime ?: System.currentTimeMillis()
    return buildString {
        appendLine("ОТЧЁТ СЕССИИ OBD2")
        appendLine("Период: ${df.format(Date(trip.startTime))} — ${df.format(Date(end))}")
        appendLine("Длительность: ${formatDuration(trip.durationMs)}")
        appendLine()
        appendLine("Сводка")
        appendLine("Расстояние: ${"%.2f".format(trip.distanceKm)} км")
        appendLine("Средняя скорость: ${"%.1f".format(trip.avgSpeedKmh)} км/ч")
        appendLine("Макс. скорость: ${"%.1f".format(trip.maxSpeedKmh)} км/ч")
        appendLine("Топливо: ${"%.3f".format(trip.fuelUsedLiters)} л")
        appendLine("Средний расход: ${"%.2f".format(trip.avgFuelConsumption)} л/100км")
        state.tripCostRub?.let { appendLine("Стоимость: ${"%.0f".format(it)} ₽") }
        state.peakAccelMs2?.let { appendLine("Пиковое ускорение: ${"%+.2f".format(it)} м/с²") }
        appendLine("GPS-точек: ${state.routePoints.size}")
        if (state.parameters.isNotEmpty()) {
            appendLine()
            appendLine("Параметры")
            state.parameters.forEach { p ->
                appendLine("${p.title}: мин ${"%.2f".format(p.min)} ${p.unit}, ср ${"%.2f".format(p.avg)} ${p.unit}, макс ${"%.2f".format(p.max)} ${p.unit}")
            }
        }
    }
}

private fun writeExport(
    context: Context,
    name: String,
    mimeType: String,
    writer: (OutputStream) -> Unit,
): Uri? {
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

private fun exportFileName(trip: Trip?, ext: String): String {
    val baseTime = trip?.startTime ?: System.currentTimeMillis()
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(baseTime))
    return "obd2_trip_$stamp.$ext"
}

private fun formatDuration(ms: Long): String {
    val totalSec = ceil(ms / 1000.0).toLong().coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
