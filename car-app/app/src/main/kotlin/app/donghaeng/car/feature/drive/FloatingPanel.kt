package app.donghaeng.car.feature.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.donghaeng.car.R

/*
 * 주행 중 순정 내비 위에 얹히는 플로팅 패널 (docs/05 §5.2, 사용자 확정 2026-10-28).
 *
 * 크기: 폭 ≤ 내비 화면 폭의 22%(Pleos 가로 2창 = 1707 dp × 22% ≈ 375 dp), 높이 ≤ 디스플레이 높이 30%(차 4대 + 이벤트 줄 + [무전] 기준 ≈ 375 dp,
 *       5대 이상이면 4대 + "외 N대" 줄 ≈ 410 dp). 실제 폭·배율은 surface.PanelLayout 이 디스플레이에서 계산해 [width]·[scale] 로 준다.
 * 가독성(사용자 지시): 거리·도착 시각 숫자 31sp(시안 24sp의 1.3배), 이름 29sp(1.2배), 행 간격 10 dp 이상. 머리줄·이벤트 줄·무전 줄도 1.2배.
 * 위치: 오른쪽 위 고정(내비 TBT 왼쪽 위·하단 버튼을 피한다), 정차 중에만 드래그로 4모서리 중 선택(FloatingPanelService).
 * 주간/야간: 시스템 야간이면 짙은 반투명 바탕 + 흰 글씨, 주간이면 밝은 반투명 바탕 + 짙은 글씨. [무전] 라임 버튼은 둘 다 유지.
 */

data class FloatingCar(
    val name: String,
    val colorArgb: Long,
    /** 방향 + 숫자(크게) — "앞 1.2". 값 없으면 "—" */
    val gapText: String,
    /** 단위(작게) — "km" / "m". 값 없으면 "" */
    val gapUnit: String = "",
    /** 목적지 도착 예상 "13:12", 없으면 "—" */
    val etaText: String,
    val lowEnergy: Boolean = false,
    /** 간격 3 km 초과 또는 연결 끊김 — 주황 */
    val warn: Boolean = false,
    val offline: Boolean = false,
)

data class FloatingPanelUi(
    val groupName: String,
    /** 다른 차(선두부터, 최대 4대) */
    val cars: List<FloatingCar>,
    val event: EventBannerUi?,
    val ptt: PttUi = PttUi(),
    val connected: Boolean = true,
    /** 도착 시각의 기준 지점 — "경포해변 도착" */
    val etaLabel: String,
    /** 4대를 넘는 나머지 차 수 — "외 N대" (0 이면 줄 없음) */
    val moreCount: Int = 0,
)

/** 패널 기준 치수 — scale 1 = Pleos 가로 2창(1 dp = 1 px, 내비 1707 dp) */
object FloatingPanelSpec {
    val WIDTH: Dp = 375.dp
    const val HEADER_SP = 22f
    const val NAME_SP = 29f
    const val NUMBER_SP = 31f
    const val UNIT_SP = 18f
    const val EVENT_SP = 24f
    const val PTT_SP = 31f
    const val ROW_GAP_DP = 10f
}

private data class PanelPalette(
    val bg: Color,
    val text: Color,
    val muted: Color,
    val danger: Color,
    val caution: Color,
    val info: Color,
    val eventAlpha: Float,
    val border: Color?,
)

/** 야간(기본) — 흰 지도·어두운 지도 어디서나 읽히는 짙은 반투명 */
private val DarkPalette = PanelPalette(
    bg = Color(0xE6161A20), text = Color(0xFFF2F4F8), muted = Color(0xFFB4BAC6),
    danger = DriveTone.danger, caution = DriveTone.caution, info = DriveTone.info, eventAlpha = 0.22f, border = null,
)

/** 주간 — 밝은 반투명 바탕 + 짙은 글씨(톤 색은 흰 바탕 대비 4.5:1 이상으로 진하게) */
private val LightPalette = PanelPalette(
    bg = Color(0xEDFAFBFC), text = Color(0xFF14171C), muted = Color(0xFF4A515C),
    danger = Color(0xFFC62828), caution = Color(0xFFB45309), info = Color(0xFF15803D), eventAlpha = 0.14f, border = Color(0x26000000),
)

private fun PanelPalette.tone(tone: Tone): Color = when (tone) {
    Tone.DANGER -> danger
    Tone.CAUTION -> caution
    Tone.INFO -> info
    Tone.NEUTRAL -> muted
}

@Composable
fun FloatingPanel(
    ui: FloatingPanelUi,
    modifier: Modifier = Modifier,
    light: Boolean = false,
    width: Dp = FloatingPanelSpec.WIDTH,
    maxHeight: Dp = Dp.Unspecified,
    scale: Float = 1f,
    onPtt: () -> Unit = {},
) {
    val p = if (light) LightPalette else DarkPalette
    val s = Scaler(scale)
    val sized = modifier.width(width).let { if (maxHeight != Dp.Unspecified) it.heightIn(max = maxHeight) else it }
    Surface(sized, shape = RoundedCornerShape(s.dp(22f)), color = p.bg, shadowElevation = 8.dp, border = p.border?.let { BorderStroke(1.dp, it) }) {
        Column(Modifier.padding(horizontal = s.dp(14f), vertical = s.dp(12f)), verticalArrangement = Arrangement.spacedBy(s.dp(10f))) {
            // 머리줄: 그룹 이름 · 기준 지점 · 연결 점
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ui.groupName, color = p.muted, fontSize = s.sp(FloatingPanelSpec.HEADER_SP), lineHeight = s.sp(26f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f))
                Spacer(Modifier.width(s.dp(8f)))
                Text(ui.etaLabel, color = p.muted, fontSize = s.sp(FloatingPanelSpec.HEADER_SP), lineHeight = s.sp(26f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(0.55f))
                Spacer(Modifier.width(s.dp(8f)))
                Box(Modifier.size(s.dp(12f)).clip(CircleShape).background(if (ui.connected) p.info else p.caution))
            }
            // 차별 한 줄(선두부터): 색 점 + 이름 + 간격(내 차 기준) + 도착 시각
            Column(verticalArrangement = Arrangement.spacedBy(s.dp(FloatingPanelSpec.ROW_GAP_DP))) {
                if (ui.cars.isEmpty()) {
                    Text(stringResource(R.string.surface_panel_waiting), color = p.muted, fontSize = s.sp(FloatingPanelSpec.HEADER_SP), lineHeight = s.sp(28f))
                }
                ui.cars.forEach { CarRow(it, p, s) }
                if (ui.moreCount > 0) {
                    Text(stringResource(R.string.surface_panel_more, ui.moreCount), color = p.muted, fontSize = s.sp(FloatingPanelSpec.HEADER_SP), lineHeight = s.sp(26f), modifier = Modifier.padding(start = s.dp(22f)))
                }
            }
            // 최근 이벤트 1줄(10초 이내, 없으면 생략)
            ui.event?.let { e ->
                val c = p.tone(e.tone)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(s.dp(12f))).background(c.copy(alpha = p.eventAlpha)).padding(horizontal = s.dp(10f), vertical = s.dp(8f)),
                ) {
                    Icon(DriveTone.icon(e.tone), contentDescription = null, tint = c, modifier = Modifier.size(s.dp(26f)))
                    Spacer(Modifier.width(s.dp(8f)))
                    Text(e.text, color = p.text, fontSize = s.sp(FloatingPanelSpec.EVENT_SP), lineHeight = s.sp(28f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            PanelPttRow(ui.ptt, s, onPtt)
        }
    }
}

/** 배율 적용 도우미 — 패널 폭이 기준(375 dp)보다 좁은 화면에서 글자·여백을 같은 비율로 줄인다 */
private class Scaler(val k: Float) {
    fun sp(v: Float): TextUnit = (v * k).sp
    fun dp(v: Float): Dp = (v * k).dp
}

@Composable
private fun CarRow(c: FloatingCar, p: PanelPalette, s: Scaler) {
    val numberColor = if (c.warn) p.caution else p.text
    Row(Modifier.fillMaxWidth().heightIn(min = s.dp(40f)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(s.dp(14f)).clip(CircleShape).background(if (c.offline) p.muted else Color(c.colorArgb)))
        Spacer(Modifier.width(s.dp(8f)))
        // 이름 열 고정폭(한글 3자) — 넘치면 말줄임
        Text(
            c.name, color = if (c.offline) p.muted else p.text, fontSize = s.sp(FloatingPanelSpec.NAME_SP), lineHeight = s.sp(34f), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(s.dp(88f)), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (c.lowEnergy) {
                Canvas(Modifier.size(s.dp(22f))) { drawFuel(Offset.Zero, size.height, p.caution) }
                Spacer(Modifier.width(s.dp(4f)))
            }
            Row {
                Text(
                    c.gapText, color = numberColor, fontSize = s.sp(FloatingPanelSpec.NUMBER_SP), lineHeight = s.sp(36f),
                    fontWeight = if (c.warn) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    modifier = Modifier.alignByBaseline(),
                )
                if (c.gapUnit.isNotEmpty()) {
                    Spacer(Modifier.width(s.dp(3f)))
                    Text(c.gapUnit, color = if (c.warn) p.caution else p.muted, fontSize = s.sp(FloatingPanelSpec.UNIT_SP), maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                }
            }
        }
        Spacer(Modifier.width(s.dp(6f)))
        Text(c.etaText, color = p.text, fontSize = s.sp(FloatingPanelSpec.NUMBER_SP), lineHeight = s.sp(36f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

/** [무전] 버튼 또는 무전 진행 상태 한 줄(녹음·확인 대기·전송·수신 재생) */
@Composable
private fun PanelPttRow(ptt: PttUi, s: Scaler, onPtt: () -> Unit) {
    val (bg, fg) = when (ptt.phase) {
        PttPhase.IDLE -> DriveTone.ptt to DriveTone.onPtt
        PttPhase.RECORDING -> DriveTone.danger to Color.White
        PttPhase.CONFIRMING -> Color(0xFF2F6BFF) to Color.White
        PttPhase.SENT -> DriveTone.info to Color.Black
        PttPhase.PLAYING -> Color(ptt.fromColorArgb ?: DriveDashboardSamples.BLUE) to Color.White
    }
    val big = s.sp(FloatingPanelSpec.PTT_SP)
    Surface(onClick = onPtt, shape = RoundedCornerShape(s.dp(16f)), color = bg, modifier = Modifier.fillMaxWidth().height(s.dp(60f))) {
        Row(Modifier.padding(horizontal = s.dp(16f)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            when (ptt.phase) {
                PttPhase.IDLE -> {
                    Canvas(Modifier.size(s.dp(30f))) { drawMic(size, fg) }
                    Spacer(Modifier.width(s.dp(12f)))
                    Text(stringResource(R.string.dash_ptt), color = fg, fontSize = big, fontWeight = FontWeight.Bold)
                }
                PttPhase.RECORDING -> {
                    Box(Modifier.size(s.dp(14f)).clip(CircleShape).background(fg))
                    Spacer(Modifier.width(s.dp(10f)))
                    Waveform(ptt.level, fg, Modifier.width(s.dp(110f)).height(s.dp(30f)))
                    Spacer(Modifier.width(s.dp(12f)))
                    Text(stringResource(R.string.dash_ptt_seconds_left, ptt.seconds), color = fg, fontSize = s.sp(26f), fontWeight = FontWeight.Bold)
                }
                PttPhase.CONFIRMING -> {
                    Text(stringResource(R.string.dash_ptt_confirm_big), color = fg, fontSize = s.sp(28f), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${ptt.seconds}", color = fg, fontSize = big, fontWeight = FontWeight.Bold)
                }
                PttPhase.SENT -> Text(stringResource(R.string.dash_ptt_sent, ptt.sentTo), color = fg, fontSize = s.sp(26f), fontWeight = FontWeight.Bold)
                PttPhase.PLAYING -> {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = fg, modifier = Modifier.size(s.dp(30f)))
                    Spacer(Modifier.width(s.dp(8f)))
                    Text(stringResource(R.string.dash_ptt_playing_big, ptt.fromName ?: "", ptt.durationText ?: ""), color = fg, fontSize = s.sp(26f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 시안·프리뷰 샘플(닉네임·장소는 가상). 실제 데이터는 surface.FloatingPanelMapper 가 만든다 */
object FloatingPanelSamples {
    val base = FloatingPanelUi(
        groupName = "강릉 가족 여행",
        etaLabel = "경포해변 도착",
        cars = listOf(
            FloatingCar("아빠", DriveDashboardSamples.BLUE, gapText = "앞 1.2", gapUnit = "km", etaText = "14:28"),
            FloatingCar("삼촌", DriveDashboardSamples.PURPLE, gapText = "뒤 2.1", gapUnit = "km", etaText = "14:31"),
            FloatingCar("둘째", DriveDashboardSamples.GREEN, gapText = "뒤 5.7", gapUnit = "km", etaText = "14:35", lowEnergy = true, warn = true),
        ),
        event = DriveDashboardSamples.eventCaution,
    )

    /** 다른 차 5대 → 4대 + "외 1대" (높이 상한 확인용) */
    val full = base.copy(
        cars = base.cars + FloatingCar("막내", DriveDashboardSamples.ORANGE, gapText = "뒤 350", gapUnit = "m", etaText = "14:33"),
        moreCount = 1,
    )

    fun of(variant: String): FloatingPanelUi = when (variant) {
        "recording" -> base.copy(event = null, ptt = PttUi(PttPhase.RECORDING, seconds = 9, totalSeconds = 15, level = 0.7f))
        "confirm" -> base.copy(event = null, ptt = PttUi(PttPhase.CONFIRMING, seconds = 4, listening = true))
        "playing" -> base.copy(event = DriveDashboardSamples.eventDanger, ptt = PttUi(PttPhase.PLAYING, seconds = 3, fromName = "아빠 차", fromColorArgb = DriveDashboardSamples.BLUE, durationText = "0:07"))
        "noevent" -> base.copy(event = null)
        "full" -> full
        else -> base
    }
}
