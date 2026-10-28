package app.donghaeng.car.debug

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.feature.drive.FloatingPanel
import app.donghaeng.car.feature.drive.FloatingPanelSamples
import app.donghaeng.car.ui.theme.DongHaengTheme

/**
 * 시안용(debug): 순정 내비 위에 플로팅 패널([FloatingPanel])을 TYPE_APPLICATION_OVERLAY 로 띄운다.
 * `am broadcast … --es action panel --es variant base|recording|confirm|playing|noevent` / `--ez on false` 로 닫기.
 * 위치: 오른쪽 위(내비 2D 버튼 아래) — 내비 TBT(왼쪽 위)·지도 중심·하단 버튼을 피한다.
 */
class FloatingPanelProbeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var view: ComposeView? = null
    private val variant = mutableStateOf("base")

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("on", true) == false) { remove(); stopSelf(); return START_NOT_STICKY }
        variant.value = intent?.getStringExtra("variant") ?: "base"
        show()
        return START_NOT_STICKY
    }

    private fun show() {
        if (view != null) { DhLog.i("probe panel: variant=${variant.value}"); return }
        if (!Settings.canDrawOverlays(this)) { DhLog.w("probe panel: 오버레이 권한 없음"); stopSelf(); return }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingPanelProbeService)
            setViewTreeSavedStateRegistryOwner(this@FloatingPanelProbeService)
            setContent { DongHaengTheme(darkTheme = true) { FloatingPanel(FloatingPanelSamples.of(variant.value)) } }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = (110 * density).toInt(); y = (190 * density).toInt() }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        runCatching { wm.addView(compose, lp); view = compose; DhLog.i("probe panel: shown variant=${variant.value}") }
            .onFailure { DhLog.w("probe panel: addView 실패 ${it.javaClass.simpleName}: ${it.message?.take(100)}"); stopSelf() }
    }

    private fun remove() {
        view?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        view = null
        DhLog.i("probe panel: removed")
    }

    override fun onDestroy() {
        remove()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}
