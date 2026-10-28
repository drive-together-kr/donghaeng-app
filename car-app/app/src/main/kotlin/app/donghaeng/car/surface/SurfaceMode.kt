package app.donghaeng.car.surface

import app.donghaeng.car.domain.session.ReceivedGroupEvent
import kotlinx.coroutines.flow.StateFlow

/**
 * 주행 중 정보 표면 모드 (docs/05 §5.2, 사용자 확정 2026-10-28).
 *
 * 주행 중 앞 화면은 항상 순정 내비다. 동행은 내비를 가리지 않는 표면으로만 알린다:
 * (1) 플로팅 패널(TYPE_APPLICATION_OVERLAY) (2) 이벤트 알림 카드(8초) (3) 음성 (4) 그룹 휴게소 = 내비 경유지.
 *
 * - [OFF]: 그룹 세션 없음 — 아무 표면도 없다.
 * - [PANEL]: 오버레이 권한 있음 — 플로팅 패널 + 알림 카드 + 음성.
 * - [CARDS_ONLY]: 오버레이 권한 없음(Pleos 는 설정 화면이 없어 `appops` 로만 허용된다, docs/10 R16) — 알림 카드 + 음성, 정차 대시보드에 안내 1줄.
 */
enum class SurfaceMode { OFF, PANEL, CARDS_ONLY }

/** 모드·패널 표시 판정 — 순수 함수 */
object SurfacePolicy {
    fun mode(hasSession: Boolean, canDrawOverlays: Boolean): SurfaceMode = when {
        !hasSession -> SurfaceMode.OFF
        canDrawOverlays -> SurfaceMode.PANEL
        else -> SurfaceMode.CARDS_ONLY
    }

    /** 동행 화면(MainActivity)이 보이면(started — 권한 다이얼로그로 paused 여도) 같은 정보를 대시보드가 보여 주므로 패널은 숨긴다 */
    fun panelVisible(mode: SurfaceMode, appVisible: Boolean): Boolean = mode == SurfaceMode.PANEL && !appVisible
}

/** 정차 대시보드(GroupDriveViewModel)가 보는 표면 상태 — 테스트에서 가짜로 바꿔 끼운다 */
interface SurfaceFeed {
    val mode: StateFlow<SurfaceMode>

    /** 앱 시작 이후 받은 그룹 이벤트(최신 먼저, 실제 수신 시각 포함). 그룹이 바뀌면 비운다 */
    val recentEvents: StateFlow<List<ReceivedGroupEvent>>

    /** 서버 시계 기준 지금(T31 ClockSync) — 이벤트 신선도·"N초 전"([SurfaceEvents]). 기본 = 로컬 시계 */
    fun serverNow(): Long = System.currentTimeMillis()

    /** 연속 주행(ms, [app.donghaeng.car.feature.drive.ContinuousDriveTimer]) — 주행 구간 합, 15분 이상 쉬면 0. null = 모름 */
    fun continuousDriveMs(): Long? = null
}
