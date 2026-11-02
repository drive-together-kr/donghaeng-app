package app.donghaeng.car.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.donghaeng.car.feature.creategroup.CreateGroupRoute
import app.donghaeng.car.feature.drive.DemoTourRoute
import app.donghaeng.car.feature.events.EventsRoute
import app.donghaeng.car.feature.groupdrive.GroupDriveRoute
import app.donghaeng.car.feature.home.HomeRoute
import app.donghaeng.car.feature.pairing.PairingRoute
import app.donghaeng.car.feature.permissions.LocationPermissionGate
import app.donghaeng.car.feature.presets.PresetsScreen
import app.donghaeng.car.feature.settings.DiagnosticsRoute
import app.donghaeng.car.feature.settings.SettingsRoute

/** 화면 경로 상수. 인자가 있는 화면은 `"$ROUTE/{id}"` 형태로 확장한다. */
object DhRoutes {
    const val HOME = "home"
    const val PAIRING = "pairing"            // T12
    const val CREATE_GROUP = "create_group"  // T17
    const val GROUP_DRIVE = "group_drive"    // T25
    const val EVENTS = "events"              // T26
    const val PRESETS = "presets"            // T53
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"    // T11 확인용 화면
    const val DESIGN = "design"              // 주행 대시보드 시안(변형 인자) — 인텐트 extra screen=design&variant=…
    const val TOUR = "tour"                  // 예시 화면 둘러보기(폰 없이 보는 예시 데이터, 2-1)

    /** 권한 요청(위치·마이크·알림)을 미루는 화면 — 초대 QR 이 시스템 권한 창에 가리지 않게 */
    fun defersPermissionAsk(route: String?): Boolean = route == CREATE_GROUP || route == TOUR || route?.startsWith(DESIGN) == true
}

@Composable
fun DhNavHost(
    navController: NavHostController = rememberNavController(),
    /** MainActivity 인텐트 extra `screen` 으로 요청된 화면(T30 시연·알림). 소비 후 [onRouteConsumed] */
    requestedRoute: kotlinx.coroutines.flow.StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    onRouteConsumed: () -> Unit = {},
) {
    val entry by navController.currentBackStackEntryAsState()
    // T21: 활성 그룹 + 정차 시 위치·알림·마이크 권한 1회 요청 — 왜 필요한지 안내 한 장 뒤에. 초대 QR 화면에서는 미룬다
    LocationPermissionGate(deferred = DhRoutes.defersPermissionAsk(entry?.destination?.route))
    val requested by requestedRoute.collectAsState()
    LaunchedEffect(requested) {
        val route = requested ?: return@LaunchedEffect
        if (route == DhRoutes.HOME) navController.popBackStack(DhRoutes.HOME, inclusive = false)
        else navController.navigate(route) { launchSingleTop = true; popUpTo(DhRoutes.HOME) }
        onRouteConsumed()
    }
    NavHost(navController = navController, startDestination = DhRoutes.HOME) {
        composable(DhRoutes.HOME) {
            HomeRoute(
                onPairPhone = { navController.navigate(DhRoutes.PAIRING) },
                onStartGroup = { navController.navigate(DhRoutes.CREATE_GROUP) },
                onOpenGroup = { navController.navigate(DhRoutes.GROUP_DRIVE) },
                onOpenSettings = { navController.navigate(DhRoutes.SETTINGS) },
                onOpenDiagnostics = { navController.navigate(DhRoutes.DIAGNOSTICS) },
                onOpenTour = { navController.navigate(DhRoutes.TOUR) { launchSingleTop = true } },
            )
        }
        composable(DhRoutes.PAIRING) {
            PairingRoute(
                onBack = { navController.popBackStack() },
                // 페어링 성공 → 홈으로 자동 복귀(홈은 TokenStore 를 구독하므로 '연결됨' 으로 바뀌어 있다)
                onPaired = { navController.popBackStack(DhRoutes.HOME, inclusive = false) },
                onStartGroup = { navController.navigate(DhRoutes.CREATE_GROUP) { popUpTo(DhRoutes.HOME) } },
                onOpenGroup = { navController.navigate(DhRoutes.GROUP_DRIVE) { popUpTo(DhRoutes.HOME) } },
                onOpenTour = { navController.navigate(DhRoutes.TOUR) { launchSingleTop = true } },
            )
        }
        composable(DhRoutes.CREATE_GROUP) {
            CreateGroupRoute(
                onBack = { navController.popBackStack() },
                onOpenDrive = { navController.navigate(DhRoutes.GROUP_DRIVE) { popUpTo(DhRoutes.HOME) } },
            )
        }
        composable(DhRoutes.GROUP_DRIVE) {
            GroupDriveRoute(
                onBack = { navController.popBackStack(DhRoutes.HOME, inclusive = false) },
                onOpenInvite = { navController.navigate(DhRoutes.CREATE_GROUP) },
                onOpenEvents = { navController.navigate(DhRoutes.EVENTS) },
                // 정차 대시보드 블록: 그룹 관리(초대 QR·멤버·나가기/종료) / 설정
                onManageGroup = { navController.navigate(DhRoutes.CREATE_GROUP) },
                onOpenSettings = { navController.navigate(DhRoutes.SETTINGS) },
                // 그룹이 끝난 화면(E115): [새 그룹] / [다시 연결]
                onNewGroup = { navController.navigate(DhRoutes.CREATE_GROUP) { popUpTo(DhRoutes.HOME) } },
                onRepair = { navController.navigate(DhRoutes.PAIRING) { popUpTo(DhRoutes.HOME) } },
            )
        }
        composable(DhRoutes.EVENTS) { EventsRoute(onBack = { navController.popBackStack() }) }
        composable(DhRoutes.PRESETS) { PresetsScreen() }
        composable(DhRoutes.SETTINGS) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenDiagnostics = { navController.navigate(DhRoutes.DIAGNOSTICS) },
            )
        }
        composable(DhRoutes.DIAGNOSTICS) { DiagnosticsRoute(onBack = { navController.popBackStack() }) }
        composable(DhRoutes.TOUR) { DemoTourRoute(onClose = { navController.popBackStack() }) }
        composable("${DhRoutes.DESIGN}/{variant}") { backEntry -> app.donghaeng.car.feature.drive.DesignPreviewHost(backEntry.arguments?.getString("variant") ?: "drive_landscape") }
    }
}
