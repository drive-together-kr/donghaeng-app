package app.donghaeng.car.debug

import app.donghaeng.protocol.EventAudience
import app.donghaeng.protocol.MemberState

/**
 * 디버그 `event` 액션의 출처 고르기 (순수 함수 — `src/testDebug` 에서 검증).
 *
 * 급제동(`HARD_BRAKE`)은 출처보다 뒤에 있는 차만 받는다(docs/03 §5.4, docs/10 R18). 출처를 명단 밖 [DEFAULT_SOURCE] 로 두면
 * 앞뒤를 몰라(`EventAudience.Basis.UNKNOWN`) **선두에도** '앞쪽 시연 차가 급제동' 이 떠, 고치기 전 impl_02 장면이 그대로 다시 나온다.
 * 그래서 급제동이고 `source`·`audience` 를 둘 다 주지 않으면 출처 기본값을 스냅샷의 **내 바로 앞 차**(`ahead`)로 한다 —
 * 앞 차가 없으면(내가 선두·위치 모름) 주입하지 않는다([Pick.NoNeighbour], 리시버가 경고 로그만 남긴다).
 * 명단 밖 출처로 일부러 확인하려면 `--es source veh_DEMO` 를 명시한다.
 */
internal object DemoEventSource {

    /** 명단에 없는 가상 출처 — 앞뒤를 몰라 '대상'으로 친다 */
    const val DEFAULT_SOURCE = "veh_DEMO"

    sealed interface Pick {
        /** 이 출처로 주입. [arg] = 사용자가 준(또는 기본으로 정한) 값(`ahead`/`behind`/vehicleId) */
        data class Source(val vehicleId: String, val arg: String) : Pick

        /** `ahead`/`behind` 이웃이 스냅샷에 없음 — 주입하지 않는다 */
        data class NoNeighbour(val which: String, val defaulted: Boolean) : Pick
    }

    /**
     * @param sourceArg `--es source` 값(null = 안 줌)
     * @param audienceGiven `--es audience` 를 줬는지(주면 서버가 정한 audience 처럼 그 값이 판정하므로 출처 기본값은 [DEFAULT_SOURCE])
     */
    fun pick(type: String, sourceArg: String?, audienceGiven: Boolean, me: String?, members: List<MemberState>?): Pick {
        val defaulted = sourceArg == null
        val arg = sourceArg ?: if (EventAudience.isBehindOnly(type) && !audienceGiven) AHEAD else DEFAULT_SOURCE
        if (arg != AHEAD && arg != BEHIND) return Pick.Source(arg, arg)
        val vid = neighbour(arg, me, members) ?: return Pick.NoNeighbour(arg, defaulted)
        return Pick.Source(vid, arg)
    }

    /** 스냅샷에서 내 바로 앞(`ahead`: 선두까지 거리가 나보다 작은 것 중 가장 큰) / 바로 뒤(`behind`) 멤버. 내 위치를 모르면 null */
    fun neighbour(which: String, me: String?, members: List<MemberState>?): String? {
        val mine = members?.firstOrNull { it.vehicleId == me }?.distanceToLeaderM ?: return null
        val others = members.filter { it.vehicleId != me }.mapNotNull { m -> m.distanceToLeaderM?.let { m.vehicleId to it } }
        return if (which == BEHIND) others.filter { it.second > mine }.minByOrNull { it.second }?.first
        else others.filter { it.second < mine }.maxByOrNull { it.second }?.first
    }

    const val AHEAD = "ahead"
    const val BEHIND = "behind"
}
