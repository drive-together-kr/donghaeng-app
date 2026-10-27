# 동행 (DongHaeng)

> 전화 없이, 한 대처럼. — 여러 대의 현대·기아·제네시스 차량을 하나의 그룹으로 묶어 위치·도착 예정·차량 상태·경로를 실시간으로 공유하고 음성으로 알려 주는 차량 앱

## 실행 방법
_(구현 후 작성)_ 1) `infra/`에서 `docker compose up -d` 2) Pleos Connect 에뮬레이터에 `apk/` APK 설치 3) 공식 앱을 흉내 낸 demo-host(companion)로 차량 QR 스캔

## 테스트 환경 · 설정
- Android Studio, Pleos Connect Emulator v2.1.0 (API 34), minSdk 28
- 서버: Docker Compose (PostgreSQL 16, Redis 7, EMQX 5, Spring Boot 3.3)

## APK
`apk/` 폴더

## 문서
| 문서 | 내용 |
|---|---|
| [01 상세기획서(PRD)](docs/01_상세기획서_PRD.md) | 문제, 페르소나, 기능 F1~F15, 화면, KPI, 사업성 |
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
| `protocol/` | car-app·server 공용 메시지 스키마 (kotlinx.serialization) |
| `infra/` | docker compose (postgres·redis·emqx·server), Caddy(T32 예정) |
| `docs/` | 기획·설계 문서 |
| `apk/` | 제출용 release APK |
