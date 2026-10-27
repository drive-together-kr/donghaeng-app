package app.donghaeng.companion.demo.donghaeng

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.KiaButton
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.KiaTag
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.protocol.MemberState

/**
 * 삽입 지점 B — 홈의 "내 차 위치" 카드 바로 위에 뜨는 "동행 중" 카드. 그룹 진행 중일 때만 노출된다.
 * 그룹명 · 멤버별 차량/ETA/잔량 배지 · 최근 이벤트 1줄 · [메시지 보내기]
 */
@Composable
fun ActiveGroupCard(
    group: ActiveGroup,
    snapshot: GroupSnapshot?,
    latestNotification: DhNotification?,
    onSendMessage: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KiaCard(modifier = modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(KiaColors.Accent),
            )
            Spacer(Modifier.width(8.dp))
            Text("동행 중", style = MaterialTheme.typography.labelLarge, color = KiaColors.Accent)
            Spacer(Modifier.weight(1f))
            MsIcon(R.drawable.ms_chevron_right, null, tint = KiaColors.TextSecondary, size = 20.dp)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            KiaTag("${snapshot?.members?.size ?: group.members.size}대")
        }
        Spacer(Modifier.height(14.dp))

        val members = snapshot?.members
        if (members == null) {
            group.members.forEach { m ->
                MemberLine(color = parseHexColor(m.color), name = m.nickname, isMine = m.vehicleId == group.myVehicleId, eta = null, energy = null, status = "연결 중…")
            }
        } else {
            members.forEach { m ->
                MemberLine(
                    color = parseHexColor(m.color),
                    name = m.nickname,
                    isMine = m.vehicleId == group.myVehicleId,
                    eta = etaLabel(m.eta?.remainS),
                    energy = energyLabel(m.energy),
                    status = statusLabel(m.status),
                    energyLow = (energyPct(m.energy) ?: 100) <= 15,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(KiaColors.SubCard)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MsIcon(R.drawable.ms_notifications_active, null, tint = KiaColors.Accent, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                latestNotification?.body ?: "아직 새 소식이 없어요",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            latestNotification?.let {
                Text(relativeTime(it.atMs), style = MaterialTheme.typography.labelSmall, color = KiaColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        KiaButton("메시지 보내기", onClick = onSendMessage)
    }
}

@Composable
private fun MemberLine(
    color: Color,
    name: String,
    isMine: Boolean,
    eta: String?,
    energy: String?,
    status: String,
    energyLow: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isMine) {
            Text("내 차", style = MaterialTheme.typography.labelSmall, color = KiaColors.TextSecondary, modifier = Modifier.padding(end = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(text = eta?.let { if (it == "도착") "도착" else "ETA $it" } ?: status)
            energy?.let { Badge(text = it, color = if (energyLow) KiaColors.Dot else KiaColors.TextPrimary) }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color = KiaColors.TextPrimary) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(KiaColors.SubCard)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 지도(D)용 마커 변환 — 스냅샷이 없으면 멤버 목록만(위치 없음 → 마커 없음) */
fun groupMarkers(snapshot: GroupSnapshot?, group: ActiveGroup): List<app.donghaeng.companion.demo.ui.components.MapMarker> {
    val members = snapshot?.members ?: return emptyList()
    val leaderId = group.members.firstOrNull { it.role == app.donghaeng.companion.sdk.MemberRole.LEADER }?.vehicleId
    return members.mapNotNull { m: MemberState ->
        val lat = m.lat ?: return@mapNotNull null
        val lon = m.lon ?: return@mapNotNull null
        app.donghaeng.companion.demo.ui.components.MapMarker(
            id = m.vehicleId,
            lat = lat,
            lon = lon,
            label = m.nickname,
            color = parseHexColor(m.color),
            isLeader = m.vehicleId == leaderId,
            isMine = false,
        )
    }
}
