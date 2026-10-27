package app.donghaeng.companion.demo.feature.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.AccentSlider
import app.donghaeng.companion.demo.ui.components.CarParkedArt
import app.donghaeng.companion.demo.ui.components.CarSideSilhouette
import app.donghaeng.companion.demo.ui.components.Disclaimer
import app.donghaeng.companion.demo.ui.components.Dots
import app.donghaeng.companion.demo.ui.components.KiaButton
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.KiaChip
import app.donghaeng.companion.demo.ui.components.KiaTopBar
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.RowDivider
import app.donghaeng.companion.demo.ui.components.SectionHeader
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.demo.ui.theme.KiaDimens

/*
 * 홈 탭 — 기아 앱 홈을 재현한 정적 화면. 위에서부터
 *   주행가능거리 → 퀵 컨트롤 → 검색 → 날씨 → 즐겨찾기 칩 → [동행 카드 슬롯] → 내 차 위치 → hi-pass 배너
 *   → 서비스 바로가기(첫 칸 "동행") → 내 스케줄 → 실시간 핫플레이스 → Driving Insight → 테마 배너 → 이벤트 → 약관 → 고지.
 * 동행 연동 지점은 두 곳뿐: [donghaengCard] 슬롯과 서비스 바로가기의 "동행" 칸. 나머지는 전부 onDemoTap 토스트.
 */

private val H = KiaDimens.ScreenPadding

/** 날씨 카드의 해 아이콘(콘텐츠 색, 디자인 토큰 아님) */
private val SunYellow = Color(0xFFF5B82E)

@Composable
fun HomeScreen(
    vehicleName: String,
    userName: String,
    bellUnread: Boolean,
    onQr: () -> Unit,
    onBell: () -> Unit,
    onProfile: () -> Unit,
    donghaengCard: (@Composable () -> Unit)?,
    onOpenDongHaeng: () -> Unit,
    onDemoTap: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(KiaColors.Bg),
    ) {
        KiaTopBar(title = vehicleName, onQr = onQr, onBell = onBell, bellUnread = bellUnread, onProfile = onProfile)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item { RangeHero(onDemoTap) }
            item { QuickControls(onDemoTap) }
            item { SearchBar(onDemoTap) }
            item { WeatherCard(onDemoTap) }
            item { FavoriteChips(onDemoTap) }
            // B. 동행 진행 카드 — 참여 중인 그룹이 있을 때만, "내 차 위치" 바로 위
            if (donghaengCard != null) {
                item {
                    Box(Modifier.padding(start = H, end = H, top = 20.dp)) { donghaengCard() }
                }
            }
            item { MyCarLocationCard(onDemoTap) }
            item { HiPassBanner(onDemoTap) }
            item { ServiceShortcuts(onOpenDongHaeng, onDemoTap) }
            item { ScheduleSection(onDemoTap) }
            item { HotPlaceSection(onDemoTap) }
            item { DrivingInsightSection(onDemoTap) }
            item { ThemeBanner(onDemoTap) }
            item { EventSection(onDemoTap) }
            item { FooterLink(onDemoTap) }
            item { Disclaimer() }
        }
    }
}

// ───────────────────────── 상단: 주행가능거리 + 차량 ─────────────────────────

@Composable
private fun RangeHero(onDemoTap: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onDemoTap("차량 상태") }
            .padding(horizontal = H, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("999", style = MaterialTheme.typography.displayLarge)
            Text(
                "+km",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
        Box(
            Modifier
                .padding(top = 6.dp)
                .width(120.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(KiaColors.Accent),
        )
        Text(
            "17,312km",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(top = 14.dp),
        )
        CarSideSilhouette(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 24.dp),
            height = 140.dp,
        )
    }
}

private data class QuickAction(val label: String, val toast: String, @DrawableRes val icon: Int)

private val quickActions = listOf(
    QuickAction("켜기", "시동 켜기", R.drawable.ms_power_settings_new),
    QuickAction("잠금 해제", "잠금 해제", R.drawable.ms_lock_open),
    QuickAction("열기", "문 열기", R.drawable.ms_door_open),
    QuickAction("켜기", "비상등 켜기", R.drawable.ms_warning),
)

@Composable
private fun QuickControls(onDemoTap: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = H + 8.dp, vertical = 8.dp),
    ) {
        quickActions.forEach { a ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onDemoTap(a.toast) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MsIcon(a.icon, a.toast, size = 34.dp)
                Spacer(Modifier.height(12.dp))
                Text(a.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SearchBar(onDemoTap: (String) -> Unit) {
    Row(
        Modifier
            .padding(start = H, end = H, top = 28.dp)
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .background(KiaColors.TextPrimary)
            .clickable { onDemoTap("검색") }
            .padding(start = 20.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "차량명, 상품명, 장소명을 검색해보세요",
            style = MaterialTheme.typography.bodyLarge,
            color = KiaColors.SubCard,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(KiaColors.Bg),
            contentAlignment = Alignment.Center,
        ) { MsIcon(R.drawable.ms_search, "검색", size = 20.dp) }
    }
}

@Composable
private fun WeatherCard(onDemoTap: (String) -> Unit) {
    KiaCard(
        modifier = Modifier.padding(start = H, end = H, top = 20.dp),
        onClick = { onDemoTap("근처 날씨") },
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MsIcon(R.drawable.ms_wb_sunny, null, tint = SunYellow, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text("14.8°C", style = MaterialTheme.typography.titleMedium)
            Text(" · 맑음 주간", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            MsIcon(R.drawable.ms_location_searching, null, tint = KiaColors.TextSecondary, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text("근처 날씨", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
        }
    }
}

private data class Favorite(val label: String, @DrawableRes val icon: Int)

private val favorites = listOf(
    Favorite("우리집", R.drawable.ms_home),
    Favorite("회사", R.drawable.ms_apartment),
    Favorite("부모님집", R.drawable.ms_star),
    Favorite("즐겨찾기", R.drawable.ms_star),
)

@Composable
private fun FavoriteChips(onDemoTap: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(top = 20.dp),
        contentPadding = PaddingValues(horizontal = H),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(favorites) { f ->
            KiaChip(f.label, icon = f.icon, onClick = { onDemoTap("즐겨찾기 ${f.label}") })
        }
    }
}

// ───────────────────────── 내 차 위치 · hi-pass 배너 ─────────────────────────

@Composable
private fun MyCarLocationCard(onDemoTap: (String) -> Unit) {
    KiaCard(
        modifier = Modifier.padding(start = H, end = H, top = 20.dp),
        onClick = { onDemoTap("내 차 위치") },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("내 차 위치", style = MaterialTheme.typography.bodyLarge, color = KiaColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "서울특별시 중구 세종대로 110 (서울시청 주차장)",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    MsIcon(
                        R.drawable.ms_edit,
                        "주소 편집",
                        tint = KiaColors.TextSecondary,
                        size = 18.dp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onDemoTap("주소 편집") },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("현재 위치에서 10m", style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .width(104.dp)
                    .height(92.dp),
            ) {
                CarParkedArt(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    height = 64.dp,
                )
                MsIcon(
                    R.drawable.ms_location_on,
                    null,
                    tint = KiaColors.Accent,
                    size = 30.dp,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun HiPassBanner(onDemoTap: (String) -> Unit) {
    KiaCard(modifier = Modifier.padding(start = H, end = H, top = 24.dp)) {
        Row {
            Text(
                "고속도로 하이패스,\n우리집에서 간편하게\n신청하세요",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, top = 8.dp),
            )
            Column(horizontalAlignment = Alignment.End) {
                MsIcon(
                    R.drawable.ms_close,
                    "배너 닫기",
                    tint = KiaColors.TextSecondary,
                    size = 24.dp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onDemoTap("배너 닫기") },
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(KiaColors.SubCard),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("hi-pass", style = MaterialTheme.typography.labelLarge, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        KiaButton("e hi-pass 신청") { onDemoTap("e hi-pass 신청") }
    }
}

// ───────────────────────── 서비스 바로가기 (C. 첫 칸 = 동행) ─────────────────────────

private data class ServiceItem(
    val label: String,
    @DrawableRes val icon: Int,
    val accent: Boolean = false,
    val isNew: Boolean = false,
)

private val serviceRow1 = listOf(
    ServiceItem("동행", R.drawable.ms_groups, accent = true, isNew = true),
    ServiceItem("채팅 상담", R.drawable.ms_chat),
    ServiceItem("자주 묻는 질문", R.drawable.ms_edit),
    ServiceItem("기아 앱 사용법", R.drawable.ms_menu_book),
)
private val serviceRow2 = listOf(ServiceItem("고객센터", R.drawable.ms_headset_mic))

@Composable
private fun ServiceShortcuts(onOpenDongHaeng: () -> Unit, onDemoTap: (String) -> Unit) {
    Column(Modifier.padding(start = H, end = H, top = KiaDimens.SectionGap)) {
        HeaderRow("서비스 바로가기") {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDemoTap("바로가기 편집") }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MsIcon(R.drawable.ms_swap_horiz, null, tint = KiaColors.TextSecondary, size = 18.dp)
                Spacer(Modifier.width(4.dp))
                Text("편집", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
            }
        }
        KiaCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                serviceRow1.forEach { item ->
                    ServiceCell(item, Modifier.weight(1f)) {
                        if (item.accent) onOpenDongHaeng() else onDemoTap(item.label)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                serviceRow2.forEach { item -> ServiceCell(item, Modifier.weight(1f)) { onDemoTap(item.label) } }
                Spacer(Modifier.weight((4 - serviceRow2.size).toFloat()))
            }
        }
        Dots(
            count = 2,
            selected = 0,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 16.dp),
        )
    }
}

@Composable
private fun ServiceCell(item: ServiceItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = if (item.accent) KiaColors.Accent else KiaColors.TextPrimary
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            MsIcon(item.icon, item.label, tint = tint, size = 28.dp)
            if (item.isNew) {
                Text(
                    "NEW",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 16.dp, y = (-8).dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(KiaColors.Accent)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(item.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/** 섹션 제목 + 임의의 오른쪽 액션(SectionHeader 가 못 그리는 "⇌ 편집", "주변 5km ⌄" 용) */
@Composable
private fun HeaderRow(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ───────────────────────── 내 스케줄 · 실시간 핫플레이스 ─────────────────────────

@Composable
private fun ScheduleSection(onDemoTap: (String) -> Unit) {
    Column(Modifier.padding(start = H, end = H, top = KiaDimens.SectionGap)) {
        SectionHeader("내 스케줄")
        KiaCard(onClick = { onDemoTap("내 스케줄") }) {
            Text("오늘 10월 27일", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(KiaColors.TextPrimary),
                )
                Spacer(Modifier.width(14.dp))
                Text("오늘 예정된 일정이 없습니다.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private data class HotPlace(val name: String, val category: String, val address: String, val count: Int)

private val hotPlaces = listOf(
    HotPlace("서울숲", "공원", "서울특별시 성동구 뚝섬로 273", 80),
    HotPlace("광화문광장", "광장", "서울특별시 종로구 세종대로 172", 67),
    HotPlace("여의도 한강공원", "공원", "서울특별시 영등포구 여의동로 330", 55),
)

@Composable
private fun HotPlaceSection(onDemoTap: (String) -> Unit) {
    Column(Modifier.padding(start = H, end = H, top = KiaDimens.SectionGap)) {
        HeaderRow("실시간 핫플레이스") {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDemoTap("핫플레이스 반경") }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("주변 5km", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(2.dp))
                MsIcon(R.drawable.ms_keyboard_arrow_down, null, size = 22.dp)
            }
        }
        KiaCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("오후 12:00 업데이트", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDemoTap("핫플레이스 업데이트") }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MsIcon(R.drawable.ms_refresh, null, tint = KiaColors.Accent, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("업데이트", style = MaterialTheme.typography.bodyLarge)
                }
            }
            RowDivider()
            hotPlaces.forEach { p -> HotPlaceRow(p, onDemoTap) }
        }
    }
}

@Composable
private fun HotPlaceRow(p: HotPlace, onDemoTap: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onDemoTap("핫플레이스 ${p.name}") }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "${p.category} · ${p.address}",
                style = MaterialTheme.typography.bodyMedium,
                color = KiaColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "${p.count}대의 목적지",
                style = MaterialTheme.typography.bodyLarge,
                color = KiaColors.Accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .border(1.dp, KiaColors.TextTertiary, CircleShape)
                .clickable { onDemoTap("길안내 ${p.name}") },
            contentAlignment = Alignment.Center,
        ) { MsIcon(R.drawable.ms_near_me, "길안내", size = 20.dp) }
    }
}

// ───────────────────────── Driving Insight · 테마 배너 ─────────────────────────

@Composable
private fun DrivingInsightSection(onDemoTap: (String) -> Unit) {
    Column(Modifier.padding(top = KiaDimens.SectionGap)) {
        SectionHeader(
            "Driving Insight",
            modifier = Modifier.padding(horizontal = H),
            action = "더 보기",
            onAction = { onDemoTap("Driving Insight 더 보기") },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = H),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { InsightDistanceCard(onDemoTap) }
            item { InsightStatusCard(onDemoTap) }
        }
    }
}

private val insightCardModifier = Modifier
    .width(200.dp)
    .height(210.dp)

@Composable
private fun InsightDistanceCard(onDemoTap: (String) -> Unit) {
    KiaCard(modifier = insightCardModifier, onClick = { onDemoTap("10월 주행 기록") }) {
        Text("10월 한 달의 기록", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = KiaColors.Accent)) { append("2,080km") }
                append(" 달렸어요")
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.weight(1f))
        // 슬라이더 손잡이 위 값 풍선 — 손잡이와 같은 72% 지점
        Box(Modifier.fillMaxWidth(0.72f), contentAlignment = Alignment.CenterEnd) {
            Text(
                "2,080",
                style = MaterialTheme.typography.labelMedium,
                color = KiaColors.Accent,
                modifier = Modifier
                    .offset(x = 20.dp)
                    .border(1.dp, KiaColors.Accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        AccentSlider(0.72f, Modifier.fillMaxWidth())
    }
}

@Composable
private fun InsightStatusCard(onDemoTap: (String) -> Unit) {
    KiaCard(modifier = insightCardModifier, onClick = { onDemoTap("내 차 상태 둘러보기") }) {
        Text("내 차 상태 둘러보기", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text("내 차 브레이크 패드,\n지금 초록불일까?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .fillMaxWidth()
                .height(76.dp),
        ) {
            MsIcon(
                R.drawable.ms_check_circle,
                null,
                tint = KiaColors.Accent,
                size = 34.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp),
            )
            CarSideSilhouette(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .width(170.dp)
                    .offset(x = 40.dp, y = 10.dp),
                height = 64.dp,
            )
        }
    }
}

@Composable
private fun ThemeBanner(onDemoTap: (String) -> Unit) {
    Column(Modifier.padding(start = H, end = H, top = 24.dp)) {
        KiaCard(
            onClick = { onDemoTap("디스플레이 테마") },
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 26.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("가을 디스플레이\n테마 런칭", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ThemeStrip()
            }
        }
        Dots(
            count = 6,
            selected = 0,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 16.dp),
        )
    }
}

/** 테마 미리보기 띠 — 작은 타일 6장(이미지 대신 색 블록) */
@Composable
private fun ThemeStrip() {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(KiaColors.SubCard)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(6) { i ->
            Box(
                Modifier
                    .size(14.dp, 28.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (i % 2 == 0) KiaColors.Beige else KiaColors.Accent.copy(alpha = 0.75f)),
            )
        }
    }
}

// ───────────────────────── 이벤트 · 약관 ─────────────────────────

private data class EventItem(
    val headline: String,
    val caption: String,
    val title: String,
    val period: String,
    val bg: Color,
    val showCar: Boolean,
)

private val events = listOf(
    EventItem(
        headline = "The new Carnival\n보증연장 서비스\n할인 프로모션",
        caption = "프로모션 가입 시\n가입비 5% 할인",
        title = "「The new Carnival」 보증연장 서비스 할인 프로모션",
        period = "2026. 10. 15. ~ 2026. 12. 31.",
        bg = KiaColors.Beige,
        showCar = true,
    ),
    EventItem(
        headline = "가을 디스플레이 테마\n출시 기념 이벤트",
        caption = "지금 바로\n커넥트 스토어에서",
        title = "가을 디스플레이 테마 출시 기념 이벤트",
        period = "2026. 10. 1. ~ 2026. 11. 30.",
        bg = KiaColors.AccentDim,
        showCar = false,
    ),
)

@Composable
private fun EventSection(onDemoTap: (String) -> Unit) {
    Column(Modifier.padding(top = KiaDimens.SectionGap + 12.dp)) {
        SectionHeader(
            "이벤트",
            modifier = Modifier.padding(horizontal = H),
            action = "더 보기",
            onAction = { onDemoTap("이벤트 더 보기") },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = H),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(events) { ev -> EventCard(ev) { onDemoTap("이벤트 ${ev.title}") } }
        }
    }
}

@Composable
private fun EventCard(ev: EventItem, onClick: () -> Unit) {
    Column(
        Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(KiaDimens.CardRadius))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(168.dp)
                .clip(RoundedCornerShape(KiaDimens.CardRadius))
                .background(ev.bg),
        ) {
            Text(ev.headline, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
            Text(
                ev.caption,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp),
            )
            if (ev.showCar) {
                CarSideSilhouette(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .width(200.dp)
                        .offset(x = 36.dp, y = 10.dp),
                    height = 84.dp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(ev.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            ev.period,
            style = MaterialTheme.typography.bodyMedium,
            color = KiaColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FooterLink(onDemoTap: (String) -> Unit) {
    Text(
        buildAnnotatedString {
            append("서비스 이용약관 및 ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("개인정보 처리방침") }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = KiaColors.TextSecondary,
        textDecoration = TextDecoration.Underline,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDemoTap("서비스 이용약관") }
            .padding(top = 44.dp, bottom = 8.dp),
    )
}
