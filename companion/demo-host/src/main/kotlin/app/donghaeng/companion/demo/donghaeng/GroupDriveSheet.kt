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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.KiaTag
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.OutlinePill
import app.donghaeng.companion.demo.ui.components.RowDivider
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.companion.sdk.MemberRole

/**
 * 삽입 지점 D — 지도 탭에서 "동행" 칩을 켰을 때 바텀시트가 바뀌는 "그룹 주행" 목록: 멤버별 ETA·선두와 거리·잔량.
 * 지도 위 마커는 [groupMarkers] 가 만든다. 이 컴포저블은 바텀시트 내용만 그린다(시트 틀은 MapScreen 소유).
 */
@Composable
fun GroupDriveSheet(
    group: ActiveGroup,
    snapshot: GroupSnapshot?,
    onSendMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leaderId = group.members.firstOrNull { it.role == MemberRole.LEADER }?.vehicleId
    Column(modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MsIcon(R.drawable.ms_groups, null, tint = KiaColors.Accent, size = 22.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("그룹 주행 · ${group.name}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    snapshot?.let { "${it.members.size}대 · 실시간" } ?: "그룹 상태를 기다리는 중…",
                    style = MaterialTheme.typography.bodySmall,
                    color = KiaColors.TextSecondary,
                )
            }
            OutlinePill("메시지", icon = R.drawable.ms_chat_bubble, onClick = onSendMessage)
        }
        Spacer(Modifier.height(12.dp))
        val members = snapshot?.members
        if (members == null) {
            group.members.forEachIndexed { i, m ->
                if (i > 0) RowDivider()
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(parseHexColor(m.color)))
                    Spacer(Modifier.width(10.dp))
                    Text(m.nickname, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (m.vehicleId == leaderId) KiaTag("선두")
                }
            }
        } else {
            members.forEachIndexed { i, m ->
                if (i > 0) RowDivider()
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(parseHexColor(m.color)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.nickname, style = MaterialTheme.typography.titleSmall)
                            if (m.vehicleId == group.myVehicleId) {
                                Spacer(Modifier.width(6.dp))
                                Text("내 차", style = MaterialTheme.typography.labelSmall, color = KiaColors.TextSecondary)
                            }
                        }
                        Text(
                            listOfNotNull(
                                statusLabel(m.status),
                                m.speedKph?.let { "${it.toInt()}km/h" },
                                energyLabel(m.energy),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = KiaColors.TextSecondary,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            etaLabel(m.eta?.remainS)?.let { if (it == "도착") "도착" else "ETA $it" } ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            color = KiaColors.Accent,
                        )
                        Text(
                            if (m.vehicleId == leaderId) "선두" else distanceLabel(m.distanceToLeaderM)?.let { "선두와 $it" } ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = KiaColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}
