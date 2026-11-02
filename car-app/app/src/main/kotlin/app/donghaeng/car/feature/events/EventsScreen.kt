package app.donghaeng.car.feature.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.R
import app.donghaeng.car.feature.drive.DriveTone
import app.donghaeng.car.feature.drive.EventIcons

/** 이벤트 피드 진입점 (T26) */
@Composable
fun EventsRoute(onBack: () -> Unit, viewModel: EventsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EventsScreen(state = state, onBack = onBack, onRefresh = viewModel::refresh)
}

/**
 * 이벤트 피드 (T26): 최근 그룹 이벤트(저잔량·급제동·도착·정차/재출발·합류/나감·동승자 메시지 …) 최신순.
 * 앱 시작 이후 받은 것(발생 시각·서버 시계 기준 "N분 전", 5초마다 갱신) + 화면을 열 때 서버에서 가져온 최근 20개(시각 없음)를 합친다.
 * 줄 = 종류별 아이콘(4-4) · 제목(음성 문구) · 부제(종류·요약 — 제목과 겹치면 뺀다) · 오른쪽 열 시각. 주행 중에도 보기만은 허용(docs/05 §4).
 */
@Composable
fun EventsScreen(state: EventsUiState, onBack: () -> Unit, onRefresh: () -> Unit) {
    Scaffold { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 32.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.group_back)) }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.events_title), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        state.groupName?.let { stringResource(R.string.events_subtitle, it, state.events.size) } ?: stringResource(R.string.events_no_group),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onRefresh, enabled = !state.loading && state.groupName != null, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.group_btn_refresh)) }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            Card(Modifier.fillMaxSize()) {
                if (state.events.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(if (state.loading) stringResource(R.string.events_loading) else stringResource(R.string.events_empty), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        items(state.events, key = { it.eventId }) { e ->
                            EventRow(e)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: FeedEvent) {
    val color = DriveTone.color(e.tone)
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(EventIcons.of(e.type, e.tone), contentDescription = null, tint = color, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (e.mine) {
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.events_mine), fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (e.subtitle.isNotEmpty()) Text(e.subtitle, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        // 시각 열 — "3분 전"(서버 조회분은 비움) + 1분 안이면 "방금" 꼬리표
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 120.dp)) {
            e.ageText?.let { Text(it, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End) }
            if (e.live) {
                Text(
                    stringResource(R.string.events_live),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}
