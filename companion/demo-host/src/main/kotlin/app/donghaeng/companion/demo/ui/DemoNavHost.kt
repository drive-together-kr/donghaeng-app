package app.donghaeng.companion.demo.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.donghaeng.companion.demo.DemoSession
import app.donghaeng.companion.demo.ui.group.GroupScreen
import app.donghaeng.companion.demo.ui.home.HomeScreen
import app.donghaeng.companion.demo.ui.login.LoginScreen
import app.donghaeng.companion.demo.ui.scan.ScanScreen

/** 화면 경로 */
object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SCAN = "scan"
    const val GROUP = "group/{groupId}"

    fun group(groupId: String): String = "group/$groupId"
}

@Composable
fun DemoNavHost() {
    val navController = rememberNavController()
    val start = if (DemoSession.account.value == null) Routes.LOGIN else Routes.HOME

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onScan = { navController.navigate(Routes.SCAN) },
                onOpenGroup = { groupId -> navController.navigate(Routes.group(groupId)) },
                onLogout = {
                    DemoSession.logout()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SCAN) {
            ScanScreen(
                onBack = { navController.popBackStack() },
                onJoined = { result ->
                    navController.navigate(Routes.group(result.groupId)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }
        composable(
            route = Routes.GROUP,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) {
            GroupScreen(onBack = { navController.popBackStack() })
        }
    }
}
