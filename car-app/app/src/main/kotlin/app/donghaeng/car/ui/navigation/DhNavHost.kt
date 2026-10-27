package app.donghaeng.car.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.donghaeng.car.feature.creategroup.CreateGroupScreen
import app.donghaeng.car.feature.events.EventsScreen
import app.donghaeng.car.feature.groupdrive.GroupDriveScreen
import app.donghaeng.car.feature.home.HomeRoute
import app.donghaeng.car.feature.pairing.PairingScreen
import app.donghaeng.car.feature.presets.PresetsScreen
import app.donghaeng.car.feature.settings.SettingsScreen

/** 화면 경로 상수. 인자가 있는 화면은 `"$ROUTE/{id}"` 형태로 확장한다. */
object DhRoutes {
    const val HOME = "home"
    const val PAIRING = "pairing"            // T12
    const val CREATE_GROUP = "create_group"  // T17
    const val GROUP_DRIVE = "group_drive"    // T25
    const val EVENTS = "events"              // T26
    const val PRESETS = "presets"            // T53
    const val SETTINGS = "settings"
}

@Composable
fun DhNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = DhRoutes.HOME) {
        composable(DhRoutes.HOME) {
            HomeRoute(
                onPairPhone = { navController.navigate(DhRoutes.PAIRING) },
                onStartGroup = { navController.navigate(DhRoutes.CREATE_GROUP) },
                onOpenSettings = { navController.navigate(DhRoutes.SETTINGS) },
            )
        }
        composable(DhRoutes.PAIRING) { PairingScreen() }
        composable(DhRoutes.CREATE_GROUP) { CreateGroupScreen() }
        composable(DhRoutes.GROUP_DRIVE) { GroupDriveScreen() }
        composable(DhRoutes.EVENTS) { EventsScreen() }
        composable(DhRoutes.PRESETS) { PresetsScreen() }
        composable(DhRoutes.SETTINGS) { SettingsScreen() }
    }
}
