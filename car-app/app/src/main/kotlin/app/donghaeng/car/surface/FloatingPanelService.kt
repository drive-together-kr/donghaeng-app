package app.donghaeng.car.surface

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.donghaeng.car.R
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.feature.drive.FloatingPanel
import app.donghaeng.car.ui.theme.DongHaengTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 순정 내비 위 플로팅 패널 (docs/05 §5.2). TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCH_MODAL —
 * 패널 밖 터치는 내비로 그대로 간다. 시작·종료는 [DriveSurfaceController] 가 [SurfaceState.panelVisible] 을 보고 정한다.
 *
 * - 데이터: [SurfaceState.panelUi](Hilt EntryPoint). 크기: [PanelLayout.geometry](폭 ≤ 내비 22%, 높이 ≤ 30%).
 * - 위치: 저장된 모서리(기본 오른쪽 위). **정차 중에만** 드래그 → 놓으면 가장 가까운 모서리로 스냅·저장. 주행 중에는 드래그를 무시하고 [무전] 탭만 받는다.
 * - 주간/야간: Configuration.uiMode 야간이면 짙은 패널, 아니면 밝은 패널.
 */
class FloatingPanelService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun surfaceState(): SurfaceState
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var state: SurfaceState
    private lateinit var wm: WindowManager
    private var frame: PanelDragFrame? = null
    private var params: WindowManager.LayoutParams? = null
    private var scope: CoroutineScope? = null

    private val night = mutableStateOf(false)
    private val geometry = mutableStateOf(PanelLayout.Geometry(375, 432, 1f))
    private var corner = PanelCorner.TOP_END
    private var dragOrigin: Pair<Int, Int>? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        state = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).surfaceState()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        night.value = isNight(resources.configuration)
        geometry.value = computeGeometry()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        show()
        return START_NOT_STICKY
    }

    private fun show() {
        if (frame != null) return
        if (!Settings.canDrawOverlays(this)) {
            DhLog.w("surface panel: 오버레이 권한 없음 — 표시 안 함(알림 카드 + 음성 모드)")
            stopSelf()
            return
        }
        val owner = this
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { PanelContent() }
        }
        val f = PanelDragFrame(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            listener = dragListener
            dragEnabled = !state.isDriving.value
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        corner = state.corner.value
        place(lp, corner)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        try {
            wm.addView(f, lp)
        } catch (e: Exception) {
            DhLog.w("surface panel: addView 실패 ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            stopSelf()
            return
        }
        frame = f
        params = lp
        shown = true
        val g = geometry.value
        DhLog.i("surface panel: shown corner=$corner night=${night.value} widthPx=${g.widthPx} maxHeightPx=${g.maxHeightPx} scale=${"%.2f".format(g.scale)} display=${displaySize().let { "${it.first}x${it.second}" }}")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { sc ->
            sc.launch {
                state.corner.collect { c ->
                    if (c != corner && dragOrigin == null) {
                        corner = c
                        relayout()
                    }
                }
            }
            sc.launch {
                state.isDriving.collect { driving ->
                    f.dragEnabled = !driving
                    DhLog.d("surface panel: drag ${if (driving) "잠금(주행 중)" else "허용(정차)"}")
                }
            }
        }
    }

    @Composable
    private fun PanelContent() {
        val ui by state.panelUi.collectAsState()
        val g = geometry.value
        val dark = night.value
        val density = LocalDensity.current
        DongHaengTheme(darkTheme = dark) {
            ui?.let {
                FloatingPanel(
                    it,
                    light = !dark,
                    width = with(density) { g.widthPx.toDp() },
                    maxHeight = with(density) { g.maxHeightPx.toDp() },
                    scale = g.scale,
                    onPtt = ::onPtt,
                )
            }
        }
    }

    private fun onPtt() {
        DhLog.i("surface: ptt tapped (F16 미구현)")
        Toast.makeText(this, R.string.surface_ptt_soon, Toast.LENGTH_SHORT).show()
    }

    private val dragListener = object : PanelDragFrame.Listener {
        override fun onDragStart() {
            val lp = params ?: return
            dragOrigin = lp.x to lp.y
            DhLog.i("surface panel: drag start from $corner")
        }

        override fun onDrag(dx: Float, dy: Float) {
            val lp = params ?: return
            val (ox, oy) = dragOrigin ?: return
            lp.x = ox + (if (corner.isEnd) -dx else dx).toInt()
            lp.y = oy + (if (corner.isBottom) -dy else dy).toInt()
            frame?.let { runCatching { wm.updateViewLayout(it, lp) } }
        }

        override fun onDragEnd() {
            val lp = params ?: return
            val f = frame ?: return
            val (dw, dh) = displaySize()
            val cx = if (corner.isEnd) dw - lp.x - f.width / 2f else lp.x + f.width / 2f
            val cy = if (corner.isBottom) dh - lp.y - f.height / 2f else lp.y + f.height / 2f
            val target = PanelLayout.nearestCorner(cx, cy, dw, dh)
            dragOrigin = null
            corner = target
            relayout()
            state.setCorner(target)
        }
    }

    private fun place(lp: WindowManager.LayoutParams, c: PanelCorner) {
        val (dw, dh) = displaySize()
        val (x, y) = PanelLayout.offsetPx(c, dw, dh, resources.displayMetrics.density)
        lp.gravity = (if (c.isBottom) Gravity.BOTTOM else Gravity.TOP) or (if (c.isEnd) Gravity.END else Gravity.START)
        lp.x = x
        lp.y = y
    }

    private fun relayout() {
        val lp = params ?: return
        val f = frame ?: return
        place(lp, corner)
        runCatching { wm.updateViewLayout(f, lp) }.onFailure { DhLog.w("surface panel: relayout 실패 ${it.javaClass.simpleName}") }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        night.value = isNight(newConfig)
        geometry.value = computeGeometry()
        relayout()
        DhLog.i("surface panel: config night=${night.value} widthPx=${geometry.value.widthPx}")
    }

    private fun computeGeometry(): PanelLayout.Geometry {
        val (dw, dh) = displaySize()
        return PanelLayout.geometry(dw, dh, resources.displayMetrics.density)
    }

    private fun displaySize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val b = wm.maximumWindowMetrics.bounds
        b.width() to b.height()
    } else {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        m.widthPixels to m.heightPixels
    }

    private fun isNight(c: Configuration): Boolean = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        frame?.let { runCatching { wm.removeView(it) } }
        if (frame != null) DhLog.i("surface panel: removed")
        frame = null
        params = null
        shown = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    companion object {
        /** 패널 창이 지금 붙어 있는가(디버그 `surface` 액션) */
        @Volatile
        var shown: Boolean = false
            private set
    }
}

/**
 * 패널 창의 뿌리 뷰 — [dragEnabled] 일 때 터치가 슬롭을 넘으면 가로채 화면 좌표(rawX/rawY) 기준 이동량을 [Listener] 로 넘긴다.
 * 짧은 탭은 그대로 Compose([무전] 버튼)로 간다.
 */
class PanelDragFrame(context: Context) : FrameLayout(context) {
    interface Listener {
        fun onDragStart()
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
    }

    var listener: Listener? = null

    @Volatile
    var dragEnabled: Boolean = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = track(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return dragEnabled || dragging
    }

    private fun track(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!dragging && dragEnabled && hypot(dx, dy) > slop) {
                    dragging = true
                    listener?.onDragStart()
                }
                if (dragging) listener?.onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                listener?.onDragEnd()
                return true
            }
        }
        return dragging
    }
}
