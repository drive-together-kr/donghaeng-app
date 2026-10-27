package app.donghaeng.car

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.donghaeng.car.ui.navigation.DhNavHost
import app.donghaeng.car.ui.theme.DongHaengTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 단일 액티비티. 화면 전환은 Navigation Compose(`DhNavHost`)가 담당한다.
 *
 * 인텐트 extra `screen`(home | drive | events | create | settings | diagnostics)이 오면 그 화면으로 이동한다 — 시연 스크립트(`tools/demo`, debug 리시버 `open`)와
 * 알림 탭에서 쓴다. 없으면 홈.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestedRoute = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedRoute.value = routeOf(intent)
        setContent {
            DongHaengTheme {
                DhNavHost(requestedRoute = requestedRoute, onRouteConsumed = { requestedRoute.value = null })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute.value = routeOf(intent)
    }

    private fun routeOf(intent: android.content.Intent?): String? = when (intent?.getStringExtra(EXTRA_SCREEN)) {
        "home" -> app.donghaeng.car.ui.navigation.DhRoutes.HOME
        "drive", "group_drive" -> app.donghaeng.car.ui.navigation.DhRoutes.GROUP_DRIVE
        "events" -> app.donghaeng.car.ui.navigation.DhRoutes.EVENTS
        "create", "create_group" -> app.donghaeng.car.ui.navigation.DhRoutes.CREATE_GROUP
        "settings" -> app.donghaeng.car.ui.navigation.DhRoutes.SETTINGS
        "diagnostics", "diag" -> app.donghaeng.car.ui.navigation.DhRoutes.DIAGNOSTICS
        else -> null
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
    }
}
