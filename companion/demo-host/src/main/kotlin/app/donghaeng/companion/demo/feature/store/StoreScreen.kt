package app.donghaeng.companion.demo.feature.store

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.CarSideSilhouette
import app.donghaeng.companion.demo.ui.components.Disclaimer
import app.donghaeng.companion.demo.ui.components.Dots
import app.donghaeng.companion.demo.ui.components.KiaButton
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.KiaTopBar
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.SectionHeader
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.demo.ui.theme.KiaDimens

/*
 * 스토어 탭 — 완전 정적 재현 화면(동행 기능 없음).
 * 시승 예약 캐러셀 → 기아 커넥트 스토어 배너 → 서비스 바로가기 → Best Kia 순서.
 * 차량 이미지는 브랜드 자산이라 [CarSideSilhouette] 로 대신하고, 배너는 그라데이션 박스로 그린다.
 */

private data class TestDrive(val model: String, val trim: String)

private data class StoreBanner(val eyebrow: String, val title: String, val colors: List<Color>)

private data class Shortcut(val label: String, @DrawableRes val icon: Int)

private data class BestKia(val rank: Int, val name: String, val price: String)

private val testDrives = listOf(
    TestDrive("니로", "(더 뉴 니로) 하이브리드 시그니처"),
    TestDrive("셀토스", "1.6 HEV 시그니처"),
    TestDrive("쏘렌토", "2.5 터보 하이브리드 그래비티"),
    TestDrive("스포티지", "1.6 터보 하이브리드 시그니처"),
    TestDrive("EV3", "롱레인지 GT-라인"),
    TestDrive("EV6", "롱레인지 GT-라인"),
    TestDrive("카니발", "하이브리드 시그니처"),
    TestDrive("K5", "2.0 가솔린 시그니처"),
    TestDrive("레이", "EV 에어"),
)

private val storeBanners = listOf(
    StoreBanner("가을 디스플레이 테마", "Kia Connect Store", listOf(Color(0xFF5B6B2E), Color(0xFF8C6A2A), Color(0xFF1A2418))),
    StoreBanner("겨울 디스플레이 테마", "Kia Connect Store", listOf(Color(0xFF2B4C6F), Color(0xFF5A7FA3), Color(0xFF101C28))),
    StoreBanner("내비게이션 음성 팩", "Kia Connect Store", listOf(Color(0xFF3A3F5C), Color(0xFF6B5B8A), Color(0xFF14161F))),
)

private val shortcuts = listOf(
    Shortcut("기아 커넥트 스토어", R.drawable.ms_storefront),
    Shortcut("기아 샵", R.drawable.ms_shopping_bag),
    Shortcut("시승신청", R.drawable.ms_directions_car),
    Shortcut("기아 인증 중고차", R.drawable.ms_verified),
    Shortcut("기아렌터카", R.drawable.ms_car_rental),
    Shortcut("견적내기", R.drawable.ms_request_quote),
    Shortcut("구매상담신청", R.drawable.ms_support_agent),
)

private val bestKia = listOf(
    BestKia(1, "The 2027 Carnival Hi Limousine", "78,190,000원"),
    BestKia(2, "The 2027 Carnival", "61,870,000원"),
    BestKia(3, "The 2027 Seltos", "29,200,000원"),
    BestKia(4, "The 2027 Sorento", "47,690,000원"),
)

private val CarouselCardWidth = 300.dp

@Composable
fun StoreScreen(onQr: () -> Unit, onProfile: () -> Unit, onDemoTap: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(KiaColors.Bg),
    ) {
        KiaTopBar(title = "스토어", onQr = onQr, onSearch = { onDemoTap("검색") }, onProfile = onProfile)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            TestDriveSection(onDemoTap)
            Spacer(Modifier.height(KiaDimens.SectionGap + 8.dp))
            ConnectStoreSection(onDemoTap)
            Spacer(Modifier.height(KiaDimens.SectionGap))
            ShortcutSection(onDemoTap)
            Spacer(Modifier.height(KiaDimens.SectionGap))
            BestKiaSection(onDemoTap)
            Spacer(Modifier.height(12.dp))
            Disclaimer()
        }
    }
}

// ───────── 시승 예약 ─────────

@Composable
private fun TestDriveSection(onDemoTap: (String) -> Unit) {
    Column(Modifier.padding(horizontal = KiaDimens.ScreenPadding)) {
        Text("Kia와 만나 볼까요?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        SectionHeader(
            title = "가까운 시승 지점에서 기다릴게요.",
            titleStyle = MaterialTheme.typography.bodyMedium.copy(color = KiaColors.TextSecondary),
            action = "더 보기",
            onAction = { onDemoTap("시승 지점 더 보기") },
        )
        Spacer(Modifier.height(10.dp))
        NearbyBranchPill(place = "중구", distance = "2.0km", onClick = { onDemoTap("시승 지점 · 중구") })
    }
    Spacer(Modifier.height(KiaDimens.SectionGap))

    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
        contentPadding = PaddingValues(horizontal = KiaDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(KiaDimens.CardGap),
    ) {
        items(testDrives, key = { it.model }) { TestDriveCard(it, onDemoTap) }
    }
    Spacer(Modifier.height(18.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Dots(count = testDrives.size, selected = listState.firstVisibleItemIndex.coerceIn(0, testDrives.lastIndex))
    }
}

/** 가까운 시승 지점 — 어두운 녹색 가로 pill, 라임 핀·거리 */
@Composable
private fun NearbyBranchPill(place: String, distance: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(KiaColors.AccentDim)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MsIcon(R.drawable.ms_location_on, null, tint = KiaColors.Accent, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(place, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(distance, style = MaterialTheme.typography.titleSmall, color = KiaColors.Accent)
    }
}

@Composable
private fun TestDriveCard(item: TestDrive, onDemoTap: (String) -> Unit) {
    KiaCard(
        modifier = Modifier.width(CarouselCardWidth),
        onClick = { onDemoTap("시승 차량 · ${item.model}") },
        contentPadding = PaddingValues(24.dp),
    ) {
        Text(item.model, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(item.trim, style = MaterialTheme.typography.bodyLarge, color = KiaColors.TextSecondary, maxLines = 1)
        Spacer(Modifier.height(30.dp))
        CarSideSilhouette(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            height = 120.dp,
        )
        Spacer(Modifier.height(30.dp))
        KiaButton("시승 예약") { onDemoTap("시승 예약 · ${item.model}") }
    }
}

// ───────── 기아 커넥트 스토어 배너 ─────────

@Composable
private fun ConnectStoreSection(onDemoTap: (String) -> Unit) {
    SectionHeader(
        title = "기아 커넥트 스토어",
        action = "더 보기",
        onAction = { onDemoTap("기아 커넥트 스토어 더 보기") },
        modifier = Modifier.padding(horizontal = KiaDimens.ScreenPadding),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = KiaDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(KiaDimens.CardGap),
    ) {
        items(storeBanners, key = { it.eyebrow }) { BannerCard(it, onClick = { onDemoTap("커넥트 스토어 · ${it.eyebrow}") }) }
    }
}

/** 사진 대신 그라데이션 + 은은한 빛 번짐으로 채운 배너 카드 */
@Composable
private fun BannerCard(banner: StoreBanner, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(CarouselCardWidth)
            .height(220.dp)
            .clip(RoundedCornerShape(KiaDimens.CardRadius))
            .background(Brush.linearGradient(banner.colors))
            .clickable(onClick = onClick),
    ) {
        // 오른쪽 위 햇빛 번짐
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = (-40).dp)
                .size(180.dp)
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent)), CircleShape),
        )
        // 아래쪽 어두운 그라데이션(글자 가독성)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))),
        )
        Column(Modifier.padding(24.dp)) {
            Text(banner.eyebrow, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(4.dp))
            Text(banner.title, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

// ───────── 서비스 바로가기 ─────────

@Composable
private fun ShortcutSection(onDemoTap: (String) -> Unit) {
    Column(Modifier.padding(horizontal = KiaDimens.ScreenPadding)) {
        SectionHeader(title = "서비스 바로가기")
        KiaCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 24.dp)) {
            shortcuts.chunked(4).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { ShortcutItem(it, onClick = { onDemoTap(it.label) }) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ShortcutItem(item: Shortcut, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MsIcon(item.icon, item.label, size = 30.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            item.label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ───────── Best Kia ─────────

@Composable
private fun BestKiaSection(onDemoTap: (String) -> Unit) {
    Column(
        Modifier.padding(horizontal = KiaDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(KiaDimens.CardGap),
    ) {
        SectionHeader(title = "Best Kia", modifier = Modifier.padding(bottom = 0.dp))
        bestKia.forEach { car -> BestKiaCard(car, onClick = { onDemoTap("Best Kia · ${car.name}") }) }
    }
}

@Composable
private fun BestKiaCard(car: BestKia, onClick: () -> Unit) {
    KiaCard(onClick = onClick, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(car.rank.toString(), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(car.name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(car.price, style = MaterialTheme.typography.bodyLarge, color = KiaColors.TextSecondary)
            }
            Spacer(Modifier.width(12.dp))
            CarSideSilhouette(modifier = Modifier.width(150.dp), height = 64.dp)
        }
    }
}
