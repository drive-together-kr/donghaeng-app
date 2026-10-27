package app.donghaeng.car.adapter.location

import ai.pleos.playground.location.fused.FusedLocation
import android.content.Context
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Fix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Pleos FusedLocation 1.1.3 어댑터 — **T04 시점에는 골격만**. Playground 상 "준비 중"이라 의존하지 않는다(CLAUDE.md 규칙 5).
 * 위치 소스 1순위이지만 런타임에 [isSupported] 가 true 일 때만 쓰고, 아니면 NaviHelper → Android → Fake 로 내려간다(docs/05 §3.2).
 *
 * SDK 시그니처(`FusedLocation-1.1.3.aar` javap):
 * ```
 * FusedLocation(context).initialize() / release() / isSupported(): Boolean
 * registerFusedLocationCallback(FusedLocationListener) / unregisterFusedLocationCallback(listener)
 * FusedLocationListener { onChanged(LocationData(latitude, longitude, altitude, heading, confidence, timestampMillis)); onError(Exception) }
 * ```
 * 주의: 이 AAR 의 매니페스트는 `uses-feature android.hardware.type.automotive required=true` 를 선언한다 → pleos flavor APK 는
 * 자동차(AAOS) 기기에만 설치된다. 일반 AVD 는 fake flavor 를 쓴다.
 *
 * TODO(T21): locations() = callbackFlow { ... registerFusedLocationCallback(l); awaitClose { unregisterFusedLocationCallback(l) } }
 *            LocationData → Fix(source = LocSource.FUSED, accuracy = confidence 기반 추정)
 */
class PleosFusedLocationAdapter(context: Context) : LocationAdapter {

    private val appContext = context.applicationContext
    private var fused: FusedLocation? = null

    /** 서비스 없음·미지원이면 false — 호출 측은 다음 순위 소스로 넘어간다. */
    fun isSupported(): Boolean = try {
        val f = fused ?: FusedLocation(appContext).also { it.initialize(); fused = it }
        f.isSupported().also { DhLog.i("FusedLocation isSupported=$it") }
    } catch (e: Exception) {
        DhLog.w("FusedLocation 초기화 실패 → 미지원으로 간주", e)
        fused = null
        false
    }

    fun release() {
        runCatching { fused?.release() }.onFailure { DhLog.w("FusedLocation release 실패", it) }
        fused = null
    }

    override fun locations(): Flow<Fix> = emptyFlow()   // TODO(T21)
}
