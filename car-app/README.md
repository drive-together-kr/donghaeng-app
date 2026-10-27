# car-app — 동행 차량 앱 (Pleos Connect / AAOS)

Kotlin 2.0.21 · Jetpack Compose(BOM 2024.12.01, Material3) · Hilt 2.53.1(KSP) · AGP 8.8.2 · Gradle 8.10.2 · JDK 17 · minSdk 28 / compile·target 35

- 설계: `docs/05_차량앱_설계.md` — 어댑터 인터페이스(§3)는 그대로 코드에 옮겼다
- 작업: T04 스켈레톤 (`docs/08_구현계획서.md`). 다음 작업 ID 는 소스의 `TODO(Txx)` 주석으로 표시
- 공용 스키마 `protocol/` 은 `settings.gradle.kts` 의 `includeBuild("../protocol")` 로 합성 빌드한다(복사 금지, CLAUDE.md 규칙 7)

## 빌드

```powershell
cd car-app
# JDK 17 이 JAVA_HOME 이어야 한다. Android SDK 경로는 local.properties(sdk.dir, git 제외)에 둔다.
.\gradlew.bat :app:assemblePleosDebug --console=plain    # 실 SDK (Pleos Connect AVD 전용)
.\gradlew.bat :app:assembleFakeDebug  --console=plain    # 전부 Fake (일반 폰·태블릿 AVD 에서도 실행)
.\gradlew.bat :app:testFakeDebugUnitTest --console=plain # 단위 테스트

# 클라우드(T32) 빌드 — 서버 주소를 Gradle 속성으로 덮어쓴다 (기본값은 gradle.properties: 에뮬레이터 → 호스트)
.\gradlew.bat :app:assemblePleosRelease -PdhPublicBaseUrl=https://1-2-3-4.sslip.io -PdhMqttPublicUri=ssl://1-2-3-4.sslip.io:8883
# T40 전까지 release 에는 signingConfig 가 없어 위 명령의 산출물은 app-pleos-release-unsigned.apk(미서명) 이고 adb install 이 거부한다
# (INSTALL_PARSE_FAILED_NO_CERTIFICATES). 설치해서 확인할 클라우드 빌드가 필요하면 같은 -P 옵션으로 :app:assemblePleosDebug 를 쓴다.

# 캐시 없이 전부 다시 빌드·테스트해 확인하고 싶을 때 (gradle.properties 의 org.gradle.caching=true 는 유지)
.\gradlew.bat :app:assembleFakeDebug :app:testFakeDebugUnitTest --rerun-tasks --no-build-cache --console=plain
```

산출물: `app/build/outputs/apk/{pleos|fake}/{debug|release}/app-{flavor}-{buildType}.apk`
— release 는 T40 까지 `app-pleos-release-unsigned.apk` / `app-fake-release-unsigned.apk` (서명 없음)
(release 서명·`apk/{팀명}_동행_release.apk` 복사는 T40)

`-PdhPublicBaseUrl` 의 스킴이 매니페스트 `android:usesCleartextTraffic` 을 정한다: `http://`(기본값, 로컬 개발) → `true`,
`https://`(클라우드) → `false`. HiveMQ(`tcp://`)에는 적용되지 않으므로 MqttGateway(T16)는 서버 주소가 https 면 `ssl://` 만 허용한다.

AGP 9 로 올리지 않는다(Android Studio 가 권해도 거절). 툴체인 버전은 `gradle/libs.versions.toml` 한 곳에서만 바꾼다.

## 빌드 변형 (flavor dimension `adapter`)

| flavor | 어댑터 | Pleos SDK 의존성 | 실행 대상 |
|---|---|---|---|
| `pleos` | T11: `PleosVehicleAdapter`(CarInfo 차량 ID + `checkXxxCapabilities`; 나머지 Flow 는 T22 까지 내부에서 Fake 위임) + Navi/Location 은 Fake(T20/T21) + `PleosSdkProbe` | Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3 (`pleosImplementation`) | Pleos Connect AVD (`Pleos_Connect_v2.1.0`, API 34 automotive) |
| `fake` | 전부 Fake (`FakeVehicleAdapter`, `FakeNaviAdapter`, `FakeLocationAdapter`) + Android TTS | 없음 | 아무 AVD / 폰 / 태블릿 |

두 flavor 는 applicationId 가 같다(`app.donghaeng.car`) — 한 기기에 둘을 동시에 설치할 수 없다.
Pleos 차량 권한(`pleos.car.permission.*`, `android.car.permission.*`)은 `src/pleos/AndroidManifest.xml` 에만 있다.
FusedLocation AAR 이 `uses-feature automotive required=true` 를 끌고 오므로 `pleos` APK 는 자동차 기기에만 설치된다.

`BuildConfig`: `FLAVOR`, `ADAPTER`("pleos"/"fake"), `DH_PUBLIC_BASE_URL`, `DH_MQTT_PUBLIC_URI`, `VERSION_NAME`(0.1.0-pleos / 0.1.0-fake)

## 패키지 구조 (`app.donghaeng.car`, 단일 모듈)

| 패키지 | 내용 | 상태 |
|---|---|---|
| `DongHaengApp`, `MainActivity` | `@HiltAndroidApp`(시작 로그 `car app started …`, `SdkProbe` + `CapabilityReporter` 1회 실행), 단일 액티비티 Compose | T04/T11 |
| `ui.theme`, `ui.navigation`, `ui.components` | Material3 테마(동적 색 끔), `DhNavHost`/`DhRoutes`(home·pairing·settings·diagnostics …), 자리표시자 화면 | T04 |
| `core.common` | `DhLog`(태그 `DongHaeng`, capability 전용 `DH_CAP`), `CoordinateMasking`/`TextMasking`(규칙 8, `maskVehicleId`), `TokenStore`/`EncryptedTokenStore`(security-crypto)/`InMemoryTokenStore`, `VehicleIdFallback`(설치별 ID), `DrivingStateMonitor`(T29 자리) | T04/T12 |
| `core.network` | `ServerConfig`(BuildConfig 주소), `IdentityApi`(Retrofit 2.11 + kotlinx-serialization, `/v1/device/code`·`/token`), `ApiResult`/`safeApiCall`(428/429/410/404 분기), `NetworkModule`(OkHttp BASIC 로그는 debug 만, Authorization 가림) | T12 |
| `core.network.mqtt` | `MqttGateway`(인터페이스: connect/disconnect/subscribe/publish, `status`·`inbound` Flow) + `HiveMqttGateway`(HiveMQ MQTT 5 — username/clientId = vehicleId, password = 차량 JWT, 자동 재연결 1~30s 지수 백오프·재연결 시 최신 토큰, 연결될 때마다 구독 복구, LWT `presence {"online":false}` retained, 접속 시 `online:true`) + `InMemoryMqttGateway`(테스트). `MqttUriPolicy`: `ssl://`→TLS(8883), 서버가 https 인데 `tcp://` 면 접속 거부 | T16 |
| `core.network` (T17) | `GroupApi`(Retrofit: `POST /v1/groups`, `/invites`, `GET /v1/groups/{id}`, `/leave`, `/end`), `VehicleAuthInterceptor`(차량 JWT Bearer) | T17 |
| `domain.group` | `GroupRepository`(생성 → `GroupSession(isLeader, invite)` 저장 + 그룹 토픽 구독, 초대 재발급, 조회, 나가기/종료) | T17 |
| `feature.creategroup` | `CreateGroupViewModel`(Form → Active, 멤버 5초 폴링 + 이벤트 갱신, 초대 남은 시간) · `CreateGroupScreen`(목적지 프리셋/내비, QR, 멤버, 종료/나가기) | T17 |
| `adapter.navi` | `NaviAdapter` + `PleosNaviAdapter`(NaviHelper 3.0.5 콜백 → `NaviEvent`, 1Hz 위치 요청, 요청 API 매핑) + `FakeNaviAdapter`(`assets/gpx/*.gpx` 1Hz 재생, 도착·경유지 이벤트) + `GpxRoute`(파서·재표본화·거리/방위) | T20 |
| `adapter.location` | `LocationSource` + `CompositeLocationAdapter`(Fused→Navi→Android→Fake 폴백·상위 병렬 재탐색) + `NaviLocationSource`·`AndroidLocationSource`·`FakeLocationAdapter` (+pleos `PleosFusedLocationAdapter`) | T21 |
| `adapter.vehicle` (T22) | `PleosVehicleAdapter` 신호 Flow(Fuel·EvBattery·Brake·TurnSignal·Airbag register/unregister, 미지원은 null/빈 스트림) + `CarApiSignals`(AAOS Car API 속도 `PERF_VEHICLE_SPEED`·기어 `GEAR_SELECTION` 폴백) | T22 |
| `domain.vehicle` | `VehicleSignals`(앱 유일 신호 구독자 — 최신값·수신 수·스트림 상태, 끝난 스트림 30s 재구독, 값 변경 시만 로그) | T22 |
| `domain.telemetry` | `TelemetryCollector`(활성 그룹 동안 1Hz `TELEMETRY` QoS 0: LocationFeed 최신 Fix + 차량 속도/기어 + ETA 30s/변화 시 + 에너지 1%p 변화 시·60s) | T23 |
| `service` | `DriveSessionService`(포그라운드 알림, location/dataSync 타입 자동 선택) + `DriveSessionController`(그룹 세션 ↔ 서비스) | T23 |
| `domain.location` | `LocationFeed`(앱 유일 위치 구독자 — `fixes`/`speedKph`/`state`, 권한 변경 시 restart) + `SpeedEstimator`(Haversine 3샘플) | T21 |
| `feature.permissions` | `LocationPermissionGate`(활성 그룹 + 정차 시 위치·알림 권한 1회 요청) | T21 |
| `domain.navi` | `NaviEventMonitor`(앱에서 유일한 내비 구독자 — 콜백 종류별 수·마지막 값 `state`, 공유 `events`, 끊기면 10초 뒤 재구독) | T20 |
| `domain.session` | `MqttSessionManager`(페어링 있으면 접속 + 자기 `cmd` 구독, cmd `GROUP_JOINED`→`GroupSessionStore` 저장 + 그룹 `state/event` 구독, `GROUP_ENDED`→정리, `TOKEN_REVOKED`→자격 증명 삭제; `groupEvents`/`groupState`/`lastCommand` 노출), `GroupSessionStore`(DataStore `group_session` / InMemory) | T16 |
| `domain.model` | `Fix`, `Place`(= protocol `Place` typealias), `Gear`, `TurnSignalState`, `CapabilityDomain`/`CapabilityEntry`/`VehicleCapabilities`, `NaviEvent`, `RouteState`, `WaypointIndex`, `Priority` | T04/T11 |
| `domain.capability` | `CapabilityReporter` — 앱 시작 시 `vehicleId()`·`capabilities()` 1회 → `DH_CAP` 로그 + 진단 화면 | T11 |
| `domain.pairing` | `PairingRepository` — device/code·token 호출, TokenStore 저장, 차량 ID 출처(CAR_INFO / INSTALL_ID) | T12 |
| `domain` | TelemetryCollector(T23), EventDetector(T26), VoiceAnnouncer(T27), RouteFollower(T28) | 예정 |
| `adapter.vehicle` | `VehicleAdapter`, `FakeVehicleAdapter`, `VehicleIdNormalizer`; pleos: `PleosVehicleAdapter`(CarInfo VIN→CRN, 6개 도메인 capability; Flow 는 T22) | T11 → T22 |
| `adapter.navi` | `NaviAdapter`, `FakeNaviAdapter`; pleos: `PleosNaviAdapter`(골격) | T04 → T20 |
| `adapter.location` | `LocationAdapter`, `FakeLocationAdapter`(1 Hz 고정점); pleos: `PleosFusedLocationAdapter`(골격) | T04 → T21 |
| `adapter.voice` | `VoiceAdapter`, `AndroidTtsVoiceAdapter`(TTS 미준비 시 로그 폴백) | T04 → T27 |
| `adapter.sdk` | `SdkProbe` — pleos: `PleosSdkProbe`(Class.forName + 서비스 패키지 확인), fake: `NoSdkProbe` | T04 |
| `di` | flavor 별 `AdapterModule`(어댑터 바인딩, pleos 는 `PleosVehicleAdapter`), main `CoreModule`(TokenStore·VehicleIdFallback), `core.network.NetworkModule` | T04/T11/T12 |
| `feature.home` | 홈: 연결 상태(`TokenStore` 구독 — 확인 중/연결 안 됨/연결됨 · 별명 + 차량 ID 마스킹), `내 폰과 연결하기`(활성), `같이 가기 시작`(T17 까지 비활성), 설정·진단 버튼, 빌드 정보·SDK 점검 카드 | T04/T12 |
| `feature.pairing` | `PairingViewModel`(상태 기계) + `PairingScreen`(ZXing QR·`KQ7-4MZ` 코드·남은 시간·오류/재시도), `PairingFormat`, `QrCodes` | T12 |
| `feature.settings` | `SettingsScreen`(연결 상태·**연결 해제**·진단 진입), `DiagnosticsScreen`(capability 표 · SDK 점검 · 빌드 정보 · 차량 ID 마스킹) | T11/T12 |
| `feature.groupdrive` | `GroupDriveViewModel`(STATE 스냅샷·이벤트·내 위치·연결 → `GroupDriveUiState`) · `GroupDriveScreen`(가로/세로 반응형, 멤버 리스트·이벤트 스트립·초대 QR/나가기·종료) · `GroupMapCanvas` + `GroupMapProjection`(Canvas 경량 지도) | T25 |
| `domain.routesync` | `RouteSyncEngine`(선두: 내비 목적지/경유지 변경 → ROUTE_CHANGED 발행 · 멤버: ROUTE_FOLLOW_PROPOSAL → 음성·제안 카드 → 수락 시 내비 재요청 + ACK, 30초 자동 거절, 자동 따라가기) | T28 |
| `domain.voice` | `VoiceAnnouncer`(그룹 이벤트 → 음성 큐: 신선도 5s·내 차 생략·우선순위·중복 10s·큐 대기 30s) | T27 |
| `adapter.voice` | `VoiceAdapter` + `AndroidTtsVoiceAdapter`(오디오 포커스·선행 묵음 200 ms·완료 콜백·엔진 없으면 로그 폴백, `status`) | T04/T27 |
| `domain.event` | `EventDetector`(저잔량·급제동·정차/재출발·에어백·도착 판정, 순수 로직) + `VehicleEventEngine`(활성 그룹 동안 1Hz 판정 → `…/event` QoS 1, 진단 상태) | T26 |
| `feature.events` | `EventsViewModel`(실시간 그룹 이벤트 + 서버 최근 20 합침) · `EventsScreen`(이벤트 피드) | T26 |
| `feature.presets` | 자리표시자 | T53 |

규칙: Pleos SDK 타입은 `adapter.*` 밖으로 내보내지 않는다. 밖에서는 `domain.model` 타입만 쓴다.

## Pleos AVD 에서 실행

```powershell
$env:JAVA_HOME = "<JDK17>"; $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd Pleos_Connect_v2.1.0 -no-snapshot-load   # 콜드 부팅
adb devices                                            # emulator-5554 등 serial 확인 (adb -s <serial> emu avd name 으로 구분)
adb -s emulator-5554 wait-for-device shell getprop sys.boot_completed   # 1 이면 부팅 완료
adb -s emulator-5554 install -r app\build\outputs\apk\pleos\debug\app-pleos-debug.apk
adb -s emulator-5554 shell am start -n app.donghaeng.car/.MainActivity
adb -s emulator-5554 logcat -d -s DongHaeng            # "car app started flavor=pleos …", "sdk probe: …"
```

기대 로그 (시작 2줄 + SDK 점검 5줄, 서비스 버전은 AVD 이미지에 따라 다를 수 있다 — `Pleos_Connect_v2.1.0` 실측값):
```
I DongHaeng: car app started flavor=pleos base=http://10.0.2.2:8080 mqtt=tcp://10.0.2.2:1883
I DongHaeng: version=0.1.0-pleos(1) adapter=pleos debug=true
I DongHaeng: sdk probe: Vehicle 2.3.2 OK — ai.pleos.playground.vehicle.Vehicle
I DongHaeng: sdk probe: NaviHelper 3.0.5 OK — ai.pleos.playground.navi.helper.NaviHelper
I DongHaeng: sdk probe: FusedLocation 1.1.3 OK — ai.pleos.playground.location.fused.FusedLocation
I DongHaeng: sdk probe: Vehicle Service OK — ai.pleos.playground.service.vehicle v2.3.22
I DongHaeng: sdk probe: FusedLocation Service OK — ai.pleos.playground.service.location.fused v1.0.0
```
같은 내용이 홈 화면 "Pleos SDK 점검" 카드에도 나온다(pleos flavor 만). fake flavor 는 시작 2줄 뒤에 `sdk probe: fake flavor (Pleos SDK 미포함)` 한 줄만 나온다.

### T11 — 차량 ID · capability 실측 (`adb logcat -s DongHaeng DH_CAP`)

앱 시작 직후 `CapabilityReporter` 가 `vehicleId()` 와 `capabilities()` 를 한 번 부르고 결과를 태그 `DH_CAP` 으로 한 줄씩 남긴다
(형식 `도메인 supported=true|false|unknown detail=… source=PLEOS_SDK|FAKE|UNCHECKED`). 같은 표가 홈 → **진단** 화면에 나온다.
`Pleos_Connect_v2.1.0` 실측(2026-10):
```
I DongHaeng: PleosVehicleAdapter initialize OK (attempt=1)
I DongHaeng: CarInfo VIN=placeholder(len=9) CRN=placeholder(len=9) → 사용 불가(폴백 ID 사용)
I DongHaeng: vehicle id: null adapter=pleos
I DH_CAP  : CarInfo supported=false detail=VIN accessType=READ, CRN accessType=READ; value VIN=placeholder(len=9) CRN=placeholder(len=9) source=PLEOS_SDK
I DH_CAP  : Fuel supported=false detail=onFailed a: ERROR_NOT_SUPPORTED_PROPERTY source=PLEOS_SDK
I DH_CAP  : EvBattery supported=true detail=accessType=READ source=PLEOS_SDK
I DH_CAP  : Brake supported=true detail=accessType=READ source=PLEOS_SDK
I DH_CAP  : TurnSignal supported=true detail=accessType=READ source=PLEOS_SDK
I DH_CAP  : Hazard supported=false detail=TurnSignalValue=OFF/LEFT/RIGHT 뿐(동시 표시 값 없음) → 비상등 기능 숨김; turnSignal=true source=PLEOS_SDK
I DH_CAP  : Airbag supported=false detail=onFailed a: ERROR_NOT_SUPPORTED_PROPERTY source=PLEOS_SDK
I DH_CAP  : Speed supported=unknown detail=T22: Car API PERF_VEHICLE_SPEED(CAR_SPEED 런타임 권한) 확인 예정 source=UNCHECKED
I DH_CAP  : Gear supported=unknown detail=T22: vehicle.getGear() 는 check 메서드 없음 → … source=UNCHECKED
```
- 이 AVD 의 CarInfo VIN/CRN 은 Gson 직렬화된 `"invalid"`(따옴표 포함 9자)다 → `VehicleIdNormalizer` 가 자리표시자로 판정하고
  페어링은 설치별 고정 ID(`VehicleIdFallback`, `DHAVD-…`, 일반 SharedPreferences)를 쓴다. 실차에서는 VIN 이 그대로 쓰인다.
- Fuel·Airbag 은 이 AVD(EV)에서 `ERROR_NOT_SUPPORTED_PROPERTY` → supported=false 로 UI 에서 숨길 항목(T22). 비상등은 SDK 2.3.2 에 동시 표시 값이 없어 항상 false(docs/10 R3).
- Speed/Gear 는 T22(Car API) 전까지 `unknown`. `supported=null`(unknown) 과 `false` 는 구분해 기록한다.
- fake flavor 는 전부 `source=FAKE`(EvBattery·Hazard 만 false).

### T12 — 페어링 (`adb logcat -s DongHaeng`)

홈 → **내 폰과 연결하기**: `POST /v1/device/code` → QR(verifyUri 그대로) + `KQ7-4MZ` 코드 + 남은 시간 → `interval` 초마다 `POST /v1/device/token`
(428 계속 / 429 간격 +2초 / 410·404·로컬 만료 → 새 코드 최대 3회 / 200 → `EncryptedTokenStore` 저장 → 홈으로 자동 복귀, "연결됨 · 별명").
로그는 상태 전이만 남고 userCode·deviceCode·토큰은 찍히지 않는다:
```
I DongHaeng: pairing state=CHECKING
I DongHaeng: pairing state=REQUESTING_CODE
I DongHaeng: pairing vehicleId source=INSTALL_ID len=18          # pleos AVD (실차·fake 는 CAR_INFO)
D DongHaeng: http --> POST http://10.0.2.2:8080/v1/device/code (77-byte body)   # debug 빌드만, BASIC
I DongHaeng: pairing state=CODE_ISSUED expiresIn=600 interval=3 attempt=1
I DongHaeng: pairing state=CONNECTED vehicleId=veh_…
I DongHaeng: token store: saved vehicleId=veh_… token=eyJh…(len=…)
```
서버가 아직 `/v1/device/code` 를 제공하지 않으면 `pairing state=ERROR reason=SERVER retryable=true detail=HTTP 404 NOT_FOUND` 와 함께
오류 화면("다시 시도")이 뜬다. 설정 → **연결 해제** 가 TokenStore 를 비워 홈이 '연결 안 됨' 으로 돌아간다(반복 시연용).
토큰 저장소: `androidx.security:security-crypto:1.0.0` EncryptedSharedPreferences(`dh_tokens`), Keystore 실패 시 메모리 전용 폴백.

### T16 — MQTT (`adb logcat -s DongHaeng`)
페어링돼 있으면 앱이 뜨자마자 `MqttSessionManager` 가 브로커에 붙는다. 기대 로그:
```
mqtt session manager started
mqtt connected broker=10.0.2.2:1883 tls=false vid=veh_…** reconnect=false
mqtt connack reason=SUCCESS sessionPresent=false
mqtt subscribed topic=dh/v1/vehicle/veh_…**/cmd
```
- 브로커 재시작(`cd infra && docker compose restart emqx`) 뒤 `mqtt disconnected source=SERVER …` → 수 초 안에 `mqtt connected … reconnect=true` + `mqtt subscribed …` 가 다시 찍히면 자동 복구 OK. 설정 → 진단 화면의 **MQTT 연결** 카드에서 상태/재연결 횟수/구독 토픽/마지막 cmd 를 그대로 볼 수 있다.
- 서버 재시작 뒤 `CONNACK … NOT_AUTHORIZED` 가 반복되면: EMQX 가 JWKS 를 다시 받을 때까지(최대 30초) 정상이며, 계속되면 토큰이 서버의 새 서명 키와 맞지 않는 것 — `infra/.env` 에 `DH_JWT_PRIVATE_KEY_PEM` 을 고정하고 차량을 다시 페어링한다.
- TLS: `-PdhMqttPublicUri=ssl://…:8883` 또는 서버 응답 `mqtt.uri` 가 `ssl://` 이면 시스템 신뢰 저장소로 TLS 접속(`tls=true`). 서버가 https 인데 브로커가 `tcp://` 면 `mqtt refused: 서버가 https 이므로 브로커도 ssl:// 이어야 해요` 를 남기고 붙지 않는다(`MqttUriPolicyTest`).

### T17 — 그룹 생성 · 초대 QR (`adb logcat -s DongHaeng`)
홈(연결됨) → **같이 가기 시작** → 목적지(내비 안내 중이면 자동, 아니면 시연용 프리셋) · 그룹 이름 · 최대 인원 → **같이 가기 시작** 버튼 → 서버 `POST /v1/groups`(차량 JWT) → 초대 QR(`invite.url` 그대로) + 남은 시간 + 멤버 목록. 기대 로그:
```
navi destination none → preset          (Pleos AVD 는 내비 안내 중이 아님)
group created groupId=grp_…** maxMembers=4 inviteExpiresAt=…
group session saved (leader) groupId=grp_…**
mqtt subscribed topic=dh/v1/group/grp_…**/state  /  …/event
```
- 홈으로 돌아가면 "그룹: … (선두)" 과 **그룹 보기** 버튼이 보인다. 멤버가 합류하면(T18) 멤버 목록이 5초 폴링·`MEMBER_JOINED` 이벤트로 갱신된다. 초대는 10분, **초대 다시 만들기**로 재발급(이전 QR 은 즉시 만료).
- QR 아래에 초대 URL 텍스트도 보인다 — 카메라 없는 폰 AVD 에서는 demo-host 스캔 화면의 디버그 "QR 문자열 붙여넣기"에 그 값을 넣는다.
- 차량 토큰은 `VehicleAuthInterceptor` 가 `/v1/groups/…`·`/v1/me/…` 요청에만 `Authorization: Bearer` 로 붙인다. 생성 차량은 `GROUP_JOINED` 를 받지 않으므로 `MqttSessionManager.onGroupCreated` 가 그룹 토픽을 직접 구독한다.
- 캡처: `docs/images/car-app/t17_group_qr.png`

### T20 — 내비 어댑터 (`adb logcat -s DongHaeng | grep navi`)
- 앱 시작 시 `navi monitor: subscribe` → pleos 는 `navi: NaviHelper initialized`, `navi: listener added`, 이후 콜백마다 `navi onXxx …` + `navi monitor: <이벤트> … total=N`(위치는 10개마다 한 줄, 좌표 마스킹).
- 진단 화면 **내비 이벤트 (T20)** 카드: 구독 상태·콜백 종류별 수·마지막 위치(마스킹)·내비 상태·목적지·남은 거리. 버튼 **목적지 조회**(`currentDestination`, 2초 대기) · **경로 요청(확인용)**(대전역으로 `requestRoute`) · **내비 화면**(`requestNavigationScreen`) → "마지막 요청" 줄과 콜백 수 변화로 응답을 본다.
- Pleos AVD 실측(2026-10-27): `onCurrentRoadSpeedLimit`(1Hz, 0) 과 `onNavigationStatus(INITIALIZING)` 만 온다 — 내비 앱이 "실행 지연" 상태(docs/10 R4). 위치·경로 콜백은 실차에서 같은 카드로 확인.
- fake flavor: `FakeNavi asset=gpx/lead.gpx points=73 track=3424 lengthM=57142` → 1Hz `fix #N`, 10초마다 `TripInfo 57.1 km · 57분`, 종점에서 `DestinationArrived`. 후속차 인스턴스용 `gpx/follow.gpx` 는 T30 에서 선택.
- 캡처: `docs/images/t20/carA_pleos_diag_navi.png`(Pleos AVD 내비 카드), `carB_fake_diag_navi.png`(fake — requestRoute 후 DestinationChanged/RouteStateInfo/TripInfo), `carA_navi_app_delayed.png`(AVD 내비 앱 '실행 지연' 화면).
- 권한: `pleos.car.permission.NAVI_ROUTE`·`NAVI_ROUTE_SEARCH` 외에 `requestNavigationScreen` 은 `NAVI_CUSTOM_MAP` 이 필요하다(없으면 `SecurityException`, 실측) — pleos 매니페스트에 `NAVI_CUSTOM_MAP`·`NAVI_CUSTOM_ROUTE` 추가.

### T21 — 위치 체인 (`adb logcat -s DongHaeng | grep location`)
- 앱 시작 시 `location feed: subscribe` → 체인 로그 `location: 활성 소스 = NAVI (2/4)` → `location: NAVI 중단 — no first fix` → `location: 활성 소스 = ANDROID (3/4)` … → `location feed: fix #N Fix(37.5**, …) src=ANDROID speed=62.6km/h`(10개마다).
- 진단 화면 **위치 소스 (T21)** 카드: 활성 소스·체인 순서, 소스 가용성, fix/전환 수와 마지막 중단 사유, 마지막 위치(마스킹)·heading·acc, 위치 계산 속도, 권한(FINE/FUSED/POST_NOTIFICATIONS). 버튼 **위치 권한 요청**(시스템 다이얼로그) · **체인 다시 탐색**.
- AVD 에서 움직이는 GPS 넣기: `adb -s emulator-5556 emu geo fix <lon> <lat>` 를 1초마다 보내면(세션 스크립트 `t21_location_check.py` 의 `geo_walk`) Android 소스가 1Hz 로 받고 속도가 계산된다. 캡처: `docs/images/t21/`.
- Pleos AVD 실측(2026-10-27): `FUSED_LOCATION=미승인` → FUSED 건너뜀, NAVI 첫 Fix 없음 → ANDROID(gps) 활성, 속도 ≈ 62 km/h. fake flavor: NAVI(GPX 재생) 활성 ≈ 60 km/h.

### T22 — 차량 신호 (`adb logcat -s DongHaeng | grep "vehicle signal"`)
- 앱 시작 시 `Vehicle SDK fuelPercent registered` … 값이 바뀔 때만 `vehicle signal gear=P`, `turn=OFF`, `brake=false`, `soc=100`, `speed=0.0`(Car API, CAR_SPEED 승인 시). 미지원은 `… 미지원/실패: …` 한 줄 뒤 `stream completed`(30초 뒤 재시도).
- 진단 화면 **차량 신호 (T22)** 카드: 에너지(연료·SoC) / 주행(속도·기어·브레이크) / 차체(방향지시등·에어백) / 신호별 수신 수(마지막 수신 n초 전) / 끝난·실패 스트림. 버튼 **신호 다시 구독**.
- Virtual Console 로 바꾸기(스크립트 `t22_vehicle_check.py`): `adb -s emulator-5556 forward tcp:8089 tcp:8088` → `POST /browser/session` 을 1초마다(세션 timeout 3초, 없으면 PUT 503) → `PUT /vehicle/gear?gear=Reverse`, `PUT /vehicle/controls?speed_kph=55&brake_pct=70`, `PUT /vehicle/turn-signal?mode=BlinkLeft`, `PUT /vehicle/ev-battery?field=soc&value=23`.
- Pleos AVD 실측(2026-10-27): 위 조작 → `vehicle signal gear=R`, `brake=true`, `turn=LEFT`, `soc=23`, `speed=55.0`; 원복 → `gear=P`, `brake=false`, `speed=0.0`, `turn=OFF`. Fuel 은 AVD 미지원(`property ID is not in carPropertyConfig list`), Airbag check 실패 → 빈 스트림. DH_CAP: `Speed supported=true … CAR_API`, `Gear supported=true … PLEOS_SDK`.

### T23 — 텔레메트리 (`adb logcat -s DongHaeng | grep telemetry`)
- 활성 그룹이 있으면 `telemetry: 그룹 활성 → 1Hz 발행 시작 (<그룹명>)` → `telemetry #1 (37.5**, 126.9**) src=ANDROID speed=0.0 heading=0 gear=P energy=fuel:- soc:23` … (10개마다 한 줄, 좌표 마스킹). 자격 증명이 없으면 `자격 증명 없음 → 건너뜀`, 위치가 없거나 10초보다 오래되면 그 틱은 건너뛴다(진단 카드 "건너뜀" 수).
- 포그라운드 서비스: `drive session service: start 요청 (group=…)` → `foreground type=location`(위치 권한 없으면 `dataSync`). 그룹이 끝나면 `stop 요청`.
- 진단 화면 **텔레메트리 (T23)** 카드: 상태(발행 중/대기), 발행/실패/건너뜀 수, 마지막 발행 요약, ETA/에너지 포함 횟수, 토픽(마스킹), 서비스 실행 여부.
- 브로커에서 확인(스크립트 `t23_telemetry_check.py`): EMQX 대시보드 API 로 로그인(`POST /api/v5/login`, admin/public) → `POST /api/v5/trace {name, type:"topic", topic:"dh/v1/vehicle/+/telemetry", start_at, end_at}` → `GET /api/v5/trace/{name}/log?bytes=…` 에 발행 페이로드가 그대로 남는다. 2026-10-27 실측: 1초 간격 seq 연속, `authorization_matched_allow … emqx_authz_http`, Virtual Console 속도 30 → `"speedKph":30.0`, SoC 23→61 → `"energy":{"socPct":61}` 2회(변화 시만), ETA 는 내비 미동작으로 없음.

### T25 — 그룹 주행 화면
- 홈 → **그룹 보기**(활성 그룹이 있을 때) → 그룹 주행 화면. 가로: 좌 멤버 리스트(별명·선두/나 태그·속도·선두까지 거리·ETA·에너지·상태·n초 전) / 우 경량 지도(선두차 중심, 북쪽 위, 거리 링, 멤버 점 + 진행 방향, 내 차 테두리, 목적지 깃발 또는 가장자리 화살표). 아래 최근 소식 3줄 + **초대 QR 보기**(방장)/**그룹 종료·나가기**(확인 대화상자). 주행 중이면 버튼 비활성.
- 상단 연결 칩(연결됨/다시 연결 중/연결 안 됨)과 "그룹 상태 n초 전"(서버 2초 스냅샷). 스냅샷이 아직 없으면 "서버에서 그룹 상태를 기다리는 중".
- Pleos AVD 실측(2026-10-27): 멤버 2대 — DemoCar(선두·나, 정차, 배터리 61%), DemoCarB(오프라인). 캡처 `docs/images/t25/`. 스크립트 `t25_drive_check.py`.

### T26 — 차량 이벤트 · 이벤트 피드 (`adb logcat -s DongHaeng | grep "vehicle event"`)
- 활성 그룹이 있으면 `event engine: 그룹 활성 → 판정 시작` → 판정 시 `vehicle event LOW_ENERGY LowEnergy(kind=EV, pct=12) published=true` 등. 서버가 그룹 이벤트로 돌려주면 `group event` 로 수신돼 그룹 주행 화면 '최근 소식' 과 **소식 더 보기**(이벤트 피드)에 보인다.
- 진단 화면 **차량 이벤트 (T26)** 카드: 판정 중/샘플 수, 종류별 판정 수, 발행/실패, 마지막 이벤트, 판정기 상태(에너지 경고 %, 정차 n초).
- Virtual Console 로 만들기(스크립트 `t26_event_check.py`): `PUT /vehicle/ev-battery?field=soc&value=12` → LOW_ENERGY(EV 12%); `gear=Drive`, `speed_kph=60` 4초 → `speed_kph=0&brake_pct=80` → HARD_BRAKE(60 km/h → 0, 16.7 m/s²); 0 km/h 60초 → STOPPED; `speed_kph=30` → RESUMED.

### T27 — 음성 안내 (`adb logcat -s DongHaeng | grep -E "voice:|TTS"`)
- 앱 시작 시 `TTS 준비 완료 (… ko-KR)` 또는 `TTS 초기화 실패 status=-1 → 로그 폴백 (엔진 없음?)`. Pleos AVD 에는 TTS 엔진이 없어 폴백 모드다(정책 로그로 검증). 그룹 이벤트가 오면 `voice: speak PASSENGER_MESSAGE priority=NORMAL len=32` / `voice: skip own LOW_ENERGY` / `voice: skip stale …` / `voice: skip duplicate …`.
- 설정 → **음성 안내** 스위치(끄면 큐를 비우고 재생 중지). 진단 → **음성 안내 (T27)** 카드: TTS 엔진 상태, 큐·읽음/생략 수(오래됨·내 차·중복·꺼짐·문구 없음), TTS 호출 수, 마지막 문장(종류·길이만), **테스트 발화**/**음성 끄기** 버튼.
- 시연 트리거: 동승자 메시지 `POST /v1/groups/{gid}/messages`(그룹 소유 계정 토큰) → 그룹 전원에게 읽힘(출처 무관). 다른 차의 급제동·저잔량은 해당 차가 아닌 차들에서만 읽힌다. 스크립트 `t27_voice_check.py`.

### T28 — 선두 경로 동기화 (`adb logcat -s DongHaeng | grep routesync`)
- 선두차: 내비 목적지·경유지가 바뀌면 `routesync: ROUTE_CHANGED published=true …`. AVD 는 내비 콜백이 없으므로 진단 → **경로 동기화 (T28)** 카드의 **경로 변경 테스트 발행**(그룹 목적지 + 덕평휴게소)으로 만든다.
- 멤버: `cmd ROUTE_FOLLOW_PROPOSAL proposal=prp_…` → `routesync: proposal … waypoints=1` → 음성(`voice: speak ROUTE_FOLLOW_PROPOSAL`) + 그룹 주행 화면 제안 카드 → **따라가기**(`routesync: accepted … navi=true ack=true`, `FakeNavi requestRouteWithWaypoints …`) 또는 30초 뒤 `routesync: declined auto=true`. 설정 → **선두 경로 자동 따라가기** 스위치.
- 서버 로그: `route proposal created proposalId=… targets=N sent=N`, `route ack proposalId=… accepted=…`. 스크립트 `t28_routesync_check.py`(EMQX trace cmd/ack/event).

### Virtual Console (차량 상태 조작, docs/09 §1)

에뮬레이터마다 포트를 다르게 포워딩한다. 두 번째 포워딩이 안 되면 B 차량은 `fake` flavor 로 시연한다.
```powershell
adb -s emulator-5554 forward tcp:8088 tcp:8088   # A → http://127.0.0.1:8088
adb -s emulator-5556 forward tcp:8089 tcp:8088   # B → http://127.0.0.1:8089
```
위치 이동은 Extended Controls → Location → Routes 에 GPX 를 불러와 재생한다(T30 에서 GPX 2개 제공). 스크립트에서는 `adb emu geo fix <lon> <lat>` 1Hz(T21) 로 대신한다.
Virtual Console 은 웹 UI 외에 HTTP API 도 있다(위 T22 절 참고: `/vehicle/state`, `/vehicle/catalog`, `PUT /vehicle/...`, 세션 keepalive 필요).

## 서버 연결

> **Pleos AVD 에서 `SocketTimeoutException`(서버 연결 불가)이 나면** Wi-Fi 를 끈다: `adb -s <pleos-serial> shell svc wifi disable`. 이 AVD 는 Wi-Fi(wlan0)가 기본 네트워크일 때 호스트 10.0.2.2 에 닿지 않고 유선(eth0)으로만 닿는다. 확인 `adb -s <serial> shell ping -c 1 10.0.2.2`, 되돌리기 `svc wifi enable`. (docs/09 §1)

- 에뮬레이터 → 호스트 서버: `http://10.0.2.2:8080`, MQTT `tcp://10.0.2.2:1883` (기본값, `infra/` docker compose)
- 클라우드: `https://<VM-IP>.sslip.io`, `ssl://<VM-IP>.sslip.io:8883` (`-P` 로 빌드 시 지정, 고정 도메인 없음)
- 차량 앱은 QR 문자열을 만들지 않는다 — 페어링 QR(verifyUri)·그룹 QR(invite.url)은 서버 응답을 그대로 표시한다.
