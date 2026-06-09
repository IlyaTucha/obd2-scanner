package com.obd2monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.obd2monitor.BuildConfig
import com.obd2monitor.domain.model.GeoPoint
import com.obd2monitor.ui.theme.ColorAccent
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorPrimary
import com.obd2monitor.ui.theme.ColorSuccess
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.map.PolylineMapObject
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider

/**
 * Нативная Yandex MapKit карта в Compose.
 * Показывает маршрут поездки (polyline) + маркеры старт/финиш.
 *
 * Лимит ключа: 1000 уникальных пользователей в сутки.
 * Тайлы кэшируются MapKit'ом — повторный показ той же области не тратит запрос.
 *
 * Используется ТОЛЬКО на экране сводки поездки (TripSummaryScreen),
 * чтобы экономить запросы.
 */
@Composable
fun YandexMapView(
    points: List<GeoPoint>,
    modifier: Modifier = Modifier,
) {
    if (BuildConfig.YANDEX_API_KEY.isBlank()) {
        Box(
            modifier
                .background(ColorCard)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Карта недоступна: укажите YANDEX_API_KEY в local.properties",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (points.isEmpty()) {
        Box(
            modifier
                .background(ColorCard)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "GPS-точек нет — маршрут не записан",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val context = LocalContext.current
    val mapView = remember { MapView(context) }

    DisposableEffect(Unit) {
        MapKitFactory.getInstance().onStart()
        mapView.onStart()
        onDispose {
            mapView.onStop()
            MapKitFactory.getInstance().onStop()
        }
    }

    LaunchedEffect(points) {
        renderRoute(mapView, points)
    }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
}

private fun renderRoute(mapView: MapView, geoPoints: List<GeoPoint>) {
    val map = mapView.mapWindow.map
    map.mapObjects.clear()

    val mapKitPoints = geoPoints.map { Point(it.latitude, it.longitude) }
    if (mapKitPoints.size >= 2) {
        val polyline = Polyline(mapKitPoints)
        val line: PolylineMapObject = map.mapObjects.addPolyline(polyline)
        line.setStrokeColor(ColorAccent.toArgb())
        line.strokeWidth = 4f
        line.outlineColor = 0x33000000
        line.outlineWidth = 1f
    }

    // Маркеры старт/финиш через простые ImageProvider из drawable circle.
    addMarker(mapView, mapKitPoints.first(), color = ColorSuccess.toArgb())
    if (mapKitPoints.size > 1) {
        addMarker(mapView, mapKitPoints.last(), color = ColorPrimary.toArgb())
    }

    // Подгоняем камеру.
    val center = mapKitPoints[mapKitPoints.size / 2]
    mapView.mapWindow.map.move(
        CameraPosition(center, computeZoom(mapKitPoints), 0f, 0f)
    )
}

private fun addMarker(mapView: MapView, point: Point, color: Int) {
    val bmp = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(16f, 16f, 12f, paint)
    paint.color = 0xFFFFFFFF.toInt()
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawCircle(16f, 16f, 12f, paint)
    val placemark: PlacemarkMapObject = mapView.mapWindow.map.mapObjects.addPlacemark(point)
    placemark.setIcon(ImageProvider.fromBitmap(bmp), IconStyle())
}

/** Простая эвристика зума: чем больше bbox — тем меньше zoom. */
private fun computeZoom(points: List<Point>): Float {
    if (points.size < 2) return 14f
    var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
    points.forEach {
        if (it.latitude < minLat) minLat = it.latitude
        if (it.latitude > maxLat) maxLat = it.latitude
        if (it.longitude < minLon) minLon = it.longitude
        if (it.longitude > maxLon) maxLon = it.longitude
    }
    val deltaDeg = maxOf(maxLat - minLat, maxLon - minLon)
    return when {
        deltaDeg > 1.0 -> 7f
        deltaDeg > 0.3 -> 9f
        deltaDeg > 0.1 -> 11f
        deltaDeg > 0.03 -> 13f
        else -> 15f
    }
}
