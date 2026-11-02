package app.donghaeng.companion.demo.feature.mycar

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.donghaeng.VehicleListStatus
import app.donghaeng.companion.demo.ui.components.CarSideSilhouette
import app.donghaeng.companion.demo.ui.components.Disclaimer
import app.donghaeng.companion.demo.ui.components.GreenProgress
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.KiaChip
import app.donghaeng.companion.demo.ui.components.KiaTag
import app.donghaeng.companion.demo.ui.components.KiaTopBar
import app.donghaeng.companion.demo.ui.components.ListRow
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.OutlinePill
import app.donghaeng.companion.demo.ui.components.RowDivider
import app.donghaeng.companion.demo.ui.components.SectionHeader
import app.donghaeng.companion.demo.ui.components.SubCard
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.demo.ui.theme.KiaDimens
import app.donghaeng.companion.sdk.MyVehicle

/*
 * 마이카 탭 — 기아 앱 "마이카" 화면 재현.
 * 차량 카드 → 정비 예약 알림 → 정비·인카페이먼트·소프트웨어·사용설명서 → Kia Connect subscription(연동 서비스 안에 동행 진입점 E)
 * → 이용가능한 서비스 → 소모품 현황 → 로그아웃 → 고지 문구.
 * 정적 요소는 전부 [onDemoTap] 으로 "시연용 화면입니다" 토스트만 띄운다.
 */

/** 리스트 카드의 공통 안쪽 여백(행이 자체 세로 패딩을 가진다) */
private val ListCardPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)

/**
 * @param vehiclesLoading·[vehiclesError] 계정 차 목록 불러오기 상태 — 목록이 비었을 때 '불러오는 중'·'불러오지 못함 [다시 불러오기]'를
 *   '연결된 차가 없어요(QR 등록)'와 구분한다([VehicleListStatus], RD-4)
 * @param onRetryVehicles [다시 불러오기]
 */
@Composable
fun MyCarScreen(
    vehicleName: String,
    vehicles: List<MyVehicle>,
    vehiclesLoading: Boolean,
    vehiclesError: String?,
    onRetryVehicles: () -> Unit,
    onQr: () -> Unit,
    onProfile: () -> Unit,
    onOpenDongHaengSettings: () -> Unit,
    onLogout: () -> Unit,
    onDemoTap: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(KiaColors.Bg),
    ) {
        KiaTopBar(title = "마이카", onQr = onQr, onSearch = { onDemoTap("검색") }, onProfile = onProfile)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KiaDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(8.dp))
            VehicleCard(
                vehicleName = vehicleName,
                vehicles = vehicles,
                listStatus = VehicleListStatus.of(vehicles, vehiclesLoading, vehiclesError),
                vehiclesError = vehiclesError,
                onRetryVehicles = onRetryVehicles,
                onQr = onQr,
                onDemoTap = onDemoTap,
            )

            Spacer(Modifier.height(KiaDimens.CardGap))
            MaintenanceNoticeCard(onDemoTap = onDemoTap)

            Spacer(Modifier.height(KiaDimens.CardGap))
            VehicleServiceCard(vehicleName = vehicleName, onDemoTap = onDemoTap)

            Spacer(Modifier.height(KiaDimens.SectionGap))
            SectionHeader(title = "Kia Connect subscription")
            ConnectCard(onOpenDongHaengSettings = onOpenDongHaengSettings, onDemoTap = onDemoTap)

            Spacer(Modifier.height(KiaDimens.SectionGap))
            SectionHeader(title = "이용가능한 서비스")
            AvailableServicesCard(onDemoTap = onDemoTap)

            Spacer(Modifier.height(KiaDimens.SectionGap))
            SectionHeader(title = "소모품 현황")
            ConsumablesCard(onDemoTap = onDemoTap)

            Spacer(Modifier.height(20.dp))
            Text(
                "로그아웃",
                style = MaterialTheme.typography.labelLarge,
                color = KiaColors.TextSecondary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onLogout)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Disclaimer()
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ───────────────────────── 차량 카드 ─────────────────────────

/** 차량명 ⇌ · [+ 등록] · 태그 · 옆모습 · ⚙ 차량 관리 · 계정에 연결된 차량 목록 */
@Composable
private fun VehicleCard(
    vehicleName: String,
    vehicles: List<MyVehicle>,
    listStatus: VehicleListStatus,
    vehiclesError: String?,
    onRetryVehicles: () -> Unit,
    onQr: () -> Unit,
    onDemoTap: (String) -> Unit,
) {
    KiaCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDemoTap("차량 전환") },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(vehicleName, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                MsIcon(R.drawable.ms_swap_horiz, "차량 전환", size = 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            OutlinePill(text = "등록", icon = R.drawable.ms_add, onClick = { onDemoTap("차량 등록") })
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KiaTag("대표 차량")
            KiaTag("공유받은 차량")
        }

        Spacer(Modifier.height(10.dp))
        CarSideSilhouette(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            height = 130.dp,
        )

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onDemoTap("차량 관리") }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            MsIcon(R.drawable.ms_settings, null, size = 20.dp)
            Text("차량 관리", style = MaterialTheme.typography.bodyLarge)
        }

        // 계정에 연결된 차량(서버/시연 게이트웨이) — 불러오는 중·실패는 '없어요(QR 등록)'와 구분한다(RD-4)
        Spacer(Modifier.height(12.dp))
        RowDivider()
        Spacer(Modifier.height(14.dp))
        Text("연결된 차량", style = MaterialTheme.typography.labelMedium, color = KiaColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        when (listStatus) {
            VehicleListStatus.LOADING -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = KiaColors.Accent)
                Spacer(Modifier.width(10.dp))
                Text("내 차 목록을 불러오는 중…", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
            }
            VehicleListStatus.ERROR -> Column {
                Text(
                    "내 차 목록을 불러오지 못했어요: ${vehiclesError.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KiaColors.Dot,
                )
                Spacer(Modifier.height(8.dp))
                KiaChip("다시 불러오기", icon = R.drawable.ms_refresh, color = KiaColors.SubCard, onClick = onRetryVehicles)
            }
            VehicleListStatus.EMPTY -> SubCard(onClick = onQr, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MsIcon(R.drawable.ms_qr_code_scanner, null, tint = KiaColors.Accent, size = 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "계정에 연결된 차가 없어요 — QR 스캔으로 등록",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KiaColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    MsIcon(R.drawable.ms_chevron_right, null, tint = KiaColors.TextSecondary, size = 20.dp)
                }
            }
            VehicleListStatus.LIST -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vehicles.forEach { v -> LinkedVehicleRow(vehicle = v, onClick = { onDemoTap("차량 선택") }) }
            }
        }
    }
}

/** 연결된 차량 한 대 — 닉네임 · 태그 · 브랜드·모델 */
@Composable
private fun LinkedVehicleRow(vehicle: MyVehicle, onClick: () -> Unit) {
    SubCard(onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MsIcon(R.drawable.ms_directions_car, null, size = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vehicle.nickname, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    // 서버 /me/vehicles 는 내 계정 소유 차만 준다 — '공유받은'이 아니다(D026·D093)
                    KiaTag(if (vehicle.isPrimary) "대표 차량" else "내 차")
                }
                Text(
                    "${vehicle.brand} · ${vehicle.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = KiaColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            MsIcon(R.drawable.ms_chevron_right, null, tint = KiaColors.TextSecondary, size = 20.dp)
        }
    }
}

// ───────────────────────── 정비 예약 · 차량 서비스 ─────────────────────────

@Composable
private fun MaintenanceNoticeCard(onDemoTap: (String) -> Unit) {
    KiaCard(onClick = { onDemoTap("정비 예약") }, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 22.dp)) {
        Text("11월 4일 정비 예약이 있어요", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("자세히 보기", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
            MsIcon(R.drawable.ms_chevron_right, null, tint = KiaColors.TextSecondary, size = 18.dp)
        }
    }
}

/** 정비 / 인카페이먼트 / 차량 소프트웨어 / 사용설명서 */
@Composable
private fun VehicleServiceCard(vehicleName: String, onDemoTap: (String) -> Unit) {
    // "쏘렌토 HEV(시연)" → "쏘렌토" (사용설명서 제목용)
    val shortName = vehicleName.substringBefore("(").trim().substringBefore(" ").ifBlank { vehicleName }
    KiaCard(contentPadding = ListCardPadding) {
        ListRow(
            title = "정비",
            subtitle = "예약 정보, 정비 내역 확인",
            icon = R.drawable.ms_build,
            onClick = { onDemoTap("정비") },
        )
        RowDivider()
        ListRow(
            title = "인카페이먼트",
            subtitle = "실물 카드 없이 간편 결제",
            icon = R.drawable.ms_credit_card,
            onClick = { onDemoTap("인카페이먼트") },
        )
        RowDivider()
        ListRow(
            title = "차량 소프트웨어",
            subtitle = "내 차에 맞는 소프트웨어 업데이트",
            icon = R.drawable.ms_system_update,
            onClick = { onDemoTap("차량 소프트웨어") },
        )
        RowDivider()
        ListRow(
            title = "$shortName 사용설명서",
            subtitle = "쉽게 찾아보는 내 차의 사용설명서",
            icon = R.drawable.ms_menu_book,
            onClick = { onDemoTap("사용설명서") },
        )
    }
}

// ───────────────────────── Kia Connect subscription (동행 진입점 E) ─────────────────────────

@Composable
private fun ConnectCard(onOpenDongHaengSettings: () -> Unit, onDemoTap: (String) -> Unit) {
    KiaCard(contentPadding = ListCardPadding) {
        ListRow(
            title = "Kia Connect",
            icon = R.drawable.ms_verified,
            onClick = { onDemoTap("Kia Connect") },
        )
        RowDivider()
        ListRow(
            title = "디지털 키 관리",
            icon = R.drawable.ms_key,
            onClick = { onDemoTap("디지털 키 관리") },
        )
        Spacer(Modifier.height(10.dp))
        SubCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
            Text("연동 서비스", style = MaterialTheme.typography.labelLarge, color = KiaColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            // E: 동행 그룹 주행 — 첫 행, 라임 아이콘 + NEW 태그
            LinkedServiceRow(
                title = "동행 그룹 주행",
                icon = R.drawable.ms_groups,
                iconTint = KiaColors.Accent,
                isNew = true,
                onClick = onOpenDongHaengSettings,
            )
            LinkedServiceRow(title = "디지털 액자", icon = R.drawable.ms_image, onClick = { onDemoTap("디지털 액자") })
            LinkedServiceRow(title = "스트리밍 서비스", icon = R.drawable.ms_wifi, onClick = { onDemoTap("스트리밍 서비스") })
            LinkedServiceRow(title = "카투홈 서비스", icon = R.drawable.ms_home, onClick = { onDemoTap("카투홈 서비스") })
            LinkedServiceRow(title = "캘린더 서비스", icon = R.drawable.ms_calendar_month, onClick = { onDemoTap("캘린더 서비스") })
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 연동 서비스 한 줄 — 작은 아이콘 + 이름 (+ NEW 태그), 쉐브론 없음 */
@Composable
private fun LinkedServiceRow(
    title: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    iconTint: Color = KiaColors.TextPrimary,
    isNew: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MsIcon(icon, null, tint = iconTint, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
        if (isNew) {
            Spacer(Modifier.width(8.dp))
            KiaTag("NEW")
        }
    }
}

// ───────────────────────── 이용가능한 서비스 · 소모품 현황 ─────────────────────────

@Composable
private fun AvailableServicesCard(onDemoTap: (String) -> Unit) {
    KiaCard(contentPadding = ListCardPadding) {
        ListRow(
            title = "원격 제어",
            subtitle = "시동·공조·문 잠금을 앱에서",
            icon = R.drawable.ms_power_settings_new,
            onClick = { onDemoTap("원격 제어") },
        )
        RowDivider()
        ListRow(
            title = "안심 주차",
            subtitle = "주차 중 충격·침입 알림",
            icon = R.drawable.ms_local_parking,
            onClick = { onDemoTap("안심 주차") },
        )
        RowDivider()
        ListRow(
            title = "실시간 교통정보",
            subtitle = "내비게이션에 실시간 교통 상황 반영",
            icon = R.drawable.ms_route,
            onClick = { onDemoTap("실시간 교통정보") },
        )
    }
}

@Composable
private fun ConsumablesCard(onDemoTap: (String) -> Unit) {
    KiaCard(onClick = { onDemoTap("소모품 현황") }, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp)) {
        ConsumableRow(name = "엔진오일", fraction = 0.85f)
        Spacer(Modifier.height(20.dp))
        ConsumableRow(name = "브레이크 패드", fraction = 0.60f)
        Spacer(Modifier.height(20.dp))
        ConsumableRow(name = "타이어", fraction = 0.72f)
    }
}

/** 소모품 이름 ····· 85% / 녹색 진행 바 */
@Composable
private fun ConsumableRow(name: String, fraction: Float) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = KiaColors.Accent,
            )
        }
        Spacer(Modifier.height(10.dp))
        GreenProgress(fraction = fraction)
    }
}
