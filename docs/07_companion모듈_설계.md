# 동행 companion 모듈 설계 (공식 앱 내장 SDK)

> 2026-10-27 설계 변경. 폰 쪽은 별도 Flutter 앱이 아니라 **현대 블루링크·마이현대, 기아 커넥트, 제네시스 커넥티드 서비스 앱에 내장되는 "동행 모듈"**이다.
> 사용자는 이미 쓰는 공식 앱 안의 "동행" 메뉴에서 그룹 QR 을 찍고, 계정·차량 정보는 공식 앱 것을 그대로 쓴다.

## 1. 위치와 역할
차량에는 카메라·키보드가 없으므로 **"사람 확인이 필요한 일"**을 폰이 맡는다. 다만 그 폰 기능은 공식 앱의 일부다.

| 누가 | 제공하는 것 |
|---|---|
| 호스트 앱(공식 앱) | 계정(OAuth 2.0 토큰), 계정에 연결된 차량 목록, QR 스캐너 화면, 푸시 채널, 브랜드 UI |
| 동행 모듈(`companion/sdk`) | 동행 서버 연동(그룹 합류·메시지·그룹 상태), 공개 API 6개, QR 문자열 해석 규칙, 대체 수단인 차량 페어링 승인 |
| 시연용 호스트 앱(`companion/demo-host`) | 공식 앱을 흉내 낸 데모 앱 — dev 로그인, 내 차 목록, QR 스캔 버튼 → sdk 호출 |

모듈에는 **자체 로그인 화면·계정 저장소가 없다.** 토큰은 호스트 앱이 `TokenProvider`로 넘긴다.

## 2. 디렉터리
```
companion/
 ├─ sdk/         Android Kotlin 라이브러리(AAR). minSdk 28, Kotlin explicitApi, protocol/ 재사용
 │   ├─ DongHaeng.kt          공개 진입점(object)
 │   ├─ api/                  DongHaengApi(전송 추상화) → HttpDongHaengApi(REST, T13) | FakeDongHaengApi(데모·테스트)
 │   ├─ qr/                   QrParser — 경로 기준 QR 판별 (§5)
 │   └─ auth/                 TokenProvider 토큰 → 서버 /v1/auth/exchange → 동행 JWT 캐시
 └─ demo-host/   시연용 호스트 앱(application). Compose, CameraX + ML Kit 바코드 스캔
```
차량 앱·서버와 같은 AGP 8.8.x / Gradle 8.10.x / Kotlin 2.0.x / JDK 17. 메시지 스키마는 `protocol/`만 쓴다(CLAUDE.md 규칙 7).

## 3. 공개 API (최소 6개 — 이 외는 전부 `internal`)
```kotlin
public object DongHaeng {
    public fun init(config: DongHaengConfig, tokenProvider: TokenProvider)
    public suspend fun listMyVehicles(): List<MyVehicle>                                  // 계정에 연결된 내 차
    public suspend fun joinGroup(qrPayload: String, vehicleId: String? = null): GroupJoinResult
    public suspend fun pairVehicle(qrPayload: String, nickname: String): PairResult       // 대체 수단 (§4.3)
    public suspend fun sendPassengerMessage(groupId: String, text: String)
    public fun observeGroup(groupId: String): Flow<GroupSnapshot>
}
public data class DongHaengConfig(val publicBaseUrl: String, val provider: AccountProvider, val appVersion: String)
public enum class AccountProvider { BLUELINK, MYHYUNDAI, KIA_CONNECT, GENESIS_CONNECTED, DEV }
public fun interface TokenProvider { public suspend fun accessToken(): String }         // 호스트 앱의 OAuth 2.0 access token
```

| API | 하는 일 | 서버 호출 (docs/03) |
|---|---|---|
| `init` | 서버 공개 주소(`DH_PUBLIC_BASE_URL`)·계정 제공자·토큰 공급자 등록 | 없음 |
| `listMyVehicles` | 내 차 목록 | `GET /v1/me/vehicles` |
| `joinGroup` | 그룹 QR(`{base}/j/{token}`) → 내 차를 그룹에 합류 | `POST /v1/groups/join` |
| `pairVehicle` | 페어링 QR(`{base}/pair?c=…`) 승인 — 계정에 차가 없을 때만 | `POST /v1/device/approve` |
| `sendPassengerMessage` | 동승자 메시지 → 그룹 차량에서 음성으로 읽힘 | `POST /v1/groups/{id}/messages` |
| `observeGroup` | 그룹 스냅샷·이벤트 스트림(`protocol.GroupState`·`GroupEvent` 재사용) | `GET /v1/groups/{id}` 폴링 15s → 푸시(P1) |

반환 타입: `MyVehicle(vehicleId, nickname, brand, model, isPrimary)`, `GroupJoinResult(groupId, name, members)`, `GroupMember(vehicleId, nickname, color, role)`, `PairResult(vehicleId, nickname)`, `GroupSnapshot(groupId, members: List<protocol.MemberState>, events: List<protocol.GroupEvent>)`.
오류: `DongHaengException` 봉인 클래스 — `NotInitialized`, `Unauthorized`, `InvalidQr`, `OtherServer`, `Api(code, message)`.

## 4. 인증 설계
### 4.1 실서비스 — 공식 앱 계정 OAuth 2.0 토큰 검증
```
호스트 앱 로그인(공식 계정) ─► TokenProvider.accessToken()
   ─► sdk: POST /v1/auth/exchange { provider, accessToken }
   ─► 서버 identity: ExternalTokenVerifier(provider).verify(accessToken) → (provider, sub, displayName)
   ─► 동행 JWT 발급(사용자 토큰 1h) → sdk 가 캐시, 이후 모든 호출에 Bearer
차량 목록: 서버 VehicleDirectory — 실서비스는 OEM 차량 API(계정에 연결된 차), MVP 는 동행 DB
```
서버에는 **검증 인터페이스만** 둔다: `ExternalTokenVerifier` (구현 자리: Bluelink/MyHyundai, KiaConnect, GenesisConnected). 실제 IdP 연동은 OEM 협의 대상이며 공모전 범위 밖이다.

### 4.2 MVP — dev 로그인
`provider = DEV`. demo-host 가 `POST /v1/auth/login {provider:"dev", email, displayName}` 로 받은 토큰을 `TokenProvider` 로 넘기고, 서버의 `DevTokenVerifier` 가 이를 그대로 인정한다. 계정에 연결된 차량은 4.3 의 페어링으로 만든다.

### 4.3 차량 페어링(Device Authorization Grant)은 대체 수단
공식 앱 계정에 연결된 차량이 없을 때(렌터카, 가족 차, 데모용 에뮬레이터)만 쓴다. 차 화면 QR(`{base}/pair?c=CODE`)을 호스트 앱 스캐너로 찍으면 `pairVehicle` 이 `POST /v1/device/approve` 로 승인한다. 계정에 차가 있으면 이 단계는 건너뛴다.

## 5. QR 판별 규칙
QR 문자열은 서버가 `{DH_PUBLIC_BASE_URL}` + 경로로 만든다(docs/03 머리말). 호스트 앱 스캐너가 읽은 문자열을 모듈이 **경로로** 판별하고, 호스트는 `init` 에 준 서버와 같은지만 확인한다.
```
{DH_PUBLIC_BASE_URL}/j/inv_xxxxx     → 그룹 합류  → joinGroup      (예: http://10.0.2.2:8080/j/inv_abc)
{DH_PUBLIC_BASE_URL}/pair?c=XXXXXX   → 차량 페어링 → pairVehicle    (예: https://1-2-3-4.sslip.io/pair?c=KQ74MZ)
경로가 위 둘이 아님                  → InvalidQr  "동행 QR이 아니에요"
호스트가 설정된 서버와 다름          → OtherServer "다른 서버의 QR이에요"
```
웹 폴백: 앱이 없는 폰 기본 카메라로 찍으면 서버가 같은 경로에서 "앱(공식 앱의 동행 메뉴) 안내" 정적 페이지를 돌려준다(서버 내장, 도메인 비의존).

## 6. demo-host (시연용 호스트 앱)
공식 앱의 "동행" 메뉴를 흉내 낸 앱이다. 제품이 아니라 **통합 절차를 그대로 보여주는 시연 장치**다.

| 화면 | 내용 | sdk 호출 |
|---|---|---|
| 로그인(dev) | 이메일 + 닉네임 → dev 토큰 → `TokenProvider` | `DongHaeng.init` |
| 홈 | "공식 앱 흉내 (데모용)" 표시, 내 차 목록, **QR 스캔** 버튼, 참여 중 그룹 | `listMyVehicles` |
| 스캔 | CameraX `PreviewView` + ML Kit 바코드(번들 모델). 경로 판별 후 "그룹 합류 / 차량 등록" 확인. 에뮬레이터용 "테스트 QR 붙여넣기" 입력 | `joinGroup` / `pairVehicle` |
| 그룹 | 멤버 리스트(닉네임·상태·ETA·잔량), 최근 이벤트, 메시지 입력 | `observeGroup`, `sendPassengerMessage` |

권한: `CAMERA`(런타임). 패키지 `app.donghaeng.companion.demo`. 실행 대상: `Medium_Phone` AVD 또는 실기기.

## 7. 호스트 앱 통합 절차 (공식 앱 팀 관점)
1. `companion/sdk` AAR 의존성 추가(`protocol` 포함).
2. 앱 시작 시 `DongHaeng.init(DongHaengConfig(base, provider, version)) { 공식 계정 access token }`.
3. 공식 앱 QR 스캐너 결과를 `joinGroup(payload)` 로 넘긴다(`pair` 경로면 `pairVehicle`).
4. "동행" 메뉴 화면에서 `listMyVehicles`·`observeGroup`·`sendPassengerMessage` 로 UI 를 그린다(브랜드 UI 는 호스트 소유).
5. (선택) 호스트 푸시 채널로 그룹 이벤트 수신 → P1.

## 8. 제약과 확장
- 공식 앱 내장은 **OEM 협의·SDK 배포 채널이 필요한 가정**이다(docs/10 R11, 설명회 Q2). 모듈은 호스트 앱 없이도 AAR 로 독립 빌드·테스트되며, demo-host 가 통합 절차를 대신 보여준다.
- Pleos ID 로그인이 열리면 `AccountProvider` 하나 추가로 차량 앱·모듈 계정을 통합하고 페어링 단계를 생략한다.
- iOS 공식 앱용은 같은 API 를 Swift 패키지로 제공(추후). 메시지 스키마는 `protocol/` 를 기준으로 생성한다.
- 모듈은 위치를 수집하지 않는다(차량이 보낸다). 수집·보관 원칙은 docs/01 §7 과 같다.
