package com.obd2monitor.ui.components

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.obd2monitor.BuildConfig
import com.obd2monitor.domain.model.GeoPoint
import com.obd2monitor.ui.theme.ColorAccent
import com.obd2monitor.ui.theme.ColorCard
import com.obd2monitor.ui.theme.ColorPrimary
import com.obd2monitor.ui.theme.ColorSuccess
import com.obd2monitor.ui.theme.ColorTextSecondary
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.map.PolylineMapObject
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider

/**
 * Нативная карта Yandex MapKit в Compose: линия маршрута + маркеры старта и финиша.
 * Используется на вкладке «Сессия» (живой маршрут) и в отчёте сессии.
 *
 * Тайлы кэшируются MapKit'ом — повторный показ той же области не тратит запрос.
 */
@Composable
fun YandexMapView(
    points: List<GeoPoint>,
    modifier: Modifier = Modifier,
    emptyText: String = "Маршрут появится после первых точек GPS",
    /** Красный маркер «машина была здесь»; камера переезжает к нему. */
    highlight: GeoPoint? = null,
) {
    if (BuildConfig.YANDEX_API_KEY.isBlank() || points.isEmpty()) {
        Box(modifier.background(ColorCard).padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                if (BuildConfig.YANDEX_API_KEY.isBlank()) "Карта недоступна: не задан ключ Yandex MapKit" else emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = ColorTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    val route = remember(mapView) { RouteLayer(mapView) }
    // Карта рисуется на отдельной GL-поверхности, которая не участвует в анимациях
    // Compose: при уходе с экрана она «висела» поверх списка до конца перехода.
    // Поэтому прячем её сразу, как только экран перестаёт быть активным (ON_PAUSE —
    // начало перехода назад/вперёд), и показываем снова на ON_RESUME.
    var shown by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    // MapView живёт по жизненному циклу экрана: при сворачивании приложения карта
    // останавливается, при возврате — запускается снова.
    DisposableEffect(lifecycleOwner, mapView) {
        var running = false
        fun startMap() { if (!running) { running = true; MapKitLifecycle.acquire(); mapView.onStart() } }
        fun stopMap() { if (running) { running = false; mapView.onStop(); MapKitLifecycle.release() } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startMap()
                Lifecycle.Event.ON_RESUME -> shown = true
                Lifecycle.Event.ON_PAUSE -> shown = false
                Lifecycle.Event.ON_STOP -> stopMap()
                else -> Unit
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startMap()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopMap()
        }
    }

    AndroidView(
        // Карта стоит внутри прокручиваемой страницы и пейджера вкладок: раньше жест
        // одним пальцем забирала прокрутка, и карту двигали только двумя. Обёртка при
        // касании запрещает родителям перехватывать жест — Compose отдаёт его карте
        // целиком: одним пальцем она перемещается, двумя масштабируется.
        factory = { ctx ->
            object : FrameLayout(ctx) {
                override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
                    return super.dispatchTouchEvent(ev)
                }
            }.apply {
                (mapView.parent as? ViewGroup)?.removeView(mapView)
                addView(mapView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        modifier = modifier.fillMaxSize(),
        update = {
            mapView.visibility = if (shown) View.VISIBLE else View.INVISIBLE
            route.render(points)
            route.highlight(highlight)
        },
    )
}

/**
 * MapKitFactory.onStart/onStop — глобальные на весь процесс. Если две карты живут
 * одновременно (вкладка «Сессия» и открытый поверх отчёт), stop одной не должен
 * останавливать другую: раньше именно так карта отчёта оставалась пустой.
 */
private object MapKitLifecycle {
    private var users = 0
    fun acquire() { if (users++ == 0) MapKitFactory.getInstance().onStart() }
    fun release() { if (users > 0 && --users == 0) MapKitFactory.getInstance().onStop() }
}

/** Линия маршрута и маркеры. Объекты карты создаются один раз и обновляются на месте. */
private class RouteLayer(private val mapView: MapView) {
    private var line: PolylineMapObject? = null
    private var start: PlacemarkMapObject? = null
    private var finish: PlacemarkMapObject? = null
    private var renderedPoints: List<GeoPoint>? = null
    private var cameraFitted = false
    private var marker: PlacemarkMapObject? = null
    private var highlighted: GeoPoint? = null

    fun highlight(p: GeoPoint?) {
        if (p == highlighted) return
        highlighted = p
        val m = marker
        if (p == null) {
            m?.isVisible = false
            return
        }
        val pt = Point(p.latitude, p.longitude)
        val mk = m ?: mapView.mapWindow.map.mapObjects.addPlacemark(pt).also {
            it.setIcon(dot(HIGHLIGHT_COLOR, size = 48), IconStyle())
            it.zIndex = 10f
            marker = it
        }
        mk.geometry = pt
        mk.isVisible = true
        // Сразу после создания у карты нулевой размер — ждём разметку, как в fitCamera.
        mapView.doOnLayout {
            val map = mapView.mapWindow.map
            val zoom = map.cameraPosition.zoom.coerceAtLeast(15f)
            map.move(CameraPosition(pt, zoom, 0f, 0f), Animation(Animation.Type.SMOOTH, 0.3f), null)
        }
    }

    fun render(points: List<GeoPoint>) {
        if (points === renderedPoints || points.isEmpty()) return
        renderedPoints = points
        val objects = mapView.mapWindow.map.mapObjects
        val mk = points.map { Point(it.latitude, it.longitude) }

        if (mk.size >= 2) {
            val l = line ?: objects.addPolyline(Polyline(mk)).also {
                it.setStrokeColor(ColorAccent.toArgb())
                it.strokeWidth = 4f
                it.outlineColor = 0x33000000
                it.outlineWidth = 1f
                line = it
            }
            l.geometry = Polyline(mk)
        }
        val s = start ?: objects.addPlacemark(mk.first()).also {
            it.setIcon(dot(ColorSuccess.toArgb()), IconStyle())
            start = it
        }
        s.geometry = mk.first()
        if (mk.size > 1) {
            val f = finish ?: objects.addPlacemark(mk.last()).also {
                it.setIcon(dot(ColorPrimary.toArgb()), IconStyle())
                finish = it
            }
            f.geometry = mk.last()
        }

        // Камеру подгоняем после разметки: до неё у карты нулевой размер, и
        // вычисленная позиция получалась неверной (маршрут «вне кадра»).
        // При выбранной точке камеру держит маркер, а не весь маршрут.
        mapView.doOnLayout { if (highlighted == null) fitCamera(mk, animate = cameraFitted) }
    }

    private fun fitCamera(mk: List<Point>, animate: Boolean) {
        val map = mapView.mapWindow.map
        val position = if (mk.size < 2) {
            CameraPosition(mk.first(), 15f, 0f, 0f)
        } else {
            val box = BoundingBox(
                Point(mk.minOf { it.latitude }, mk.minOf { it.longitude }),
                Point(mk.maxOf { it.latitude }, mk.maxOf { it.longitude }),
            )
            val fitted = map.cameraPosition(Geometry.fromBoundingBox(box))
            // Небольшой отступ от краёв и не ближе 17 зума для коротких маршрутов.
            CameraPosition(fitted.target, (fitted.zoom - 0.6f).coerceAtMost(17f), 0f, 0f)
        }
        if (animate) map.move(position, Animation(Animation.Type.SMOOTH, 0.4f), null)
        else map.move(position)
        cameraFitted = true
    }

    private fun dot(color: Int, size: Int = 32): ImageProvider {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = android.graphics.Paint.Style.FILL
        }
        val c = size / 2f
        val r = size * 0.375f
        canvas.drawCircle(c, c, r, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = size / 10f
        canvas.drawCircle(c, c, r, paint)
        return ImageProvider.fromBitmap(bmp)
    }

    private companion object {
        /** Ярко-красный: на карте не путается ни с линией маршрута, ни со стартом/финишем. */
        const val HIGHLIGHT_COLOR = 0xFFE53935.toInt()
    }
}
