package app.donghaeng.companion.demo.feature.map

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.CarParkedArt
import app.donghaeng.companion.demo.ui.components.Disclaimer
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.KiaChip
import app.donghaeng.companion.demo.ui.components.MapMarker
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.OsmMap
import app.donghaeng.companion.demo.ui.components.QrScanIcon
import app.donghaeng.companion.demo.ui.components.RowDivider
import app.donghaeng.companion.demo.ui.components.SectionHeader
import app.donghaeng.companion.demo.ui.components.TopBarIcon
import app.donghaeng.companion.demo.ui.theme.KiaColors
import org.osmdroid.util.GeoPoint

/*
 * 지도 탭 — 기아 앱 지도 화면 재현. 전체 화면 지도 위에 검색 pill·카테고리 칩·둥근 버튼을 띄우고,
 * 아래에는 고정 높이 바텀시트("내 차 위치" 제안 카드)를 깐다.
 *
 * 동행 삽입 지점 D:
 *  - 카테고리 칩 맨 앞의 "동행" 칩 → [onToggleDonghaeng]
 *  - 켜지면 [groupMarkers] 가 내 차 핀과 함께 지도에 올라가고(followMarkers), 바텀시트 내용이 [groupSheet] 로 바뀐다.
 */

private val SEOUL_CITY_HALL = GeoPoint(37.5665, 126.9780)
private val SHEET_HEIGHT = 300.dp

/** 지도 위에 띄우는 어두운 반투명 바탕(검색 pill·둥근 버튼) */
private val OverlayDark = Color(0xEE18222B)

@Composable
fun MapScreen(
    userName: String,
    bellUnread: Boolean,
    onQr: () -> Unit,
    onBell: () -> Unit,
    onProfile: () -> Unit,
    donghaengAvailable: Boolean,
    donghaengOn: Boolean,
    onToggleDonghaeng: () -> Unit,
    groupMarkers: List<MapMarker>,
    groupSheet: (@Composable () -> Unit)?,
    onDemoTap: (String) -> Unit,
) {
    val myCar = remember {
        MapMarker(id = "me", lat = SEOUL_CITY_HALL.latitude, lon = SEOUL_CITY_HALL.longitude, label = "내 차", color = KiaColors.MapBlue, isMine = true)
    }
    // 동행 칩을 켜면 내 차의 실시간 위치가 그룹 마커에 들어 있으므로 주차 위치 핀은 숨긴다
    val markers = remember(groupMarkers, donghaengOn) { if (donghaengOn && groupMarkers.isNotEmpty()) groupMarkers else listOf(myCar) + groupMarkers }

    Box(
        Modifier
            .fillMaxSize()
            .background(KiaColors.Bg),
    ) {
        OsmMap(
            center = SEOUL_CITY_HALL,
            zoom = 16.0,
            markers = markers,
            modifier = Modifier.fillMaxSize(),
            darkTiles = true,
            followMarkers = groupMarkers.isNotEmpty(),
        )

        // ── 상단 오버레이: 검색 pill + 길안내 버튼 / 카테고리 칩 / 작은 둥근 QR·알림·프로필 버튼 ──
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 12.dp),
        ) {
            SearchRow(onSearch = { onDemoTap("장소 검색") }, onNavigate = { onDemoTap("길안내") })
            Spacer(Modifier.height(10.dp))
            CategoryChips(
                donghaengAvailable = donghaengAvailable,
                donghaengOn = donghaengOn,
                onToggleDonghaeng = onToggleDonghaeng,
                onDemoTap = onDemoTap,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                RoundOverlay(size = 44.dp) { QrScanIcon(onClick = onQr, size = 22.dp) }
                RoundOverlay(size = 44.dp) { TopBarIcon(R.drawable.ms_notifications, "알림", dot = bellUnread, onClick = onBell) }
                RoundOverlay(size = 44.dp) { TopBarIcon(R.drawable.ms_person, "프로필", onClick = onProfile) }
            }
        }

        // ── 오른쪽 아래(시트 바로 위): 내 차로 이동 · 현재 위치 ──
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = SHEET_HEIGHT + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundMapButton(R.drawable.ms_directions_car, "내 차 위치로", tint = KiaColors.Accent) { onDemoTap("내 차 위치로 이동") }
            RoundMapButton(R.drawable.ms_my_location, "현재 위치") { onDemoTap("현재 위치") }
        }

        // ── 바텀시트(고정 높이, 내용 스크롤) ──
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(SHEET_HEIGHT)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(KiaColors.Bg),
        ) {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 44.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(KiaColors.SubCard)
                    .align(Alignment.CenterHorizontally),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (groupSheet != null) {
                    Spacer(Modifier.height(8.dp))
                    groupSheet()
                } else {
                    MyCarSheet(userName = userName, onDemoTap = onDemoTap)
                }
                Disclaimer()
            }
        }
    }
}

/** 검색 pill("장소, 주소 검색") + 오른쪽 라임 길안내 버튼 */
@Composable
private fun SearchRow(onSearch: () -> Unit, onNavigate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(CircleShape)
                .background(OverlayDark)
                .clickable(onClick = onSearch)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MsIcon(R.drawable.ms_search, null, tint = KiaColors.TextSecondary, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text("장소, 주소 검색", style = MaterialTheme.typography.bodyLarge, color = KiaColors.TextSecondary)
        }
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(KiaColors.Accent)
                .clickable(onClick = onNavigate),
            contentAlignment = Alignment.Center,
        ) {
            MsIcon(R.drawable.ms_near_me, "길안내", tint = Color.White, size = 24.dp)
        }
    }
}

/** 카테고리 칩 — 맨 앞 "동행"(삽입 지점 D), 그 뒤 주유소·맛집 로드·주차장·세차장 */
@Composable
private fun CategoryChips(
    donghaengAvailable: Boolean,
    donghaengOn: Boolean,
    onToggleDonghaeng: () -> Unit,
    onDemoTap: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KiaChip(
            text = "동행",
            icon = R.drawable.ms_groups,
            accent = donghaengOn,
            color = OverlayDark,
            modifier = Modifier.alpha(if (donghaengOn || donghaengAvailable) 1f else 0.6f),
            onClick = onToggleDonghaeng,
        )
        KiaChip("주유소", icon = R.drawable.ms_local_gas_station, color = OverlayDark, onClick = { onDemoTap("주유소") })
        KiaChip("맛집 로드", icon = R.drawable.ms_restaurant, color = OverlayDark, onClick = { onDemoTap("맛집 로드") })
        KiaChip("주차장", icon = R.drawable.ms_local_parking, color = OverlayDark, onClick = { onDemoTap("주차장") })
        KiaChip("세차장", icon = R.drawable.ms_local_car_wash, color = OverlayDark, onClick = { onDemoTap("세차장") })
    }
}

/** 어두운 원 바탕 — 안에 44dp 클릭 아이콘(QrScanIcon·TopBarIcon)을 넣는다 */
@Composable
private fun RoundOverlay(size: Dp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(OverlayDark),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 지도 오른쪽 둥근 버튼(내 차로 이동·현재 위치) */
@Composable
private fun RoundMapButton(@DrawableRes icon: Int, desc: String, tint: Color = KiaColors.TextPrimary, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(OverlayDark)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(icon, desc, tint = tint, size = 24.dp)
    }
}

/** 기본 시트 내용: 아이콘 탭 3개 → "OO님을 위한 제안 · 내 차 중심" → 내 차 위치 카드 */
@Composable
private fun MyCarSheet(userName: String, onDemoTap: (String) -> Unit) {
    SheetTabs(onDemoTap)
    Spacer(Modifier.height(20.dp))
    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionHeader(
            title = "${userName}님을 위한 제안",
            action = "내 차 중심",
            actionChevron = false,
            onAction = { onDemoTap("내 차 중심") },
        )
        KiaCard(onClick = { onDemoTap("내 차 위치") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MsIcon(R.drawable.ms_directions_car, null, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Text("내 차 위치", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "편집",
                    style = MaterialTheme.typography.labelLarge,
                    color = KiaColors.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onDemoTap("내 차 위치 편집") }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            RowDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("서울특별시 중구 세종대로 110 (서울시청 주차장)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("현재 위치에서 10m", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                    Spacer(Modifier.height(2.dp))
                    Text("오후 12:01 기준", style = MaterialTheme.typography.bodySmall, color = KiaColors.TextTertiary)
                }
                Spacer(Modifier.width(12.dp))
                CarParkedArt(Modifier.width(104.dp), height = 72.dp)
            }
        }
    }
}

/** 시트 상단 아이콘 탭(제안 · 최근 · 즐겨찾기) — 첫 탭 선택, 라임 밑줄 */
@Composable
private fun SheetTabs(onDemoTap: (String) -> Unit) {
    val tabs = listOf(
        Triple(R.drawable.ms_grid_view, "제안", true),
        Triple(R.drawable.ms_schedule, "최근", false),
        Triple(R.drawable.ms_star, "즐겨찾기", false),
    )
    Row(Modifier.fillMaxWidth()) {
        tabs.forEach { (icon, label, selected) ->
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onDemoTap(label) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
                    MsIcon(icon, label, tint = if (selected) KiaColors.TextPrimary else KiaColors.TextSecondary, size = 28.dp)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(2.dp)
                        .background(if (selected) KiaColors.Accent else Color.Transparent),
                )
            }
        }
    }
}
