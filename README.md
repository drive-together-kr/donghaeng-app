# 동행 (DongHaeng)

## 앱 이름 및 한 줄 소개
**동행(DongHaeng)** — 전화 없이, 한 대처럼. 여러 대의 현대·기아·제네시스 차량을 하나의 그룹으로 묶어 위치·도착 예정·차량 상태·경로를 실시간으로 공유하고 운전자에게 음성으로 알려 주는 차량 앱

| 제출물 | 위치 |
|---|---|
| 소스 코드 | 이 저장소 (`car-app/`·`companion/`·`server/`·`protocol/`·`infra/`) |
| APK (release) | [`apk/DongHaeng_동행_release.apk`](apk/DongHaeng_동행_release.apk) — 차량 앱 · [`apk/DongHaeng_동행_폰시연앱_release.apk`](apk/DongHaeng_동행_폰시연앱_release.apk) — 폰 시연 앱(QR 합류용) |
| 시연 영상 | [`docs/video/DongHaeng_demo.mp4`](docs/video/DongHaeng_demo.mp4) (2분 42초, 실제 앱 화면 녹화) |

## 실행 방법
차량 앱은 **차 화면에 QR·코드를 보여 주기만** 하고, 스캔은 폰의 공식 앱(동행 메뉴 — 시연용 `demo-host`)이 한다. 폰에서 QR 을 스캔한 뒤 적용할 차를 고르면 그 차에 반영된다.

1. **서버** — 따로 띄울 필요 없음. release 차량 앱·demo-host 의 기본 서버는 클라우드 `https://3.24.206.147.sslip.io` 다.
   (로컬로 돌릴 때: `cd infra && docker compose up -d` → 차 설정 제목을 5번 탭해 '서버 주소'를 `http://10.0.2.2:8080` 으로)
2. **차(Pleos Connect 에뮬레이터)** — 차량 APK 설치 + 플로팅 패널 권한(에뮬레이터에 설정 화면이 없어 adb 로 한 번):
   ```
   adb install apk/DongHaeng_동행_release.apk
   adb shell appops set --user 10 app.donghaeng.car SYSTEM_ALERT_WINDOW allow
   ```
   권한이 없어도 동작한다(내비 위 패널 대신 알림 카드 + 음성).
3. **폰(휴대폰 AVD 또는 실기기)** — `adb install apk/DongHaeng_동행_폰시연앱_release.apk` → 로그인(설치마다 다른 시연 계정이 미리 채워져 있다).
   **카메라가 없을 때**(카메라 없는 PC 의 에뮬레이터 등) QR 을 읽는 방법 세 가지 — ① 스캔 화면 **[사진에서 QR 읽기]**: 차 화면 QR 을 찍은 사진·스크린샷을 고른다 ② **링크로 열기**: 차 화면의 주소(`…/j/inv_…`·`…/pair?c=…`)를 메신저로 받아 누르면 이 앱이 바로 열린다(Android App Links — 서버가 `/.well-known/assetlinks.json` 제공, 브라우저에서 열리면 [앱에서 열기]) ③ **코드·주소 직접 입력**: 차 화면의 코드 6자 또는 주소. 에뮬레이터에서 사진을 넣으려면 `adb push <QR 캡처>.png /sdcard/Pictures/` 뒤 `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/<파일>`, 링크는 `adb shell am start -a android.intent.action.VIEW -d "<차 화면 주소>"`.
4. **차 등록** — 차 홈 [내 폰과 연결하기] → 차 화면에 QR·코드 → 폰 [QR 스캔](카메라가 없으면 '코드·주소 직접 입력'에 차 화면 코드 6자) → 차 이름 정하고 [등록] → 차 화면 '연결됨'.
5. **같이 가기** — 차 홈 [같이 가기 시작] → 목적지 고르기 → 그룹 초대 QR → 함께 갈 다른 차의 운전자가 폰으로 스캔 → **합류시킬 차를 고르고** [함께 가기] → 그 차 화면에 그룹이 뜬다.
6. **주행** — 앞 화면은 순정 내비 그대로, 동행은 내비 위 작은 패널·음성으로 차 간격·도착 시각·급제동·저잔량·휴게소 도착·무전을 알린다. 정차 중에는 [그룹 보기]로 대시보드.

시연 영상(2분 42초, 실제 앱 화면 녹화 — 그룹 만들기·폰 합류·주행 패널·차량 상태 공유·동승자 메시지·경로 따라가기·핸들 버튼 무전·도착 알림·급제동 경고·정차 대시보드): [docs/video/DongHaeng_demo.mp4](docs/video/DongHaeng_demo.mp4)

폰이 없으면 차 홈의 **[예시 화면 둘러보기]** 로 정차 대시보드·내비 위 패널·급제동 강조·무전 화면을 예시 데이터로 볼 수 있다.
두 대 시연(차 2대 + 서버, 장면 자동 실행)은 [docs/09](docs/09_테스트_데모계획.md) §4 와 `tools/demo/demo.py`.

**직접 빌드**(JDK 17, Android SDK): `cd car-app && ./gradlew :app:assemblePleosRelease -PdhCloudHost=<서버 호스트>`, `cd companion && ./gradlew :demo-host:assembleRelease -PdhCloudHost=<서버 호스트> -PdhDevLoginKey=<키>`. 서명 정보 파일 경로는 `-PdhReleaseSigning=<경로>`(없으면 서명 안 된 APK). 서버는 `cd server && ./gradlew test bootRun`(JDK 21), 클라우드 배포는 [infra/cloud/README.md](infra/cloud/README.md).

## 테스트 환경 및 필요한 설정
- 차량: Android Studio, Pleos Connect Emulator v2.1.0 (API 34), minSdk 28
- 폰(demo-host): 휴대폰 AVD(예: Android Studio 기본 Medium Phone, API 35) 또는 실기기(Android 9+, 카메라) — 카메라로 찍을 수 없으면 스캔 화면의 '코드·주소 직접 입력'(차 화면에 보이는 6자리 코드(예: `KQ7-4MZ` — 차마다·매번 새로 만들어진다)만 넣어도 된다, 초대는 URL·`inv_…`)으로 대신. 로그인 기본 이메일은 설치마다 다른 `judge-<8hex>@dev.local`
- 서버: Docker Compose (PostgreSQL 16, Redis 7, EMQX 5, Spring Boot 3.3)
  - 클라우드(차량 앱 release 기본값): **`https://3.24.206.147.sslip.io`** — MQTT `ssl://3.24.206.147.sslip.io:8883`, 8883 이 막힌 망은 `wss://3.24.206.147.sslip.io/mqtt` 로 자동 전환. 배포·운영 [infra/cloud/README.md](infra/cloud/README.md)
  - 로컬: `infra/` 에서 `docker compose up -d` → 에뮬레이터는 `http://10.0.2.2:8080`(설정 제목 5번 탭 → '서버 주소' 로 바꿀 수 있다)
- 음성: Pleos TTS 로 자동 동작(설정 없음 — 앱이 Android TTS → Pleos TTS SDK → 화면 표시 순으로 골라 진단 화면 '음성 안내' 카드에 표시, [예시 안내 듣기]로 확인). 더 좋은 음질을 원하면 [docs/09 §1.4](docs/09_테스트_데모계획.md) 의 오픈 소스 한국어 엔진(Supertonic 3)을 설치

## APK 파일 위치
`apk/` 폴더 — 차량 앱 [`DongHaeng_동행_release.apk`](apk/DongHaeng_동행_release.apk)(Pleos 에뮬레이터용, 기본 서버 클라우드) + 폰 시연 앱 [`DongHaeng_동행_폰시연앱_release.apk`](apk/DongHaeng_동행_폰시연앱_release.apk)(공식 앱의 동행 메뉴 재현). 버전 1.0.0, 같은 키로 서명 — 자세한 내용 [apk/README.md](apk/README.md)

## 문서
| 문서 | 내용 |
|---|---|
| [01 상세기획서(PRD)](docs/01_상세기획서_PRD.md) | 문제, 페르소나, 기능 F1~F16(F16 무전), 화면, KPI, 사업성 |
| [02 시스템 아키텍처](docs/02_시스템아키텍처.md) | 운영 설계(MQTT·Kafka·EKS)와 MVP 매핑, 시퀀스 |
| [03 API·프로토콜 명세](docs/03_API_프로토콜_명세.md) | REST, MQTT 토픽·스키마 |
| [04 데이터 모델](docs/04_데이터모델.md) | DB DDL, Redis 키 |
| [05 차량 앱 설계](docs/05_차량앱_설계.md) | Pleos SDK 어댑터, UX 제한 |
| [06 서버 설계](docs/06_서버_설계.md) | 패키지, 파이프라인, 보안 |
| [07 companion 모듈 설계](docs/07_companion모듈_설계.md) | 공식 앱 내장 SDK, 공개 API 6개, 인증 설계, QR 처리, demo-host |
| [08 구현 계획서](docs/08_구현계획서.md) | 마일스톤, 작업 ID, 일정 |
| [09 테스트·데모 계획](docs/09_테스트_데모계획.md) | 에뮬레이터 2대 시연 시나리오 |
| [10 리스크·확인 사항](docs/10_리스크_확인사항.md) | SDK 제약, 설명회 질문 |
| [11 제출 체크리스트](docs/11_제출체크리스트.md) | 1·2차 제출물 |

## 저장소 구조
| 경로 | 내용 |
|---|---|
| `car-app/` | Pleos 차량 앱 (Kotlin, Compose, Hilt) — flavor `pleos` / `fake` |
| `companion/` | 공식 앱 내장 모듈: `sdk/`(Android AAR, 공개 API 6개) + `demo-host/`(시연용 호스트 앱) |
| `server/` | Kotlin + Spring Boot 3.3 모듈러 모놀리스 |
| `protocol/` | car-app·server·companion/sdk 공용 메시지 스키마 (kotlinx.serialization; sdk 는 `MemberState`·`GroupEvent` 를 공개 API 에 재노출) |
| `infra/` | docker compose (postgres·redis·emqx·server) + `cloud/`(클라우드 VM: Caddy+layer4 오버라이드·`deploy.sh`, T32) |
| `docs/` | 기획·설계 문서 |
| `apk/` | 제출용 release APK |
