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
| `suspend DongHaeng.joinGroup(qrPayload: String, vehicleId: String? = null): GroupJoinResult` | 그룹 QR(`{base}/j/{token}`)로 합류 (`POST /groups/join`). vehicleId 생략 시 대표 차량 |
| `suspend DongHaeng.pairVehicle(qrPayload: String, nickname: String): PairResult` | **대체 수단**: 계정에 차가 없을 때 차 화면 QR(`{base}/pair?c=CODE`)로 등록 (Device Authorization Grant 승인, `POST /device/approve`) |
| `suspend DongHaeng.sendPassengerMessage(groupId: String, text: String)` | 동승자 메시지 → 그룹 차량에서 음성으로 읽힘 (`POST /groups/{id}/messages`) |
| `DongHaeng.observeGroup(groupId: String): Flow<GroupSnapshot>` | 그룹 스냅샷 스트림. MVP 는 `GET /v1/groups/{id}` **15초 폴링**(수집 취소 시 종료) → 이후 푸시로 즉시(docs/07 §3). 서버 응답의 `snapshot.members` 가 있으면 그대로, 없으면 Member 목록을 `online=false`·`OFFLINE` 으로 채운다. `events` 는 `recentEvents` 를 오래된 것부터로 뒤집은 것. 서버 오류는 흐름 예외(`Api`) |
| `data class DongHaengConfig(publicBaseUrl, provider, appVersion)` | `publicBaseUrl` = 서버 `DH_PUBLIC_BASE_URL`(끝 `/` 허용) |
| `enum AccountProvider { BLUELINK, MYHYUNDAI, KIA_CONNECT, GENESIS_CONNECTED, DEV }` | 모듈을 내장한 공식 앱(= 서버가 검증할 IdP) |
| `fun interface TokenProvider { suspend fun accessToken(): String }` | 호스트 앱의 OAuth 2.0 액세스 토큰. 모듈이 `POST /v1/auth/exchange { provider, accessToken }` 로 동행 JWT 와 교환해 캐시한다(401 → 재교환 1회 → `Unauthorized`, docs/07 §4.1). 빈 문자열 → `Unauthorized`(요청 없음). dev 는 `POST /v1/auth/login` 응답의 accessToken — 모듈은 토큰 형식을 해석하지 않는다(만료 판단에 JWT `exp` 만 읽는다) |
| `data class MyVehicle(vehicleId, nickname, brand, model, isPrimary)` | |
| `data class GroupMember(vehicleId, nickname, color, role: MemberRole)` / `enum MemberRole { LEADER, MEMBER }` | |
| `data class GroupJoinResult(groupId, name, members)` / `data class PairResult(vehicleId, nickname)` | |
| `data class GroupSnapshot(groupId, members: List<protocol.MemberState>, events: List<protocol.GroupEvent>)` | 상태·이벤트는 `app.donghaeng.protocol` 타입을 그대로 사용 |
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

### 내부 구조
| 파일 | 역할 |
|---|---|
| `DongHaeng.kt` | 공개 진입점. 초기화 검사 + QR 해석 후 `DongHaengApi` 에 위임. `initWithApi(config, factory)` 는 **테스트 전용 internal 훅**(Fake 끼우기) |
| `internal/DongHaengApi.kt` | 전송 인터페이스 |
| `internal/HttpDongHaengApi.kt` | **REST 구현(OkHttp 4.12 + protocol DTO, 타임아웃 10초)**. `/v1/auth/exchange` 교환·캐시, 401 재교환 1회, `GET /v1/me/vehicles`, `POST /v1/device/approve`(200 `VehicleSummary` → `PairResult`), `POST /v1/groups/join`, `POST /v1/groups/{id}/messages`, `GET /v1/groups/{id}` 15초 폴링. 오류 봉투 → `Api(code, message)`. 토큰·닉네임·코드는 로그에 남기지 않는다 |
| `internal/Wire.kt` | `AccountProvider.wireName`(교환 요청의 provider 문자열), `JwtClaims.expiresAtMs`(exp 만 읽음, 서명 검증 없음) |
| `internal/FakeDongHaengApi.kt` | 메모리 구현 — **단위 테스트 전용**(`DongHaeng.initWithApi` 로만 끼운다): 내 차 2대, 3대짜리 그룹, 2초 틱 스냅샷, 10틱마다 `LOW_ENERGY`, 메시지 → `PASSENGER_MESSAGE` |
| `internal/QrParser.kt` | 경로 기반 QR 판별 (단위 테스트 있음) |
| `consumer-rules.pro` | 호스트 앱 R8 에 합쳐지는 규칙(serialization·공개 API 유지) |

## 2. 호스트 앱(공식 앱)에 넣는 법

1. **AAR 추가** — `sdk/build/outputs/aar/sdk-debug.aar`(또는 release)를 의존성으로 넣는다. AAR 파일에는 POM 이 없으므로 런타임 의존성 `app.donghaeng:protocol`, `kotlinx-serialization-json`, `kotlinx-coroutines-android`, `com.squareup.okhttp3:okhttp:4.12.0`(REST 전송용, 버전은 `gradle/libs.versions.toml`)을 호스트가 직접 추가해야 한다(합성 빌드 대신 사내 Maven 에 올리면 POM 이 전이한다).
2. **로그인 직후 초기화** — 앱의 토큰 저장소를 `TokenProvider` 로 감싼다. 모듈은 동행 JWT 교환이 필요할 때(`POST /v1/auth/exchange` — 캐시가 없거나 만료됐거나 401 일 때)만 `accessToken()` 을 부르므로 갱신은 앱이 평소 하던 대로 하면 된다. `init` 자체는 네트워크를 쓰지 않는다.
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
4. **그룹 화면** — `observeGroup(groupId)` 를 수집해 멤버·이벤트를 그리고, 입력창은 `sendPassengerMessage` 로 보낸다.
5. **오류 처리** — `DongHaengException` 만 잡으면 된다. `Unauthorized` 는 앱의 재로그인 흐름으로, `OtherServer` 는 서버 설정 안내로, `Api` 는 `message`(서버 문구) 를 그대로 보여 주면 된다(`code == "NETWORK"` 는 연결 실패).
6. **평문 HTTP** — 로컬 개발(`http://10.0.2.2:8080`)에서는 호스트 앱 매니페스트에 `usesCleartextTraffic` 이 필요하다. demo-host 처럼 서버 주소 스킴에 묶어 두면(`http://` 일 때만 true) 클라우드(https) 빌드에서 평문이 새지 않는다.

권한: 모듈은 매니페스트에 아무 권한도 넣지 않는다. 카메라는 앱 스캐너의 것이고, 네트워크(`INTERNET`)는 앱이 이미 가진다.

## 3. demo-host — 시연용 호스트 앱

공식 앱을 흉내 낸 최소 앱. 모든 데이터는 **실제 서버**(`BuildConfig.DH_PUBLIC_BASE_URL`)에서 온다. 화면 4개:

| 화면 | 내용 |
|---|---|
| 로그인(dev) | 이메일·닉네임(미리 채워진 dev 계정) → 호스트의 `DevAuthClient` 가 `POST /v1/auth/login {provider:"dev", email, displayName}` 호출 → 토큰 쌍을 `DemoSession` 메모리에 보관 → `DongHaeng.init(…, AccountProvider.DEV, DevTokenProvider { accessToken })`. 입력 검증·네트워크·서버 오류는 화면에 한국어로 표시(서버에 `/v1/auth/login` 이 없으면 "아직 없어요(T10)" 안내). 모듈은 첫 호출 때 이 토큰을 `/v1/auth/exchange` 로 교환한다 |
| 홈 | 제목 "동행 — 공식 앱 흉내 (데모용)", 내 차 목록(`listMyVehicles` = 서버 `GET /v1/me/vehicles`), 상단 **새로고침** 버튼(화면 복귀 시에도 자동 재조회), 비어 있으면 "계정에 연결된 차가 없어요. 차 화면의 QR을 스캔해 등록하세요", 큰 **QR 스캔** 버튼, 참여 중인 그룹 |
| 스캔 | CameraX `PreviewView` + ML Kit 바코드(번들). 첫 QR 값 → 경로 보고 "그룹 합류 / 차량 등록" 확인 → `joinGroup`/`pairVehicle`. 카메라 없는 에뮬레이터용 **"QR 문자열 붙여넣기"** 입력칸(차 화면 QR 문자열 — logcat 의 `verifyUri`/초대 `url` — 을 그대로)과 예시 버튼. 차량 등록 성공 시 `PairResult`(별명·차량 ID)를 보여 주고 홈으로 돌아가며 목록이 다시 읽힌다 |
| 그룹 | `observeGroup`(15초 폴링) → 멤버(닉네임·상태·ETA·잔량)와 최근 이벤트 5개, 하단 입력창 → `sendPassengerMessage`. 서버 그룹 API 는 T14 에서 구현됐다(docs/03 §2) |

- 서버 주소는 `BuildConfig.DH_PUBLIC_BASE_URL` — Gradle 속성 `dhPublicBaseUrl`(기본 `http://10.0.2.2:8080`). 클라우드: `-PdhPublicBaseUrl=https://<VM-IP>.sslip.io`
- 평문 HTTP: `android:usesCleartextTraffic="${usesCleartext}"` — `dhPublicBaseUrl` 이 `http://` 일 때만 true (car-app 과 같은 규칙)
- 시작 로그: `Log.i("DongHaeng", "demo-host started base=…")`, 로그인 `dev login ok user=usr_…`, 모듈 `exchange ok provider=dev ttlS=…` (토큰·닉네임은 찍지 않는다)
- 카메라 권한은 스캔 화면 진입 시 런타임 요청. 에뮬레이터에서 미리 주려면 `adb shell pm grant app.donghaeng.companion.demo android.permission.CAMERA`
- 시연 순서(차량 등록, docs/07 §4.3 대체 수단): 서버(`infra` compose) 기동 → Pleos AVD 의 차량 앱이 `POST /v1/device/code` 로 QR 표시 → Medium_Phone AVD 의 demo-host 로그인 → 스캔(또는 `verifyUri` 붙여넣기) → "차량 등록" → 홈 목록에 차가 생긴다

## 4. 빌드 · 테스트

```powershell
cd companion
.\gradlew.bat :sdk:assembleDebug :demo-host:assembleDebug --console=plain   # AAR + APK
.\gradlew.bat :sdk:testDebugUnitTest --console=plain                        # QrParserTest, DongHaengFakeFlowTest, HttpDongHaengApiTest(MockWebServer)
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
