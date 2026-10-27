# CLAUDE.md — 동행(DongHaeng) 개발 가이드

현대자동차그룹 차량용 앱 공모전(Pleos App Market) 출품작. 여러 대의 현대·기아·제네시스 차량을 하나의 "그룹"으로 묶어 위치·ETA·차량 상태·경로를 실시간 공유하고, 운전자에게 **음성으로** 알려 주는 차량 앱.

## 반드시 먼저 읽을 문서
- `docs/01_상세기획서_PRD.md` — 기능 F1~F15, 우선순위
- `docs/02_시스템아키텍처.md` — 운영 설계 vs MVP 매핑
- `docs/03_API_프로토콜_명세.md` — REST·MQTT 토픽·메시지 스키마 (**계약. 바꾸면 문서도 같이 수정**)
- `docs/04_데이터모델.md`, `05_차량앱_설계.md`, `06_서버_설계.md`, `07_companion모듈_설계.md`
- `docs/08_구현계획서.md` — 작업 ID(T00~T57). 작업 지시는 이 ID로 온다.
- `docs/10_리스크_확인사항.md` — SDK 제약

## 저장소 구조
```
car-app/     Android(AAOS/Pleos) Kotlin, Compose, Hilt   ── flavors: pleos / fake
server/      Kotlin + Spring Boot 3.3 모듈러 모놀리스        ── packages = 미래 마이크로서비스
companion/   공식 앱(블루링크·마이현대·기아 커넥트·제네시스) 내장 모듈 ── sdk/(Android AAR, 공개 API 6개) + demo-host/(시연용 호스트 앱)
protocol/    Kotlin 공용 메시지 스키마 (car-app·server·companion/sdk 가 의존 — sdk 는 MemberState·GroupEvent 를 공개 API 에 재노출)
infra/       docker-compose(postgres, redis, emqx, server), Caddy(T32 예정)
docs/        설계 문서
apk/         제출용 release APK
```

## 명령어
```bash
# 인프라 + 서버
cd infra && docker compose up -d        # postgres 호스트 포트 15432 (로컬 PostgreSQL 18이 5432 사용)
cd server && ./gradlew test bootRun

# 차량 앱
cd car-app && ./gradlew :app:assemblePleosDebug     # 실 SDK
cd car-app && ./gradlew :app:assembleFakeDebug      # 일반 에뮬레이터용
cd car-app && ./gradlew test

# companion (sdk + 시연용 호스트 앱)
cd companion && ./gradlew :sdk:testDebugUnitTest :sdk:assembleDebug :demo-host:assembleDebug
```

## 핵심 규칙
1. **Pleos SDK 타입은 `adapter/` 패키지 밖으로 새지 않는다.** 밖에서는 도메인 타입(`Fix`, `Place`, `NaviEvent` 등)만 사용.
2. 모든 어댑터는 Pleos 구현 + Fake 구현을 함께 만든다. Fake로 단위·통합 테스트가 돌아야 한다.
3. SDK 콜백은 `callbackFlow`로 감싸고 `awaitClose`에서 **같은 리스너로** unregister. `Vehicle.release()`는 모든 콜백 이후.
4. 기능 사용 전 `checkXxxCapabilities` → 미지원이면 UI에서 숨기고 로그만 남긴다. 크래시 금지.
5. **준비 중 SDK(FusedLocation, TTS, STT, Vehicle Control)에 기능적으로 의존하지 않는다.** 위치 기본 체인은 NaviHelper → Android LocationManager → Fake 이며, FusedLocation 은 `isSupported()` 와 런타임 권한 승인이 모두 true 일 때만 선택적으로 앞에 붙인다(없어도 동작). 음성은 Android `TextToSpeech`.
6. 차량 제어(Set) API 사용 금지 — 조회만. 안전 원칙: 주행 중 입력 UI 비활성(CarUxRestrictions), 정보는 음성 우선.
7. 메시지 스키마는 `protocol/`에만 정의. car-app·server·companion에서 중복 정의 금지.
8. 로그에 좌표·닉네임·토큰 원문 금지(마스킹 유틸 사용).
9. 비밀 값(.env, keystore, JWT 키)은 커밋 금지. `.env.example`만 커밋.
10. **실명·학교 등 개인정보를 코드·README·커밋 메시지·리소스에 넣지 않는다**(블라인드 심사).
11. minSdk 28 유지. JDK 17(car-app·companion) / JDK 21(server, Gradle toolchain). 로컬 JDK 경로는 커밋하지 않고 `~/.gradle/gradle.properties`의 `org.gradle.java.installations.paths`로 지정한다.
12. 서버 주소: 에뮬레이터 → `http://10.0.2.2:8080`, 클라우드 → `https://<VM-IP>.sslip.io` (고정 도메인 없음). QR·딥링크 URL은 서버 설정값 `DH_PUBLIC_BASE_URL` + 경로로만 만든다.
13. `companion/sdk` 공개 API는 docs/07 §3의 6개뿐(`explicitApi`). 자체 로그인 UI·계정 저장 금지 — 토큰은 호스트 앱의 `TokenProvider`로 받는다. 공식 앱 OAuth 토큰은 서버 `ExternalTokenVerifier` 인터페이스로만 검증하고 Device Authorization Grant 는 대체 수단으로 유지한다.

## 작업 방식
- 작업 ID 하나 = 하나의 브랜치/커밋 묶음. 완료 시 08 문서의 완료 기준을 직접 검증(테스트 실행·빌드)하고 결과를 보고한다.
- 실기기·에뮬레이터 확인이 필요한 부분은 **확인용 로그/디버그 화면을 먼저 만들어** 사용자가 결과만 붙여넣으면 되게 한다. 사용자에게 막연한 수동 테스트를 요청하지 않는다.
- 불확실한 SDK 시그니처는 공식 샘플(github.com/Pleos-Playground/pleos-sample-vehicle-app, pleos-sample-navi-app, pleos-sample-fusedlocation-app)과 Gradle 캐시의 SDK 소스/클래스로 확인 후 사용.
- 응답·문서·주석은 한국어, 식별자는 영어.
