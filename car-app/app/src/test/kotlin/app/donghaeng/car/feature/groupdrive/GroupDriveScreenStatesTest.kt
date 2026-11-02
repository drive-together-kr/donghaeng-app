package app.donghaeng.car.feature.groupdrive

import app.donghaeng.car.R
import app.donghaeng.car.domain.routesync.RouteProposal
import app.donghaeng.protocol.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 제안 카드 상태 줄(E006·E034 — 주행 중 보류)과 그룹이 끝난 화면의 이유별 문구·버튼(E115) */
class GroupDriveScreenStatesTest {

    private val now = 1_700_000_000_000L
    private fun proposal(held: Boolean, expiresInMs: Long) =
        RouteProposal("prp_1", Place(37.7, 128.9, "경포해변"), listOf(Place(37.3, 127.3, "덕평휴게소")), "선두차가 경로를 바꿨어요", now, now + expiresInMs, heldUntilParked = held)

    @Test
    fun `held proposal while driving says answer when parked instead of counting down the hold cap`() {
        val held = GroupDriveUiState(proposal = proposal(held = true, expiresInMs = 9 * 60_000L), isDriving = true, nowMs = now)
        assertEquals(ProposalStatus.Held, held.proposalStatus)
        assertEquals("막 정차함 — 엔진이 5초 정차를 확인하는 중", ProposalStatus.Settling, held.copy(isDriving = false).proposalStatus)
        val parked = GroupDriveUiState(proposal = proposal(held = false, expiresInMs = 30_000), isDriving = false, nowMs = now)
        assertEquals(ProposalStatus.Countdown(30), parked.proposalStatus)
        assertNull(GroupDriveUiState().proposalStatus)
    }

    @Test
    fun `ended pane - unpaired asks to reconnect, every other reason offers a new group`() {
        assertEquals(EndedAction.REPAIR, EndedPaneSpec.of(SessionEndReason.UNPAIRED).action)
        assertEquals(R.string.drive_ended_unpaired_title, EndedPaneSpec.of(SessionEndReason.UNPAIRED).titleRes)
        listOf(SessionEndReason.GROUP_ENDED, SessionEndReason.ENDED_WHILE_OFFLINE, SessionEndReason.REMOVED, SessionEndReason.LEFT, SessionEndReason.OTHER).forEach {
            assertEquals(it.name, EndedAction.NEW_GROUP, EndedPaneSpec.of(it).action)
        }
        assertEquals(R.string.drive_ended_removed_title, EndedPaneSpec.of(SessionEndReason.REMOVED).titleRes)
        assertEquals(R.string.drive_ended_group_ended_title, EndedPaneSpec.of(SessionEndReason.GROUP_ENDED).titleRes)
        assertEquals(R.string.drive_ended_offline_text, EndedPaneSpec.of(SessionEndReason.ENDED_WHILE_OFFLINE).textRes)
    }
}
