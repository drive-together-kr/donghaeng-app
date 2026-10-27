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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.R
import app.donghaeng.protocol.Severity

/** 이벤트 피드 진입점 (T26) */
@Composable
fun EventsRoute(onBack: () -> Unit, viewModel: EventsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EventsScreen(state = state, onBack = onBack, onRefresh = viewModel::refresh)
}

/**
 * 이벤트 피드 (T26): 최근 그룹 이벤트(저잔량·급제동·도착·정차/재출발·합류/이탈·동승자 메시지 …) 최신순.
 * MQTT 로 실시간 수신한 것 + 화면을 열 때 서버에서 가져온 최근 20개를 합친다. 주행 중에도 보기만은 허용(docs/05 §4).
 */
@Composable
fun EventsScreen(state: EventsUiState, onBack: () -> Unit, onRefresh: () -> Unit) {
    Scaffold { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.group_back)) }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.events_title), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        state.groupName?.let { stringResource(R.string.events_subtitle, it, state.events.size) } ?: stringResource(R.string.events_no_group),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onRefresh, enabled = !state.loading && state.groupName != null, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.group_btn_refresh)) }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            Card(Modifier.fillMaxSize()) {
                if (state.events.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(if (state.loading) stringResource(R.string.events_loading) else stringResource(R.string.events_empty), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
    val color = when (e.severity) {
        Severity.ALERT -> MaterialTheme.colorScheme.error
        Severity.WARN -> MaterialTheme.colorScheme.tertiary
        Severity.INFO -> MaterialTheme.colorScheme.secondary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.speech ?: e.typeLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(e.typeLabel, e.detail, e.ageText).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (e.live) Text(stringResource(R.string.events_live), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** 심각도에 쓸 색 — 미리보기·테스트 보조 */
internal fun severityColor(severity: Severity): Color = when (severity) {
    Severity.ALERT -> Color(0xFFD32F2F)
    Severity.WARN -> Color(0xFFFFB300)
    Severity.INFO -> Color(0xFF00897B)
}
