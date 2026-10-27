package app.donghaeng.companion.demo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.companion.demo.DemoSession
import app.donghaeng.companion.demo.donghaeng.ActiveGroupCard
import app.donghaeng.companion.demo.donghaeng.DongHaengStore
import app.donghaeng.companion.demo.donghaeng.GroupDriveSheet
import app.donghaeng.companion.demo.donghaeng.PassengerMessageSheet
import app.donghaeng.companion.demo.donghaeng.groupMarkers
import app.donghaeng.companion.demo.feature.control.ControlScreen
import app.donghaeng.companion.demo.feature.home.HomeScreen
import app.donghaeng.companion.demo.feature.map.MapScreen
import app.donghaeng.companion.demo.feature.mycar.MyCarScreen
import app.donghaeng.companion.demo.feature.store.StoreScreen
import app.donghaeng.companion.demo.ui.components.KiaBottomBar
import app.donghaeng.companion.demo.ui.components.KiaTab
import app.donghaeng.companion.demo.ui.components.demoToast
import app.donghaeng.companion.demo.ui.theme.KiaColors

/**
 * 하단 탭 5개를 품는 셸. 동행 삽입 지점은 여기서 슬롯으로 각 탭에 꽂힌다:
 * - A 상단 QR 스캔 아이콘(모든 탭) → [onScan]
 * - B 홈 "동행 중" 카드 → [ActiveGroupCard] (그룹 진행 중일 때만)
 * - C 홈 서비스 바로가기 "동행" → [onOpenDongHaeng]
 * - D 지도 "동행" 칩 → 그룹 차량 마커 + [GroupDriveSheet]
 * - E 마이카 > 연동 서비스 "동행 그룹 주행" → [onOpenDongHaengSettings]
 * - F 알림 벨 → [onOpenNotifications]
 * - G 홈 카드 [메시지 보내기] → [PassengerMessageSheet]
 */
@Composable
fun MainScreen(
    onScan: () -> Unit,
    onOpenDongHaeng: () -> Unit,
    onOpenDongHaengSettings: () -> Unit,
    onOpenNotifications: () -> Unit,
    onProfile: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(KiaTab.HOME) }
    var donghaengOnMap by rememberSaveable { mutableStateOf(false) }
    var showMessageSheet by rememberSaveable { mutableStateOf(false) }

    val account by DemoSession.account.collectAsStateWithLifecycle()
    val activeGroupState by DongHaengStore.activeGroup.collectAsStateWithLifecycle()
    val activeGroup = activeGroupState
    val snapshot by DongHaengStore.snapshot.collectAsStateWithLifecycle()
    val notifications by DongHaengStore.notifications.collectAsStateWithLifecycle()
    val vehicles by DongHaengStore.vehicles.collectAsStateWithLifecycle()
    val unread = notifications.any { !it.read }
    val userName = account?.nickname ?: DemoSession.USER_NAME
    val onDemoTap: (String) -> Unit = { demoToast(context, it) }

    Scaffold(
        containerColor = KiaColors.Bg,
        bottomBar = { KiaBottomBar(selected = tab, onSelect = { tab = it }) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            when (tab) {
                KiaTab.HOME -> HomeScreen(
                    vehicleName = DemoSession.VEHICLE_NAME,
                    userName = userName,
                    bellUnread = unread,
                    onQr = onScan,
                    onBell = onOpenNotifications,
                    onProfile = onProfile,
                    donghaengCard = activeGroup?.let { group ->
                        {
                            ActiveGroupCard(
                                group = group,
                                snapshot = snapshot,
                                latestNotification = notifications.firstOrNull(),
                                onSendMessage = { showMessageSheet = true },
                                onOpen = onOpenDongHaeng,
                            )
                        }
                    },
                    onOpenDongHaeng = onOpenDongHaeng,
                    onDemoTap = onDemoTap,
                )
                KiaTab.MAP -> MapScreen(
                    userName = userName,
                    bellUnread = unread,
                    onQr = onScan,
                    onBell = onOpenNotifications,
                    onProfile = onProfile,
                    donghaengAvailable = activeGroup != null,
                    donghaengOn = donghaengOnMap && activeGroup != null,
                    onToggleDonghaeng = {
                        if (activeGroup == null) demoToast(context, "참여 중인 동행 그룹이 없어요. QR 을 스캔해 합류하세요") else donghaengOnMap = !donghaengOnMap
                    },
                    groupMarkers = if (donghaengOnMap && activeGroup != null) groupMarkers(snapshot, activeGroup) else emptyList(),
                    groupSheet = if (donghaengOnMap && activeGroup != null) {
                        { GroupDriveSheet(group = activeGroup, snapshot = snapshot, onSendMessage = { showMessageSheet = true }) }
                    } else {
                        null
                    },
                    onDemoTap = onDemoTap,
                )
                KiaTab.CONTROL -> ControlScreen(onQr = onScan, onDemoTap = onDemoTap)
                KiaTab.STORE -> StoreScreen(onQr = onScan, onProfile = onProfile, onDemoTap = onDemoTap)
                KiaTab.MYCAR -> MyCarScreen(
                    vehicleName = DemoSession.VEHICLE_NAME,
                    vehicles = vehicles,
                    onQr = onScan,
                    onProfile = onProfile,
                    onOpenDongHaengSettings = onOpenDongHaengSettings,
                    onLogout = onLogout,
                    onDemoTap = onDemoTap,
                )
            }
        }
    }

    if (showMessageSheet && activeGroup != null) {
        PassengerMessageSheet(onDismiss = { showMessageSheet = false })
    }
}
