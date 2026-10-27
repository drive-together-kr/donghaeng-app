package app.donghaeng.companion.demo.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.theme.KiaColors
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

/** 지도 위 마커 한 개 — 내 차(파란 핀) 또는 동행 그룹 차량(멤버 색 원 + 이름표) */
data class MapMarker(
    val id: String,
    val lat: Double,
    val lon: Double,
    val label: String,
    val color: Color,
    val isLeader: Boolean = false,
    val isMine: Boolean = false,
)

/**
 * osmdroid 지도(OpenStreetMap 타일, API 키 불필요). 기아 앱 지도 탭 느낌에 맞춰 타일을 어둡게 반전한다.
 * 마커 목록이 바뀌면 그 자리에서 갱신한다(지도는 재생성하지 않음).
 *
 * @param followMarkers true 면 마커 전체가 보이도록 화면을 맞춘다(동행 칩 켰을 때)
 */
@Composable
fun OsmMap(
    center: GeoPoint,
    zoom: Double,
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    darkTiles: Boolean = true,
    followMarkers: Boolean = false,
) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid-tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            if (darkTiles) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            controller.setZoom(zoom)
            controller.setCenter(center)
        }
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }
    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            view.overlays.removeAll { it is Marker }
            markers.forEach { m ->
                val marker = Marker(view).apply {
                    position = GeoPoint(m.lat, m.lon)
                    setAnchor(Marker.ANCHOR_CENTER, if (m.isMine) Marker.ANCHOR_BOTTOM else Marker.ANCHOR_CENTER)
                    icon = if (m.isMine) pinDrawable(context) else dotDrawable(context, m)
                    title = m.label
                    setInfoWindow(null)
                }
                view.overlays.add(marker)
            }
            if (followMarkers && markers.isNotEmpty()) {
                val lats = markers.map { it.lat }
                val lons = markers.map { it.lon }
                val box = org.osmdroid.util.BoundingBox(lats.max() + 0.004, lons.max() + 0.004, lats.min() - 0.004, lons.min() - 0.004)
                view.post { runCatching { view.zoomToBoundingBox(box, true, 80) } }
            }
            view.invalidate()
        },
    )
}

/** 내 차 핀 — 파란 물방울 + 흰 차 아이콘 */
private fun pinDrawable(context: android.content.Context): Drawable {
    val car = ResourcesCompat.getDrawable(context.resources, R.drawable.ms_directions_car, context.theme)!!.mutate()
    car.setTint(android.graphics.Color.WHITE)
    return object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KiaColors.MapBlue.toArgb() }
        private val size = (36 * context.resources.displayMetrics.density).toInt()
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = (size * 1.35f).toInt()
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val r = w / 2f
            canvas.drawCircle(bounds.left + r, bounds.top + r, r, paint)
            val path = android.graphics.Path().apply {
                moveTo(bounds.left + r * 0.25f, bounds.top + r * 1.6f)
                lineTo(bounds.left + r, bounds.bottom.toFloat())
                lineTo(bounds.left + r * 1.75f, bounds.top + r * 1.6f)
                close()
            }
            canvas.drawPath(path, paint)
            val inset = (r * 0.45f).toInt()
            car.setBounds(bounds.left + inset, bounds.top + inset, bounds.left + w.toInt() - inset, bounds.top + w.toInt() - inset)
            car.draw(canvas)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}

/** 그룹 차량 — 멤버 색 원(선두는 라임 테두리) + 아래 이름표 */
private fun dotDrawable(context: android.content.Context, m: MapMarker): Drawable {
    val density = context.resources.displayMetrics.density
    return object : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = m.color.toArgb() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (m.isLeader) KiaColors.Accent.toArgb() else android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val labelBg = GradientDrawable().apply {
            cornerRadius = 8f * density
            setColor(KiaColors.Card.toArgb())
        }
        private val dot = (22 * density).toInt()
        private val labelW = (text.measureText(m.label) + 16 * density).toInt()
        private val labelH = (20 * density).toInt()
        override fun getIntrinsicWidth() = maxOf(dot, labelW)
        override fun getIntrinsicHeight() = dot + labelH + (4 * density).toInt()
        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX()
            val top = bounds.top.toFloat()
            canvas.drawCircle(cx, top + dot / 2f, dot / 2f - ring.strokeWidth / 2f, fill)
            canvas.drawCircle(cx, top + dot / 2f, dot / 2f - ring.strokeWidth / 2f, ring)
            val lt = (top + dot + 4 * density).toInt()
            labelBg.setBounds((cx - labelW / 2f).toInt(), lt, (cx + labelW / 2f).toInt(), lt + labelH)
            labelBg.draw(canvas)
            canvas.drawText(m.label, cx, lt + labelH * 0.7f, text)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
