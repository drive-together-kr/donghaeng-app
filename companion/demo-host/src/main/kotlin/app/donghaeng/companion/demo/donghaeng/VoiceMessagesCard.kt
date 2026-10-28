package app.donghaeng.companion.demo.donghaeng

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.GreenProgress
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.RowDivider
import app.donghaeng.companion.demo.ui.theme.KiaColors

/**
 * 동행 메인(C) "진행 중 그룹" 탭의 무전 카드 — 그룹 차량이 보낸 무전(F16) 목록: 보낸 차(색·이름) · 길이 · 보낸 시각 · [재생].
 * 오디오는 동행 모듈이 미리 받아 둔 파일이다(READY 일 때만 버튼이 켜진다). 재생은 [VoicePlayer].
 */
@Composable
fun VoiceMessagesCard(items: List<VoiceItem>, modifier: Modifier = Modifier, maxRows: Int = 5) {
    val player by VoicePlayer.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    KiaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MsIcon(R.drawable.ms_headset_mic, null, tint = KiaColors.Accent, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text("무전", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) {
                Text("${items.size}건", style = MaterialTheme.typography.labelSmall, color = KiaColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (items.isEmpty()) {
            Text(
                "그룹 차량이 핸들 버튼으로 무전을 보내면 여기서 들을 수 있어요. 무전은 24시간 보관되고 그룹이 끝나면 지워져요.",
                style = MaterialTheme.typography.bodyMedium,
                color = KiaColors.TextSecondary,
            )
        }
        items.take(maxRows).forEachIndexed { i, item ->
            if (i > 0) RowDivider()
            VoiceRow(item = item, player = player, onToggle = { toggleVoice(context, item) })
        }
    }
}

@Composable
private fun VoiceRow(item: VoiceItem, player: VoicePlayer.State, onToggle: () -> Unit) {
    val playing = player.voiceId == item.voiceId
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(parseHexColor(item.senderColor)),
            contentAlignment = Alignment.Center,
        ) { MsIcon(R.drawable.ms_headset_mic, null, tint = KiaColors.TextPrimary, size = 18.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.senderNickname, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.mine) {
                    Text(" · 내 차", style = MaterialTheme.typography.labelSmall, color = KiaColors.TextSecondary)
                }
            }
            val sub = when {
                playing && player.preparing -> "재생 준비 중…"
                playing -> "${durationLabel(player.positionMs)} / ${durationLabel(player.durationMs)}"
                else -> listOfNotNull(durationLabel(item.durationMs), voiceStatusText(item.status) ?: relativeTime(item.sentAtMs)).joinToString(" · ")
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = KiaColors.TextSecondary)
            if (playing) {
                Spacer(Modifier.height(6.dp))
                GreenProgress(player.fraction)
            }
        }
        Spacer(Modifier.width(12.dp))
        VoicePlayButton(item = item, playing = playing, onClick = onToggle)
    }
}

/** 재생/멈춤 원형 버튼. READY 가 아니면 꺼진다 */
@Composable
fun VoicePlayButton(item: VoiceItem, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = playing || item.status == VoiceAudioStatus.READY
    val label = if (playing) "${item.senderNickname} 무전 멈춤" else "${item.senderNickname} 무전 재생"
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) KiaColors.Accent else KiaColors.SubCard)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(
            if (playing) R.drawable.ms_stop else R.drawable.ms_play_arrow,
            null,
            tint = if (enabled) KiaColors.Bg else KiaColors.TextTertiary,
            size = 22.dp,
        )
    }
}

/** 재생/멈춤 — 재생하지 못하면 이유를 토스트로 */
fun toggleVoice(context: Context, item: VoiceItem) {
    VoicePlayer.toggle(context, item)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
}
