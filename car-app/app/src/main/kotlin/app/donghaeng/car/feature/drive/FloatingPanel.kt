package app.donghaeng.car.feature.drive

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 주행 중 순정 내비 위에 얹히는 플로팅 패널 (방향 재정정 2026-10-28, docs/05 §5.2 예정).
 * 크기는 내비 화면의 10% 이하(가로 440 dp 이내), 위치는 내비 TBT(왼쪽 위)·지도 중심·하단 버튼을 피한 오른쪽 위 모서리.
 * 내용: 다른 차 이름·간격(내 차 기준)·휴게소 도착 시각 미니 목록 + 최근 이벤트 1줄 + [무전]. 무전 확인은 음성 대화로만 하고 여기에는 상태 한 줄만.
 * 내비 화면이 흰 배경(에뮬레이터 지도 스타일)·주간·야간 어디서나 읽히도록 짙은 반투명 바탕 + 흰 글씨 고정.
 */

data class FloatingCar(val name: String, val colorArgb: Long, val gapText: String, val etaText: String, val lowEnergy: Boolean = false, val warn: Boolean = false, val offline: Boolean = false)

data class FloatingPanelUi(
    val groupName: String,
    val cars: List<FloatingCar>,
    val event: EventBannerUi?,
    val ptt: PttUi = PttUi(),
    val connected: Boolean = true,
    /** 휴게소 도착 시각의 기준 지점 — "덕평휴게소 도착" */
    val etaLabel: String,
)

private val PanelBg = Color(0xE6161A20)
private val PanelText = Color(0xFFF2F4F8)
private val PanelMuted = Color(0xFFB4BAC6)

@Composable
fun FloatingPanel(ui: FloatingPanelUi, modifier: Modifier = Modifier, onPtt: () -> Unit = {}) {
    Surface(modifier.width(440.dp), shape = RoundedCornerShape(22.dp), color = PanelBg, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 머리줄: 그룹 이름 · 기준 지점 · 연결 점
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ui.groupName, color = PanelMuted, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(ui.etaLabel, color = PanelMuted, fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (ui.connected) DriveTone.info else DriveTone.caution))
            }
            // 차별 한 줄: 색 점 + 이름 + 간격 + 도착 시각
            ui.cars.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(if (c.offline) PanelMuted else Color(c.colorArgb)))
                    Spacer(Modifier.width(10.dp))
                    Text(c.name, color = PanelText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(84.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.lowEnergy) { Canvas(Modifier.size(22.dp)) { drawFuel(Offset.Zero, size.height, DriveTone.caution) }; Spacer(Modifier.width(6.dp)) }
                    Text(c.gapText, color = if (c.warn) DriveTone.caution else PanelText, fontSize = 24.sp, fontWeight = if (c.warn) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(c.etaText, color = PanelText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
            // 최근 이벤트 1줄 (없으면 생략)
            ui.event?.let { e ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DriveTone.color(e.tone).copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Icon(DriveTone.icon(e.tone), contentDescription = null, tint = DriveTone.color(e.tone), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(e.text, color = PanelText, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            PanelPttRow(ui.ptt, onPtt)
        }
    }
}

/** [무전] 버튼 또는 무전 진행 상태 한 줄(녹음·확인 대기·전송·수신 재생) */
@Composable
private fun PanelPttRow(ptt: PttUi, onPtt: () -> Unit) {
    val (bg, fg) = when (ptt.phase) {
        PttPhase.IDLE -> DriveTone.ptt to DriveTone.onPtt
        PttPhase.RECORDING -> DriveTone.danger to Color.White
        PttPhase.CONFIRMING -> Color(0xFF2F6BFF) to Color.White
        PttPhase.SENT -> DriveTone.info to Color.Black
        PttPhase.PLAYING -> Color(ptt.fromColorArgb ?: DriveDashboardSamples.BLUE) to Color.White
    }
    Surface(onClick = onPtt, shape = RoundedCornerShape(16.dp), color = bg, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            when (ptt.phase) {
                PttPhase.IDLE -> {
                    Canvas(Modifier.size(28.dp)) { drawMic(size, fg) }
                    Spacer(Modifier.width(12.dp))
                    Text("무전", color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text("핸들 버튼 가능", color = fg.copy(alpha = 0.7f), fontSize = 16.sp)
                }
                PttPhase.RECORDING -> {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(fg))
                    Spacer(Modifier.width(10.dp))
                    Waveform(ptt.level, fg, Modifier.width(120.dp).height(30.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("${ptt.seconds}초 남음", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                PttPhase.CONFIRMING -> {
                    Text("보낼까요?", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text("\"네\" / \"아니\"", color = fg.copy(alpha = 0.85f), fontSize = 20.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${ptt.seconds}", color = fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                PttPhase.SENT -> Text("무전 보냄 · ${ptt.sentTo}대", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                PttPhase.PLAYING -> {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = fg, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${ptt.fromName ?: ""} 무전 ${ptt.durationText ?: ""}", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

object FloatingPanelSamples {
    val base = FloatingPanelUi(
        groupName = "강릉 가족 여행",
        etaLabel = "덕평휴게소 도착",
        cars = listOf(
            FloatingCar("아빠", DriveDashboardSamples.BLUE, "앞 1.2 km", "13:12"),
            FloatingCar("삼촌", DriveDashboardSamples.PURPLE, "뒤 2.1 km", "13:15"),
            FloatingCar("둘째", DriveDashboardSamples.GREEN, "뒤 5.7 km", "13:19", lowEnergy = true, warn = true),
        ),
        event = DriveDashboardSamples.eventCaution,
    )

    fun of(variant: String): FloatingPanelUi = when (variant) {
        "recording" -> base.copy(event = null, ptt = PttUi(PttPhase.RECORDING, seconds = 9, totalSeconds = 15, level = 0.7f))
        "confirm" -> base.copy(event = null, ptt = PttUi(PttPhase.CONFIRMING, seconds = 4, listening = true))
        "playing" -> base.copy(event = DriveDashboardSamples.eventDanger, ptt = PttUi(PttPhase.PLAYING, seconds = 3, fromName = "아빠 차", fromColorArgb = DriveDashboardSamples.BLUE, durationText = "0:07"))
        "noevent" -> base.copy(event = null)
        else -> base
    }
}
