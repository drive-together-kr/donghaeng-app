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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.KiaButton
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.DestinationBanner
import app.donghaeng.companion.demo.ui.components.KiaChip
import app.donghaeng.companion.demo.ui.components.RoadCar
import app.donghaeng.companion.demo.ui.components.RoadStrip
import app.donghaeng.companion.demo.ui.components.KiaTag
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.protocol.MemberState

/**
 * 삽입 지점 B — 홈의 "내 차 위치" 카드 바로 위에 뜨는 "동행 중" 카드. 그룹 진행 중일 때만 노출된다.
 * 그룹명 · 멤버별 차량/ETA/잔량 배지 · 최근 이벤트 1줄 · [메시지 보내기]
 *
 * 머리줄은 관찰 연결 상태([DongHaengStore.connection])를 그대로 보인다(D021·D022·D028): 끊겨 다시 연결 중이면 회색 점·마지막 갱신 시각·[다시 시도],
 * 그룹이 끝났거나 내 차가 나갔으면 그 사실과 [기록으로 옮기기] — 멈춘 스냅샷을 '동행 중'으로 보이지 않게. 끝난 그룹에는 메시지를 보낼 수 없다.
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
    val connection by DongHaengStore.connection.collectAsStateWithLifecycle()
    KiaCard(modifier = modifier, onClick = onOpen) {
        val (label, color) = linkLabel(connection.link)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            Spacer(Modifier.weight(1f))
            MsIcon(R.drawable.ms_chevron_right, null, tint = KiaColors.TextSecondary, size = 20.dp)
        }
        ConnectionNotice(connection)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            KiaTag("${snapshot?.members?.size ?: group.members.size}대")
        }
        Spacer(Modifier.height(12.dp))
        // 목적지 사진 + 고속도로 표지, 그 아래 도로 띠(도착 예정이 짧을수록 앞) — 차 화면과 같은 디자인 언어
        if (group.destinationName.isNotBlank()) {
            DestinationBanner(group.destinationName, height = 112.dp)
            Spacer(Modifier.height(6.dp))
        }
        RoadStrip(roadCars(snapshot, group))
        Spacer(Modifier.height(10.dp))

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
        KiaButton("메시지 보내기", enabled = !connection.finished, onClick = onSendMessage)
    }
}

/**
 * 도로 띠에 놓을 차 — 남은 시간(ETA)이 짧을수록 앞(목적지 쪽). 남은 시간을 모르면 멤버 순서대로 고르게.
 * 스냅샷이 없으면 합류 때 멤버 목록으로.
 */
internal fun roadCars(snapshot: GroupSnapshot?, group: ActiveGroup): List<RoadCar> {
    val members = snapshot?.members
    if (members == null) {
        val n = group.members.size
        return group.members.mapIndexed { i, m -> RoadCar(m.nickname, parseHexColor(m.color), m.vehicleId == group.myVehicleId, if (n <= 1) 0.5f else 0.15f + 0.7f * i / (n - 1)) }
    }
    val remains = members.mapNotNull { it.eta?.remainS }
    val max = remains.maxOrNull()
    val min = remains.minOrNull()
    val n = members.size
    return members.mapIndexed { i, m ->
        val r = m.eta?.remainS
        val pos = when {
            r != null && max != null && min != null && max > min -> 0.12f + 0.76f * (max - r).toFloat() / (max - min).toFloat()
            r != null -> 0.6f
            n <= 1 -> 0.5f
            else -> 0.15f + 0.7f * i / (n - 1)
        }
        RoadCar(m.nickname, parseHexColor(m.color), m.vehicleId == group.myVehicleId, pos)
    }
}

/** 연결 상태 → 머리줄 문구·색 */
fun linkLabel(link: GroupLink): Pair<String, Color> = when (link) {
    GroupLink.LIVE -> "동행 중" to KiaColors.Accent
    GroupLink.CONNECTING -> "연결 중…" to KiaColors.TextSecondary
    GroupLink.RECONNECTING -> "다시 연결하는 중" to KiaColors.TextSecondary
    GroupLink.ENDED -> "그룹이 끝났어요" to KiaColors.TextSecondary
    GroupLink.LEFT -> "그룹에서 나갔어요" to KiaColors.TextSecondary
    GroupLink.AUTH_EXPIRED -> "다시 로그인이 필요해요" to KiaColors.Dot
    GroupLink.FAILED -> "연결이 끊겼어요" to KiaColors.Dot
}

/**
 * 연결이 정상이 아닐 때의 안내 줄 — 문구, 마지막 갱신 시각, [다시 시도](끊김) 또는 [기록으로 옮기기](끝남).
 * 동행 메인·지도 시트에서도 쓴다.
 */
@Composable
fun ConnectionNotice(connection: GroupConnection, modifier: Modifier = Modifier) {
    val message = connection.message ?: return
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KiaColors.SubCard)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall)
        connection.lastSnapshotAtMs?.let {
            Text("마지막 갱신 ${relativeTime(it)}", style = MaterialTheme.typography.labelSmall, color = KiaColors.TextSecondary)
        }
        when {
            connection.retryable -> {
                Spacer(Modifier.height(8.dp))
                KiaChip("다시 시도", icon = R.drawable.ms_refresh, onClick = { DongHaengStore.retryObserving() })
            }
            connection.finished -> {
                Spacer(Modifier.height(8.dp))
                KiaChip("기록으로 옮기기", icon = R.drawable.ms_history, onClick = { DongHaengStore.leaveLocally() })
            }
        }
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
    // 방장은 위임·승계로 바뀐다 — 합류 때 역할이 아니라 스냅샷의 지금 방장(D084)
    val leaderId = currentLeaderId(group, snapshot)
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
