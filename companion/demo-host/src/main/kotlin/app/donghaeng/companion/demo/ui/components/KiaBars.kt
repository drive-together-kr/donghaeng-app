package app.donghaeng.companion.demo.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.theme.KiaColors

/** 하단 탭 5개 — 홈 / 지도 / 제어 / 스토어 / 마이카 */
enum class KiaTab(val label: String, @DrawableRes val icon: Int) {
    HOME("홈", R.drawable.ms_home),
    MAP("지도", R.drawable.ms_location_on),
    CONTROL("제어", R.drawable.ms_directions_car),
    STORE("스토어", R.drawable.ms_shopping_bag),
    MYCAR("마이카", R.drawable.ms_person),
}

/**
 * 상단바: 왼쪽 큰 제목(홈은 차량명), 오른쪽 아이콘 [QR 스캔(라임 가로줄 포인트)] [알림 벨(빨간 점)] [프로필].
 * 제어 탭은 [actions] 로 톱니만 넣는 식으로 조합한다.
 */
@Composable
fun KiaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onQr: (() -> Unit)? = null,
    onBell: (() -> Unit)? = null,
    bellUnread: Boolean = false,
    onSearch: (() -> Unit)? = null,
    onProfile: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(KiaColors.Bg)
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TopBarIcon(R.drawable.ms_arrow_back, "뒤로", onClick = onBack)
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onQr != null) QrScanIcon(onQr)
            if (onSearch != null) TopBarIcon(R.drawable.ms_search, "검색", onClick = onSearch)
            if (onBell != null) TopBarIcon(R.drawable.ms_notifications, "알림", dot = bellUnread, onClick = onBell)
            if (onProfile != null) TopBarIcon(R.drawable.ms_person, "프로필", onClick = onProfile)
            if (onSettings != null) TopBarIcon(R.drawable.ms_settings, "설정", onClick = onSettings)
            actions?.invoke(this)
        }
    }
}

/** QR 스캔 아이콘 — 네 모서리 괄호 + 가운데 라임 가로줄(기아 앱의 스캔 아이콘 느낌) */
@Composable
fun QrScanIcon(onClick: () -> Unit, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 26.dp) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(R.drawable.ms_qr_code_scanner, "QR 스캔", size = size)
        Box(
            Modifier
                .width(size * 0.62f)
                .height(2.5.dp)
                .background(KiaColors.Accent),
        )
    }
}

@Composable
fun TopBarIcon(@DrawableRes icon: Int, contentDescription: String, dot: Boolean = false, tint: Color = KiaColors.TextPrimary, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(icon, contentDescription, tint = tint, size = 26.dp)
        Box(Modifier.size(26.dp)) { RedDot(dot) }
    }
}

/** 하단 탭바 — 아웃라인 아이콘, 선택 시 흰색 굵게, 비선택 회색 */
@Composable
fun KiaBottomBar(selected: KiaTab, onSelect: (KiaTab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(KiaColors.Bg)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(KiaColors.Divider.copy(alpha = 0.6f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KiaTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val color = if (isSelected) KiaColors.TextPrimary else KiaColors.TextSecondary
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    MsIcon(tab.icon, tab.label, tint = color, size = 26.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
