package app.donghaeng.car.adapter.location

import app.donghaeng.car.domain.model.Fix
import app.donghaeng.protocol.LocSource
import kotlinx.coroutines.flow.Flow

/**
 * 위치 체인의 한 칸 (docs/05 §3.2: Fused → Navi → Android → Fake). [CompositeLocationAdapter] 가 순서대로 시도한다.
 *
 * - [isAvailable]: 권한·서비스 지원 여부 등 "시도해 볼 수 있는가". true 여도 Fix 가 안 오면 체인이 다음으로 넘어간다.
 * - [staleTimeoutMs]: 이 시간 동안 Fix 가 없으면 끊긴 것으로 보고 다음 순위로 넘어간다.
 */
interface LocationSource {
    val source: LocSource

    val staleTimeoutMs: Long get() = DEFAULT_STALE_TIMEOUT_MS

    suspend fun isAvailable(): Boolean

    /** 위치 스트림. 구독 시 리스너 등록, 취소 시 해제. 예외는 흘려도 된다(체인이 잡아서 다음으로 넘어간다). */
    fun locations(): Flow<Fix>

    companion object {
        const val DEFAULT_STALE_TIMEOUT_MS = 5_000L
    }
}
