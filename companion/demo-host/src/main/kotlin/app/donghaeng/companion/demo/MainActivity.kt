package app.donghaeng.companion.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.donghaeng.companion.demo.donghaeng.DongHaengNotifier
import app.donghaeng.companion.demo.donghaeng.DongHaengStore
import app.donghaeng.companion.demo.ui.KiaApp
import app.donghaeng.companion.demo.ui.theme.KiaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DongHaengNotifier.ensureChannel(this)
        DongHaengStore.notifier = { DongHaengNotifier.show(applicationContext, it) }
        setContent {
            KiaTheme {
                KiaApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DongHaengStore.appInForeground = true
    }

    override fun onStop() {
        super.onStop()
        // 백그라운드에서 들어오는 그룹 이벤트는 시스템 알림으로 (삽입 지점 F)
        DongHaengStore.appInForeground = false
    }
}
