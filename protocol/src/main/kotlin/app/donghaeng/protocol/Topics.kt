package app.donghaeng.protocol

/**
 * MQTT 토픽 생성·해석 (docs/03 §4).
 *
 * | 토픽 | 방향 | QoS |
 * |---|---|---|
 * | `dh/v1/vehicle/{vid}/telemetry` | 차량 → 서버 | 0 |
 * | `dh/v1/vehicle/{vid}/event`     | 차량 → 서버 | 1 |
 * | `dh/v1/vehicle/{vid}/ack`       | 차량 → 서버 | 1 |
 * | `dh/v1/vehicle/{vid}/cmd`       | 서버 → 차량 | 1 |
 * | `dh/v1/vehicle/{vid}/presence`  | 브로커 LWT  | 1 |
 * | `dh/v1/group/{gid}/state`       | 서버 → 그룹 | 0 (retained) |
 * | `dh/v1/group/{gid}/event`       | 서버 → 그룹 | 1 |
 */
object Topics {
    const val PREFIX = "dh/v1"
    private const val VEHICLE = "$PREFIX/vehicle"
    private const val GROUP = "$PREFIX/group"

    /** 서버 인스턴스들이 나눠 받는 공유 구독 필터 */
    const val SERVER_SHARED_VEHICLE_SUB = "\$share/server/$VEHICLE/+/#"

    fun vehicleTelemetry(vid: String): String = "$VEHICLE/$vid/telemetry"
    fun vehicleEvent(vid: String): String = "$VEHICLE/$vid/event"
    fun vehicleAck(vid: String): String = "$VEHICLE/$vid/ack"
    fun vehicleCmd(vid: String): String = "$VEHICLE/$vid/cmd"
    fun vehiclePresence(vid: String): String = "$VEHICLE/$vid/presence"

    fun groupState(gid: String): String = "$GROUP/$gid/state"
    fun groupEvent(gid: String): String = "$GROUP/$gid/event"

    /** 차량 토픽의 마지막 세그먼트 종류 */
    enum class VehicleTopicKind(val leaf: String) {
        TELEMETRY("telemetry"),
        EVENT("event"),
        ACK("ack"),
        CMD("cmd"),
        PRESENCE("presence"),
        ;

        companion object {
            fun fromLeaf(leaf: String): VehicleTopicKind? = entries.firstOrNull { it.leaf == leaf }
        }
    }

    /**
     * `dh/v1/vehicle/{vid}/...` 에서 `{vid}` 를 꺼낸다. 형식이 다르면 null.
     * `$share/{group}/` 접두사가 붙어 있어도 해석한다.
     */
    fun vehicleIdFromTopic(topic: String): String? = idFromTopic(topic, "vehicle")

    /**
     * `dh/v1/group/{gid}/...` 에서 `{gid}` 를 꺼낸다. 형식이 다르면 null.
     */
    fun groupIdFromTopic(topic: String): String? = idFromTopic(topic, "group")

    /**
     * 차량 토픽의 종류(telemetry/event/ack/cmd/presence). 차량 토픽이 아니거나 모르는 종류면 null.
     */
    fun vehicleTopicKind(topic: String): VehicleTopicKind? {
        val parts = segments(topic) ?: return null
        if (parts[2] != "vehicle") return null
        return VehicleTopicKind.fromLeaf(parts[4])
    }

    /**
     * `dh/v1/{scope}/{id}/{leaf}` 정확히 5 세그먼트, 모두 비어 있지 않을 때만 유효.
     * 와일드카드(`+`, `#`)는 ID 로 취급하지 않는다.
     */
    private fun idFromTopic(topic: String, scope: String): String? {
        val parts = segments(topic) ?: return null
        if (parts[2] != scope) return null
        val id = parts[3]
        if (id == "+" || id == "#") return null
        return id
    }

    private fun segments(topic: String): List<String>? {
        var t = topic
        if (t.startsWith("\$share/")) {
            // $share/{group}/{실제 토픽}
            val rest = t.removePrefix("\$share/")
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            t = rest.substring(slash + 1)
        }
        val parts = t.split('/')
        if (parts.size != 5) return null
        if (parts.any { it.isEmpty() }) return null
        if (parts[0] != "dh" || parts[1] != "v1") return null
        return parts
    }
}
