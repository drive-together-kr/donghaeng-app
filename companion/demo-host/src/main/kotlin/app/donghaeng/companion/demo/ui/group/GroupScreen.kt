package app.donghaeng.companion.demo.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Severity
import kotlinx.serialization.json.jsonPrimitive

/** (4) 그룹 — 멤버 상태(닉네임·상태·ETA·잔량)와 최근 이벤트 5개, 동승자 메시지 보내기 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    onBack: () -> Unit,
    viewModel: GroupViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.name ?: "그룹") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("메시지 보내기 (차에서 음성으로 읽혀요)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            viewModel.send(text)
                            text = ""
                        },
                        enabled = text.isNotBlank() && !state.sending,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "메시지 보내기")
                    }
                }
            }
        },
    ) { padding ->
        val snapshot = state.snapshot
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            if (snapshot == null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("그룹 상태를 기다리는 중…")
                    }
                }
            } else {
                item { Text("멤버 ${snapshot.members.size}대", style = MaterialTheme.typography.titleMedium) }
                items(snapshot.members, key = { it.vehicleId }) { MemberRow(it) }

                item {
                    Spacer(Modifier.size(8.dp))
                    Text("최근 이벤트", style = MaterialTheme.typography.titleMedium)
                }
                val recent = snapshot.events.takeLast(5).asReversed()
                if (recent.isEmpty()) {
                    item { Text("아직 이벤트가 없어요", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(recent, key = { it.eventId }) { EventRow(it) }
                }
                if (state.sentCount > 0) {
                    item {
                        Text(
                            "보낸 메시지 ${state.sentCount}건",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: MemberState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(parseColor(member.color), CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(member.nickname, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        statusLabel(member.status),
                        member.speedKph?.let { "${it.toInt()} km/h" },
                        member.distanceToLeaderM?.takeIf { it > 0 }?.let { "선두까지 ${formatDistance(it)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    listOfNotNull(
                        member.eta?.remainS?.let { "도착까지 ${formatMinutes(it)}" },
                        energyLabel(member.energy),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: GroupEvent) {
    val tint = when (event.severity) {
        Severity.INFO -> MaterialTheme.colorScheme.primary
        Severity.WARN -> Color(0xFFB7791F)
        Severity.ALERT -> MaterialTheme.colorScheme.error
    }
    val body = event.speech
        ?: runCatching { event.payload["text"]?.jsonPrimitive?.content }.getOrNull()
        ?: event.type
    Row(verticalAlignment = Alignment.Top) {
        Text(eventLabel(event.type), style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.width(88.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

private fun statusLabel(status: MemberStatus): String = when (status) {
    MemberStatus.DRIVING -> "주행 중"
    MemberStatus.STOPPED -> "정차"
    MemberStatus.OFFLINE -> "오프라인"
    MemberStatus.ARRIVED -> "도착"
}

private fun energyLabel(energy: Energy?): String? = when {
    energy == null -> null
    energy.socPct != null -> "배터리 ${energy.socPct}%"
    energy.fuelPct != null -> "연료 ${energy.fuelPct}%"
    else -> null
}

private fun formatMinutes(seconds: Int): String {
    val minutes = (seconds + 59) / 60
    return if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
}

private fun formatDistance(meters: Int): String =
    if (meters >= 1000) String.format("%.1f km", meters / 1000.0) else "$meters m"

private fun eventLabel(type: String): String = when (GroupEventType.entries.firstOrNull { it.name == type }) {
    GroupEventType.LOW_ENERGY -> "잔량 부족"
    GroupEventType.HARD_BRAKE -> "급제동"
    GroupEventType.HAZARD_ON -> "비상등"
    GroupEventType.AIRBAG_DEPLOYED -> "에어백"
    GroupEventType.ROUTE_CHANGED -> "경로 변경"
    GroupEventType.WAYPOINT_ARRIVED -> "경유지 도착"
    GroupEventType.DESTINATION_ARRIVED -> "목적지 도착"
    GroupEventType.STOPPED -> "정차"
    GroupEventType.RESUMED -> "재출발"
    GroupEventType.PRESET_MESSAGE -> "프리셋"
    GroupEventType.MEMBER_JOINED -> "합류"
    GroupEventType.MEMBER_LEFT -> "이탈"
    GroupEventType.LEADER_CHANGED -> "방장 변경"
    GroupEventType.MEMBER_OFFLINE -> "오프라인"
    GroupEventType.REST_STOP_PROPOSAL -> "휴게소 제안"
    GroupEventType.REST_STOP_VOTE_RESULT -> "투표 결과"
    GroupEventType.DEVIATION -> "경로 이탈"
    GroupEventType.PASSENGER_MESSAGE -> "동승자"
    GroupEventType.GROUP_ENDED -> "그룹 종료"
    null -> type
}
