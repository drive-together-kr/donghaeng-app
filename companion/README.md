# companion — 동행 모듈 (공식 앱 내장 SDK) + 시연용 호스트 앱

폰 쪽은 별도 앱이 아니다. **현대 블루링크·마이현대, 기아 커넥트, 제네시스 커넥티드 서비스 앱에 내장되는 "동행 모듈"**(Android 라이브러리, AAR)이다.
공식 앱이 이미 가진 것(계정 로그인, 내 차 목록, QR 스캐너)은 그대로 쓰고, 모듈은 "사람 확인이 필요한 일"만 맡는다:
그룹 QR 로 내 차를 그룹에 합류시키기, 동승자 메시지 보내기, 그룹 상태 보기.

- 설계: `docs/07_companion모듈_설계.md`(공식 앱 내장 모듈 설계), `docs/02_시스템아키텍처.md`, QR 판별 규칙 `docs/07 §5`
- 작업: T05(스켈레톤, 이 문서) → T13(HTTP 전송·차량 등록 확인) → T18(그룹 합류 데모) — `docs/08_구현계획서.md`
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
| `DongHaeng.init(config: DongHaengConfig, tokenProvider: TokenProvider)` | 로그인 직후 1회. `publicBaseUrl` 은 `http://`/`https://` 로 시작해야 한다(아니면 `IllegalArgumentException`). `provider == DEV` 는 dev 로그인 계정(서버 `DevTokenVerifier` 가 검증하는 `/v1/auth/login` 토큰, docs/07 §4.2) — **T13 전까지만 임시로** 메모리 Fake 로 동작 |
| `suspend DongHaeng.listMyVehicles(): List<MyVehicle>` | 계정에 연결된 내 차 (`GET /me/vehicles`) |
| `suspend DongHaeng.joinGroup(qrPayload: String, vehicleId: String? = null): GroupJoinResult` | 그룹 QR(`{base}/j/{token}`)로 합류 (`POST /groups/join`). vehicleId 생략 시 대표 차량 |
| `suspend DongHaeng.pairVehicle(qrPayload: String, nickname: String): PairResult` | **대체 수단**: 계정에 차가 없을 때 차 화면 QR(`{base}/pair?c=CODE`)로 등록 (Device Authorization Grant 승인, `POST /device/approve`) |
| `suspend DongHaeng.sendPassengerMessage(groupId: String, text: String)` | 동승자 메시지 → 그룹 차량에서 음성으로 읽힘 (`POST /groups/{id}/messages`) |
| `DongHaeng.observeGroup(groupId: String): Flow<GroupSnapshot>` | 그룹 스냅샷 스트림. 주기는 서버 전송 방식이 정한다 — MVP `GET /v1/groups/{id}` 15초 폴링 → 푸시 시 즉시(docs/07 §3). 약 2초 틱은 T13 전 DEV 메모리 Fake 의 동작일 뿐 |
| `data class DongHaengConfig(publicBaseUrl, provider, appVersion)` | `publicBaseUrl` = 서버 `DH_PUBLIC_BASE_URL`(끝 `/` 허용) |
| `enum AccountProvider { BLUELINK, MYHYUNDAI, KIA_CONNECT, GENESIS_CONNECTED, DEV }` | 모듈을 내장한 공식 앱(= 서버가 검증할 IdP) |
| `fun interface TokenProvider { suspend fun accessToken(): String }` | 호스트 앱의 OAuth 2.0 액세스 토큰. 모듈이 `POST /v1/auth/exchange` 로 동행 JWT 와 교환해 캐시한다(T13; 401 → 재교환 1회 → `Unauthorized`, docs/07 §4.1). dev 는 `POST /v1/auth/login` 응답의 accessToken — 모듈은 토큰 형식을 해석하지 않는다 |
| `data class MyVehicle(vehicleId, nickname, brand, model, isPrimary)` | |
| `data class GroupMember(vehicleId, nickname, color, role: MemberRole)` / `enum MemberRole { LEADER, MEMBER }` | |
| `data class GroupJoinResult(groupId, name, members)` / `data class PairResult(vehicleId, nickname)` | |
| `data class GroupSnapshot(groupId, members: List<protocol.MemberState>, events: List<protocol.GroupEvent>)` | 상태·이벤트는 `app.donghaeng.protocol` 타입을 그대로 사용 |
| `sealed class DongHaengException` — `NotInitialized`, `Unauthorized`, `InvalidQr(reason)`, `OtherServer(host)`, `Api(code, message)` | API 호출이 던지는 모든 오류(`init` 의 설정 오류만 `IllegalArgumentException`) |

### QR 판별 (docs/07 §5)
QR 은 서버가 `{DH_PUBLIC_BASE_URL}` + 경로로 만든다. 모듈은 **호스트가 아니라 경로**로 종류를 가르고, 호스트는 `publicBaseUrl` 과 같은지만 본다(포트까지 비교).

```
{base}/pair?c=CODE   → pairVehicle  (차량 등록)
{base}/j/{token}     → joinGroup    (그룹 합류)
경로는 맞고 호스트가 다름 → DongHaengException.OtherServer(host)
그 외                → DongHaengException.InvalidQr
```
`joinGroup` 에 차량 등록 QR을 주면(또는 반대) `InvalidQr` 이다. `reason` 은 사용자에게 그대로 보여 줄 수 있는 한 문장("차량 등록용 QR이에요. '차량 등록'에서 스캔해 주세요")이고, 어느 API 를 불러야 하는지는 KDoc 에 적혀 있다(호스트가 경로로 먼저 고른다).

### 내부 구조
| 파일 | 역할 |
|---|---|
| `DongHaeng.kt` | 공개 진입점. 초기화 검사 + QR 해석 후 `DongHaengApi` 에 위임 |
| `internal/DongHaengApi.kt` | 전송 인터페이스 |
| `internal/FakeDongHaengApi.kt` | 메모리 구현 — T13 전까지 DEV 가 임시로 사용, 이후 테스트·데모 전용: 내 차 2대, 3대짜리 그룹, 2초 틱(Fake 전용 주기)으로 움직이는 스냅샷, 10틱마다 `LOW_ENERGY`, 메시지 → `PASSENGER_MESSAGE` |
| `internal/HttpDongHaengApi.kt` | OkHttp REST 구현 자리 — **T13** (지금은 `TODO`). 계획: `POST /v1/auth/exchange` 로 동행 JWT 교환·캐시 → 모든 요청 `Bearer <동행 JWT>`, 401 → 재교환 1회 → `Unauthorized`, 15초 폴링 → 푸시. T13 부터 DEV 도 이 구현을 쓴다 |
| `internal/QrParser.kt` | 경로 기반 QR 판별 (단위 테스트 있음) |
| `consumer-rules.pro` | 호스트 앱 R8 에 합쳐지는 규칙(serialization·공개 API 유지) |

## 2. 호스트 앱(공식 앱)에 넣는 법

1. **AAR 추가** — `sdk/build/outputs/aar/sdk-debug.aar`(또는 release)를 의존성으로 넣는다. AAR 파일에는 POM 이 없으므로 런타임 의존성 `app.donghaeng:protocol`, `kotlinx-serialization-json`, `kotlinx-coroutines-android`, `com.squareup.okhttp3:okhttp:4.12.0`(REST 전송용, 버전은 `gradle/libs.versions.toml`)을 호스트가 직접 추가해야 한다(합성 빌드 대신 사내 Maven 에 올리면 POM 이 전이한다).
2. **로그인 직후 초기화** — 앱의 토큰 저장소를 `TokenProvider` 로 감싼다. 모듈은 동행 JWT 교환이 필요할 때(`POST /v1/auth/exchange` — 캐시가 없거나 401 일 때) `accessToken()` 을 부르므로 갱신은 앱이 평소 하던 대로 하면 된다.
   ```kotlin
   DongHaeng.init(
       config = DongHaengConfig(
           publicBaseUrl = BuildConfig.DONGHAENG_BASE_URL,      // 서버 DH_PUBLIC_BASE_URL (http:// 또는 https://)
           provider = AccountProvider.BLUELINK,                  // 내장한 앱에 맞게 (서버 전송은 T13)
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
5. **오류 처리** — `DongHaengException` 만 잡으면 된다. `Unauthorized` 는 앱의 재로그인 흐름으로, `OtherServer` 는 서버 설정 안내로 보낸다.

권한: 모듈은 매니페스트에 아무 권한도 넣지 않는다. 카메라는 앱 스캐너의 것이고, 네트워크(`INTERNET`)는 앱이 이미 가진다.

## 3. demo-host — 시연용 호스트 앱

공식 앱을 흉내 낸 최소 앱. 화면 4개:

| 화면 | 내용 |
|---|---|
| 로그인(dev) | 이메일·닉네임 → `DevTokenProvider(email)` 가 임시 placeholder 토큰을 돌려준다(T13 에서 `POST /v1/auth/login` 응답의 accessToken 으로 교체) → `DongHaeng.init(…, AccountProvider.DEV, …)`. T13 전까지 모듈은 메모리 Fake 로 동작(네트워크 없음) |
| 홈 | 제목 "동행 — 공식 앱 흉내 (데모용)", 내 차 목록(`listMyVehicles`), 큰 **QR 스캔** 버튼, 참여 중인 그룹 |
| 스캔 | CameraX `PreviewView` + ML Kit 바코드(번들). 첫 QR 값 → 경로 보고 "그룹 합류 / 차량 등록" 확인 → `joinGroup`/`pairVehicle`. 카메라 없는 에뮬레이터용 **테스트 QR 붙여넣기** 입력칸과 예시 버튼 |
| 그룹 | `observeGroup` → 멤버(닉네임·상태·ETA·잔량)와 최근 이벤트 5개, 하단 입력창 → `sendPassengerMessage` |

- 서버 주소는 `BuildConfig.DH_PUBLIC_BASE_URL` — Gradle 속성 `dhPublicBaseUrl`(기본 `http://10.0.2.2:8080`). 클라우드: `-PdhPublicBaseUrl=https://<VM-IP>.sslip.io`
- 시작 로그: `Log.i("DongHaeng", "demo-host started base=…")`
- 카메라 권한은 스캔 화면 진입 시 런타임 요청. 에뮬레이터에서 미리 주려면 `adb shell pm grant app.donghaeng.companion.demo android.permission.CAMERA`

## 4. 빌드 · 테스트

```powershell
cd companion
.\gradlew.bat :sdk:assembleDebug :demo-host:assembleDebug --console=plain   # AAR + APK
.\gradlew.bat :sdk:testDebugUnitTest --console=plain                        # QrParserTest, DongHaengFakeFlowTest
.\gradlew.bat :sdk:assembleRelease                                          # 배포용 AAR

adb install -r demo-host\build\outputs\apk\debug\demo-host-debug.apk
adb shell am start -n app.donghaeng.companion.demo/.MainActivity
adb logcat -s DongHaeng
```

산출물: `sdk/build/outputs/aar/sdk-debug.aar`, `demo-host/build/outputs/apk/debug/demo-host-debug.apk`.
`local.properties`(SDK 경로)는 커밋하지 않는다. JDK 는 17 (`JAVA_HOME`), 툴체인 블록 없음.

## 5. 다음 작업
- **T13** `HttpDongHaengApi` 구현(OkHttp; dev 토큰 교환 `POST /v1/auth/exchange` → 동행 JWT 캐시 → `Authorization: Bearer`, 401 → 재교환 1회 → `Unauthorized`, 오류 본문 → `Api(code, message)`), **DEV → `HttpDongHaengApi` 전환**(Fake 는 `internal` 테스트 훅으로만, 공개 API 변경 없음), demo-host 는 `POST /v1/auth/login` 으로 dev 토큰 수령, 서버 identity 의 외부 IdP 토큰 검증 인터페이스와 연결
- **T18** 그룹 합류 데모(차량 AVD 2대 + 데모 호스트)
- 서버 실연결 후 `observeGroup` 을 폴링 → 푸시로 교체
