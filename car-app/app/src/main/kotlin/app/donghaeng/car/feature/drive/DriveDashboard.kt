package app.donghaeng.car.feature.drive

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.donghaeng.car.R

/*
 * 주행 대시보드 (재설계안 — docs/05 §5.1, 사용자 지침 2026-10-28)
 *
 * 가로(2창): 대열 막대 → [그룹 도착 | 다음 정차] → 내 주행 정보 줄 → 이벤트 띠 → 하단 [무전]·[내비 보기]
 * 세로(1창): 같은 블록을 위에서 아래로 쌓는다. 정차(P)면 프리셋·이벤트 목록·보내지 않은 무전·그룹 관리·설정이 아래에 더 붙는다.
 * 무전 상태 오버레이는 화면 어디서든 상단에 얇게.
 * 색+아이콘 이중 표기: DANGER=빨강+경고, CAUTION=주황+경고, INFO=초록+체크.
 */

// ---------------------------------------------------------------- 톤 색

object DriveTone {
    val danger = Color(0xFFFF4D4F)
    val caution = Color(0xFFFF9F1C)
    val info = Color(0xFF2ECC71)

    @Composable
    fun color(tone: Tone): Color = when (tone) {
        Tone.DANGER -> danger
        Tone.CAUTION -> caution
        Tone.INFO -> info
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    fun icon(tone: Tone): ImageVector = when (tone) {
        Tone.DANGER, Tone.CAUTION -> Icons.Filled.Warning
        Tone.INFO -> Icons.Filled.CheckCircle
        Tone.NEUTRAL -> Icons.Filled.Info
    }
}

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
    onResendUnsent: () -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val narrow = maxWidth < 900.dp
            Column(Modifier.fillMaxSize()) {
                PttOverlay(ui.ptt)
                Column(Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (narrow) {
                        FormationLadder(ui.formation, ui.groupName, Modifier.fillMaxWidth().height(260.dp))
                        ArrivalCard(ui.arrival, Modifier.fillMaxWidth())
                        ui.nextStop?.let { NextStopCard(it, Modifier.fillMaxWidth(), compact = true) }
                        MyDriveLine(ui.myDrive, Modifier.fillMaxWidth(), compact = true)
                        EventBanner(ui.event, Modifier.fillMaxWidth())
                        if (ui.parked) ParkedExtras(ui, false, Modifier.weight(1f), onPreset, onManageGroup, onSettings, onResendUnsent) else Spacer(Modifier.weight(1f))
                    } else {
                        // 주행 중: 대열 막대와 카드가 세로 공간을 나눠 채운다(스크롤 없음). 정차: 아래 추가 블록이 남는 공간을 가진다
                        FormationBar(ui.formation, ui.groupName, Modifier.fillMaxWidth().weight(if (ui.parked) 1.0f else 1.15f), big = !ui.parked)
                        Row(Modifier.fillMaxWidth().weight(if (ui.parked) 0.9f else 1.0f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ArrivalCard(ui.arrival, Modifier.weight(1f).fillMaxHeight(), big = true)
                            ui.nextStop?.let { NextStopCard(it, Modifier.weight(1.4f).fillMaxHeight(), big = true) }
                        }
                        MyDriveLine(ui.myDrive, Modifier.fillMaxWidth(), big = !ui.parked)
                        EventBanner(ui.event, Modifier.fillMaxWidth(), big = !ui.parked)
                        if (ui.parked) ParkedExtras(ui, landscape, Modifier.weight(1.7f), onPreset, onManageGroup, onSettings, onResendUnsent)
                    }
                }
                BottomBar(ui, onPtt, onOpenNavi, compact = narrow)
            }
        }
    }
}

// ---------------------------------------------------------------- 1. 대열 막대

@Composable
fun FormationBar(ui: FormationBarUi, groupName: String, modifier: Modifier = Modifier, big: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val labelSp = if (big) 22.sp else 18.sp
    val dotDp = if (big) 19.dp else 16.dp
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceVariant
    val nameStyle = TextStyle(fontSize = labelSp, fontWeight = FontWeight.SemiBold, color = onSurface)
    val gapStyle = TextStyle(fontSize = labelSp, color = muted)
    val gapWarnStyle = TextStyle(fontSize = labelSp, color = DriveTone.caution, fontWeight = FontWeight.SemiBold)
    val destStyle = TextStyle(fontSize = labelSp, fontWeight = FontWeight.SemiBold, color = onSurface)
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(groupName, fontSize = if (big) 28.sp else 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(18.dp))
                Text(ui.routeText, fontSize = if (big) 22.sp else 18.sp, color = muted)
            }
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val padL = 40.dp.toPx(); val padR = 140.dp.toPx()
                val y = size.height * 0.52f
                val x0 = padL; val x1 = size.width - padR
                fun xOf(p: Float) = x0 + (x1 - x0) * p.coerceIn(0f, 1f)
                // 경로
                drawLine(track, Offset(x0, y), Offset(x1, y), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
                // 벌어진 구간(주황)
                val sorted = ui.cars.sortedBy { it.progress }
                sorted.zipWithNext().forEach { (a, b) ->
                    if (a.gapWarn) drawLine(DriveTone.caution, Offset(xOf(a.progress), y), Offset(xOf(b.progress), y), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
                }
                // 목적지 깃발
                val fx = x1 + 14.dp.toPx()
                val flag = Path().apply { moveTo(fx, y - 34.dp.toPx()); lineTo(fx + 30.dp.toPx(), y - 24.dp.toPx()); lineTo(fx, y - 14.dp.toPx()); close() }
                drawLine(onSurface, Offset(fx, y - 36.dp.toPx()), Offset(fx, y + 8.dp.toPx()), strokeWidth = 3.dp.toPx())
                drawPath(flag, DriveTone.info)
                drawText(measurer, ui.destinationName, topLeft = Offset(fx - 10.dp.toPx(), y + 14.dp.toPx()), style = destStyle)
                // 차들 (뒤차부터 그려 선두가 위에)
                sorted.forEachIndexed { i, car ->
                    val cx = xOf(car.progress)
                    val color = Color(car.colorArgb)
                    val r = dotDp.toPx()
                    if (car.isMe) drawCircle(color.copy(alpha = 0.25f), r + 10.dp.toPx(), Offset(cx, y))
                    drawCircle(color, r, Offset(cx, y))
                    if (car.offline) drawCircle(onSurface.copy(alpha = 0.5f), r, Offset(cx, y), style = Stroke(3.dp.toPx()))
                    if (car.isLeader) drawCircle(Color.White, 5.dp.toPx(), Offset(cx, y))
                    if (car.isMe) drawCircle(Color.White, r, Offset(cx, y), style = Stroke(3.dp.toPx()))
                    // 이름(위) — 겹침을 줄이려 홀짝으로 높이를 번갈아
                    val up = if (i % 2 == 0) 48.dp.toPx() else 86.dp.toPx()
                    val name = measurer.measure(car.name, nameStyle)
                    drawText(name, topLeft = Offset(cx - name.size.width / 2f, y - up - name.size.height / 2f))
                    if (car.lowEnergy) drawFuel(Offset(cx + name.size.width / 2f + 6.dp.toPx(), y - up - 11.dp.toPx()), 20.dp.toPx(), DriveTone.caution)
                    // 간격(아래)
                    car.gapText?.let { gap ->
                        val down = if (i % 2 == 0) 30.dp.toPx() else 62.dp.toPx()
                        val t = measurer.measure(gap, if (car.gapWarn) gapWarnStyle else gapStyle)
                        drawText(t, topLeft = Offset(cx - t.size.width / 2f, y + down))
                    }
                }
            }
        }
    }
}

/** 주유기 아이콘(Canvas) — 저잔량·주유 가능 표시 */
fun DrawScope.drawFuel(topLeft: Offset, size: Float, color: Color) {
    val w = size * 0.62f; val h = size
    drawRoundRect(color, topLeft, Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size * 0.12f))
    drawRect(Color.Black.copy(alpha = 0.35f), Offset(topLeft.x + w * 0.18f, topLeft.y + h * 0.14f), Size(w * 0.64f, h * 0.3f))
    val nozzle = Path().apply {
        moveTo(topLeft.x + w + size * 0.05f, topLeft.y + h * 0.3f)
        lineTo(topLeft.x + w + size * 0.3f, topLeft.y + h * 0.45f)
        lineTo(topLeft.x + w + size * 0.3f, topLeft.y + h * 0.9f)
    }
    drawPath(nozzle, color, style = Stroke(size * 0.12f, cap = StrokeCap.Round))
}

// ---------------------------------------------------------------- 2·3. 카드

@Composable
private fun CardBox(modifier: Modifier, fill: Boolean, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 18.dp).then(if (fill) Modifier.fillMaxHeight() else Modifier),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) { content() }
    }
}

@Composable
fun ArrivalCard(ui: ArrivalCardUi, modifier: Modifier = Modifier, big: Boolean = false) {
    val numSp = if (big) 64.sp else 44.sp
    val subSp = if (big) 22.sp else 18.sp
    CardBox(modifier, fill = big) {
        Text(stringResource(R.string.dash_group_arrival), fontSize = if (big) 24.sp else 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ui.groupArrivalText, fontSize = numSp, lineHeight = numSp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text(ui.lastCarText, fontSize = subSp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dash_my_arrival), fontSize = subSp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(ui.myArrivalText, fontSize = if (big) 36.sp else 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(14.dp))
            Text(ui.myRemainText, fontSize = subSp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NextStopCard(ui: NextStopCardUi, modifier: Modifier = Modifier, compact: Boolean = false, big: Boolean = false) {
    val numSp = if (big) 64.sp else 44.sp
    val subSp = if (big) 22.sp else 18.sp
    CardBox(modifier, fill = big) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dash_next_stop), fontSize = if (big) 24.sp else 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(ui.name, fontSize = if (big) 30.sp else 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (!compact && ui.fuelAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(22.dp)) { drawFuel(Offset.Zero, size.height, DriveTone.info) }
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dash_fuel), fontSize = subSp)
            }
            if (!compact && ui.chargeAvailable) {
                Spacer(Modifier.width(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(22.dp)) { drawBolt(size, DriveTone.info) }
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dash_charge), fontSize = subSp)
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ui.distanceText, fontSize = numSp, lineHeight = numSp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    if (ui.fuelAvailable) { Canvas(Modifier.size(22.dp)) { drawFuel(Offset.Zero, size.height, DriveTone.info) }; Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dash_fuel), style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.width(14.dp)) }
                    if (ui.chargeAvailable) { Canvas(Modifier.size(22.dp)) { drawBolt(size, DriveTone.info) }; Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dash_charge), style = MaterialTheme.typography.bodyLarge) }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                ui.perCar.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(if (big) 18.dp else 14.dp).clip(CircleShape).background(Color(c.colorArgb)))
                        Spacer(Modifier.width(8.dp))
                        Text(c.etaText, fontSize = subSp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        if (compact) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ui.perCar.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.colorArgb)))
                    Spacer(Modifier.width(6.dp))
                    Text(c.etaText, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        val (icon, color) = when (ui.reachable) {
            true -> Icons.Filled.CheckCircle to DriveTone.info
            false -> Icons.Filled.Warning to DriveTone.caution
            null -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (big) 30.dp else 24.dp))
            Spacer(Modifier.width(10.dp))
            Text(ui.reachableText, fontSize = subSp, fontWeight = FontWeight.Medium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

// ---------------------------------------------------------------- 4. 내 주행 정보 줄

@Composable
fun MyDriveLine(ui: MyDriveLineUi, modifier: Modifier = Modifier, compact: Boolean = false, big: Boolean = false) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact) Row(verticalAlignment = Alignment.CenterVertically) {
            if (ui.drivingTimeWarn) { Icon(Icons.Filled.Warning, contentDescription = null, tint = DriveTone.caution, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)) }
            Text(ui.drivingTimeText, fontSize = 20.sp, color = if (ui.drivingTimeWarn) DriveTone.caution else MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.drivingTimeWarn) { Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.dash_rest_hint), style = MaterialTheme.typography.bodyLarge, color = DriveTone.caution) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            ui.speedLimitKph?.let { limit ->
                Box(Modifier.size(if (big) 76.dp else 64.dp).clip(CircleShape).background(Color.White).border(if (big) 7.dp else 6.dp, DriveTone.danger, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$limit", fontSize = if (big) 30.sp else 26.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
            ui.nextTurnText?.let { turn ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(if (big) 52.dp else 44.dp)) { drawTurn(ui.nextTurnKind, size, Color(0xFF8AB4FF)) }
                    Spacer(Modifier.width(12.dp))
                    Text(turn, fontSize = if (big) 30.sp else 24.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!compact) Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.drivingTimeWarn) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = DriveTone.caution, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(8.dp))
                }
                Text(ui.drivingTimeText, fontSize = if (big) 26.sp else 22.sp, color = if (ui.drivingTimeWarn) DriveTone.caution else MaterialTheme.colorScheme.onSurface, fontWeight = if (ui.drivingTimeWarn) FontWeight.SemiBold else FontWeight.Normal)
                if (ui.drivingTimeWarn) {
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.dash_rest_hint), style = MaterialTheme.typography.bodyLarge, color = DriveTone.caution)
                }
            }
        }
        }
    }
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
    drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    // 화살촉
    val head = Path()
    when (kind) {
        TurnKind.RIGHT -> { head.moveTo(w * 0.62f, h * 0.2f); head.lineTo(w * 0.86f, h * 0.4f); head.lineTo(w * 0.62f, h * 0.6f) }
        TurnKind.LEFT -> { head.moveTo(w * 0.38f, h * 0.2f); head.lineTo(w * 0.14f, h * 0.4f); head.lineTo(w * 0.38f, h * 0.6f) }
        TurnKind.UTURN -> { head.moveTo(w * 0.5f, h * 0.5f); head.lineTo(w * 0.7f, h * 0.72f); head.lineTo(w * 0.9f, h * 0.5f) }
        else -> { head.moveTo(w * 0.3f, h * 0.35f); head.lineTo(w * 0.5f, h * 0.1f); head.lineTo(w * 0.7f, h * 0.35f) }
    }
    drawPath(head, color, style = Stroke(sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

// ---------------------------------------------------------------- 5. 이벤트 띠

@Composable
fun EventBanner(ui: EventBannerUi?, modifier: Modifier = Modifier, big: Boolean = false) {
    val tone = ui?.tone ?: Tone.NEUTRAL
    val color = DriveTone.color(tone)
    val faded = ui == null || ui.ageS > 10
    Surface(
        modifier.heightIn(min = if (big) 92.dp else 72.dp).alpha(if (faded) 0.55f else 1f),
        shape = RoundedCornerShape(20.dp),
        color = if (ui == null) MaterialTheme.colorScheme.surface else color.copy(alpha = 0.16f),
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(DriveTone.icon(tone), contentDescription = null, tint = color, modifier = Modifier.size(if (big) 42.dp else 34.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                ui?.text ?: stringResource(R.string.dash_no_event),
                fontSize = if (big) 32.sp else 26.sp, lineHeight = if (big) 40.sp else 32.sp, fontWeight = FontWeight.SemiBold,
                color = if (ui == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- 6. 하단 고정

@Composable
fun BottomBar(ui: DriveDashboardUi, onPtt: () -> Unit, onOpenNavi: () -> Unit, compact: Boolean = false) {
    val recording = ui.ptt.phase == PttPhase.RECORDING
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = onPtt,
                modifier = Modifier.weight(1.3f).height(92.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (recording) DriveTone.danger else MaterialTheme.colorScheme.primary),
            ) {
                Canvas(Modifier.size(36.dp)) { drawMic(size, Color.White) }
                Spacer(Modifier.width(16.dp))
                if (recording) {
                    Waveform(ui.ptt.level, Color.White, Modifier.width(90.dp).height(36.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.dash_ptt_seconds, ui.ptt.seconds), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text(stringResource(R.string.dash_ptt), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(onClick = onOpenNavi, modifier = Modifier.weight(1f).height(92.dp), shape = RoundedCornerShape(24.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.dash_open_navi), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = if (compact) 84.dp else 110.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(if (ui.connected) DriveTone.info else DriveTone.caution))
                    Spacer(Modifier.width(8.dp))
                    Text(if (ui.connected) stringResource(R.string.dash_connected) else stringResource(R.string.dash_reconnecting), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ui.latencyMs?.let { Text(stringResource(R.string.dash_latency, it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

fun DrawScope.drawMic(size: Size, color: Color) {
    val w = size.width; val h = size.height
    drawRoundRect(color, Offset(w * 0.33f, 0f), Size(w * 0.34f, h * 0.58f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.17f))
    val arc = Path().apply { addArc(androidx.compose.ui.geometry.Rect(w * 0.15f, h * 0.2f, w * 0.85f, h * 0.78f), 0f, 180f) }
    drawPath(arc, color, style = Stroke(w * 0.09f, cap = StrokeCap.Round))
    drawLine(color, Offset(w * 0.5f, h * 0.78f), Offset(w * 0.5f, h * 0.95f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.3f, h * 0.97f), Offset(w * 0.7f, h * 0.97f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
}

@Composable
fun Waveform(level: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val bars = 9
        val gap = size.width / bars
        for (i in 0 until bars) {
            val k = 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin(i * 1.3f + level * 6f))
            val hh = size.height * k * (0.4f + 0.6f * level)
            drawLine(color, Offset(gap * i + gap / 2, size.height / 2 - hh / 2), Offset(gap * i + gap / 2, size.height / 2 + hh / 2), strokeWidth = gap * 0.5f, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- 7. 무전 상태 오버레이

@Composable
fun PttOverlay(ptt: PttUi, modifier: Modifier = Modifier) {
    if (ptt.phase == PttPhase.IDLE) return
    val (bg, fg) = when (ptt.phase) {
        PttPhase.RECORDING -> DriveTone.danger to Color.White
        PttPhase.CONFIRMING -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        PttPhase.SENT -> DriveTone.info to Color.Black
        PttPhase.PLAYING -> Color(ptt.fromColorArgb ?: DriveDashboardSamples.BLUE) to Color.White
        PttPhase.IDLE -> Color.Transparent to Color.Transparent
    }
    Surface(modifier.fillMaxWidth(), color = bg) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            when (ptt.phase) {
                PttPhase.RECORDING -> {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(fg))
                    Text(stringResource(R.string.dash_ptt_recording), color = fg, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Waveform(ptt.level, fg, Modifier.width(140.dp).height(30.dp))
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.dash_ptt_seconds_left, ptt.seconds), color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                PttPhase.CONFIRMING -> {
                    Canvas(Modifier.size(28.dp)) { drawMic(size, fg) }
                    Text(stringResource(R.string.dash_ptt_confirm_q), color = fg, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (ptt.fallbackCountdown) stringResource(R.string.dash_ptt_confirm_fallback, ptt.seconds) else stringResource(R.string.dash_ptt_confirm_hint),
                        color = fg.copy(alpha = 0.85f), fontSize = 20.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (ptt.listening) ListeningDots(fg)
                    Spacer(Modifier.width(10.dp))
                    Text("${ptt.seconds}", color = fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                PttPhase.SENT -> {
                    Icon(Icons.Filled.Send, contentDescription = null, tint = fg, modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.dash_ptt_sent, ptt.sentTo), color = fg, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
                PttPhase.PLAYING -> {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = fg, modifier = Modifier.size(30.dp))
                    Text(stringResource(R.string.dash_ptt_playing, ptt.fromName ?: ""), color = fg, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Waveform(0.6f, fg, Modifier.width(140.dp).height(30.dp))
                    Spacer(Modifier.weight(1f))
                    Text("0:%02d".format(ptt.seconds), color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                PttPhase.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun ListeningDots(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color.copy(alpha = 0.5f)))
        Box(Modifier.size(14.dp).clip(CircleShape).background(color.copy(alpha = 0.8f)))
        Box(Modifier.size(18.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.dash_ptt_listening), color = color, fontSize = 18.sp)
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
    onResendUnsent: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 프리셋 메시지
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.dash_presets), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.presets.forEach { p -> OutlinedButton(onClick = { onPreset(p) }, modifier = Modifier.height(60.dp), shape = RoundedCornerShape(16.dp)) { Text(p, fontSize = 20.sp) } }
            }
        }
        val lists: @Composable (Modifier) -> Unit = { m ->
            Surface(m, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.dash_recent_events), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ui.recentEvents.take(5).forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(DriveTone.icon(e.tone), contentDescription = null, tint = DriveTone.color(e.tone), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(e.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.whenText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        val side: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.dash_unsent_ptt, ui.unsentPtt.size), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            if (ui.unsentPtt.isNotEmpty()) OutlinedButton(onClick = onResendUnsent, modifier = Modifier.height(52.dp)) { Text(stringResource(R.string.dash_resend), fontSize = 18.sp) }
                        }
                        ui.unsentPtt.forEach { u -> Row(verticalAlignment = Alignment.CenterVertically) { Canvas(Modifier.size(20.dp)) { drawMic(size, DriveTone.caution) }; Spacer(Modifier.width(10.dp)); Text(u, style = MaterialTheme.typography.bodyLarge) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onManageGroup, modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.dash_manage_group), fontSize = 20.sp) }
                    OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.dash_settings), fontSize = 20.sp) }
                }
            }
        }
        if (landscape) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { lists(Modifier.weight(1.4f).fillMaxHeight()); side(Modifier.weight(1f)) }
        else { lists(Modifier.fillMaxWidth()); side(Modifier.fillMaxWidth()) }
    }
}

/** 좁은 폭(1창 세로)용 수직 대열 — 위가 선두, 각 차의 이름·간격을 오른쪽에 */
@Composable
fun FormationLadder(ui: FormationBarUi, groupName: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceVariant
    val nameStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
    val gapStyle = TextStyle(fontSize = 18.sp, color = muted)
    val gapWarnStyle = TextStyle(fontSize = 18.sp, color = DriveTone.caution, fontWeight = FontWeight.SemiBold)
    val destStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(groupName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui.routeText, style = MaterialTheme.typography.bodyLarge, color = muted)
            }
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val x = 36.dp.toPx()
                val top = 34.dp.toPx(); val bottom = size.height - 14.dp.toPx()
                // 순위 간격(위가 선두) — 진행률 비례로 놓으면 차들이 뭉쳐 라벨이 겹친다. 거리는 간격 문구가 말해 준다
                val sorted = ui.cars.sortedBy { it.progress }
                val n = sorted.size
                fun yOf(rank: Int) = if (n <= 1) (top + bottom) / 2 else bottom - (bottom - top) * rank / (n - 1)
                drawLine(track, Offset(x, top - 8.dp.toPx()), Offset(x, bottom), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
                sorted.forEachIndexed { i, a -> if (i < n - 1 && a.gapWarn) drawLine(DriveTone.caution, Offset(x, yOf(i)), Offset(x, yOf(i + 1)), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round) }
                val fy = top - 20.dp.toPx()
                val flag = Path().apply { moveTo(x, fy - 12.dp.toPx()); lineTo(x + 26.dp.toPx(), fy - 3.dp.toPx()); lineTo(x, fy + 6.dp.toPx()); close() }
                drawPath(flag, DriveTone.info)
                drawText(measurer, ui.destinationName, topLeft = Offset(x + 34.dp.toPx(), fy - 12.dp.toPx()), style = destStyle)
                sorted.forEachIndexed { i, car ->
                    val cy = yOf(i)
                    val color = Color(car.colorArgb)
                    val r = 15.dp.toPx()
                    if (car.isMe) drawCircle(color.copy(alpha = 0.25f), r + 9.dp.toPx(), Offset(x, cy))
                    drawCircle(color, r, Offset(x, cy))
                    if (car.isLeader) drawCircle(Color.White, 5.dp.toPx(), Offset(x, cy))
                    if (car.isMe) drawCircle(Color.White, r, Offset(x, cy), style = Stroke(3.dp.toPx()))
                    val name = measurer.measure(car.name, nameStyle)
                    val gap = car.gapText?.let { measurer.measure(it, if (car.gapWarn) gapWarnStyle else gapStyle) }
                    val tx = x + 30.dp.toPx()
                    drawText(name, topLeft = Offset(tx, cy - name.size.height / 2f))
                    if (car.lowEnergy) drawFuel(Offset(tx + name.size.width + 8.dp.toPx(), cy - 11.dp.toPx()), 22.dp.toPx(), DriveTone.caution)
                    gap?.let { drawText(it, topLeft = Offset(tx + name.size.width + (if (car.lowEnergy) 44.dp.toPx() else 14.dp.toPx()), cy - it.size.height / 2f)) }
                }
            }
        }
    }
}
