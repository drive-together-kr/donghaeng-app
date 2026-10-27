# protocol — 공용 메시지 스키마

car-app · server · companion/sdk 가 함께 쓰는 Kotlin 데이터 클래스(kotlinx.serialization). MQTT 메시지 스키마는 **여기에서만** 정의한다(CLAUDE.md 규칙 7). car-app·server·companion 에서 같은 클래스를 다시 만들지 않는다. companion/sdk 는 `GroupState.kt` 의 `MemberState` 와 `GroupEvent` 를 공개 API(`GroupSnapshot`)에 그대로 재노출한다(`api` 의존).

- 명세: `docs/03_API_프로토콜_명세.md` §4(토픽)~§5(메시지), `docs/04_데이터모델.md` §4
- 작업: T01 (`docs/08_구현계획서.md`)
- 좌표: `app.donghaeng:protocol:0.1.0`, 패키지 `app.donghaeng.protocol`
- 빌드: Gradle 8.10.2 wrapper, Kotlin 2.0.21, JVM 17 바이트코드(툴체인 없음 — 데몬 JDK 17/21 어느 쪽이든 빌드됨)

## 무엇이 들어 있나

| 파일 | 내용 |
|---|---|
| `Envelope.kt` | 공통 봉투 `Envelope<T>(v=1, type, vid, ts, seq, data)` |
| `MessageTypes.kt` | 고정 `type` 상수: `TELEMETRY`, `STATE`, `EVENT`, `ACK`, `PRESENCE` |
| `Telemetry.kt` | `Telemetry`, `Eta`, `Energy`, `LocSource` (§5.1) |
| `Place.kt` | 목적지·경유지 `Place` |
| `VehicleEvents.kt` | 차량 이벤트 `VehicleEventType` + 본문 클래스(`LowEnergy`, `HardBrake`, `HazardOn`, …) + `vehicleEventSerializer()` (§5.2) |
| `GroupState.kt` | `GroupState`, `MemberState`, `MemberStatus` (§5.3) |
| `GroupEvent.kt` | `GroupEvent`, `Severity`, `GroupEventType` (§5.4) |
| `Commands.kt` | `CmdType` + 본문 클래스(`GroupJoined`, `RouteFollowProposal`, `RestStopProposal`, `GroupEnded`, `TokenRevoked`) + `cmdSerializer()` (§5.5) |
| `Ack.kt` | `Ack` (§5.6), `Presence` (LWT 본문) |
| `Topics.kt` | 토픽 문자열 생성·해석 (§4) |
| `DhJson.kt` | 공용 `Json` 설정과 `encode / decode / peek / decodeData / payloadOf / payloadAs` |

### JSON 규칙 (`DhJson.json`)

- `ignoreUnknownKeys = true` — 상대가 더 새 스키마여도 깨지지 않음
- `encodeDefaults = true` — `v = 1`, `waypoints = []` 같은 기본값도 항상 내보냄
- `explicitNulls = false` — null 은 생략, 없는 필드는 null 로 읽음
- 빈 본문(`HAZARD_ON`, `AIRBAG_DEPLOYED`, `TOKEN_REVOKED`)은 `@Serializable object` 라서 `{}` 로 직렬화됨

car-app·server·companion/sdk 는 자체 `Json {}` 을 만들지 말고 `DhJson.json` 을 쓴다. 세 소비자의 해석 규칙이 어긋나지 않게 하기 위함이다.

### 봉투 `type` 값

| 토픽 | `type` | `data` |
|---|---|---|
| `vehicle/{vid}/telemetry` | `MessageTypes.TELEMETRY` | `Telemetry` |
| `vehicle/{vid}/event` | `VehicleEventType.name` | `vehicleEventSerializer(type)` 로 고름 |
| `vehicle/{vid}/ack` | `MessageTypes.ACK` | `Ack` |
| `vehicle/{vid}/cmd` | `CmdType.name` | `cmdSerializer(type)` 로 고름 |
| `group/{gid}/state` | `MessageTypes.STATE` | `GroupState` |
| `group/{gid}/event` | `MessageTypes.EVENT` | `GroupEvent` (세부 종류는 `GroupEvent.type`) |
| `vehicle/{vid}/presence` | 봉투 없음 | `Presence` 그대로 (`{"online":false}`) |

## car-app / server / companion 에서 쓰는 법

모노레포 안의 **Gradle 합성 빌드(composite build)** 로 포함한다. 별도 publish 없이 소스가 바로 연결된다.

```kotlin
// car-app/settings.gradle.kts, server/settings.gradle.kts, companion/settings.gradle.kts
includeBuild("../protocol")
```

```kotlin
// 모듈의 build.gradle.kts
dependencies {
    implementation("app.donghaeng:protocol")   // 버전 생략 — includeBuild 가 소스 프로젝트로 치환한다
    // companion/sdk 만 api("app.donghaeng:protocol") — GroupSnapshot 이 MemberState·GroupEvent 를 호스트 앱에 그대로 노출하므로
}
```

`kotlinx-serialization-json` 은 `api` 로 노출되므로 소비자가 `JsonObject` 등을 쓰기 위해 따로 추가할 필요는 없다(소비 모듈 자체에서 `@Serializable` 클래스를 만들려면 그 모듈에도 `kotlin("plugin.serialization")` 플러그인은 필요하다).

### 보내기 / 받기 예

```kotlin
// 보내기 (차량 앱)
val raw = DhJson.encode(
    Envelope(type = MessageTypes.TELEMETRY, vid = vehicleId, ts = now, seq = seq++, data = telemetry),
)
mqtt.publish(Topics.vehicleTelemetry(vehicleId), raw, qos = 0)

// 받기 (서버 라우터) — type 을 먼저 보고 본문을 해석
val head = DhJson.peek(raw)
when (head.type) {
    MessageTypes.TELEMETRY -> handle(DhJson.decodeData<Telemetry>(head))
    MessageTypes.ACK       -> handle(DhJson.decodeData<Ack>(head))
    else -> VehicleEventType.entries.firstOrNull { it.name == head.type }?.let { type ->
        val payload = DhJson.json.decodeFromJsonElement(vehicleEventSerializer(type), head.data)
        handleEvent(type, payload)
    }
}

// 그룹 이벤트 payload 만들기 / 읽기
val event = GroupEvent(eventId, GroupEventType.LOW_ENERGY.name, vid, Severity.WARN, speech, DhJson.payloadOf(lowEnergy))
val lowEnergy = DhJson.payloadAs<LowEnergy>(event.payload)
```

## 테스트 실행

```powershell
cd protocol
.\gradlew.bat test --console=plain     # Windows
./gradlew test --console=plain         # macOS / Linux / Git Bash
.\gradlew.bat build                    # jar 까지 (build/libs/protocol-0.1.0.jar)
```

테스트(`src/test/kotlin`)가 보장하는 것:

1. 모든 스키마 클래스의 JSON 왕복(encode → decode → equals)
2. `docs/03` §5 의 예시 JSON 이 그대로 디코딩됨
3. 모르는 키 무시, null 생략/부재 처리, 빈 본문 `{}`
4. `Envelope<Telemetry>` 전체 왕복과 `peek` 라우팅
5. 토픽 문자열 생성·해석
6. `VehicleEventType` ⊂ `GroupEventType`, 레지스트리 함수가 모든 종류를 다룸

## 스키마를 바꿀 때

1. `docs/03_API_프로토콜_명세.md` 를 먼저 고친다(계약 문서).
2. 이 모듈의 클래스와 테스트를 고친다. 필드 추가는 **기본값이 있거나 nullable** 로만 한다(구버전 앱과의 호환).
3. car-app · server · companion 을 다시 빌드해서 깨지는 곳을 고친다(companion/sdk 는 공개 API 에 재노출하므로 호스트 앱도 영향을 받는다).
