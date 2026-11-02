# 동행 (DongHaeng)

> 전화 없이, 한 대처럼. — 여러 대의 현대·기아·제네시스 차량을 하나의 그룹으로 묶어 위치·도착 예정·차량 상태·경로를 실시간으로 공유하고 음성으로 알려 주는 차량 앱

## 실행 방법
_(구현 후 작성)_ 1) `infra/`에서 `docker compose up -d` 2) Pleos Connect 에뮬레이터에 `apk/` 의 차량 APK 설치 3) 휴대폰 AVD(예: Android Studio 기본 Medium Phone) 또는 실기기에 `apk/` 의 demo-host APK(공식 앱을 흉내 낸 companion 호스트) 설치 → 차량 QR 스캔(카메라가 없으면 스캔 화면 '코드·주소 직접 입력'에 차 화면 코드) (T41 에서 빌드·설치 명령 확정)

## 테스트 환경 · 설정
- 차량: Android Studio, Pleos Connect Emulator v2.1.0 (API 34), minSdk 28
- 폰(demo-host): 휴대폰 AVD(예: Android Studio 기본 Medium Phone, API 35) 또는 실기기(Android 9+, 카메라) — 카메라로 찍을 수 없으면 스캔 화면의 '코드·주소 직접 입력'(차 화면에 보이는 6자리 코드(예: `KQ7-4MZ` — 차마다·매번 새로 만들어진다)만 넣어도 된다, 초대는 URL·`inv_…`)으로 대신. 로그인 기본 이메일은 설치마다 다른 `judge-<8hex>@dev.local`
- 서버: Docker Compose (PostgreSQL 16, Redis 7, EMQX 5, Spring Boot 3.3)
  - 클라우드(차량 앱 release 기본값): **`https://3.24.206.147.sslip.io`** — MQTT `ssl://3.24.206.147.sslip.io:8883`, 8883 이 막힌 망은 `wss://3.24.206.147.sslip.io/mqtt` 로 자동 전환. 배포·운영 [infra/cloud/README.md](infra/cloud/README.md)
  - 로컬: `infra/` 에서 `docker compose up -d` → 에뮬레이터는 `http://10.0.2.2:8080`(설정 제목 5번 탭 → '서버 주소' 로 바꿀 수 있다)
- 음성: Pleos TTS 로 자동 동작(설정 없음 — 앱이 Android TTS → Pleos TTS SDK → 화면 표시 순으로 골라 진단 화면 '음성 안내' 카드에 표시, [예시 안내 듣기]로 확인). 더 좋은 음질을 원하면 [docs/09 §1.4](docs/09_테스트_데모계획.md) 의 오픈 소스 한국어 엔진(Supertonic 3)을 설치

## APK
`apk/` 폴더 — 차량 앱 `{팀명}_동행_release.apk` + 데모 호스트 `{팀명}_동행_demo-host_release.apk` (T40)

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
