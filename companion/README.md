# companion — 동행 모듈 (공식 앱 내장 SDK) + 시연용 호스트 앱

폰 쪽은 별도 앱이 아니다. **현대 블루링크·마이현대, 기아 커넥트, 제네시스 커넥티드 서비스 앱에 내장되는 "동행 모듈"**(Android 라이브러리, AAR)이다.
공식 앱이 이미 가진 것(계정 로그인, 내 차 목록, QR 스캐너)은 그대로 쓰고, 모듈은 "사람 확인이 필요한 일"만 맡는다:
그룹 QR 로 내 차를 그룹에 합류시키기, 동승자 메시지 보내기, 그룹 상태 보기.

- 설계: `docs/07_companion모듈_설계.md`(공식 앱 내장 모듈 설계), `docs/02_시스템아키텍처.md`, QR 판별 규칙 `docs/07 §5`
- 작업: T05(스켈레톤) → **T13(서버 REST 전송·dev 토큰 교환·차량 등록, 이 문서)** → T18(그룹 합류 데모, 서버 그룹 API 는 T14) — `docs/08_구현계획서.md`
- 스택: AGP 8.8.2 / Gradle 8.10.2 / Kotlin 2.0.21 / JDK 17 / minSdk 28 / compileSdk 35, Compose BOM 2024.12.01 (AGP 9 업그레이드 금지)

```
companion/
├── sdk/         com.android.library  — 동행 모듈 (namespace app.donghaeng.companion.sdk, explicitApi)
├── demo-host/   com.android.application — 공식 앱을 흉내 낸 시연용 호스트 (app.donghaeng.companion.demo)
└── gradle/libs.versions.toml
```

`protocol/`(공용 메시지 스키마)은 `settings.gradle.kts` 의 `includeBuild("../protocol")` 로 합성 빌드한다. 스키마 클래스를 복사하지 않는다(CLAUDE.md 규칙 7).

## 1. sdk — 공개 API

모든 공개 심볼은 `app.donghaeng.companion.sdk` 패키지에 있고 Kotlin `explicitApi()` 모드로 빌드된다(아래 표 밖에는 public 이 없다).
자체 로그인 화면·토큰 저장소·네트워크 설정 UI 는 없다. 호스트 앱이 토큰을 준다.

| 심볼 | 설명 |
|---|---|
| `object DongHaeng` | 진입점 |
| `DongHaeng.init(config: DongHaengConfig, tokenProvider: TokenProvider)` | 로그인 직후 1회. `publicBaseUrl` 은 `http://`/`https://` 로 시작해야 한다(아니면 `IllegalArgumentException`). 네트워크는 쓰지 않는다(교환은 첫 API 호출 때). **모든 제공자(DEV 포함)가 서버 REST 전송**을 쓴다 — `provider == DEV` 는 dev 로그인 계정(호스트 토큰 = 서버 `DevTokenVerifier` 가 검증하는 `/v1/auth/login` 응답의 accessToken, docs/07 §4.2) |
| `suspend DongHaeng.listMyVehicles(): List<MyVehicle>` | 계정에 연결된 내 차 (`GET /me/vehicles`) |
| `suspend DongHaeng.joinGroup(qrPayload: String, vehicleId: String? = null): GroupJoinResult` | 그룹 QR(`{base}/j/{token}`)로 합류 (`POST /groups/join`). vehicleId 생략 시 대표 차량. **멱등**: 그 차가 이미 이 그룹이면(409 `ALREADY_MEMBER`) `GET /me/groups?active=true` → `GET /groups/{id}` 로 지금 그룹을 성공으로 돌려준다(못 찾으면 409 그대로) |
| `suspend DongHaeng.pairVehicle(qrPayload: String, nickname: String): PairResult` | **대체 수단**: 계정에 차가 없을 때 차 화면 QR(`{base}/pair?c=CODE`)로 등록 (Device Authorization Grant 승인, `POST /device/approve`) |
| `suspend DongHaeng.sendPassengerMessage(groupId: String, text: String, fromVehicleId: String? = null, idempotencyKey: String? = null)` | 동승자 메시지 → 그룹 차량에서 "{보낸 차}에서 온 메시지"로 읽힘 (`POST /groups/{id}/messages`). `fromVehicleId` = 동승자가 탄 내 차(생략 시 이 프로세스에서 합류할 때 고른 차 → 서버 기본 '그룹의 내 첫 차'). `idempotencyKey` = 사용자 동작 한 번의 키(`[A-Za-z0-9_-]{8,64}`, 헤더 `Idempotency-Key` — 다시 보내기에 같은 키면 서버가 한 번만 처리, 생략 시 호출마다 새 키, 형식이 틀리면 `IllegalArgumentException`) |
| `DongHaeng.observeGroup(groupId: String): Flow<GroupSnapshot>` | 그룹 스냅샷 스트림. MVP 는 `GET /v1/groups/{id}` **15초 폴링**(수집 취소 시 종료) → 이후 푸시로 즉시(docs/07 §3). 서버 응답의 `snapshot.members` 가 있으면 그대로, 없으면 Member 목록을 `online=false`·`OFFLINE` 으로 채운다. `events` 는 `recentEvents` 를 오래된 것부터로 뒤집은 것, `leaderVehicleId` 는 지금 방장. **그룹이 `ENDED` 면 마지막 스냅샷 뒤 흐름이 정상 종료**한다. 서버 오류는 흐름 예외(`Api`) — **일시 오류(NETWORK·5xx·429) 뒤 다시 구독은 호스트 몫**(docs/07 §3.2, demo-host `GroupObservation`). **무전(`VOICE_MESSAGE`, F16)** 은 모듈이 오디오를 미리 받아 payload 에 `localAudioStatus`(READY·PENDING·EXPIRED·FAILED)·`localAudioPath` 를 붙인다(아래 §1.1) |
| `data class DongHaengConfig(publicBaseUrl, provider, appVersion)` | `publicBaseUrl` = 서버 `DH_PUBLIC_BASE_URL`(끝 `/` 허용) |
| `enum AccountProvider { BLUELINK, MYHYUNDAI, KIA_CONNECT, GENESIS_CONNECTED, DEV }` | 모듈을 내장한 공식 앱(= 서버가 검증할 IdP) |
| `fun interface TokenProvider { suspend fun accessToken(): String }` | 호스트 앱의 OAuth 2.0 액세스 토큰. 모듈이 `POST /v1/auth/exchange { provider, accessToken }` 로 동행 JWT 와 교환해 캐시한다(401 → 재교환 1회 → `Unauthorized`, docs/07 §4.1). 빈 문자열 → `Unauthorized`(요청 없음). 제공자가 던지면 `IOException` → `Api("NETWORK")`, 그 밖 → `Unauthorized`(호스트 예외가 그대로 새지 않는다). dev 는 `POST /v1/auth/login` 응답의 accessToken — 모듈은 토큰 형식을 해석하지 않는다(만료 판단에 JWT `exp` 만 읽는다) |
| `data class MyVehicle(vehicleId, nickname, brand, model, isPrimary)` | |
| `data class GroupMember(vehicleId, nickname, color, role: MemberRole)` / `enum MemberRole { LEADER, MEMBER }` | |
| `data class GroupJoinResult(groupId, name, members)` / `data class PairResult(vehicleId, nickname)` | |
| `data class GroupSnapshot(groupId, members: List<protocol.MemberState>, events: List<protocol.GroupEvent>, leaderVehicleId: String? = null)` | 상태·이벤트는 `app.donghaeng.protocol` 타입을 그대로 사용. `leaderVehicleId` = 매 폴링의 지금 방장(위임·승계 반영) |
| `sealed class DongHaengException` — `NotInitialized`, `Unauthorized`, `InvalidQr(reason)`, `OtherServer(host)`, `Api(code, message)` | API 호출이 던지는 모든 오류(`init` 의 설정 오류만 `IllegalArgumentException`). `Api.code` 는 서버 오류 봉투의 `error.code`(`CODE_EXPIRED`, `INVITE_EXPIRED` …)이고 `message` 는 서버가 준 사용자용 문구. 모듈이 만드는 코드: `NETWORK`(연결 실패·타임아웃 10초, "서버에 연결할 수 없어요"), `HTTP_<status>`(봉투 없는 오류), `BAD_RESPONSE`(본문 해석 실패). 서버 404 `NOT_FOUND`(멤버가 아닌 그룹·나간 그룹, docs/03 §2.3)는 서버 문구 그대로 전달된다 |

### QR 판별 (docs/07 §5)
QR 은 서버가 `{DH_PUBLIC_BASE_URL}` + 경로로 만든다. 모듈은 **호스트가 아니라 경로**로 종류를 가르고, 호스트는 `publicBaseUrl` 과 같은지만 본다(포트까지 비교).

```
{base}/pair?c=CODE   → pairVehicle  (차량 등록)
{base}/j/{token}     → joinGroup    (그룹 합류)
경로는 맞고 호스트가 다름 → DongHaengException.OtherServer(host)
그 외                → DongHaengException.InvalidQr
```
`joinGroup` 에 차량 등록 QR을 주면(또는 반대) `InvalidQr` 이다. `reason` 은 사용자에게 그대로 보여 줄 수 있는 한 문장("차량 등록용 QR이에요. '차량 등록'에서 스캔해 주세요")이고, 어느 API 를 불러야 하는지는 KDoc 에 적혀 있다(호스트가 경로로 먼저 고른다).

### 인증 흐름 (docs/07 §4.1 — 모든 제공자 동일)
```
호스트 앱 로그인 ──► TokenProvider.accessToken()  (호스트 토큰; dev 는 /v1/auth/login 의 accessToken)
   첫 API 호출 ──► POST /v1/auth/exchange { provider: "dev"|"bluelink"|"myhyundai"|"kia_connect"|"genesis_connected", accessToken }
              ◄── 200 { accessToken(동행 JWT, 1h), refreshToken, user }  → 모듈이 캐시(만료 = JWT exp, 없으면 55분)
   이후 요청 ──► Authorization: Bearer <동행 JWT>   (호스트 토큰은 교환 때만 서버로 간다)
   401 ──► 교환 1회 재시도 → 그래도 401 → DongHaengException.Unauthorized (호스트 앱이 재로그인)
```
교환 자체가 401 이면 `Unauthorized`, 501 `PROVIDER_NOT_SUPPORTED` 등은 `Api(code, message)`.

### 1.1 무전 수신 (F16 — docs/07 §3.1)
공개 함수는 그대로 6개다. 호스트는 동행 JWT 를 모르므로 `GET /v1/groups/{id}/voice/{voiceId}` 를 모듈이 대신 부른다(`internal/VoiceInbox`):

```
observeGroup 폴링 ─► recentEvents 에 VOICE_MESSAGE ─► 만료(서버 시계 = 응답 Date 헤더) 확인
   ─► GET /v1/groups/{gid}/voice/{voiceId}  (Bearer 동행 JWT, Accept: audio/*, 폴링당 최신 3건, 1 MB 상한)
   ─► {java.io.tmpdir}/donghaeng-voice/{gid}/{voiceId}.m4a   (안드로이드: 앱 캐시 디렉터리)
   ─► event.payload + { localAudioStatus: "READY", localAudioPath: "/data/user/0/<앱>/cache/donghaeng-voice/…" }
404·410·expiresAtMs 지남 → EXPIRED(파일 삭제 — 이전 프로세스가 받은 파일·recentEvents 밖으로 밀려난 무전 포함)
status ENDED·GROUP_ENDED → 그 그룹 캐시 삭제 · 그룹 404(나감) → 캐시 삭제 후 예외 · 일시 오류 3회 → FAILED
이벤트와 무관: 첫 처리(폴링·listMyVehicles) + 1시간마다 받은 지 25시간 넘은 파일 정리(더 보지 않는 그룹의 캐시)
파일 작업·내려받기는 Dispatchers.IO — 호스트가 메인 스레드에서 수집해도 된다
```
호스트 코드(공식 앱이 가져갈 부분 — demo-host `donghaeng/VoiceItems.kt`·`VoicePlayer.kt`):
```kotlin
val voice = DhJson.payloadAs<VoiceMessagePayload>(event.payload)          // voiceId, durationMs, mime, sizeBytes, expiresAtMs
val status = (event.payload["localAudioStatus"] as? JsonPrimitive)?.content // READY 일 때만
val path = (event.payload["localAudioPath"] as? JsonPrimitive)?.content     // → MediaPlayer.setDataSource(path)
```
폰은 자동 재생하지 않는다(차량 앱이 자동 재생 — 같은 차의 폰과 겹침 방지). 로그: `voice: cached voiceId=… bytes=… durMs=…`, `voice: expired … basis=…`.

### 내부 구조
| 파일 | 역할 |
|---|---|
| `DongHaeng.kt` | 공개 진입점. 초기화 검사 + QR 해석 후 `DongHaengApi` 에 위임. `initWithApi(config, factory)` 는 **테스트 전용 internal 훅**(Fake 끼우기) |
| `internal/DongHaengApi.kt` | 전송 인터페이스 |
| `internal/HttpDongHaengApi.kt` | **REST 구현(OkHttp 4.12 + protocol DTO, 타임아웃 10초)**. `/v1/auth/exchange` 교환·캐시, 401 재교환 1회, `GET /v1/me/vehicles`, `POST /v1/device/approve`(200 `VehicleSummary` → `PairResult`), `POST /v1/groups/join`, `POST /v1/groups/{id}/messages`, `GET /v1/groups/{id}` 15초 폴링. 오류 봉투 → `Api(code, message)`. 토큰·닉네임·코드는 로그에 남기지 않는다 |
| `internal/Wire.kt` | `AccountProvider.wireName`(교환 요청의 provider 문자열), `JwtClaims.expiresAtMs`(exp 만 읽음, 서명 검증 없음) |
| `internal/FakeDongHaengApi.kt` | 메모리 구현 — **단위 테스트 전용**(`DongHaeng.initWithApi` 로만 끼운다): 내 차 2대, 3대짜리 그룹, 2초 틱 스냅샷, 10틱마다 `LOW_ENERGY`, 메시지 → `PASSENGER_MESSAGE` |
| `internal/QrParser.kt` | 경로 기반 QR 판별 (단위 테스트 있음) |
| `internal/VoiceInbox.kt` | F16 무전 수신 — `VoiceMessages`(payload 해석·경로·키 덧붙이기, 순수) + `VoiceInbox`(캐시·서버 시계 만료·404·그룹 종료/나감 삭제·재시도·한도). `HttpDongHaengApi.fetchGroup` 이 폴링마다 부른다 |
| `consumer-rules.pro` | 호스트 앱 R8 에 합쳐지는 규칙(serialization·공개 API 유지) |

## 2. 호스트 앱(공식 앱)에 넣는 법

1. **AAR 추가** — `sdk/build/outputs/aar/sdk-debug.aar`(또는 release)를 의존성으로 넣는다. AAR 파일에는 POM 이 없으므로 런타임 의존성 `app.donghaeng:protocol`, `kotlinx-serialization-json`, `kotlinx-coroutines-android`, `com.squareup.okhttp3:okhttp:4.12.0`(REST 전송용, 버전은 `gradle/libs.versions.toml`)을 호스트가 직접 추가해야 한다(합성 빌드 대신 사내 Maven 에 올리면 POM 이 전이한다).
2. **로그인 직후 초기화** — 앱의 토큰 저장소를 `TokenProvider` 로 감싼다. 모듈은 동행 JWT 교환이 필요할 때(`POST /v1/auth/exchange` — 캐시가 없거나 만료됐거나 401 일 때)만 `accessToken()` 을 부르므로, **부를 때 만료가 가까우면 그 자리에서 갱신해 돌려준다**(demo-host `HostTokenCache` — 만료 2분 전 재발급, 네트워크 실패는 `IOException`, 거부는 빈 문자열). `init` 자체는 네트워크를 쓰지 않는다.
   ```kotlin
   DongHaeng.init(
       config = DongHaengConfig(
           publicBaseUrl = BuildConfig.DONGHAENG_BASE_URL,      // 서버 DH_PUBLIC_BASE_URL (http:// 또는 https://)
           provider = AccountProvider.BLUELINK,                  // 내장한 앱에 맞게 → 교환 요청의 provider = "bluelink"
           appVersion = BuildConfig.VERSION_NAME,
       ),
       tokenProvider = TokenProvider { authRepository.currentAccessToken() },
   )
   ```
3. **앱의 QR 스캐너에서 호출** — 스캔 문자열을 그대로 넘긴다. 어느 API 를 부를지는 경로로 앱이 고른다(모듈 `QrParser` 와 같은 모양: 경로가 정확히 `/j/{token}` 이면 합류, 정확히 `/pair` 이고 `c=` 코드가 있으면 등록. 모듈이 호스트·토큰을 다시 검증한다). demo-host 의 `QrPathRule` 이 같은 규칙의 예다(서버 주소에 경로 접두사가 있으면 먼저 뗀다).
   ```kotlin
   val uri = Uri.parse(raw)
   val path = uri.path.orEmpty().trimEnd('/')
   when {
       Regex("^/j/[A-Za-z0-9_.~-]+$").matches(path)                        -> DongHaeng.joinGroup(raw)             // 확인 대화상자 "그룹 합류"
       path == "/pair" && !uri.getQueryParameter("c").isNullOrBlank()       -> DongHaeng.pairVehicle(raw, nickname) // 확인 대화상자 "차량 등록" (계정에 차가 없을 때만)
       else                                                                 -> showMessage("동행 QR이 아니에요")
   }
   ```
4. **그룹 화면** — `observeGroup(groupId)` 를 수집해 멤버·이벤트를 그리고, 입력창은 `sendPassengerMessage(groupId, text, fromVehicleId = 합류시킨 내 차, idempotencyKey = 보낼 문장마다 UUID)` 로 보낸다. 흐름이 **일시 오류로 끝나면 백오프로 다시 구독**하고 '다시 연결 중'을 보인다(`NOT_FOUND` = 내 차가 나감, 정상 종료 = 그룹 종료 — demo-host `GroupObservation`·`DongHaengStore`, docs/07 §3.2).
5. **오류 처리** — `DongHaengException` 만 잡으면 된다. `Unauthorized` 는 앱의 재로그인 흐름으로, `OtherServer` 는 서버 설정 안내로, `Api` 는 `message`(서버 문구) 를 그대로 보여 주면 된다(`code == "NETWORK"` 는 연결 실패 — 메시지면 같은 키로 다시 보내도 한 번만 전해진다).
6. **평문 HTTP** — 로컬 개발(`http://10.0.2.2:8080`)에서는 호스트 앱 매니페스트에 `usesCleartextTraffic` 이 필요하다. demo-host 처럼 서버 주소 스킴에 묶어 두면(`http://` 일 때만 true) 클라우드(https) 빌드에서 평문이 새지 않는다.

권한: 모듈은 매니페스트에 아무 권한도 넣지 않는다. 카메라는 앱 스캐너의 것이고, 네트워크(`INTERNET`)는 앱이 이미 가진다.

## 3. demo-host — 시연용 호스트 앱 (기아 앱 5탭 재현)

공식 앱(기아 앱)의 화면 구조를 **정적으로 재현**하고(누르면 "시연용 화면입니다" 토스트), 동행이 들어가는 지점 A~G 만 실제로 동작한다. 설계·삽입 지점 표·캡처는 `docs/07_companion모듈_설계.md` §6.

| 구역 | 내용 |
|---|---|
| 로그인 | dev 로그인(`DevAuthClient` → `POST /v1/auth/login`) → `DongHaeng.init(…, DEV, DevTokenProvider)` + `SdkGateway`. **"서버 없이 둘러보기 (시연 모드)"** 는 `FakeGateway`(호스트 쪽 가짜 그룹: 서울시청에서 움직이는 3대 + 시간표 이벤트)로 B·D·F·G 가 그대로 움직인다 |
| 홈 / 지도 / 제어 / 스토어 / 마이카 | `feature/*` — 기아 앱 레이아웃 재현. 디자인 토큰 `ui/theme/KiaTheme.kt`(배경 `#08131B`, 카드 `#18222B`, 라임 `#8CC63F`, Pretendard), 공용 조각 `ui/components/`(KiaTopBar·KiaBottomBar·KiaCard·CarArt 벡터 실루엣·OsmMap) |
| 무전(F16) | C 동행 메인 진행 중 탭의 **무전 카드**(`VoiceMessagesCard`: 보낸 차 색·이름·길이·보낸 시각·[재생]/[멈춤]·진행 바)와 F 알림의 무전 행 [재생]. 상태는 `DongHaengStore.voices`(`VoiceItems.kt` — sdk 가 덧붙인 키 읽기·합치기, 이번 스냅샷의 무전은 sdk 상태 그대로·밀려난 것만 폰 시계 + 1시간), 재생은 `VoicePlayer`(MediaPlayer, `USAGE_MEDIA`·`CONTENT_TYPE_SPEECH`, `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, 다른 앱 안내 음성(CAN_DUCK)이면 일시정지 후 이어 재생 — `VoiceFocusPolicy`). 시연 모드는 30초에 아빠 차 무전(코드로 만든 신호음 WAV, `FakeVoiceTone`) |
| 동행 삽입 지점 | `donghaeng/` 패키지 하나에 모아 둔다 — **A** 상단 QR 스캔(`ScanScreen` + `JoinGroupSheet`: 초대 미리보기 → 차 선택 → `joinGroup`), **B** 홈 "동행 중" 카드(`ActiveGroupCard`), **C** 동행 메인(`DongHaengMainScreen`), **D** 지도 "동행" 칩 → 그룹 마커 + `GroupDriveSheet`, **E** 마이카 > 연동 서비스 > 동행 그룹 주행(`DongHaengSettingsScreen`), **F** 알림 벨(`NotificationsScreen`, 백그라운드면 시스템 알림), **G** 메시지 시트(`PassengerMessageSheet`, 프리셋 4 + 60자). 상태는 `DongHaengStore`, 서버/SDK 호출은 `DongHaengGateway` 로만 |

- 서버 주소는 `BuildConfig.DH_PUBLIC_BASE_URL` — car-app 과 같은 규칙: debug = Gradle 속성 `dhPublicBaseUrl`(기본 `http://10.0.2.2:8080`), release = `dhCloudHost` 가 있으면 `https://{dhCloudHost}`, 없으면 `dhPublicBaseUrl`(http 면 빌드 경고). 클라우드 release: `-PdhCloudHost=<VM-IP>.sslip.io -PdhDevLoginKey=<키>`(차량 앱 `:app:assemblePleosRelease -PdhCloudHost=<같은 값>` 과 짝), 예전 방식 `-PdhPublicBaseUrl=https://<VM-IP>.sslip.io` 도 그대로 된다
- 로그인 이메일 기본값은 **설치마다 다른** `judge-<8hex>@dev.local`(처음 실행 때 만들어 앱 전용 저장소에 둠, 화면에서 바꿀 수 있고 로그인한 이메일을 기억) — 같은 이메일 = 같은 서버 계정이라 심사위원끼리 차·그룹이 섞이지 않게
- 진행 중 그룹은 같은 계정으로 다시 로그인하면 이어 본다(앱 전용 SharedPreferences, 토큰은 저장하지 않음). 프로세스가 죽은 뒤 복원된 화면은 로그인으로 돌아간다
- dev 로그인 접속 키는 `BuildConfig.DH_DEV_LOGIN_KEY` — Gradle 속성 `dhDevLoginKey`(기본 빈 값 = 헤더 안 보냄, 로컬 서버). 클라우드 서버는 필수: `-PdhDevLoginKey=<VM .env 의 DH_DEV_LOGIN_KEY>`
  (또는 `~/.gradle/gradle.properties`). 저장소의 `gradle.properties` 에 적지 않는다. 키 없이 클라우드에 로그인하면 403 과 함께 다시 빌드하라는 안내가 뜬다(`infra/cloud/README.md` §3)
- 평문 HTTP: `android:usesCleartextTraffic="${usesCleartext}"` — `dhPublicBaseUrl` 이 `http://` 일 때만 true (car-app 은 T32 부터 같은 취지를 network_security_config 로 — https 기본 빌드에서도 10.0.2.2·localhost 평문만 허용, docs/05 §7)
- 초대 미리보기: QR 의 URL(`{base}/j/{token}`) 자체를 `Accept: application/json` 으로 읽는다(인증 없음, docs/03 §2.2). 모듈 공개 API 는 그대로 6개
- 권한: `CAMERA`(스캔 진입 시), `POST_NOTIFICATIONS`(API 33+, 알림 화면 진입 시). 에뮬레이터: `adb shell pm grant app.donghaeng.companion.demo android.permission.CAMERA`
- 카메라 없는 에뮬레이터: 스캔 화면 본문의 **"코드·주소 직접 입력"** 에 차 페어링 화면의 코드(예 `KQ7-4MZ`) 또는 QR 문자열(초대 `url` / `verifyUri`)을 넣고 [적용](클립보드 붙여넣기 칩 있음). 시연 모드에서는 "그룹 QR 예시" 칩으로 가짜 그룹에 합류한다
- 브랜드 자산 미사용: 로고 자리 "KIA (시연)", 차량은 벡터 실루엣, 폰트 Pretendard(OFL, `res/font`), 아이콘 Material Symbols Outlined(Apache-2.0, `res/drawable/ms_*.xml`). 모든 화면 하단에 "본 앱은 공모전 시연용 재현 화면이며 기아 공식 앱이 아닙니다". 참고 스크린샷은 저장소 밖에만 둔다
- 로그: `Log.i("DongHaeng", …)` — 토큰·닉네임·좌표는 찍지 않는다. 무전: `voice: received voiceId=… status=READY durMs=… mine=…`(목록 반영), `voice: play voiceId=…` → (`voice: duck_pause|resume voiceId=…`) → `voice: done|user|focus_loss|focus_timeout|error voiceId=…`

## 4. 빌드 · 테스트

```powershell
cd companion
.\gradlew.bat :sdk:assembleDebug :demo-host:assembleDebug --console=plain   # AAR + APK
.\gradlew.bat :sdk:testDebugUnitTest --console=plain                        # QrParserTest, DongHaengFakeFlowTest, HttpDongHaengApiTest(MockWebServer), VoiceInboxTest, HttpVoiceDownloadTest
.\gradlew.bat :demo-host:testDebugUnitTest --console=plain                  # VoiceItemsTest, GroupObservationTest(일시 오류 재구독·종료·나감), DongHaengStoreTest(연결 상태·복원·보낸 차·첫 스냅샷), HostTokenCacheTest(만료 전 재발급), JoinVehiclePolicyTest, ScanInputTest, DemoHostMiscTest
.\gradlew.bat :demo-host:assembleRelease -PdhCloudHost=<VM-IP>.sslip.io -PdhDevLoginKey=<키>   # 클라우드 release(차량 앱과 같은 dhCloudHost)
.\gradlew.bat :sdk:assembleRelease                                          # 배포용 AAR

adb install -r demo-host\build\outputs\apk\debug\demo-host-debug.apk
adb shell am start -n app.donghaeng.companion.demo/.MainActivity
adb logcat -s DongHaeng
```

산출물: `sdk/build/outputs/aar/sdk-debug.aar`, `demo-host/build/outputs/apk/debug/demo-host-debug.apk`.
`local.properties`(SDK 경로)는 커밋하지 않는다. JDK 는 17 (`JAVA_HOME`), 툴체인 블록 없음.

## 5. 다음 작업
- **T14** 서버 그룹 API(생성·초대·합류·조회·메시지) — 모듈은 이미 `GroupJoinResponse`/`GroupDetailResponse` DTO 로 구현돼 있어 서버가 생기면 그대로 붙는다
- **T18** 그룹 합류 데모(차량 AVD 2대 + 데모 호스트)
- 서버 실연결 후 `observeGroup` 을 15초 폴링 → 푸시로 교체, `refreshToken`(`POST /v1/auth/refresh`) 으로 재교환 없이 갱신
- 합류 시트에 '이 차는 지금 ○○ 그룹에 있어요' 표시 — 서버 `MyVehicleDto`·공개 `MyVehicle` 에 현재 그룹 필드가 필요(docs/07 §6.1 알려진 한계)
- 항목별 공유 끄기(PRD §7) — protocol 공유 설정 + 차량 발행 필터가 먼저(지금 E 화면은 '준비 중' 표시)
- F16 다음 단계: **폰에서 녹음해 보내기** — 서버 업로드가 차량 JWT 전용(docs/03 §2.6)이라 사용자 JWT 업로드(보낼 내 차 지정) 계약 + 공개 API(`sendVoiceMessage`) 추가 결정이 먼저다. 무전 수신 지연(폴링 15초)은 푸시 전환 때 즉시로
