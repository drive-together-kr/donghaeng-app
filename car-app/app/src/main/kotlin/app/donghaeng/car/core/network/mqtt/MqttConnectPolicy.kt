package app.donghaeng.car.core.network.mqtt

import app.donghaeng.car.core.common.StoredCredentials

/** [MqttGateway.connect] 를 다시 불렀을 때 지금 연결을 어떻게 할지 */
enum class ConnectDecision {
    /** 같은 차·같은 토큰 — 연결을 그대로 둔다(닉네임 등 접속과 무관한 값만 바뀐 경우 포함) */
    KEEP,

    /** 같은 차·토큰만 바뀜(갱신) — 지금 연결은 두고 다음 재연결부터 새 토큰으로 인증한다 */
    REFRESH_TOKEN,

    /** 연결이 없거나 다른 차 — (있던 연결을 끊고) 새로 접속한다 */
    CONNECT,
}

/**
 * [HiveMqttGateway.connect] 의 재연결 판단 (2026-10-28 — 순수 함수로 뽑아 단위 테스트).
 *
 * `MqttSessionManager` 는 [app.donghaeng.car.core.common.TokenStore] 가 새 자격 증명을 내보낼 때마다 `connect` 를 부른다.
 * 그룹 스냅샷 닉네임 동기화(`MqttSessionManager.syncNickname`)는 닉네임만 바꾼 자격 증명을 저장하므로,
 * 여기서 [ConnectDecision.KEEP] 이어야 폰에서 이름을 바꿨을 때 MQTT 가 끊겼다 붙지 않는다.
 * 접속에 쓰는 값은 vehicleId(= username·clientId)와 토큰뿐이라 이 둘만 본다.
 */
object MqttConnectPolicy {
    fun decide(connected: Boolean, current: StoredCredentials?, next: StoredCredentials): ConnectDecision = when {
        !connected || current == null || current.vehicleId != next.vehicleId -> ConnectDecision.CONNECT
        current.vehicleAccessToken != next.vehicleAccessToken -> ConnectDecision.REFRESH_TOKEN
        else -> ConnectDecision.KEEP
    }
}
