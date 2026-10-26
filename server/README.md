# server — 동행 서버 (donghaeng-server)

Kotlin 2.0.21 · Spring Boot 3.3 · JDK 21 (Gradle toolchain) · PostgreSQL 16 · Redis 7 · Flyway · EMQX 5(브로커, infra/)

모듈러 모놀리스 — 패키지 하나가 미래의 마이크로서비스 하나다 (`docs/06_서버_설계.md` §2).
T03 스켈레톤 단계: 부팅·헬스체크·Flyway V1·보안 기본값·JWKS 플레이스홀더·QR 웹 폴백까지. 도메인 기능은 T10 이후 패키지별로 채운다.

## 로컬 실행

```bash
# 1) 인프라 (PostgreSQL 은 호스트 15432, Redis 6379, EMQX 1883) — 로컬 PostgreSQL 이 5432 를 쓰므로 15432
cd infra && docker compose up -d postgres redis emqx

# 2) 서버
cd server
./gradlew bootRun                 # Windows: .\gradlew.bat bootRun
curl http://localhost:8080/actuator/health          # {"status":"UP",...}
curl http://localhost:8080/.well-known/jwks.json    # {"keys":[]}  (T10 에서 실제 RSA 키)
```

- `SPRING_PROFILES_ACTIVE=local` 로 `application-local.yml` 이 덧붙는다 (compose 의 server 서비스가 사용).
- 에뮬레이터에서 접근하는 공개 주소는 `DH_PUBLIC_BASE_URL`(기본 `http://10.0.2.2:8080`)이다. QR·딥링크 URL 은 항상 이 값 + 경로(`/pair?c=…`, `/j/{token}`)로 서버가 만든다 (docs/03).

## 환경 변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `DH_DB_URL` | `jdbc:postgresql://localhost:15432/donghaeng` | JDBC URL (compose 내부에서는 `jdbc:postgresql://postgres:5432/donghaeng`) |
| `DH_DB_USER` / `DH_DB_PASSWORD` | `dh` / `dh` | DB 계정 |
| `DH_REDIS_HOST` / `DH_REDIS_PORT` | `localhost` / `6379` | Redis |
| `DH_PUBLIC_BASE_URL` | `http://10.0.2.2:8080` | QR·딥링크·응답 URL 의 공개 base URL (끝에 `/` 없음). 클라우드: `https://<VM-IP>.sslip.io` |
| `DH_MQTT_URI` | `tcp://localhost:1883` | 서버 → 브로커 접속 주소 (compose: `tcp://emqx:1883`) |
| `DH_MQTT_PUBLIC_URI` | `tcp://10.0.2.2:1883` | 차량 앱에 내려 주는 브로커 공개 주소. 클라우드: `ssl://<VM-IP>.sslip.io:8883` |
| `SPRING_PROFILES_ACTIVE` | (없음) | `local` 등 |
| `SERVER_PORT` | `8080` | HTTP 포트 |

비밀 값(JWT 키 등)은 `.env`/환경 변수로만 주입하고 커밋하지 않는다 (`infra/.env.example` 만 커밋).

## JDK · 빌드

- 컴파일·테스트는 **JDK 21 toolchain** (`kotlin { jvmToolchain(21) }`). Gradle 데몬 자체는 `JAVA_HOME`(JDK 17)으로 떠도 된다.
- 로컬 JDK 경로는 **커밋하지 않고** `~/.gradle/gradle.properties` 에 적는다:
  ```properties
  org.gradle.java.installations.paths=<JDK 21 경로>,<JDK 17 경로>
  org.gradle.java.installations.auto-download=false
  ```
  감지된 toolchain 은 `./gradlew -q javaToolchains` 로 확인한다.
- Gradle 8.10.2 wrapper. `./gradlew bootJar` → `build/libs/server.jar` (`Dockerfile` 이 이 이름을 복사한다).
- Docker 이미지: `Dockerfile`(멀티 스테이지, temurin 21 jdk → jre). 보통 `infra/` 의 `docker compose build server` 로 빌드한다.

## 패키지 지도 (docs/06 §2)

`src/main/kotlin/app/donghaeng/`

| 패키지 | 책임 | 상태 |
|---|---|---|
| `identity` | 로그인, 디바이스 코드, 토큰 발급·검증, 차량 등록 | T10 |
| `group` | 그룹·초대·멤버십·방장 | T11 |
| `presence` | 텔레메트리 수신 → Redis 상태, 스냅샷 주기 발행 | — |
| `event` | 차량 이벤트 정규화·중복 억제·음성 문구·팬아웃 | — |
| `routesync` | 선두 경로 변경 → 멤버별 따라가기 제안, ACK 집계 | — |
| `insight` | 규칙 엔진(휴게소 제안·이탈·오프라인·연속 주행) | — |
| `poi` | 휴게소 시드·조회 | — |
| `notify` | FCM 푸시, 음성 문구 템플릿 | — |
| `mqtt` | 브로커 연결·재연결·토픽 라우팅 | — |
| `common` | 공용 코드 (아래) | T03 |

`common/` 구성: `config/DhProperties`(`dh.*` 설정), `api/ApiError`(docs/03 오류 형식 + `ApiException` + 전역 예외 처리), `id/Ids`(접두사 ULID), `security/SecurityConfig`(무상태·공개 경로), `web/WellKnownController`(JWKS), `web/LandingController`(`/pair`, `/j/{token}` 웹 폴백).

패키지 간 호출은 `XxxApi` 인터페이스로만 한다. 메시지 스키마는 `protocol/` 에만 정의한다 (T10 에서 의존 추가).

## 데이터베이스

- Flyway: `src/main/resources/db/migration/V1__init.sql` — `docs/04_데이터모델.md` §1 DDL 그대로. 스키마를 바꾸면 새 버전 파일을 추가하고 문서도 함께 고친다.
- ID: 접두사 + ULID (`usr_`, `veh_`, `grp_`, `inv_`, `evt_`, `prp_`) — `common/id/Ids`.

## 테스트

```bash
./gradlew test
```

| 테스트 | 종류 | 비고 |
|---|---|---|
| `common/id/IdsTest` | 단위 | ULID 길이·문자 집합·시간 순서·같은 ms 단조 증가 |
| `common/web/WebLayerTest` | `@WebMvcTest` | JWKS 200 `{"keys":[]}`, 미인증 `/v1/nope` → 401, 인증 후 404 오류 형식, 웹 폴백 이스케이프 |
| `HealthEndpointIT` | `@SpringBootTest` + Testcontainers | postgres:16 + redis:7 로 부팅, `/actuator/health` 200 "UP", Flyway V1 적용·`travel_group` 존재 확인. **Docker 가 없으면 자동으로 건너뜀**(`disabledWithoutDocker`) |

- Testcontainers 는 Spring Boot BOM 버전(1.19.x) 대신 `testcontainers.version=1.21.4` 로 올려 쓴다. 1.19.x 는 Docker API 1.32 를 고정 요청해 **Docker Engine 29(최소 API 1.40)** 에서 `400 Bad Request` 로 Docker 를 못 찾은 것으로 판단해 IT 를 건너뛴다.
- Windows 에서는 Docker Desktop 의 `\\.\pipe\docker_engine` 파이프로 붙는다(별도 설정 불필요).

## 규칙 요약

- 응답·주석·문서는 한국어, 식별자는 영어. 실명·학교·연락처 등 개인정보 금지(블라인드 심사).
- 로그에 좌표·닉네임·토큰 원문 금지. 비밀 값 커밋 금지.
