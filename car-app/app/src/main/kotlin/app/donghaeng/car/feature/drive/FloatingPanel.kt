package app.donghaeng.car.feature.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.donghaeng.car.R
import app.donghaeng.car.domain.ptt.PttNotice

/*
 * 주행 중 순정 내비 위에 얹히는 플로팅 패널 (docs/05 §5.2, 사용자 확정 2026-10-28).
 *
 * 크기: 폭 ≤ 내비 화면 폭의 22%(Pleos 가로 2창 = 1707 dp × 22% ≈ 375 dp), 높이 ≤ 디스플레이 높이 30%(차 4대 + 이벤트 줄 + [무전] 76 dp 기준 ≈ 391 dp,
 *       5대 이상이면 4대 + "외 N대" 줄 ≈ 426 dp). 실제 폭·배율은 surface.PanelLayout 이 디스플레이에서 계산해 [width]·[scale] 로 준다.
 * 가독성(사용자 지시): 거리·도착 시각 숫자 31sp(시안 24sp의 1.3배), 이름 29sp(1.2배), 행 간격 10 dp 이상. 머리줄·이벤트 줄·무전 줄도 1.2배.
 *       이름 열(100 dp)에 안 들어가는 긴 닉네임("DemoCarB")은 20sp 까지 줄여 맞추고, 그래도 넘치면 말줄임(2026-10-28 실측 "De…" 개선).
 *       간격 숫자도 남은 폭에 안 들어가면(저잔량 아이콘 + "뒤 9.9 km" 처럼) 24sp 까지 줄여 단위 "km/m" 가 잘리지 않게 한다.
 * 머리줄(2026-10-28 사용자 지시): 그룹 이름이 머리글 — 기준 문구 "{목적지} 도착"은 자기 폭(상한 180 dp)으로 먼저 자리를 잡고
 *       그룹 이름이 남는 폭을 다 쓴다. 넘치면 **그룹 이름만** 말줄임("아빠 차와 같이…"), 상한을 넘는 아주 긴 목적지만 목적지 이름을 말줄임(" 도착"은 남김).
 * 위치: 오른쪽 위 고정(내비 TBT 왼쪽 위·하단 버튼을 피한다), 정차 중에만 드래그로 4모서리 중 선택(FloatingPanelService).
 * 주간/야간: 시스템 야간이면 짙은 반투명 바탕 + 흰 글씨, 주간이면 밝은 반투명 바탕 + 짙은 글씨. [무전] 라임 버튼은 둘 다 유지.
 * 무전(F16): 맨 아래 줄이 [무전] 버튼이자 무전 진행 상태(녹음·"보낼까요?" 확인·보냄·받은 무전 재생·안내) — `SurfaceState.ptt` 를 그대로 그린다.
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
    /** 머리줄 왼쪽 — 그룹 이름. 남는 폭을 다 쓰고, 그래도 넘치면 이것만 말줄임 */
    val groupName: String,
    /** 다른 차(선두부터, 최대 4대) */
    val cars: List<FloatingCar>,
    val event: EventBannerUi?,
    val ptt: PttUi = PttUi(),
    val connected: Boolean = true,
    /** 도착 시각의 기준 지점 이름 — "경포해변"(머리줄에는 "경포해변 도착") */
    val etaPlace: String,
    /** 4대를 넘는 나머지 차 수 — "외 N대" (0 이면 줄 없음) */
    val moreCount: Int = 0,
    /**
     * 실시간 연결이 끊긴 채 그룹 상태가 15초 넘게 오래됨 — "2분 전"(E038). 있으면 머리줄 기준 문구 자리에 "연결 끊김 · 2분 전"(주의 색, 나이는 잘리지 않음),
     * 차 줄은 흐리게. null = 새 정보
     */
    val staleText: String? = null,
) {
    /** 머리줄 기준 문구 전체 — "경포해변 도착" */
    val etaLabel: String get() = etaPlace + FloatingPanelSpec.ETA_SUFFIX
}

/** 패널 기준 치수 — scale 1 = Pleos 가로 2창(1 dp = 1 px, 내비 1707 dp) */
object FloatingPanelSpec {
    val WIDTH: Dp = 375.dp
    const val HEADER_SP = 22f
    /** 기준 문구 끝("{목적지} 도착") — 목적지 이름이 상한에 걸려 말줄임돼도 이 부분은 남는다 */
    const val ETA_SUFFIX = " 도착"
    /**
     * 머리줄 기준 문구("{목적지} 도착")의 상한 폭. 보통은 자기 폭 그대로(잘리지 않음) 두고 그룹 이름이 나머지를 쓴다.
     * 아주 긴 목적지만 여기서 목적지 이름을 말줄임한다 — 머리줄 안쪽 319 dp(375 − 여백 28 − 연결 점·간격 28) 중
     * 180 dp(22sp 한글 약 7자 + " 도착", "광주송정역 도착" ≈ 160 dp 까지 그대로)를 넘지 않게 해 그룹 이름 몫을 139 dp(약 6자) 이상 남긴다.
     */
    const val HEADER_LABEL_MAX_DP = 180f
    /**
     * 연결이 끊긴 채 오래된 값일 때 머리줄 "연결 끊김 · {나이}" 의 상한 폭(E038). 나이가 핵심이라 기준 문구 상한보다 넓게 —
     * "연결 끊김 · 23시간 전"(한글 7자 + 숫자 2 + 공백·가운뎃점, SemiBold 22sp ≈ 211 dp)이 잘리지 않고, 그룹 이름 몫은 99 dp(약 4자) 남는다.
     */
    const val STALE_LABEL_MAX_DP = 220f
    const val NAME_SP = 29f
    /** 긴 닉네임을 맞출 때 내려가는 하한 */
    const val NAME_MIN_SP = 20f
    /** 이름 열 폭 — 한글 3자(29sp) 또는 영문 8자(20sp) */
    const val NAME_WIDTH_DP = 100f
    const val NUMBER_SP = 31f
    /** 간격 숫자가 남은 폭에 안 들어갈 때 내려가는 하한 */
    const val NUMBER_MIN_SP = 24f
    const val UNIT_SP = 18f
    const val EVENT_SP = 24f
    const val PTT_SP = 31f
    /** [무전] 줄 높이 — 차량 기준 최소 터치 76 dp(E037) */
    const val PTT_ROW_DP = 76f
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

/** 주간 — 밝은 반투명 바탕 + 짙은 글씨(톤 색은 흰 바탕 대비 4.5:1 이상으로 진하게 — 정차 대시보드 주간 톤과 같은 값 [DriveTone.light]) */
private val LightPalette = PanelPalette(
    bg = Color(0xEDFAFBFC), text = Color(0xFF14171C), muted = Color(0xFF4A515C),
    danger = DriveTone.light.danger, caution = DriveTone.light.caution, info = DriveTone.light.info, eventAlpha = 0.14f, border = Color(0x26000000),
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
            // 머리줄: 그룹 이름(남는 폭, 넘치면 이것만 말줄임) · 기준 문구(자기 폭, 상한 180 dp) · 연결 점.
            // 연결이 끊긴 채 오래된 값이면 기준 문구 자리에 "연결 끊김 · 2분 전"(주의 색, E038)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ui.groupName, color = p.muted, fontSize = s.sp(FloatingPanelSpec.HEADER_SP), lineHeight = s.sp(26f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(s.dp(8f)))
                val stale = ui.staleText
                if (stale != null) {
                    StaleHeaderLabel(stale, p.caution, s)
                } else {
                    EtaHeaderLabel(ui.etaPlace, p.muted, s)
                }
                Spacer(Modifier.width(s.dp(8f)))
                Box(Modifier.size(s.dp(12f)).clip(CircleShape).background(if (ui.connected) p.info else p.caution))
            }
            // 차별 한 줄(선두부터): 색 점 + 이름 + 간격(내 차 기준) + 도착 시각. 오래된 값이면 흐리게
            Column(Modifier.alpha(if (ui.staleText != null) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(s.dp(FloatingPanelSpec.ROW_GAP_DP))) {
                if (ui.cars.isEmpty()) {
                    Text(stringResource(R.string.surface_panel_waiting), color = p.muted, fontSize = s.sp(FloatingPanelSpec.HEADER_SP), lineHeight = s.sp(28f))
                }
                ui.cars.forEach { CarRow(it, p, s) }
                if (ui.moreCount > 0) {
                    Text(stringResource(R.string.surface_panel_more, ui.moreCount), color = p.muted, fontSize = s.sp(FloatingPanelSpec.HEADER_SP), lineHeight = s.sp(26f), modifier = Modifier.padding(start = s.dp(22f)))
                }
            }
            // 최근 이벤트(10초 이내, 없으면 생략) — 문장이 잘리지 않게 최대 2줄(PanelLayout.BASE_HEIGHT_DP 가 2줄을 포함한다). 위험 이벤트의 음성이 늦으면 빨간 강조(+ 음성 시작 전까지 깜빡임) — EventHighlightPolicy
            ui.event?.let { e ->
                val c = p.tone(e.tone)
                val hl = EventHighlightStyle.isOn(e.highlight)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().alpha(highlightAlpha(e.highlight)).clip(RoundedCornerShape(s.dp(12f)))
                        .background(if (hl) p.danger else c.copy(alpha = p.eventAlpha)).padding(horizontal = s.dp(10f), vertical = s.dp(8f)),
                ) {
                    Icon(EventIcons.of(e.type, e.tone), contentDescription = null, tint = if (hl) Color.White else c, modifier = Modifier.size(s.dp(26f)))
                    Spacer(Modifier.width(s.dp(8f)))
                    Text(e.text, color = if (hl) Color.White else p.text, fontSize = s.sp(FloatingPanelSpec.EVENT_SP), lineHeight = s.sp(28f), fontWeight = if (hl) FontWeight.Bold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            PanelPttRow(ui.ptt, s, onPtt)
        }
    }
}

/**
 * 머리줄 기준 문구 "{목적지} 도착": 자기 폭 그대로 그린다(잘리지 않음). 전체가 [FloatingPanelSpec.HEADER_LABEL_MAX_DP] 를
 * 넘는 아주 긴 목적지일 때만 목적지 이름을 말줄임하고 " 도착" 은 남긴다("강릉 경포해변 중앙… 도착").
 * 바깥 머리줄에서 이 문구는 가중치 없이 먼저 재고, 그룹 이름(weight 1)이 남은 폭을 받는다.
 */
@Composable
private fun EtaHeaderLabel(place: String, color: Color, s: Scaler) {
    val size = s.sp(FloatingPanelSpec.HEADER_SP)
    Row(Modifier.widthIn(max = s.dp(FloatingPanelSpec.HEADER_LABEL_MAX_DP)), verticalAlignment = Alignment.CenterVertically) {
        Text(place, color = color, fontSize = size, lineHeight = s.sp(26f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(FloatingPanelSpec.ETA_SUFFIX, color = color, fontSize = size, lineHeight = s.sp(26f), maxLines = 1, softWrap = false)
    }
}

/**
 * 머리줄 "연결 끊김 · 10분 전"(E038): 핵심은 나이 — 나이는 자기 폭 그대로 두고, 폭이 모자라면 앞쪽 "연결 끊김 ·" 만 말줄임한다.
 * 상한 [FloatingPanelSpec.STALE_LABEL_MAX_DP](220 dp)는 기준 문구(180 dp)보다 넓다 — 끊긴 동안은 나이가 그룹 이름보다 중요하다
 * ("연결 끊김 · 23시간 전" ≈ 211 dp, SemiBold 22sp). 시안 `screen=design&variant=panel_stale`.
 */
@Composable
private fun StaleHeaderLabel(age: String, color: Color, s: Scaler) {
    val size = s.sp(FloatingPanelSpec.HEADER_SP)
    Row(Modifier.widthIn(max = s.dp(FloatingPanelSpec.STALE_LABEL_MAX_DP)), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.surface_panel_stale), color = color, fontSize = size, lineHeight = s.sp(26f), fontWeight = FontWeight.SemiBold,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Text(" $age", color = color, fontSize = size, lineHeight = s.sp(26f), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
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
        // 이름 열 고정폭 — 넘치면 글자를 줄여 맞추고(하한 20sp), 그래도 넘치면 말줄임
        FitName(c.name, if (c.offline) p.muted else p.text, s)
        Spacer(Modifier.width(s.dp(6f)))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (c.lowEnergy) {
                Canvas(Modifier.size(s.dp(22f))) { drawFuel(Offset.Zero, size.height, p.caution) }
                Spacer(Modifier.width(s.dp(4f)))
            }
            FitGap(c, numberColor, if (c.warn) p.caution else p.muted, s, Modifier.weight(1f))
        }
        Spacer(Modifier.width(s.dp(6f)))
        Text(c.etaText, color = p.text, fontSize = s.sp(FloatingPanelSpec.NUMBER_SP), lineHeight = s.sp(36f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

/** 이름 열: [FloatingPanelSpec.NAME_SP] 에서 1sp 씩 줄이며 열 폭에 들어가는 가장 큰 크기를 고른다(측정만, 레이아웃 반복 없음) */
@Composable
private fun FitName(name: String, color: Color, s: Scaler) {
    val measurer = rememberTextMeasurer()
    val base = LocalTextStyle.current
    val columnPx = with(LocalDensity.current) { s.dp(FloatingPanelSpec.NAME_WIDTH_DP).roundToPx() }
    val maxSp = FloatingPanelSpec.NAME_SP * s.k
    val minSp = FloatingPanelSpec.NAME_MIN_SP * s.k
    // measurer 는 글꼴 배율(fontScale)·밀도가 바뀌면 새로 만들어진다 → 키에 넣어 다시 잰다
    val fitted = remember(name, columnPx, maxSp, base, measurer) {
        var sp = maxSp
        while (sp > minSp) {
            val w = measurer.measure(name, base.merge(TextStyle(fontSize = sp.sp, fontWeight = FontWeight.SemiBold)), maxLines = 1, softWrap = false).size.width
            if (w <= columnPx) break
            sp -= 1f
        }
        sp.coerceAtLeast(minSp)
    }
    Text(
        name, color = color, fontSize = fitted.sp, lineHeight = s.sp(34f), fontWeight = FontWeight.SemiBold,
        modifier = Modifier.width(s.dp(FloatingPanelSpec.NAME_WIDTH_DP)), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 간격 "앞 1.2" + 단위 "km": 받은 폭(저잔량 아이콘을 뺀 나머지)에 숫자+단위가 다 들어가는 가장 큰 숫자 크기를
 * [FloatingPanelSpec.NUMBER_SP] → [FloatingPanelSpec.NUMBER_MIN_SP] 에서 고른다. 단위는 18sp 그대로.
 */
@Composable
private fun FitGap(c: FloatingCar, numberColor: Color, unitColor: Color, s: Scaler, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val base = LocalTextStyle.current
        val availPx = constraints.maxWidth
        val unitGapPx = with(LocalDensity.current) { s.dp(3f).roundToPx() }
        val weight = if (c.warn) FontWeight.Bold else FontWeight.SemiBold
        val maxSp = FloatingPanelSpec.NUMBER_SP * s.k
        val minSp = FloatingPanelSpec.NUMBER_MIN_SP * s.k
        val unitStyle = base.merge(TextStyle(fontSize = s.sp(FloatingPanelSpec.UNIT_SP)))
        val fitted = remember(c.gapText, c.gapUnit, availPx, maxSp, base, weight, measurer) {
            if (availPx == Constraints.Infinity) return@remember maxSp
            val unitPx = if (c.gapUnit.isEmpty()) 0 else measurer.measure(c.gapUnit, unitStyle, maxLines = 1, softWrap = false).size.width + unitGapPx
            var sp = maxSp
            while (sp > minSp) {
                val w = measurer.measure(c.gapText, base.merge(TextStyle(fontSize = sp.sp, fontWeight = weight)), maxLines = 1, softWrap = false).size.width
                if (w + unitPx <= availPx) break
                sp -= 1f
            }
            sp.coerceAtLeast(minSp)
        }
        Row {
            Text(
                c.gapText, color = numberColor, fontSize = fitted.sp, lineHeight = s.sp(36f),
                fontWeight = weight, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                modifier = Modifier.alignByBaseline(),
            )
            if (c.gapUnit.isNotEmpty()) {
                Spacer(Modifier.width(s.dp(3f)))
                Text(c.gapUnit, color = unitColor, fontSize = s.sp(FloatingPanelSpec.UNIT_SP), maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

/**
 * [무전] 버튼 또는 무전 진행 상태 한 줄(F16 — 사용자 지시 "무전 확인은 패널 안에서"). 줄 전체가 탭 영역이고 탭 = 무전 누름
 * (녹음 중 = 끝내기, 확인 중 = 취소, 그 밖 = 새 녹음). 무엇을 그릴지는 [PttDisplay.kind].
 * - 녹음: 빨강 + 파형 + "N초 남음" / 확인: 파랑 + "보낼까요?" + "네 / 아니라고 말하세요" + 5초 원형 카운트다운
 * - 카운트다운 모드(인식기 없음): "N초 뒤 보낼게요" + "무전 버튼 = 취소" / 전송: "보내는 중…" → "N대에 보냈어요"
 * - 재생: 보낸 차 색 + "아빠 차 무전 0:07"(끝나면 체크, 3초 뒤 사라짐) / 안내 3초: 너무 짧아요·취소했어요·보내지 않았어요… / 마이크 권한 없음
 */
@Composable
private fun PanelPttRow(ptt: PttUi, s: Scaler, onPtt: () -> Unit) {
    val kind = PttDisplay.kind(ptt)
    val (bg, fg) = when (kind) {
        PttDisplayKind.IDLE -> DriveTone.ptt to DriveTone.onPtt
        PttDisplayKind.MIC_DENIED -> DriveTone.caution to Color.Black
        PttDisplayKind.NOTICE -> if (PttDisplay.noticeTone(ptt.notice!!) == Tone.CAUTION) DriveTone.caution to Color.Black else PttColors.neutral to Color.White
        PttDisplayKind.RECORDING -> DriveTone.danger to Color.White
        PttDisplayKind.CONFIRM_VOICE, PttDisplayKind.CONFIRM_COUNTDOWN -> PttColors.confirm to Color.White
        PttDisplayKind.SENDING, PttDisplayKind.SENT -> DriveTone.info to Color.Black
        PttDisplayKind.PLAYING, PttDisplayKind.PLAYED -> Color(ptt.fromColorArgb ?: DriveDashboardSamples.BLUE) to Color.White
    }
    val big = s.sp(FloatingPanelSpec.PTT_SP)
    // 주행 중 내비 위에서 받는 유일한 탭 — 차량 기준 76 dp(E037, 예전 60 dp)
    Surface(onClick = onPtt, shape = RoundedCornerShape(s.dp(16f)), color = bg, modifier = Modifier.fillMaxWidth().height(s.dp(FloatingPanelSpec.PTT_ROW_DP))) {
        Row(Modifier.padding(horizontal = s.dp(16f)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            when (kind) {
                PttDisplayKind.IDLE -> {
                    Canvas(Modifier.size(s.dp(30f))) { drawMic(size, fg) }
                    Spacer(Modifier.width(s.dp(12f)))
                    Text(stringResource(R.string.dash_ptt), color = fg, fontSize = big, fontWeight = FontWeight.Bold)
                }
                PttDisplayKind.MIC_DENIED -> {
                    Canvas(Modifier.size(s.dp(26f))) { drawMic(size, fg) }
                    Spacer(Modifier.width(s.dp(10f)))
                    Text(stringResource(R.string.ptt_mic_denied), color = fg, fontSize = s.sp(24f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PttDisplayKind.NOTICE -> {
                    val (title, hint) = noticeTexts(ptt.notice!!)
                    TwoLine(stringResource(title), hint?.let { stringResource(it) }, fg, s, Modifier.weight(1f))
                }
                PttDisplayKind.RECORDING -> {
                    Box(Modifier.size(s.dp(14f)).clip(CircleShape).background(fg))
                    Spacer(Modifier.width(s.dp(10f)))
                    Waveform(ptt.level, fg, Modifier.width(s.dp(110f)).height(s.dp(30f)))
                    Spacer(Modifier.width(s.dp(12f)))
                    Text(stringResource(R.string.dash_ptt_seconds_left, ptt.seconds), color = fg, fontSize = s.sp(26f), fontWeight = FontWeight.Bold)
                }
                PttDisplayKind.CONFIRM_VOICE -> {
                    // 이 줄을 누르면 '네'가 아니라 취소다 — 보조 줄에 함께 말한다(E061, 대시보드 버튼의 "누르면 취소"와 같은 뜻)
                    TwoLine(stringResource(R.string.dash_ptt_confirm_big), stringResource(R.string.ptt_say_or_cancel), fg, s, Modifier.weight(1f), titleSp = 26f)
                    MiniCountdownRing(ptt.seconds, ptt.totalSeconds, fg, s)
                }
                PttDisplayKind.CONFIRM_COUNTDOWN -> {
                    TwoLine(stringResource(R.string.ptt_countdown_short, ptt.seconds), stringResource(R.string.ptt_cancel_hint), fg, s, Modifier.weight(1f), titleSp = 24f)
                    MiniCountdownRing(ptt.seconds, ptt.totalSeconds, fg, s)
                }
                PttDisplayKind.SENDING -> Text(stringResource(R.string.ptt_sending), color = fg, fontSize = s.sp(26f), fontWeight = FontWeight.Bold)
                PttDisplayKind.SENT -> {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = fg, modifier = Modifier.size(s.dp(28f)))
                    Spacer(Modifier.width(s.dp(8f)))
                    Text(sentText(ptt), color = fg, fontSize = s.sp(26f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PttDisplayKind.PLAYING, PttDisplayKind.PLAYED -> {
                    Icon(if (kind == PttDisplayKind.PLAYED) Icons.Filled.CheckCircle else Icons.Filled.PlayArrow, contentDescription = null, tint = fg, modifier = Modifier.size(s.dp(30f)))
                    Spacer(Modifier.width(s.dp(8f)))
                    Text(playingText(ptt), color = fg, fontSize = s.sp(26f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (ptt.queuedIncoming > 0) {
                        Spacer(Modifier.width(s.dp(8f)))
                        Text(stringResource(R.string.ptt_queued_more, ptt.queuedIncoming), color = fg, fontSize = s.sp(22f), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** 무전 줄 색(패널·대시보드 공용) — 확인 = 파랑, 버린 안내 = 짙은 회색 */
object PttColors {
    val confirm = Color(0xFF2F6BFF)
    val neutral = Color(0xFF3A414D)
}

/** 받은 무전 줄 — "아빠 차 무전 0:07"(이름을 모르면 "무전 0:07") */
@Composable
fun playingText(ptt: PttUi): String {
    val name = ptt.fromName?.trim().orEmpty()
    val dur = ptt.durationText ?: ""
    return if (name.isEmpty()) stringResource(R.string.ptt_playing_noname, dur) else stringResource(R.string.dash_ptt_playing_big, name, dur)
}

/** 보냄 줄 — "3대에 보냈어요"(받는 차 수를 모르면 "보냈어요") */
@Composable
fun sentText(ptt: PttUi): String =
    if (ptt.sentTo > 0) stringResource(R.string.dash_ptt_sent, ptt.sentTo) else stringResource(R.string.ptt_sent_plain)

/** 안내 문구(제목, 보조) — 패널 줄·대시보드 띠 공용 */
fun noticeTexts(n: PttNotice): Pair<Int, Int?> = when (n) {
    PttNotice.TOO_SHORT -> R.string.ptt_notice_too_short to null
    PttNotice.CANCELLED -> R.string.ptt_notice_cancelled to null
    PttNotice.KEPT_UNSENT -> R.string.ptt_notice_kept to R.string.ptt_notice_kept_hint
    PttNotice.SEND_FAILED -> R.string.ptt_notice_send_failed to R.string.ptt_notice_send_failed_hint
    PttNotice.RECORD_FAILED -> R.string.ptt_notice_record_failed to null
    PttNotice.MIC_PERMISSION -> R.string.ptt_notice_mic to R.string.ptt_notice_mic_hint
    PttNotice.NO_GROUP -> R.string.ptt_notice_no_group to null
}

/** 제목(굵게) + 보조 한 줄(작게) — 60 dp 줄 안에 두 줄 */
@Composable
private fun TwoLine(title: String, hint: String?, color: Color, s: Scaler, modifier: Modifier, titleSp: Float = 24f) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(title, color = color, fontSize = s.sp(titleSp), lineHeight = s.sp(titleSp + 4f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        hint?.let { Text(it, color = color.copy(alpha = 0.9f), fontSize = s.sp(16f), lineHeight = s.sp(19f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** 패널용 작은 원형 카운트다운(확인 5초) — 남은 몫만큼 호 + 가운데 숫자 */
@Composable
private fun MiniCountdownRing(seconds: Int, total: Int, color: Color, s: Scaler) {
    Box(Modifier.size(s.dp(46f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(s.dp(46f))) {
            val sw = s.dp(5f).toPx()
            val inset = sw / 2
            val arcSize = Size(size.width - sw, size.height - sw)
            drawArc(color.copy(alpha = 0.3f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
            val sweep = 360f * (seconds.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
            drawArc(color, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text("$seconds", color = color, fontSize = s.sp(24f), fontWeight = FontWeight.Bold)
    }
}

/** 시안·프리뷰 샘플(닉네임·장소는 가상). 실제 데이터는 surface.FloatingPanelMapper 가 만든다 */
object FloatingPanelSamples {
    val base = FloatingPanelUi(
        groupName = "강릉 가족 여행",
        etaPlace = "경포해변",
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

    /**
     * 폭 최악 조합(리뷰 2026-10-28): 긴 닉네임·저잔량 아이콘·경고 굵은 글씨 "뒤 9.9 km"·"앞 990 m"·"뒤 120 km" + "13:48",
     * 머리줄은 시연 기본 그룹 이름 "아빠 차와 같이 가기"(≈ 210 dp — "경포해변 도착" 옆 남은 ≈ 180 dp 에 안 들어감 → 그룹 이름만 말줄임).
     * 단위가 잘리지 않고 이름·숫자가 줄어드는지, 기준 문구는 그대로인지 `screen=design&variant=panel_worst` 로 확인한다.
     */
    val worst = base.copy(
        groupName = "아빠 차와 같이 가기",
        cars = listOf(
            FloatingCar("DemoCarB", DriveDashboardSamples.BLUE, gapText = "뒤 9.9", gapUnit = "km", etaText = "13:48", lowEnergy = true, warn = true),
            FloatingCar("아빠", DriveDashboardSamples.PURPLE, gapText = "앞 990", gapUnit = "m", etaText = "13:48", lowEnergy = true),
            FloatingCar("삼촌네 차", DriveDashboardSamples.GREEN, gapText = "뒤 120", gapUnit = "km", etaText = "23:59", lowEnergy = true, warn = true),
            FloatingCar("둘째", DriveDashboardSamples.ORANGE, gapText = "—", etaText = "—", offline = true, warn = true),
        ),
        event = DriveDashboardSamples.eventDanger,
    )

    /**
     * 아주 긴 목적지 + 긴 그룹 이름: 기준 문구가 상한 180 dp 에 걸려 목적지 이름만 말줄임(" 도착" 남음),
     * 그룹 이름은 남은 ≈ 139 dp 에서 말줄임 — `screen=design&variant=panel_longdest`.
     */
    val longDestination = base.copy(
        groupName = "추석 귀성길 삼촌네 합류",
        etaPlace = "강릉 경포해변 중앙광장 공영주차장",
    )

    /**
     * 연결이 끊긴 채 오래된 값(E038) + 긴 그룹 이름 + 가장 긴 나이 "23시간 전" — 머리줄 "연결 끊김 · 23시간 전" 이 잘리지 않고
     * 그룹 이름만 말줄임되는지 `screen=design&variant=panel_stale` 로 확인한다.
     */
    val stale = worst.copy(staleText = "23시간 전", connected = false, event = null)

    fun of(variant: String): FloatingPanelUi = when (variant) {
        "recording" -> base.copy(event = null, ptt = PttUi(PttPhase.RECORDING, seconds = 9, totalSeconds = 15, level = 0.7f))
        "confirm" -> base.copy(event = null, ptt = PttUi(PttPhase.CONFIRMING, seconds = 4, listening = true))
        "countdown" -> base.copy(event = null, ptt = PttUi(PttPhase.CONFIRMING, seconds = 3, fallbackCountdown = true))
        "sending" -> base.copy(event = null, ptt = PttUi(PttPhase.SENT, sending = true))
        "sent" -> base.copy(event = null, ptt = PttUi(PttPhase.SENT, sentTo = 3))
        "playing" -> base.copy(event = DriveDashboardSamples.eventDanger, ptt = PttUi(PttPhase.PLAYING, seconds = 3, fromName = "아빠 차", fromColorArgb = DriveDashboardSamples.BLUE, durationText = "0:07"))
        "notice" -> base.copy(event = null, ptt = PttUi(notice = PttNotice.KEPT_UNSENT))
        "mic" -> base.copy(event = null, ptt = PttUi(micDenied = true))
        "noevent" -> base.copy(event = null)
        "full" -> full
        "worst" -> worst
        "longdest" -> longDestination
        "stale" -> stale
        else -> base
    }
}
