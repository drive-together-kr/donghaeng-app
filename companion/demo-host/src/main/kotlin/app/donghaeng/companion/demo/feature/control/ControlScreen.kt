package app.donghaeng.companion.demo.feature.control

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.CarTopSilhouette
import app.donghaeng.companion.demo.ui.components.Disclaimer
import app.donghaeng.companion.demo.ui.components.GreenProgress
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.KiaTopBar
import app.donghaeng.companion.demo.ui.components.ListRow
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.OutlinePill
import app.donghaeng.companion.demo.ui.components.RowDivider
import app.donghaeng.companion.demo.ui.components.SectionHeader
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.demo.ui.theme.KiaDimens

/*
 * 제어 탭 — 기아 공식 앱의 "제어" 화면 재현(정적). 동행 기능은 없고 모든 터치는 "시연용 화면입니다" 토스트.
 * 상단 탭 "제어 | 비디오" 두 개를 모두 그린다(선택 상태는 화면 안에서만 유지).
 */

private const val TAB_CONTROL = 0
private const val TAB_VIDEO = 1

private data class ControlTile(val title: String, val action: String, @DrawableRes val icon: Int)

private val controlTiles = listOf(
    ControlTile("시동/공조", "켜기", R.drawable.ms_power_settings_new),
    ControlTile("최대 난방", "켜기", R.drawable.ms_heat),
    ControlTile("문", "잠금 해제", R.drawable.ms_lock),
    ControlTile("창문", "열기", R.drawable.ms_window),
    ControlTile("창문", "환기하기", R.drawable.ms_air),
    ControlTile("비상등", "켜기", R.drawable.ms_warning),
    ControlTile("비상등/경적", "켜기", R.drawable.ms_campaign),
)

@Composable
fun ControlScreen(onQr: () -> Unit, onDemoTap: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(TAB_CONTROL) }

    Column(
        Modifier
            .fillMaxSize()
            .background(KiaColors.Bg),
    ) {
        KiaTopBar(title = "제어", onQr = onQr, onSettings = { onDemoTap("설정") })
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(horizontal = KiaDimens.ScreenPadding)) {
                if (tab == TAB_CONTROL) {
                    RangeChip(onClick = { onDemoTap("주행 가능 거리") })
                    Spacer(Modifier.height(12.dp))
                    CarHeader(onClick = { onDemoTap("차량 잠금 상태") })
                    Spacer(Modifier.height(20.dp))
                }
                UnderlineTabs(
                    tabs = listOf("제어", "비디오"),
                    selected = tab,
                    onSelect = { tab = it },
                )
                Spacer(Modifier.height(20.dp))
                if (tab == TAB_CONTROL) ControlTabContent(onDemoTap) else VideoTabContent(onDemoTap)
            }
            Spacer(Modifier.height(16.dp))
            Disclaimer()
        }
    }
}

/* ───────────────────────────── 공통 헤더 ───────────────────────────── */

/** "⛽ 999+km" — 작은 아이콘 박스 + 주행 가능 거리 */
@Composable
private fun RangeChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(KiaColors.SubCard)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) { MsIcon(R.drawable.ms_local_gas_station, "주행 가능 거리", size = 20.dp) }
        Spacer(Modifier.width(10.dp))
        Text(
            "999+km",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Normal,
            color = KiaColors.TextPrimary.copy(alpha = 0.9f),
        )
    }
}

/** 차량 윗모습(앞쪽이 왼쪽) + 가운데 잠금 아이콘. 세로 실루엣을 -90° 돌려 가로로 눕힌다 */
@Composable
private fun CarHeader(onClick: () -> Unit) {
    val carLength = 320.dp
    val carWidth = 170.dp
    Box(
        Modifier
            .fillMaxWidth()
            .height(carWidth + 10.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CarTopSilhouette(
            modifier = Modifier
                .width(carWidth)
                .rotate(-90f),
            height = carLength,
            body = Color(0xFF5A646E),
            shade = Color(0xFF2E3841),
            glass = Color(0xFF111A22),
            lockIcon = {
                Box(
                    Modifier
                        .rotate(90f)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0D161D).copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) { MsIcon(R.drawable.ms_lock, "잠김", size = 22.dp) }
            },
        )
    }
}

/** 밑줄 탭 — 선택: 흰색 굵게 + 2dp 흰 밑줄, 비선택: 회색 */
@Composable
private fun UnderlineTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selected
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) KiaColors.TextPrimary else KiaColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (isSelected) KiaColors.TextPrimary else Color.Transparent),
                )
            }
        }
    }
}

/* ───────────────────────────── 제어 탭 ───────────────────────────── */

@Composable
private fun ControlTabContent(onDemoTap: (String) -> Unit) {
    // 3열 타일 그리드 — 스크롤 안이라 LazyVerticalGrid 대신 Row 묶음
    controlTiles.chunked(3).forEachIndexed { rowIndex, rowTiles ->
        if (rowIndex > 0) Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            rowTiles.forEach { tile ->
                ControlTileCard(tile, onClick = { onDemoTap("${tile.title} ${tile.action}") })
            }
            repeat(3 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
        }
    }

    Spacer(Modifier.height(KiaDimens.SectionGap))
    SectionHeader("공조")
    KiaCard(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
        ListRow(
            title = "시동 / 공조 설정",
            verticalPadding = 14.dp,
            onClick = { onDemoTap("시동 / 공조 설정") },
        )
        RowDivider()
        ClimatePresetRow(
            items = listOf("10분", "Low", "사용자 설정"),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDemoTap("공조 프리셋") }
                .padding(vertical = 16.dp),
        )
    }

    Spacer(Modifier.height(KiaDimens.SectionGap))
    SectionHeader("차량")
    KiaCard(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        ListRow(title = "차량 상태", verticalPadding = 12.dp, onClick = { onDemoTap("차량 상태") })
        ListRow(title = "차량 점검", verticalPadding = 12.dp, onClick = { onDemoTap("차량 점검") })
    }
    Spacer(Modifier.height(KiaDimens.CardGap))
    KiaCard(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("발레 모드", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinePill("시작하기", onClick = { onDemoTap("발레 모드 시작하기") })
        }
    }
    Spacer(Modifier.height(KiaDimens.CardGap))
    KiaCard(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        ListRow(title = "차량 리모컨", verticalPadding = 12.dp, onClick = { onDemoTap("차량 리모컨") })
    }
}

/** 세로로 긴 제어 타일: 제목(굵게) / 동작 / 오른쪽 아래 큰 아이콘 */
@Composable
private fun RowScope.ControlTileCard(tile: ControlTile, onClick: () -> Unit) {
    KiaCard(
        modifier = Modifier
            .weight(1f)
            .height(170.dp),
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column {
                Text(
                    tile.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    tile.action,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Normal,
                    color = KiaColors.TextPrimary.copy(alpha = 0.9f),
                )
            }
            MsIcon(
                tile.icon,
                null,
                size = 30.dp,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/** "10분 | Low | 사용자 설정" — 라임 글자, 얇은 구분선 */
@Composable
private fun ClimatePresetRow(items: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .height(14.dp)
                        .background(KiaColors.Accent.copy(alpha = 0.4f)),
                )
            }
            Text(
                item,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                color = KiaColors.Accent,
            )
        }
    }
}

/* ───────────────────────────── 비디오 탭 ───────────────────────────── */

@Composable
private fun VideoTabContent(onDemoTap: (String) -> Unit) {
    SectionHeader("빌트인 캠")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KiaDimens.CardGap)) {
        // 이벤트 0
        KiaCard(
            modifier = Modifier.weight(1f),
            onClick = { onDemoTap("빌트인 캠 이벤트") },
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text("이벤트", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            Text("0", style = MaterialTheme.typography.headlineSmall)
        }
        // SD카드 ⓘ  ✓ 정상
        KiaCard(
            modifier = Modifier.weight(1f),
            onClick = { onDemoTap("SD카드 상태") },
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SD카드", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                Spacer(Modifier.width(4.dp))
                MsIcon(R.drawable.ms_info, "안내", tint = KiaColors.TextSecondary, size = 16.dp)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MsIcon(R.drawable.ms_check_circle, null, tint = KiaColors.ProgressGreen, size = 26.dp)
                Spacer(Modifier.width(8.dp))
                Text("정상", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    Spacer(Modifier.height(KiaDimens.CardGap))
    KiaCard(
        onClick = { onDemoTap("주차 중 녹화") },
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("주차 중 녹화", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(6.dp))
            MsIcon(R.drawable.ms_info, "안내", size = 18.dp)
        }
        Spacer(Modifier.height(14.dp))
        GreenProgress(fraction = 14f / 20f)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("14시간 가능", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("20시간 설정", style = MaterialTheme.typography.bodyMedium)
        }
    }

    Spacer(Modifier.height(KiaDimens.CardGap))
    KiaCard(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        ListRow(title = "차량에서 빌트인 캠 연결", verticalPadding = 12.dp, onClick = { onDemoTap("차량에서 빌트인 캠 연결") })
        ListRow(title = "빌트인 캠 저장 영상", verticalPadding = 12.dp, onClick = { onDemoTap("빌트인 캠 저장 영상") })
        ListRow(title = "주차 중 이벤트 영상", verticalPadding = 12.dp, onClick = { onDemoTap("주차 중 이벤트 영상") })
    }

    Spacer(Modifier.height(KiaDimens.SectionGap))
    SectionHeader("내 차 주변 보기")
    KiaCard(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        ListRow(title = "주변 촬영", verticalPadding = 12.dp, onClick = { onDemoTap("주변 촬영") })
        ListRow(title = "촬영 목록", verticalPadding = 12.dp, onClick = { onDemoTap("촬영 목록") })
    }
}
