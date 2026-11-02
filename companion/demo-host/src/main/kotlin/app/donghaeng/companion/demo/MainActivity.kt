package app.donghaeng.companion.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.donghaeng.companion.demo.donghaeng.DongHaengStore
import app.donghaeng.companion.demo.ui.KiaApp
import app.donghaeng.companion.demo.ui.theme.KiaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 알림 채널·시스템 알림 출구는 DemoApplication 이 Application 컨텍스트로 등록한다(D086 — 여기서 람다를 넣으면 끝난 Activity 를 붙잡았다)
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
