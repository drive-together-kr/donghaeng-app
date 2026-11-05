package app.donghaeng.car.feature.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.donghaeng.car.R
import app.donghaeng.car.domain.ptt.PttFormat

/*
 * 주행 대시보드 (재설계안 2차 — docs/05 §5.1, 사용자 지침 2026-10-28)
 * 용도(방향 재정정): 그룹 주행 경로(GROUP_DRIVE)의 정차 화면 — 실데이터는 DriveDashboardMapper(GroupDriveViewModel 상태).
 * 주행 중 전면은 순정 내비이고 동행은 플로팅 패널·알림 카드·음성으로만 정보를 준다(docs/05 §5.2).
 * 정차 블록: 지난 소식(+더 보기)·그룹 관리·설정·홈. 프리셋(F8)·보내지 않은 무전(F16)은 데이터가 있을 때만.
 *
 * 가로: 대열 막대(내용 맞춤 높이) → [그룹 도착 | 다음 정차](남는 높이 전부, 숫자 84sp) → 휴식 추천 줄(순정 내비가 보일 때) 또는 제한속도·회전 줄
 *       → 이벤트 띠(이벤트 있을 때만) → 하단 [무전](라임)·[내비 보기]
 * 세로(1창): 같은 블록을 위에서 아래로, 대열은 수직 사다리(글자 크기 가로와 동일).
 * 무전(F16 — 실제 상태 = SurfaceState.ptt, 패널과 같은 값): 녹음·확인은 대열 막대 아래를 덮는 중앙 오버레이, 수신 재생·보내는 중·보냄·3초 안내는 상단 띠,
 *   하단 [무전]은 녹음 중 빨강(누르면 끝)·확인 중 파랑 "누르면 취소"·마이크 권한 없으면 아래 줄 "마이크 권한 필요"(+정차 중 [권한 열기]).
 *   정차 블록: 보내지 않은 무전([다시 보내기]·[지우기])·지난 무전(탭 = 다시 듣기).
 */

// ---------------------------------------------------------------- 톤 색

/** 톤 색 한 벌(위험·주의·정보) — 다크/주간 두 벌을 둔다 */
data class ToneColors(val danger: Color, val caution: Color, val info: Color)

/**
 * 톤 색(E041): 다크 바탕용 밝은 톤([dark] — 바탕 대비 8~10:1)과 주간 흰 바탕용 진한 톤([light] — 플로팅 패널 LightPalette 와 같은 값, 흰 바탕 대비 4.5:1 이상).
 * 글자·아이콘·선은 [tones]/[color] 로 화면 바탕에 맞는 쪽을 쓴다. [danger]·[caution]·[info] 그대로 쓰는 곳은 꽉 찬 바탕(흰·검정 글자를 얹음)뿐이다.
 */
object DriveTone {
    val danger = Color(0xFFFF4D4F)
    val caution = Color(0xFFFF9F1C)
    val info = Color(0xFF2ECC71)

    val dark = ToneColors(danger, caution, info)
    val light = ToneColors(danger = Color(0xFFC62828), caution = Color(0xFFB45309), info = Color(0xFF15803D))

    /** [무전] 버튼 — 다크 배경에서 검정 글자와 대비 ≥ 4.5:1 (라임). 흰 바탕에선 경계가 안 보여 주간엔 진한 테두리를 함께 그린다 */
    val ptt = Color(0xFFC6FF3D)
    val onPtt = Color(0xFF101400)
    val pttBorderLight = Color(0xFF4D6B00)

    /** 지금 화면 바탕(밝기)에 맞는 톤 색 */
    @Composable
    fun tones(): ToneColors = if (isLightBackground()) light else dark

    @Composable
    fun isLightBackground(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

    @Composable
    fun color(tone: Tone): Color {
        val t = tones()
        return when (tone) {
            Tone.DANGER -> t.danger
            Tone.CAUTION -> t.caution
            Tone.INFO -> t.info
            Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    fun icon(tone: Tone): ImageVector = when (tone) {
        Tone.DANGER, Tone.CAUTION -> Icons.Filled.Warning
        Tone.INFO -> Icons.Filled.CheckCircle
        Tone.NEUTRAL -> Icons.Filled.Info
    }
}

private val FORMATION_HEIGHT = 236.dp

/** 스크롤 모드(짧은 화면)에서 정차 영역(빠른 메시지·최근 소식 — 안에 자체 스크롤이 있다)에 주는 고정 높이 */
private val PARKED_EXTRAS_SCROLL_HEIGHT = 420.dp

/** 대열 막대: 막대 중심 → 차 이름 줄 중심 거리, 한 줄 올릴 때 간격(이름 줄 → 그 위 줄) */
private val NAME_ROW = 52.dp
private val ROW_STEP = 30.dp
private val LADDER_HEIGHT = 330.dp

/** 연결이 끊긴 채 오래된 값의 불투명도(E038) */
private const val STALE_ALPHA = 0.5f

// ---------------------------------------------------------------- 화면

@Composable
fun DriveDashboardScreen(
    ui: DriveDashboardUi,
    modifier: Modifier = Modifier,
    onPtt: () -> Unit = {},
    onOpenNavi: () -> Unit = {},
    onPreset: (String) -> Unit = {},
    onManageGroup: () -> Unit = {},
    onSettings: () -> Unit = {},
    /** '보내지 않은 무전' [다시 보내기](항목 id) — 정차 블록에만 보인다 */
    onResendUnsent: (String) -> Unit = {},
    /** '보내지 않은 무전' [지우기](항목 id) */
    onDeleteUnsent: (String) -> Unit = {},
    /** '지난 무전' 탭 = 다시 듣기(voiceId) */
    onReplayPast: (String) -> Unit = {},
    /** 마이크 권한 다시 묻기 — null 이면 [권한 열기] 없음(정차 중에만 보인다) */
    onOpenMicPermission: (() -> Unit)? = null,
    /** 정차 블록 '지난 소식' 의 [더 보기](이벤트 피드). null 이면 버튼 없음 */
    onOpenEvents: (() -> Unit)? = null,
    /** 정차 블록 [홈]. null 이면 버튼 없음 */
    onHome: (() -> Unit)? = null,
    /** 플로팅 창 권한 설정 화면 열기 — 기기에 그 화면이 없으면 null(버튼 숨김) */
    onOpenOverlaySettings: (() -> Unit)? = null,
    /** 도착 예상이 없을 때(순정 내비 안내 전) [목적지 안내 시작] — 정차 중에만 보인다. null 이면 버튼 없음(4-3·5-1) */
    onStartGuidance: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val narrow = maxWidth < 900.dp
            // 화면 비율 대응(2026-11-05 점검): 높이가 낮은 와이드 화면(8:3 1920×720 등)·좁고 짧은 화면에서는 카드를 짜부라뜨리지 않고
            // 자연 높이로 두고 내용을 세로 스크롤한다 — 그룹 도착 시각·다음 정차 거리 같은 핵심 숫자가 잘리지 않게
            val short = maxHeight < (if (narrow) 1000.dp else 980.dp)
            val tight = maxWidth < 620.dp
            // E038: 실시간 연결이 끊긴 채 오래된 값은 흐리게(아래 줄에 "2분 전 정보")
            val staleAlpha = if (ui.staleText != null) STALE_ALPHA else 1f
            val guidance = if (ui.parked) onStartGuidance else null
            Column(Modifier.fillMaxSize()) {
                // 안내 문구는 늘 보이고 [권한 열기](설정 화면으로 이동)는 정차 중에만 — 주행 중 입력 UI 비활성(CLAUDE.md 규칙 6).
                // 권한 화면이 없는 기기(깨끗한 Pleos AVD, docs/10 R16)는 정차 중에 설치 안내 한 줄을 더한다(E039)
                if (ui.overlayNotice) OverlayNotice(if (ui.parked) onOpenOverlaySettings else null, showNoSettingsHint = ui.parked && onOpenOverlaySettings == null)
                PttTopBand(ui.ptt)
                Box(Modifier.weight(1f)) {
                    val scroll = if (short) Modifier.verticalScroll(rememberScrollState()) else Modifier
                    Column(Modifier.fillMaxSize().then(scroll).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (narrow) {
                            FormationLadder(ui.formation, ui.groupName, Modifier.fillMaxWidth().height(LADDER_HEIGHT).alpha(staleAlpha))
                            ArrivalCard(ui.arrival, Modifier.fillMaxWidth().alpha(staleAlpha), onStartGuidance = guidance)
                            ui.nextStop?.let { NextStopCard(it, Modifier.fillMaxWidth(), compact = true) }
                            if (ui.naviVisible) RestRow(ui.myDrive, Modifier.fillMaxWidth()) else MyDriveLine(ui.myDrive, Modifier.fillMaxWidth(), compact = true)
                            ui.event?.let { EventBanner(it, Modifier.fillMaxWidth()) }
                            if (ui.parked) ParkedExtras(ui, false, if (short) Modifier.height(PARKED_EXTRAS_SCROLL_HEIGHT) else Modifier.weight(1f), onPreset, onManageGroup, onSettings, onResendUnsent, onDeleteUnsent, onReplayPast, onOpenEvents, onHome)
                            else if (!short) Spacer(Modifier.weight(1f))
                        } else {
                            FormationBar(ui.formation, ui.groupName, Modifier.fillMaxWidth().height(FORMATION_HEIGHT).alpha(staleAlpha))
                            // 카드가 남는 높이를 전부 가진다(이벤트 띠가 없으면 그만큼 더 커진다)
                            // 짧은 화면: 남는 높이를 나눠 갖지 않고 두 카드 중 큰 쪽 높이에 맞춘다(숫자가 잘리지 않음)
                            Row(if (short) Modifier.fillMaxWidth().height(IntrinsicSize.Min) else Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ArrivalCard(ui.arrival, Modifier.weight(1f).fillMaxHeight().alpha(staleAlpha), big = !short, onStartGuidance = guidance)
                                ui.nextStop?.let { NextStopCard(it, Modifier.weight(1.45f).fillMaxHeight(), big = !short) }
                            }
                            if (ui.naviVisible) RestRow(ui.myDrive, Modifier.fillMaxWidth(), big = !short) else MyDriveLine(ui.myDrive, Modifier.fillMaxWidth(), big = !short)
                            ui.event?.let { EventBanner(it, Modifier.fillMaxWidth(), big = true) }
                            if (ui.parked) ParkedExtras(ui, landscape, if (short) Modifier.height(PARKED_EXTRAS_SCROLL_HEIGHT) else Modifier.weight(1.4f), onPreset, onManageGroup, onSettings, onResendUnsent, onDeleteUnsent, onReplayPast, onOpenEvents, onHome)
                        }
                    }
                    if (ui.ptt.phase == PttPhase.RECORDING || ui.ptt.phase == PttPhase.CONFIRMING) {
                        PttCenterOverlay(ui.ptt, topInset = (if (narrow) LADDER_HEIGHT else FORMATION_HEIGHT) + 20.dp)
                    }
                }
                BottomBar(ui, onPtt, onOpenNavi, compact = narrow, onOpenMicPermission = onOpenMicPermission, tight = tight)
            }
        }
    }
}

/**
 * '알림 카드 + 음성' 모드 안내 1줄(플로팅 창 권한 없음) — 설정 화면이 있는 기기·정차 중에만 [권한 열기].
 * 설정 화면이 없는 기기(깨끗한 Pleos AVD)면 정차 중에 [showNoSettingsHint] — 설치 안내(README)의 한 줄 명령으로 켤 수 있다는 안내(E039).
 */
@Composable
private fun OverlayNotice(onOpenSettings: (() -> Unit)?, showNoSettingsHint: Boolean = false) {
    val caution = DriveTone.tones().caution
    Surface(Modifier.fillMaxWidth(), color = DriveTone.caution.copy(alpha = 0.16f)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = caution, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.surface_cards_only_notice), fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                onOpenSettings?.let { open ->
                    OutlinedButton(onClick = open, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.surface_open_permission), fontSize = 20.sp) }
                }
            }
            if (showNoSettingsHint) {
                Text(stringResource(R.string.surface_overlay_no_settings), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 40.dp, top = 2.dp), maxLines = 2)
            }
        }
    }
}

// ---------------------------------------------------------------- 라벨 겹침 처리

private data class Label(val cx: Float, val text: String)

/** [fitLabels] 결과 — [fits] = false 면 가장 작은 크기·약칭으로도 겹친다(그리는 쪽이 위아래로 엇갈리게 둔다) */
private data class FittedLabels(val labels: List<Label>, val style: TextStyle, val fits: Boolean)

/** 인접 라벨이 [gapPx] 이상 떨어지도록 글자 크기(22→20→18→16sp)를 고르고, 그래도 겹치면 [abbreviate] 로 줄인다 */
private fun fitLabels(
    measurer: TextMeasurer,
    labels: List<Label>,
    styleOf: (Int) -> TextStyle,
    gapPx: Float,
    abbreviate: ((String) -> String)? = null,
): FittedLabels {
    val sizes = listOf(22, 20, 18, 16)
    fun fits(ls: List<Label>, style: TextStyle): Boolean {
        var lastRight = Float.NEGATIVE_INFINITY
        for (l in ls.sortedBy { it.cx }) {
            val w = measurer.measure(l.text, style).size.width
            if (l.cx - w / 2f < lastRight + gapPx) return false
            lastRight = l.cx + w / 2f
        }
        return true
    }
    for (sp in sizes) {
        val st = styleOf(sp)
        if (fits(labels, st)) return FittedLabels(labels, st, true)
    }
    if (abbreviate != null) {
        val shorter = labels.map { it.copy(text = abbreviate(it.text)) }
        for (sp in sizes) {
            val st = styleOf(sp)
            if (fits(shorter, st)) return FittedLabels(shorter, st, true)
        }
        return FittedLabels(shorter, styleOf(16), false)
    }
    return FittedLabels(labels, styleOf(16), false)
}

private fun shortName(name: String): String {
    if (name.contains('·') || name.endsWith("함께")) return name   // 묶음 라벨("아빠·둘째", "3대 함께")은 그대로
    val base = name.removeSuffix(" 차").removeSuffix("차").trim()
    return if (base.length <= 2) base else base.take(2) + "…"
}

// ---------------------------------------------------------------- 1. 대열 막대

/**
 * 가로 대열 막대. 막대 끝 = max(다음 정차지, 선두)([FormationLayout.endKm]). 화면에서 원 지름 + 8 dp 보다 가까운 차는 한 묶음 —
 * 원을 살짝 겹쳐 쌓고(내 차를 맨 위에) 라벨 하나("아빠·둘째" / "3대 함께")만 그린다(E036). 라벨이 끝까지 겹치면 위아래로 엇갈린다.
 */
@Composable
fun FormationBar(ui: FormationBarUi, groupName: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tones = DriveTone.tones()
    val nameStyleOf = { sp: Int -> TextStyle(fontSize = sp.sp, fontWeight = FontWeight.SemiBold, color = onSurface) }
    val gapStyleOf = { sp: Int -> TextStyle(fontSize = sp.sp, color = muted) }
    val gapWarnStyleOf = { sp: Int -> TextStyle(fontSize = sp.sp, color = tones.caution, fontWeight = FontWeight.SemiBold) }
    val stopStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
    val destStyle = TextStyle(fontSize = 20.sp, color = muted)
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(groupName, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(12.dp))
                Text(ui.formationLengthText, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = muted, maxLines = 1)
            }
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val y = size.height * 0.56f
                val x0 = size.width * 0.10f
                val x1 = size.width * 0.90f
                val sorted = ui.cars.sortedBy { it.offsetKm }
                val minKm = sorted.firstOrNull()?.offsetKm ?: 0f
                val endKm = FormationLayout.endKm(sorted, ui.nextStopOffsetKm)
                val span = (endKm - minKm).coerceAtLeast(0.1f)
                fun xOf(km: Float) = x0 + (x1 - x0) * ((km - minKm) / span).coerceIn(0f, 1f)

                drawLine(track, Offset(size.width * 0.02f, y), Offset(size.width * 0.98f, y), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
                sorted.zipWithNext().forEach { (rear, ahead) ->
                    if (rear.gapWarn) drawLine(tones.caution, Offset(xOf(rear.offsetKm), y), Offset(xOf(ahead.offsetKm), y), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
                }
                // 정차지 눈금만 먼저 — 라벨은 차 이름·간격 라벨 자리를 안 뒤 겹치지 않는 줄에 그린다(이 Canvas 맨 끝)
                val stopX = ui.nextStopName?.let { xOf(ui.nextStopOffsetKm ?: endKm) }
                stopX?.let { sx -> drawLine(onSurface, Offset(sx, y - 16.dp.toPx()), Offset(sx, y + 16.dp.toPx()), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round) }

                val r = 19.dp.toPx()
                val xs = sorted.map { xOf(it.offsetKm) }
                val groups = FormationLayout.clusters(xs, 2 * r + 8.dp.toPx())
                val myKm = sorted.firstOrNull { it.isMe }?.offsetKm
                data class Cluster(val cars: List<FormationCar>, val cx: Float)
                val clusters = groups.map { g -> Cluster(g.map { sorted[it] }, g.map { xs[it] }.average().toFloat()) }
                clusters.forEach { c ->
                    // 자리는 대열 순서대로(뒤 → 앞), 그리는 순서는 내 차를 맨 마지막(맨 위)
                    val placedXs = if (c.cars.size == 1) listOf(c.cx) else FormationLayout.stackXs(c.cars.size, c.cx, r * 0.9f)
                    val placed = c.cars.zip(placedXs).sortedBy { it.first.isMe }
                    placed.forEach { (car, cx) ->
                        val color = Color(car.colorArgb)
                        if (car.isMe) drawCircle(color.copy(alpha = 0.25f), r + 10.dp.toPx(), Offset(cx, y))
                        drawCircle(color, r, Offset(cx, y))
                        if (car.offline) drawCircle(onSurface.copy(alpha = 0.5f), r, Offset(cx, y), style = Stroke(3.dp.toPx()))
                        if (car.isLeader) drawCircle(Color.White, 6.dp.toPx(), Offset(cx, y))
                        if (car.isMe || c.cars.size > 1) drawCircle(Color.White, r, Offset(cx, y), style = Stroke(if (car.isMe) 3.dp.toPx() else 1.5.dp.toPx()))
                    }
                }
                val fitted = fitLabels(measurer, clusters.map { Label(it.cx, FormationLayout.clusterLabel(it.cars.map { car -> car.name })) }, nameStyleOf, 8.dp.toPx(), ::shortName)
                val nearSpans = mutableListOf<FormationLayout.Span>()
                val highSpans = mutableListOf<FormationLayout.Span>()
                fitted.labels.forEachIndexed { i, l ->
                    val t = measurer.measure(l.text, fitted.style)
                    // 끝까지 겹치면(아주 촘촘한 대열) 홀수 번째를 한 줄 위로 — 겹쳐 그리지 않는다
                    val raised = !fitted.fits && i % 2 == 1
                    val raise = if (raised) ROW_STEP.toPx() else 0f
                    val top = y - NAME_ROW.toPx() - raise - t.size.height / 2f
                    drawText(t, topLeft = Offset(l.cx - t.size.width / 2f, top))
                    val low = clusters[i].cars.any { it.lowEnergy }
                    if (low) drawFuel(Offset(l.cx + t.size.width / 2f + 4.dp.toPx(), top + t.size.height / 2f - 9.dp.toPx()), 18.dp.toPx(), tones.caution)
                    val span = FormationLayout.Span(l.cx - t.size.width / 2f, l.cx + t.size.width / 2f + if (low) 26.dp.toPx() else 0f)
                    if (raised) highSpans += span else nearSpans += span
                }
                // 간격 라벨 — 묶음마다 하나(내 차 묶음은 없음). 묶음에서 내 차에 가장 가까운 차의 간격
                val gapPicks = clusters.mapNotNull { c ->
                    if (c.cars.any { it.isMe }) return@mapNotNull null
                    val pick = c.cars.filter { it.gapToMeText != null }.minByOrNull { kotlin.math.abs(it.offsetKm - (myKm ?: it.offsetKm)) } ?: return@mapNotNull null
                    c.cx to pick
                }
                val gaps = fitLabels(measurer, gapPicks.map { (cx, car) -> Label(cx, car.gapToMeText!!) }, gapStyleOf, 6.dp.toPx()) { it.replace(" km", "") }
                val belowSpans = mutableListOf<FormationLayout.Span>()
                gaps.labels.forEachIndexed { i, l ->
                    val st = if (gapPicks[i].second.gapWarn) gapWarnStyleOf(gaps.style.fontSize.value.toInt()) else gaps.style
                    val t = measurer.measure(l.text, st)
                    drawText(t, topLeft = Offset(l.cx - t.size.width / 2f, y + 30.dp.toPx()))
                    belowSpans += FormationLayout.Span(l.cx - t.size.width / 2f, l.cx + t.size.width / 2f)
                }
                // 목적지 라벨(막대 오른쪽 끝) — 막대 아래 줄이 기본, 간격 라벨과 겹치면 옆으로 밀거나 차 이름 줄·그 위 줄로(FormationLayout.placeLabel)
                val dest = measurer.measure(ui.destinationText, destStyle)
                val destPlace = FormationLayout.placeLabel(size.width - dest.size.width, dest.size.width.toFloat(), size.width, listOf(belowSpans, nearSpans, highSpans), 12.dp.toPx(), 160.dp.toPx())
                val destCy = when (destPlace.first) {
                    0 -> y + 30.dp.toPx() + dest.size.height / 2f
                    1 -> y - NAME_ROW.toPx()
                    else -> y - NAME_ROW.toPx() - ROW_STEP.toPx()
                }
                drawText(dest, topLeft = Offset(destPlace.second, destCy - dest.size.height / 2f))
                listOf(belowSpans, nearSpans, highSpans)[destPlace.first] += FormationLayout.Span(destPlace.second, destPlace.second + dest.size.width)
                // 다음 정차지 라벨 — 차 이름 줄 → 한 줄 위 → 막대 아래 순으로 비는 자리(FormationLayout.placeStopLabel). 못 피하면 이름을 줄여 다시
                val stopName = ui.nextStopName
                if (stopX != null && stopName != null) {
                    val iconSize = 24.dp.toPx()
                    val iconGap = 8.dp.toPx()
                    val edge = size.width - 4.dp.toPx()
                    var t = measurer.measure(stopName, stopStyle)
                    var w = iconSize + iconGap + t.size.width
                    var place = FormationLayout.placeStopLabel(stopX - w / 2f, w, edge, nearSpans, highSpans, belowSpans, 12.dp.toPx(), 120.dp.toPx())
                    if (place.overlaps) {
                        t = measurer.measure(shortName(stopName), stopStyle)
                        w = iconSize + iconGap + t.size.width
                        place = FormationLayout.placeStopLabel(stopX - w / 2f, w, edge, nearSpans, highSpans, belowSpans, 12.dp.toPx(), 120.dp.toPx())
                    }
                    val cy = when (place.row) {
                        FormationLayout.StopRow.NEAR -> y - NAME_ROW.toPx()
                        FormationLayout.StopRow.HIGH -> y - NAME_ROW.toPx() - ROW_STEP.toPx()
                        FormationLayout.StopRow.BELOW -> y + 30.dp.toPx() + t.size.height / 2f
                    }
                    drawFuel(Offset(place.left, cy - iconSize / 2f), iconSize, tones.info)
                    drawText(t, topLeft = Offset(place.left + iconSize + iconGap, cy - t.size.height / 2f))
                }
            }
        }
    }
}

/** 주유기 아이콘(Canvas) — 저잔량·주유 가능·휴게소 표시 */
fun DrawScope.drawFuel(topLeft: Offset, size: Float, color: Color) {
    val w = size * 0.62f; val h = size
    drawRoundRect(color, topLeft, Size(w, h), cornerRadius = CornerRadius(size * 0.12f))
    drawRect(Color.Black.copy(alpha = 0.35f), Offset(topLeft.x + w * 0.18f, topLeft.y + h * 0.14f), Size(w * 0.64f, h * 0.3f))
    val nozzle = Path().apply {
        moveTo(topLeft.x + w + size * 0.05f, topLeft.y + h * 0.3f)
        lineTo(topLeft.x + w + size * 0.3f, topLeft.y + h * 0.45f)
        lineTo(topLeft.x + w + size * 0.3f, topLeft.y + h * 0.9f)
    }
    drawPath(nozzle, color, style = Stroke(size * 0.12f, cap = StrokeCap.Round))
}

/** 좁은 폭(1창 세로)용 수직 대열 — 위가 다음 정차지·선두, 각 차의 이름·간격을 오른쪽에(글자 크기 가로와 동일) */
@Composable
fun FormationLadder(ui: FormationBarUi, groupName: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceVariant
    val nameStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
    val gapStyle = TextStyle(fontSize = 22.sp, color = muted)
    val tones = DriveTone.tones()
    val gapWarnStyle = TextStyle(fontSize = 22.sp, color = tones.caution, fontWeight = FontWeight.SemiBold)
    val stopStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
    val destStyle = TextStyle(fontSize = 20.sp, color = muted)
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(groupName, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui.formationLengthText, fontSize = 20.sp, color = muted)
            }
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val x = 36.dp.toPx()
                val top = 44.dp.toPx(); val bottom = size.height - 30.dp.toPx()
                val sorted = ui.cars.sortedBy { it.offsetKm }
                val n = sorted.size
                fun yOf(rank: Int) = if (n <= 1) (top + bottom) / 2 else bottom - (bottom - top) * rank / (n - 1)
                drawLine(track, Offset(x, top - 24.dp.toPx()), Offset(x, bottom), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
                sorted.forEachIndexed { i, rear -> if (i < n - 1 && rear.gapWarn) drawLine(tones.caution, Offset(x, yOf(i)), Offset(x, yOf(i + 1)), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round) }
                ui.nextStopName?.let { stop ->
                    val sy = top - 24.dp.toPx()
                    drawLine(onSurface, Offset(x - 14.dp.toPx(), sy), Offset(x + 14.dp.toPx(), sy), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                    val iconSize = 24.dp.toPx()
                    drawFuel(Offset(x + 28.dp.toPx(), sy - iconSize / 2f), iconSize, tones.info)
                    val t = measurer.measure(stop, stopStyle)
                    drawText(t, topLeft = Offset(x + 28.dp.toPx() + iconSize + 8.dp.toPx(), sy - t.size.height / 2f))
                }
                val dest = measurer.measure(ui.destinationText, destStyle)
                drawText(dest, topLeft = Offset(size.width - dest.size.width, bottom + 8.dp.toPx() - dest.size.height / 2f))
                sorted.forEachIndexed { i, car ->
                    val cy = yOf(i)
                    val color = Color(car.colorArgb)
                    val r = 17.dp.toPx()
                    if (car.isMe) drawCircle(color.copy(alpha = 0.25f), r + 9.dp.toPx(), Offset(x, cy))
                    drawCircle(color, r, Offset(x, cy))
                    if (car.isLeader) drawCircle(Color.White, 6.dp.toPx(), Offset(x, cy))
                    if (car.isMe) drawCircle(Color.White, r, Offset(x, cy), style = Stroke(3.dp.toPx()))
                    val name = measurer.measure(car.name, nameStyle)
                    val tx = x + 34.dp.toPx()
                    drawText(name, topLeft = Offset(tx, cy - name.size.height / 2f))
                    var cursor = tx + name.size.width + 10.dp.toPx()
                    if (car.lowEnergy) { drawFuel(Offset(cursor, cy - 10.dp.toPx()), 20.dp.toPx(), tones.caution); cursor += 34.dp.toPx() }
                    car.gapToMeText?.let { g ->
                        val t = measurer.measure(g, if (car.gapWarn) gapWarnStyle else gapStyle)
                        drawText(t, topLeft = Offset(cursor, cy - t.size.height / 2f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 2·3. 카드

@Composable
private fun CardBox(modifier: Modifier, fill: Boolean, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp).then(if (fill) Modifier.fillMaxHeight() else Modifier),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) { content() }
    }
}

/**
 * 그룹 도착 카드. 도착 예상을 하나도 모르면(순정 내비 안내 전, [ArrivalCardUi.noEtaPlace]) 84sp "—" 대신 "도착 예상 시각 없음" +
 * "순정 내비에서 {목적지} 안내를 시작하면…" 을 보이고, 정차 중이면 [목적지 안내 시작](NaviHelper requestRoute — 4-3·5-1).
 * 전원 도착([ArrivalCardUi.allArrived])이면 "모두 도착했어요" + 목적지 이름만(안내 버튼 없음).
 */
@Composable
fun ArrivalCard(ui: ArrivalCardUi, modifier: Modifier = Modifier, big: Boolean = false, onStartGuidance: (() -> Unit)? = null) {
    val numSp = if (big) 84.sp else 56.sp
    val subSp = if (big) 24.sp else 20.sp
    CardBox(modifier, fill = big) {
        Text(stringResource(R.string.dash_group_arrival), fontSize = subSp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.allArrived) {
            // 전원 도착 — 이미 목적지에 있으므로 안내 시작 버튼을 두지 않는다
            Text(stringResource(R.string.dash_all_arrived), fontSize = if (big) 36.sp else 28.sp, lineHeight = if (big) 44.sp else 34.sp, fontWeight = FontWeight.SemiBold)
            if (ui.destinationName.isNotBlank()) {
                Text(stringResource(R.string.dash_all_arrived_hint, ui.destinationName), fontSize = if (big) 22.sp else 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@CardBox
        }
        val place = ui.noEtaPlace
        if (place != null) {
            Text(stringResource(R.string.dash_no_eta), fontSize = if (big) 36.sp else 28.sp, lineHeight = if (big) 44.sp else 34.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.dash_no_eta_hint, place), fontSize = if (big) 22.sp else 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onStartGuidance?.let { start ->
                Spacer(Modifier.height(4.dp))
                Button(onClick = start, modifier = Modifier.heightIn(min = 76.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(EventIcons.vectorOf(EventIconKind.ROUTE), contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.dash_start_guidance, place), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            return@CardBox
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ui.groupArrivalText, fontSize = numSp, lineHeight = numSp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text(ui.lastCarText, fontSize = subSp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dash_my_arrival), fontSize = subSp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(ui.myArrivalText, fontSize = if (big) 44.sp else 32.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(14.dp))
            Text(ui.myRemainText, fontSize = subSp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun NextStopCard(ui: NextStopCardUi, modifier: Modifier = Modifier, compact: Boolean = false, big: Boolean = false) {
    val numSp = if (big) 84.sp else 56.sp
    val subSp = if (big) 24.sp else 20.sp
    val tones = DriveTone.tones()
    CardBox(modifier, fill = big) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dash_next_stop), fontSize = subSp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(ui.name, fontSize = if (big) 32.sp else 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (ui.fuelAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(24.dp)) { drawFuel(Offset.Zero, size.height, tones.info) }
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dash_fuel), fontSize = subSp)
            }
            if (ui.chargeAvailable) {
                Spacer(Modifier.width(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(24.dp)) { drawBolt(size, tones.info) }
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dash_charge), fontSize = subSp)
                }
            }
        }
        Text(ui.distanceText, fontSize = numSp, lineHeight = numSp, fontWeight = FontWeight.Bold)
        // 차별 도착 시각: 폭이 모자라면 '이름 시각' 묶음째 다음 줄로(한 글자씩 세로로 꺾이지 않게 — 2026-11-05 화면 비율 점검)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ui.perCar.forEachIndexed { i, c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (i > 0) {
                        Text("·", fontSize = subSp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(if (compact) 10.dp else 16.dp))
                    }
                    Text(c.name, fontSize = subSp, color = Color(c.colorArgb), fontWeight = if (c.latest) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(4.dp))
                    Text(c.etaText, fontSize = subSp, fontWeight = if (c.latest) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                }
            }
        }
        // 도달 가능 여부를 모르면(아직 계산 안 함) 아이콘 + "—" 줄을 그리지 않는다(E121)
        if (ui.reachable != null || ui.reachableText != DriveFormat.DASH) {
            val (icon, color) = when (ui.reachable) {
                true -> Icons.Filled.CheckCircle to tones.info
                false -> Icons.Filled.Warning to tones.caution
                null -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (big) 30.dp else 24.dp))
                Spacer(Modifier.width(10.dp))
                Text(ui.reachableText, fontSize = subSp, fontWeight = FontWeight.Medium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun DrawScope.drawBolt(size: Size, color: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.55f, 0f); lineTo(w * 0.15f, h * 0.58f); lineTo(w * 0.48f, h * 0.58f); lineTo(w * 0.4f, h); lineTo(w * 0.85f, h * 0.4f); lineTo(w * 0.52f, h * 0.4f); close()
    }
    drawPath(p, color)
}

// ---------------------------------------------------------------- 4. 휴식 추천 줄 / 내 주행 정보 줄

/** 순정 내비가 같은 화면에 보일 때: 제한속도·회전은 내비에 있으므로 연속 주행 시간 + 다음 휴식 추천을 크게 */
@Composable
fun RestRow(ui: MyDriveLineUi, modifier: Modifier = Modifier, big: Boolean = false) {
    val sp = if (big) 30.sp else 24.sp
    val tones = DriveTone.tones()
    val warnColor = if (ui.drivingTimeWarn) tones.caution else MaterialTheme.colorScheme.onSurface
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.drivingTimeWarn) { Icon(Icons.Filled.Warning, contentDescription = null, tint = tones.caution, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(10.dp)) }
            Text(ui.drivingTimeText, fontSize = sp, fontWeight = FontWeight.SemiBold, color = warnColor)
            if (ui.drivingTimeWarn) { Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.dash_rest_hint), fontSize = if (big) 24.sp else 20.sp, color = tones.caution) }
            MyEnergy(ui, if (big) 24.sp else 20.sp)
            Spacer(Modifier.weight(1f))
            ui.restSuggestionText?.let { rest ->
                Canvas(Modifier.size(if (big) 30.dp else 24.dp)) { drawFuel(Offset.Zero, size.height, tones.info) }
                Spacer(Modifier.width(10.dp))
                Text(rest, fontSize = sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 1창(내비 안 보임)일 때: 제한속도·다음 회전·연속 주행 */
@Composable
fun MyDriveLine(ui: MyDriveLineUi, modifier: Modifier = Modifier, compact: Boolean = false, big: Boolean = false) {
    val signSize = if (big) 76.dp else 64.dp
    val tones = DriveTone.tones()
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ui.speedLimitKph?.let { limit ->
                    Box(Modifier.size(signSize).clip(CircleShape).background(Color.White).border(if (big) 7.dp else 6.dp, DriveTone.danger, CircleShape), contentAlignment = Alignment.Center) {
                        Text("$limit", fontSize = if (big) 30.sp else 26.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
                ui.nextTurnText?.let { turn ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(if (big) 52.dp else 44.dp)) { drawTurn(ui.nextTurnKind, size, Color(0xFF8AB4FF)) }
                        Spacer(Modifier.width(12.dp))
                        Text(turn, fontSize = if (big) 30.sp else 26.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                // 회전 안내가 없으면(정차 대시보드 — 회전은 순정 내비 몫) 그 자리에 다음 휴식 추천
                if (ui.nextTurnText == null) ui.restSuggestionText?.let { rest ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(if (big) 30.dp else 24.dp)) { drawFuel(Offset.Zero, size.height, tones.info) }
                        Spacer(Modifier.width(10.dp))
                        Text(rest, fontSize = if (big) 28.sp else 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!compact) DrivingTime(ui, if (big) 26.sp else 22.sp)
            }
            if (compact) DrivingTime(ui, 22.sp)
        }
    }
}

@Composable
private fun DrivingTime(ui: MyDriveLineUi, sp: TextUnit) {
    val tones = DriveTone.tones()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (ui.drivingTimeWarn) { Icon(Icons.Filled.Warning, contentDescription = null, tint = tones.caution, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(8.dp)) }
        Text(ui.drivingTimeText, fontSize = sp, color = if (ui.drivingTimeWarn) tones.caution else MaterialTheme.colorScheme.onSurface, fontWeight = if (ui.drivingTimeWarn) FontWeight.SemiBold else FontWeight.Normal)
        if (ui.drivingTimeWarn) { Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.dash_rest_hint), fontSize = sp, color = tones.caution) }
        MyEnergy(ui, sp)
    }
}

/** 내 차 상태(5-3) — 차량 SDK 잔량("내 차 배터리 82%"). 저잔량이면 주의 색 */
@Composable
private fun MyEnergy(ui: MyDriveLineUi, sp: TextUnit) {
    val pct = ui.myEnergyPct ?: return
    val low = pct <= app.donghaeng.car.surface.FloatingPanelMapper.LOW_ENERGY_PCT
    Spacer(Modifier.width(18.dp))
    Text(
        stringResource(if (ui.myEnergyEv) R.string.dash_my_energy_soc else R.string.dash_my_energy_fuel, pct),
        fontSize = sp,
        color = if (low) DriveTone.tones().caution else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (low) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
    )
}

fun DrawScope.drawTurn(kind: TurnKind, size: Size, color: Color) {
    val w = size.width; val h = size.height; val sw = w * 0.16f
    val p = Path()
    when (kind) {
        TurnKind.RIGHT -> { p.moveTo(w * 0.3f, h); p.lineTo(w * 0.3f, h * 0.4f); p.lineTo(w * 0.8f, h * 0.4f) }
        TurnKind.LEFT -> { p.moveTo(w * 0.7f, h); p.lineTo(w * 0.7f, h * 0.4f); p.lineTo(w * 0.2f, h * 0.4f) }
        TurnKind.UTURN -> { p.moveTo(w * 0.3f, h); p.lineTo(w * 0.3f, h * 0.35f); p.cubicTo(w * 0.3f, 0f, w * 0.7f, 0f, w * 0.7f, h * 0.35f); p.lineTo(w * 0.7f, h * 0.65f) }
        TurnKind.STRAIGHT, TurnKind.NONE -> { p.moveTo(w * 0.5f, h); p.lineTo(w * 0.5f, h * 0.15f) }
    }
    drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val head = Path()
    when (kind) {
        TurnKind.RIGHT -> { head.moveTo(w * 0.62f, h * 0.2f); head.lineTo(w * 0.86f, h * 0.4f); head.lineTo(w * 0.62f, h * 0.6f) }
        TurnKind.LEFT -> { head.moveTo(w * 0.38f, h * 0.2f); head.lineTo(w * 0.14f, h * 0.4f); head.lineTo(w * 0.38f, h * 0.6f) }
        TurnKind.UTURN -> { head.moveTo(w * 0.5f, h * 0.5f); head.lineTo(w * 0.7f, h * 0.72f); head.lineTo(w * 0.9f, h * 0.5f) }
        else -> { head.moveTo(w * 0.3f, h * 0.35f); head.lineTo(w * 0.5f, h * 0.1f); head.lineTo(w * 0.7f, h * 0.35f) }
    }
    drawPath(head, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---------------------------------------------------------------- 5. 이벤트 띠 (이벤트가 있을 때만)

@Composable
fun EventBanner(ui: EventBannerUi, modifier: Modifier = Modifier, big: Boolean = false) {
    val color = DriveTone.color(ui.tone)
    val faded = ui.ageS > 10
    // 위험 이벤트의 음성이 늦으면 빨간 강조(+ 음성 시작 전까지 깜빡임) — 패널 소식 줄과 같은 규칙(EventHighlightPolicy)
    val hl = EventHighlightStyle.isOn(ui.highlight)
    val alpha = (if (faded && !hl) 0.55f else 1f) * highlightAlpha(ui.highlight)
    Surface(modifier.heightIn(min = if (big) 92.dp else 72.dp).alpha(alpha), shape = RoundedCornerShape(20.dp), color = if (hl) DriveTone.danger else color.copy(alpha = 0.16f)) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(EventIcons.of(ui.type, ui.tone), contentDescription = null, tint = if (hl) Color.White else color, modifier = Modifier.size(if (big) 42.dp else 34.dp))
            Spacer(Modifier.width(16.dp))
            Text(ui.text, color = if (hl) Color.White else Color.Unspecified, fontSize = if (big) 32.sp else 26.sp, lineHeight = if (big) 40.sp else 32.sp, fontWeight = if (hl) FontWeight.Bold else FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- 6. 하단 고정

@Composable
fun BottomBar(ui: DriveDashboardUi, onPtt: () -> Unit, onOpenNavi: () -> Unit, compact: Boolean = false, onOpenMicPermission: (() -> Unit)? = null, tight: Boolean = false) {
    val kind = PttDisplay.kind(ui.ptt)
    val recording = kind == PttDisplayKind.RECORDING
    // 확인 중 누르면 = 취소(F16) — 버튼이 그 뜻을 말한다
    val confirming = kind == PttDisplayKind.CONFIRM_VOICE || kind == PttDisplayKind.CONFIRM_COUNTDOWN
    val container = when {
        recording -> DriveTone.danger
        confirming -> PttColors.confirm
        else -> DriveTone.ptt
    }
    val content = if (recording || confirming) Color.White else DriveTone.onPtt
    val tones = DriveTone.tones()
    // 주간(흰 바탕)에서는 라임 버튼 경계가 거의 안 보인다(1.1:1) — 진한 테두리를 함께 그린다(3-3)
    val pttBorder = if (!recording && !confirming && DriveTone.isLightBackground()) BorderStroke(3.dp, DriveTone.pttBorderLight) else null
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onPtt,
                    modifier = Modifier.fillMaxWidth().height(84.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
                    border = pttBorder,
                ) {
                    Canvas(Modifier.size(34.dp)) { drawMic(size, content) }
                    Spacer(Modifier.width(14.dp))
                    when {
                        recording -> {
                            Waveform(ui.ptt.level, Color.White, Modifier.width(90.dp).height(34.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(stringResource(R.string.dash_ptt_seconds, ui.ptt.seconds), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                        confirming -> Text(stringResource(R.string.ptt_press_to_cancel), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        else -> Text(stringResource(R.string.dash_ptt), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
                when {
                    // 마이크 권한 없음 — 안내는 늘, [권한 열기]는 정차 중에만(주행 중 입력 UI 비활성, CLAUDE.md 규칙 6)
                    ui.ptt.micDenied -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = tones.caution, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ptt_mic_denied), fontSize = 20.sp, color = tones.caution, fontWeight = FontWeight.SemiBold)
                        if (ui.parked && onOpenMicPermission != null) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = onOpenMicPermission, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.ptt_open_mic_permission), fontSize = 20.sp) }
                        }
                    }
                    // 핸들 버튼 안내 — 주행 중에도 보이는 보조 글자라 18sp 이상(3-7·5-6)
                    ui.pttKeyHint -> Text(stringResource(R.string.dash_ptt_hint), fontSize = if (tight) 16.sp else 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                }
            }
            OutlinedButton(onClick = onOpenNavi, modifier = Modifier.weight(1f).height(84.dp), shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(30.dp))
                // 좁은 화면: 글자가 한 글자씩 세로로 꺾이지 않게 짧은 이름 + 한 줄
                if (!tight) Spacer(Modifier.width(10.dp))
                Text(stringResource(if (tight) R.string.dash_open_navi_short else R.string.dash_open_navi), fontSize = if (tight) 22.sp else 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            // 실시간 연결 상태(E038·4-9): 연결됨 / 다시 연결 중 / 연결 끊김 + 끊긴 채 오래된 값이면 "2분 전 정보"
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = if (tight) 0.dp else if (compact) 120.dp else 180.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(if (ui.connected) tones.info else tones.caution))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            ui.connected -> stringResource(R.string.dash_connected)
                            ui.offline -> stringResource(R.string.dash_offline)
                            else -> stringResource(R.string.dash_reconnecting)
                        },
                        fontSize = 20.sp,
                        fontWeight = if (ui.connected) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (ui.connected) MaterialTheme.colorScheme.onSurfaceVariant else tones.caution,
                        maxLines = 1,
                    )
                }
                ui.staleText?.let { Text(stringResource(R.string.dash_stale, it), fontSize = 20.sp, color = tones.caution, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                val latency = ui.latencyText?.let { stringResource(R.string.drive_latency, it) } ?: ui.latencyMs?.let { stringResource(R.string.dash_latency, it) }
                latency?.let { Text(it, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.End) }
            }
        }
    }
}

fun DrawScope.drawMic(size: Size, color: Color) {
    val w = size.width; val h = size.height
    drawRoundRect(color, Offset(w * 0.33f, 0f), Size(w * 0.34f, h * 0.58f), cornerRadius = CornerRadius(w * 0.17f))
    val arc = Path().apply { addArc(Rect(w * 0.15f, h * 0.2f, w * 0.85f, h * 0.78f), 0f, 180f) }
    drawPath(arc, color, style = Stroke(w * 0.09f, cap = StrokeCap.Round))
    drawLine(color, Offset(w * 0.5f, h * 0.78f), Offset(w * 0.5f, h * 0.95f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.3f, h * 0.97f), Offset(w * 0.7f, h * 0.97f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
}

@Composable
fun Waveform(level: Float, color: Color, modifier: Modifier = Modifier, bars: Int = 9) {
    Canvas(modifier) {
        val gap = size.width / bars
        for (i in 0 until bars) {
            val k = 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin(i * 1.3f + level * 6f))
            val hh = size.height * k * (0.4f + 0.6f * level)
            drawLine(color, Offset(gap * i + gap / 2, size.height / 2 - hh / 2), Offset(gap * i + gap / 2, size.height / 2 + hh / 2), strokeWidth = gap * 0.5f, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- 7. 무전 오버레이

/** 수신 재생(보낸 차 색 띠, 재생 끝 3초 뒤 사라짐)·보내는 중·보냄·3초 안내는 상단 띠 — 무엇을 그릴지는 [PttDisplay.kind] */
@Composable
fun PttTopBand(ptt: PttUi, modifier: Modifier = Modifier) {
    when (val kind = PttDisplay.kind(ptt)) {
        PttDisplayKind.PLAYING, PttDisplayKind.PLAYED -> {
            val bg = Color(ptt.fromColorArgb ?: DriveDashboardSamples.BLUE)
            Surface(modifier.fillMaxWidth(), color = bg) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(if (kind == PttDisplayKind.PLAYED) Icons.Filled.CheckCircle else Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    Text(playingText(ptt), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (kind == PttDisplayKind.PLAYING) Waveform(0.6f, Color.White, Modifier.width(160.dp).height(34.dp))
                    Spacer(Modifier.weight(0.01f))
                    if (ptt.queuedIncoming > 0) Text(stringResource(R.string.ptt_queued_more, ptt.queuedIncoming), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(PttFormat.clock(ptt.seconds * 1_000L), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
        }
        PttDisplayKind.SENDING, PttDisplayKind.SENT -> Surface(modifier.fillMaxWidth(), color = DriveTone.info) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(if (kind == PttDisplayKind.SENT) Icons.Filled.CheckCircle else Icons.Filled.Send, contentDescription = null, tint = Color.Black, modifier = Modifier.size(30.dp))
                Text(if (kind == PttDisplayKind.SENT) sentText(ptt) else stringResource(R.string.ptt_sending), color = Color.Black, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        PttDisplayKind.NOTICE -> {
            val notice = ptt.notice!!
            val caution = PttDisplay.noticeTone(notice) == Tone.CAUTION
            val bg = if (caution) DriveTone.caution else PttColors.neutral
            val fg = if (caution) Color.Black else Color.White
            val (title, hint) = noticeTexts(notice)
            Surface(modifier.fillMaxWidth(), color = bg) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(if (caution) Icons.Filled.Warning else Icons.Filled.Info, contentDescription = null, tint = fg, modifier = Modifier.size(30.dp))
                    Text(stringResource(title), color = fg, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    hint?.let { Text(stringResource(it), color = fg, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        else -> Unit
    }
}

/** 녹음·확인 대기: 대열 막대 아래를 덮는 중앙 오버레이 */
@Composable
fun PttCenterOverlay(ptt: PttUi, topInset: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(top = topInset)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                when (ptt.phase) {
                    PttPhase.RECORDING -> {
                        Box(Modifier.size(120.dp).clip(CircleShape).background(DriveTone.danger), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(60.dp)) { drawMic(size, Color.White) }
                        }
                        Spacer(Modifier.height(20.dp))
                        Waveform(ptt.level, DriveTone.danger, Modifier.width(520.dp).height(96.dp), bars = 21)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.dash_ptt_recording), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = DriveTone.tones().danger)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${ptt.seconds}", fontSize = 120.sp, lineHeight = 120.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.dash_ptt_seconds_left_unit), fontSize = 30.sp, modifier = Modifier.padding(bottom = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(stringResource(R.string.dash_ptt_stop_hint), fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PttPhase.CONFIRMING -> {
                        Text(stringResource(R.string.dash_ptt_confirm_big), fontSize = 76.sp, lineHeight = 84.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (ptt.fallbackCountdown) stringResource(R.string.dash_ptt_confirm_fallback, ptt.seconds) else stringResource(R.string.dash_ptt_say),
                            fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        CountdownRing(ptt.seconds, ptt.totalSeconds, 168.dp)
                        Spacer(Modifier.height(16.dp))
                        if (ptt.listening) ListeningDots(MaterialTheme.colorScheme.primary)
                        // 확인 중 누르면 = 취소(카운트다운 모드는 위 문구가 이미 말한다)
                        if (!ptt.fallbackCountdown) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.ptt_cancel_hint), fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** 5초 원형 카운트다운 */
@Composable
fun CountdownRing(seconds: Int, total: Int, size: Dp) {
    val ring = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 14.dp.toPx()
            val rect = Rect(sw / 2, sw / 2, this.size.width - sw / 2, this.size.height - sw / 2)
            drawArc(track, 0f, 360f, false, rect.topLeft, rect.size, style = Stroke(sw, cap = StrokeCap.Round))
            val sweep = 360f * (seconds.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
            drawArc(ring, -90f, sweep, false, rect.topLeft, rect.size, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text("$seconds", fontSize = 64.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ListeningDots(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color.copy(alpha = 0.5f)))
        Box(Modifier.size(16.dp).clip(CircleShape).background(color.copy(alpha = 0.8f)))
        Box(Modifier.size(20.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.dash_ptt_listening), color = color, fontSize = 24.sp)
    }
}

// ---------------------------------------------------------------- 정차 추가 블록

@Composable
fun ParkedExtras(
    ui: DriveDashboardUi,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    onPreset: (String) -> Unit,
    onManageGroup: () -> Unit,
    onSettings: () -> Unit,
    onResendUnsent: (String) -> Unit,
    onDeleteUnsent: (String) -> Unit = {},
    onReplayPast: (String) -> Unit = {},
    onOpenEvents: (() -> Unit)? = null,
    onHome: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 빠른 메시지(F8) — 프리셋이 없으면(미구현) 블록째 숨긴다
        if (ui.presets.isNotEmpty()) Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.dash_presets), fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.presets.forEach { p -> OutlinedButton(onClick = { onPreset(p) }, modifier = Modifier.height(76.dp), shape = RoundedCornerShape(16.dp)) { Text(p, fontSize = 20.sp) } }
            }
        }
        val lists: @Composable (Modifier) -> Unit = { m ->
            Surface(m, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.dash_recent_events), fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        onOpenEvents?.let { open -> OutlinedButton(onClick = open, modifier = Modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.surface_more_events), fontSize = 20.sp) } }
                    }
                    if (ui.recentEvents.isEmpty()) Text(stringResource(R.string.dash_no_event), fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ui.recentEvents.take(5).forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(EventIcons.of(e.type, e.tone), contentDescription = null, tint = DriveTone.color(e.tone), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(e.text, fontSize = 20.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.whenText, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        val side: @Composable (Modifier) -> Unit = { m ->
            // 정차 중이라 스크롤 허용(보내지 않은 무전 최대 5 + 지난 무전 최근 20)
            Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 보내지 않은 무전(F16) — 없으면 숨긴다. 줄마다 [다시 보내기]·[지우기](정차 중에만 — 이 블록 자체가 정차 중에만 보인다)
                if (ui.unsentPtt.isNotEmpty()) UnsentPttBlock(ui.unsentPtt, onResendUnsent, onDeleteUnsent)
                // 지난 무전(F16) — 탭하면 다시 듣기. 없으면 숨긴다
                if (ui.pastPtt.isNotEmpty()) PastPttBlock(ui.pastPtt, onReplayPast)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 정차 블록 버튼 — 차량 기준 76 dp(E037)
                    OutlinedButton(onClick = onManageGroup, modifier = Modifier.weight(1f).height(76.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.dash_manage_group), fontSize = 22.sp) }
                    OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f).height(76.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.dash_settings), fontSize = 22.sp) }
                    onHome?.let { home -> OutlinedButton(onClick = home, modifier = Modifier.weight(0.7f).height(76.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.surface_home), fontSize = 22.sp) } }
                }
            }
        }
        if (landscape) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { lists(Modifier.weight(1.4f).fillMaxHeight()); side(Modifier.weight(1f)) }
        else { lists(Modifier.fillMaxWidth()); side(Modifier.fillMaxWidth()) }
    }
}

/** '보내지 않은 무전 N개' — 줄마다 길이·시각·사유 + [다시 보내기]·[지우기] */
@Composable
private fun UnsentPttBlock(rows: List<UnsentPttUi>, onResend: (String) -> Unit, onDelete: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.dash_unsent_ptt, rows.size), fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tint = if (u.failed) DriveTone.tones().caution else MaterialTheme.colorScheme.onSurfaceVariant
                    Canvas(Modifier.size(22.dp)) { drawMic(size, tint) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(u.reasonText, fontSize = 16.sp, color = tint, maxLines = 1)
                    }
                    OutlinedButton(onClick = { onResend(u.id) }, modifier = Modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dash_resend), fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { onDelete(u.id) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.dash_delete_unsent), fontSize = 20.sp) }
                }
            }
        }
    }
}

/** '지난 무전' — 줄 전체가 탭 영역(다시 듣기). 보낸 차 색 점 + "아빠 차 무전 0:07" + 상태 꼬리표 + "3분 전" */
@Composable
private fun PastPttBlock(rows: List<PastPttUi>, onReplay: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dash_past_ptt), fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.dash_past_ptt_hint), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rows.forEach { v ->
                Surface(onClick = { onReplay(v.voiceId) }, shape = RoundedCornerShape(14.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(v.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant))
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(v.text, fontSize = 20.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        v.statusText?.let { st ->
                            Text(st, fontSize = 16.sp, color = DriveTone.color(v.tone), fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(v.whenText, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
