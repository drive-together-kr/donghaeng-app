package app.donghaeng.companion.demo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.donghaeng.companion.demo.DemoSession
import app.donghaeng.companion.demo.donghaeng.DongHaengMainScreen
import app.donghaeng.companion.demo.donghaeng.DongHaengSettingsScreen
import app.donghaeng.companion.demo.donghaeng.NotificationsScreen
import app.donghaeng.companion.demo.donghaeng.ScanScreen
import app.donghaeng.companion.demo.feature.login.LoginScreen
import app.donghaeng.companion.demo.ui.components.demoToast

/** 전체 화면 경로. 탭 5개는 [MainScreen] 안에서 바뀐다(탭 상태 유지) */
object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val SCAN = "scan"
    const val DONGHAENG = "donghaeng"
    const val DONGHAENG_SETTINGS = "donghaeng/settings"
    const val NOTIFICATIONS = "notifications"
}

@Composable
fun KiaApp() {
    val navController = rememberNavController()
    val account by DemoSession.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val start = if (account == null) Routes.LOGIN else Routes.MAIN

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.MAIN) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
            )
        }
        composable(Routes.MAIN) {
            MainScreen(
                onScan = { navController.navigate(Routes.SCAN) },
                onOpenDongHaeng = { navController.navigate(Routes.DONGHAENG) },
                onOpenDongHaengSettings = { navController.navigate(Routes.DONGHAENG_SETTINGS) },
                onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                onProfile = { demoToast(context, "프로필") },
                onLogout = {
                    DemoSession.logout()
                    navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.SCAN) {
            ScanScreen(
                onBack = { navController.popBackStack() },
                onJoined = { navController.popBackStack(Routes.MAIN, inclusive = false) },
            )
        }
        composable(Routes.DONGHAENG) {
            DongHaengMainScreen(
                onBack = { navController.popBackStack() },
                onScan = { navController.navigate(Routes.SCAN) },
                onOpenSettings = { navController.navigate(Routes.DONGHAENG_SETTINGS) },
            )
        }
        composable(Routes.DONGHAENG_SETTINGS) {
            DongHaengSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.NOTIFICATIONS) {
            NotificationsScreen(onBack = { navController.popBackStack() })
        }
    }
}
