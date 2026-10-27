package app.donghaeng.companion.demo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donghaeng.companion.demo.DemoSession
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.MyVehicle

/** (2) 홈 — 내 차 목록, 큰 "QR 스캔" 버튼, 참여 중인 그룹 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onLogout: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groups by DemoSession.groups.collectAsStateWithLifecycle()
    val account by DemoSession.account.collectAsStateWithLifecycle()

    // 스캔 화면에서 차량을 등록하고 돌아오면 목록을 다시 읽는다
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("동행 — 공식 앱 흉내 (데모용)", style = MaterialTheme.typography.titleMedium) },
                actions = { TextButton(onClick = onLogout) { Text("로그아웃") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "${account?.nickname ?: "손님"} 님의 차고",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            item { Text("내 차", style = MaterialTheme.typography.titleMedium) }
            when {
                state.loading -> item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("내 차를 불러오는 중…")
                    }
                }
                state.error != null -> item {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::refresh) { Text("다시 시도") }
                }
                state.vehicles.isEmpty() -> item {
                    Text("계정에 연결된 차가 없어요. 차 화면의 등록 QR 을 스캔해 추가하세요.")
                }
                else -> items(state.vehicles, key = { it.vehicleId }) { VehicleCard(it) }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                ) {
                    Text("QR 스캔", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    "차 화면의 그룹 QR(합류) 또는 차량 등록 QR 을 스캔합니다",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item { Text("참여 중인 그룹", style = MaterialTheme.typography.titleMedium) }
            if (groups.isEmpty()) {
                item {
                    Text(
                        "아직 합류한 그룹이 없어요",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(groups, key = { it.groupId }) { group ->
                    GroupCard(group = group, onClick = { onOpenGroup(group.groupId) })
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(vehicle: MyVehicle) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(vehicle.nickname, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${vehicle.brand} · ${vehicle.model} · ${vehicle.vehicleId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vehicle.isPrimary) {
                AssistChip(onClick = {}, label = { Text("대표") })
            }
        }
    }
}

@Composable
private fun GroupCard(group: GroupJoinResult, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "멤버 ${group.members.size}대 · ${group.members.joinToString { it.nickname }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
