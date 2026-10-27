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
| `pleos` | T04: 전부 Fake + `PleosSdkProbe`(SDK 존재 점검) → T11/T20/T21/T22 에서 Pleos 구현으로 교체 | Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3 (`pleosImplementation`) | Pleos Connect AVD (`Pleos_Connect_v2.1.0`, API 34 automotive) |
| `fake` | 전부 Fake (`FakeVehicleAdapter`, `FakeNaviAdapter`, `FakeLocationAdapter`) + Android TTS | 없음 | 아무 AVD / 폰 / 태블릿 |

두 flavor 는 applicationId 가 같다(`app.donghaeng.car`) — 한 기기에 둘을 동시에 설치할 수 없다.
Pleos 차량 권한(`pleos.car.permission.*`, `android.car.permission.*`)은 `src/pleos/AndroidManifest.xml` 에만 있다.
FusedLocation AAR 이 `uses-feature automotive required=true` 를 끌고 오므로 `pleos` APK 는 자동차 기기에만 설치된다.

`BuildConfig`: `FLAVOR`, `ADAPTER`("pleos"/"fake"), `DH_PUBLIC_BASE_URL`, `DH_MQTT_PUBLIC_URI`, `VERSION_NAME`(0.1.0-pleos / 0.1.0-fake)

## 패키지 구조 (`app.donghaeng.car`, 단일 모듈)

| 패키지 | 내용 | 상태 |
|---|---|---|
| `DongHaengApp`, `MainActivity` | `@HiltAndroidApp`(시작 로그 `car app started …`), 단일 액티비티 Compose | T04 |
| `ui.theme`, `ui.navigation`, `ui.components` | Material3 테마(동적 색 끔), `DhNavHost`/`DhRoutes`, 자리표시자 화면 | T04 |
| `core.common` | `DhLog`(태그 `DongHaeng`), `CoordinateMasking`/`TextMasking`(규칙 8), `DrivingStateMonitor`(T29 자리) | T04 |
| `core.network` | `ServerConfig`(BuildConfig 주소) → RestApi(T12), MqttGateway(T16), 토큰 저장(DataStore) | 일부 |
| `domain.model` | `Fix`, `Place`(= protocol `Place` typealias), `Gear`, `TurnSignalState`, `VehicleCapabilities`, `NaviEvent`, `RouteState`, `WaypointIndex`, `Priority` | T04 |
| `domain` | TelemetryCollector(T23), EventDetector(T26), VoiceAnnouncer(T27), RouteFollower(T28) | 예정 |
| `adapter.vehicle` | `VehicleAdapter`, `FakeVehicleAdapter`; pleos: `PleosVehicleAdapter`(골격) | T04 → T11/T22 |
| `adapter.navi` | `NaviAdapter`, `FakeNaviAdapter`; pleos: `PleosNaviAdapter`(골격) | T04 → T20 |
| `adapter.location` | `LocationAdapter`, `FakeLocationAdapter`(1 Hz 고정점); pleos: `PleosFusedLocationAdapter`(골격) | T04 → T21 |
| `adapter.voice` | `VoiceAdapter`, `AndroidTtsVoiceAdapter`(TTS 미준비 시 로그 폴백) | T04 → T27 |
| `adapter.sdk` | `SdkProbe` — pleos: `PleosSdkProbe`(Class.forName + 서비스 패키지 확인), fake: `NoSdkProbe` | T04 |
| `di` (flavor 별) | `AdapterModule` — fake/pleos 각각 어댑터 바인딩 | T04 |
| `feature.home` | 홈: 앱 이름·연결 상태·버튼 2개(비활성)·빌드 정보·SDK 점검 카드, 세로/가로 | T04 |
| `feature.pairing` / `creategroup` / `groupdrive` / `events` / `presets` / `settings` | 자리표시자 | T12 / T17 / T25 / T26 / T53 / 이후 |

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

### Virtual Console (차량 상태 조작, docs/09 §1)

에뮬레이터마다 포트를 다르게 포워딩한다. 두 번째 포워딩이 안 되면 B 차량은 `fake` flavor 로 시연한다.
```powershell
adb -s emulator-5554 forward tcp:8088 tcp:8088   # A → http://127.0.0.1:8088
adb -s emulator-5556 forward tcp:8089 tcp:8088   # B → http://127.0.0.1:8089
```
위치 이동은 Extended Controls → Location → Routes 에 GPX 를 불러와 재생한다(T30 에서 GPX 2개 제공).

## 서버 연결

- 에뮬레이터 → 호스트 서버: `http://10.0.2.2:8080`, MQTT `tcp://10.0.2.2:1883` (기본값, `infra/` docker compose)
- 클라우드: `https://<VM-IP>.sslip.io`, `ssl://<VM-IP>.sslip.io:8883` (`-P` 로 빌드 시 지정, 고정 도메인 없음)
- 차량 앱은 QR 문자열을 만들지 않는다 — 페어링 QR(verifyUri)·그룹 QR(invite.url)은 서버 응답을 그대로 표시한다.
