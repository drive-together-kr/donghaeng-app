package app.donghaeng.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

/**
 * 동행 메시지 공용 [Json] 설정과 봉투 인코딩/디코딩 도우미.
 *
 * - `ignoreUnknownKeys`: 상대가 더 새 스키마라도 깨지지 않는다
 * - `encodeDefaults`: `v = 1` 같은 기본값도 항상 내보낸다
 * - `explicitNulls = false`: null 필드는 생략하고, 없는 필드는 null 로 읽는다
 *
 * car-app·server 는 반드시 이 [json] 인스턴스를 쓴다(스키마 해석 규칙을 한 곳에서 맞추기 위함).
 */
object DhJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    // ---------- 봉투 ----------

    /** 봉투를 JSON 문자열로. [dataSerializer] 는 `data` 타입의 직렬화기 */
    fun <T> encode(envelope: Envelope<T>, dataSerializer: KSerializer<T>): String =
        json.encodeToString(Envelope.serializer(dataSerializer), envelope)

    /** [encode] 의 reified 버전 — `DhJson.encode(envelope)` */
    inline fun <reified T> encode(envelope: Envelope<T>): String =
        encode(envelope, serializer<T>())

    /** JSON 문자열을 봉투로. [dataSerializer] 는 `data` 타입의 직렬화기 */
    fun <T> decode(string: String, dataSerializer: KSerializer<T>): Envelope<T> =
        json.decodeFromString(Envelope.serializer(dataSerializer), string)

    /** [decode] 의 reified 버전 — `DhJson.decode<Telemetry>(raw)` */
    inline fun <reified T> decode(string: String): Envelope<T> =
        decode(string, serializer<T>())

    /**
     * 헤더(v/type/vid/ts/seq)만 해석하고 `data` 는 원본 [JsonElement] 로 둔다.
     * 라우터가 `type` 으로 분기한 뒤 [decodeData] 로 본문을 마저 해석하는 용도.
     */
    fun peek(string: String): Envelope<JsonElement> =
        json.decodeFromString(Envelope.serializer(JsonElement.serializer()), string)

    /** [peek] 결과의 `data` 를 [dataSerializer] 로 해석해 완전한 봉투로 만든다 */
    fun <T> decodeData(peeked: Envelope<JsonElement>, dataSerializer: KSerializer<T>): Envelope<T> =
        Envelope(
            v = peeked.v,
            type = peeked.type,
            vid = peeked.vid,
            ts = peeked.ts,
            seq = peeked.seq,
            data = json.decodeFromJsonElement(dataSerializer, peeked.data),
        )

    /** [decodeData] 의 reified 버전 */
    inline fun <reified T> decodeData(peeked: Envelope<JsonElement>): Envelope<T> =
        decodeData(peeked, serializer<T>())

    // ---------- 봉투 없는 본문 (presence LWT, GroupEvent.payload) ----------

    /** 본문 객체를 [JsonObject] 로 — [GroupEvent.payload] 를 만들 때 */
    fun <T> payloadOf(value: T, serializer: KSerializer<T>): JsonObject =
        json.encodeToJsonElement(serializer, value).jsonObject

    /** [payloadOf] 의 reified 버전 */
    inline fun <reified T> payloadOf(value: T): JsonObject =
        payloadOf(value, serializer<T>())

    /** [JsonObject] 를 본문 객체로 — [GroupEvent.payload] 를 읽을 때 */
    fun <T> payloadAs(payload: JsonObject, serializer: KSerializer<T>): T =
        json.decodeFromJsonElement(serializer, payload)

    /** [payloadAs] 의 reified 버전 */
    inline fun <reified T> payloadAs(payload: JsonObject): T =
        payloadAs(payload, serializer<T>())
}
