# server — 동행 서버 (donghaeng-server)

Kotlin 2.0.21 · Spring Boot 3.3 · JDK 21 (Gradle toolchain) · PostgreSQL 16 · Redis 7 · Flyway · EMQX 5(브로커, infra/)

모듈러 모놀리스 — 패키지 하나가 미래의 마이크로서비스 하나다 (`docs/06_서버_설계.md` §2).
T03 스켈레톤(부팅·헬스체크·Flyway V1·보안 기본값·QR 웹 폴백) + T10 identity(dev 로그인·토큰 교환·refresh 회전·RS256 JWT/JWKS·디바이스 코드 페어링·내 차). 나머지 도메인은 T14 이후 패키지별로 채운다.
REST 본문은 `protocol/` 의 `@Serializable` DTO(`RestDtos.kt`)를 kotlinx-serialization 컨버터(`common/config/HttpJsonConfig`, `DhJson.json`)로 주고받고, 오류 봉투(`ApiError`)·actuator 는 Jackson 이 맡는다.

## 로컬 실행

```bash
# 1) 인프라 (PostgreSQL 은 호스트 15432, Redis 6379, EMQX 1883) — 로컬 PostgreSQL 이 5432 를 쓰므로 15432
cd infra && docker compose up -d postgres redis emqx

# 2) 서버
cd server
./gradlew bootRun                 # Windows: .\gradlew.bat bootRun
curl http://localhost:8080/actuator/health          # {"status":"UP",...}
curl http://localhost:8080/.well-known/jwks.json    # {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"…","n":"…","e":"AQAB"}]}
curl http://localhost:8080/v1/time                  # {"serverTimeMs":1730000000000}  — 차량 앱 시계 동기화(T31), 인증 없음

# identity 흐름 맛보기 (docs/03 §1)
curl -X POST localhost:8080/v1/auth/login -H 'Content-Type: application/json' -d '{"provider":"dev","email":"demo@example.com","displayName":"데모"}'
curl -X POST localhost:8080/v1/device/code -H 'Content-Type: application/json' -d '{"vehicleId":"CAR-INFO-ID","brand":"HYUNDAI","model":"GRANDEUR"}'
curl -X POST localhost:8080/v1/device/approve -H "Authorization: Bearer <accessToken>" -H 'Content-Type: application/json' -d '{"userCode":"<userCode>","nickname":"아빠 차"}'
curl -X POST localhost:8080/v1/device/token -H 'Content-Type: application/json' -d '{"deviceCode":"<deviceCode>"}'
curl localhost:8080/v1/me/vehicles -H "Authorization: Bearer <accessToken>"
```

- `DH_JWT_PRIVATE_KEY_PEM` 이 비어 있으면 기동 때마다 개발용 RSA 키를 새로 만든다(WARN 로그). **재시작하면 발급된 토큰이 전부 무효**가 되므로 오래 쓰는 환경은 `.env` 에 키를 넣는다:
  `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | awk 'BEGIN{ORS="\\n"}{print}'` (PKCS#8, 한 줄 `\n` 표기).

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
| `DH_JWT_PRIVATE_KEY_PEM` | (없음 → 개발용 키 생성) | RS256 개인키, PKCS#8 PEM(`-----BEGIN PRIVATE KEY-----`). 한 줄 `\n` 이스케이프 허용. `identity/jwt/JwtKeyProvider` |
| `DH_CAR_ID_SALT` | `dev-salt-change-me` | CarInfo 차량 ID 해시 salt (`SHA-256(id + salt)`). 운영에서 바꾸고 이후 유지 |
| `DH_INTERNAL_TOKEN` | (없음 → 검사 안 함) | EMQX ACL 훅 `POST /internal/mqtt/acl` 의 `X-Internal-Token`. compose 의 emqx 서비스가 같은 값을 헤더로 보낸다. 클라우드에서는 필수 |
| `DH_MQTT_ENABLED` | `true` | `false` 면 브로커에 붙지 않는다(브로커 없는 로컬 실행). 테스트 기본값은 false(`src/test/resources/application.properties`), `MqttIT` 만 켠다 |
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
- Docker 이미지: `Dockerfile`(멀티 스테이지, temurin 21 jdk → jre). 보통 `infra/` 의 `docker compose build server` 로 빌드한다. 빌드 컨텍스트는 **저장소 루트**(`context: ..`, `dockerfile: server/Dockerfile`) — `settings.gradle.kts` 의 `includeBuild("../protocol")` 때문에 `protocol/` 도 들어가야 하며, 컨텍스트 필터는 `server/Dockerfile.dockerignore`(BuildKit) 다.
- `protocol/` 은 합성 빌드(`includeBuild`)로 붙는다 — `implementation("app.donghaeng:protocol")` 이 소스 프로젝트로 치환되므로 publish 가 필요 없다.

## 패키지 지도 (docs/06 §2)

`src/main/kotlin/app/donghaeng/`

| 패키지 | 책임 | 상태 |
|---|---|---|
| `identity` | dev 로그인·`/auth/exchange`(`ExternalTokenVerifier`)·refresh 회전, RS256 JWT·JWKS, 디바이스 코드 페어링(`DeviceAuthService`), 차량 등록(`VehicleRegistry`)·내 차(`VehicleDirectory`) — 하위 `jwt/`, `web/`, `repo/` | T10 완료 |
| `group` | 그룹 생성·초대(재발급)·합류·조회·내 그룹·나가기·종료·방장 위임·동승자 메시지(docs/03 §2). `GroupService`(잠금 순서: 차량 advisory → 그룹 행 → 초대 행), `GroupMembershipCache`(Redis 소속 키, 커밋 뒤), `VehicleCommandPort`(cmd 출구 — T15 가 MQTT 로 구현), identity 는 `IdentityApi` 로만 호출 — 하위 `web/`, `repo/` | T14 완료 |
| `event` | 그룹 이벤트 저장·조회(`GroupEventSink`/`GroupEventReader` → `DbGroupEventStore`, 커밋 뒤 `GroupEventRecorded` Spring 이벤트), `SpeechComposer` + `Josa`(조사). **T26:** `VehicleEventHandler`(EVENT 토픽, 봉투 type = 차량 이벤트 종류, 본문 검증) → `VehicleEventService`(소속 그룹 → `EventDedup` Redis `evt:dedup:{vid}:{type}` 120s(정차/재출발 20s) → 문구·심각도(LOW_ENERGY/HARD_BRAKE/HAZARD WARN, AIRBAG ALERT, 도착·정차 INFO) → 수신 대상(HARD_BRAKE 만 `audience` = `PresenceApi.snapshotOf` 기준 출처보다 뒤차, `protocol.EventAudience.behind`; V4 `group_event.audience`) → 저장 → 팬아웃). ROUTE_CHANGED 는 T28, PRESET_MESSAGE 는 T53 | T14·T26 완료 |
| `presence` | `TelemetryIngestor`(TELEMETRY → Redis `veh:{vid}:state`·`grp:{gid}:geo`), `PresenceIngestor`(봉투 없는 presence 토픽 → `veh:{vid}:online`), `SnapshotBuilder`(명단 + 상태 → `GroupState`, 판정 규칙 docs/03 §5.3: online/OFFLINE 60s/STOPPED 60s/ARRIVED 150 m/선두 거리), `SnapshotPublisher`(`@Scheduled` 2s retained QoS 0, GROUP_ENDED 시 retained 삭제), `PresenceApi.snapshotOf`(`GET /groups/{id}` snapshot). 설정 `dh.presence.*`. 명단은 group 의 `GroupMembershipApi.rosterOf/activeRosters` | T24 완료 |
| `routesync` | `RouteChangedHandler`(ROUTE_CHANGED) → `RouteSyncService`(선두 확인 → 멤버별 cmd ROUTE_FOLLOW_PROPOSAL + 그룹 이벤트) · `AckHandler` → ACK 집계(`ProposalStore` Redis `rs:prp:{id}` 15분) | T28 완료 |
| `insight` | 규칙 엔진(휴게소 제안·이탈·오프라인·연속 주행) | — |
| `poi` | 휴게소 시드·조회 | — |
| `notify` | FCM 푸시, 음성 문구 템플릿 | — |
| `mqtt` | EMQX 연결(`ServerMqttClient`, 서버 토큰 `srv_…`)·공유 구독·`TopicRouter`·cmd 발행(`MqttVehicleCommandPort`)·그룹 이벤트 팬아웃(`GroupEventMqttFanout`)·ACL 훅(`POST /internal/mqtt/acl`, `MqttAclService`) — docs/03 §4 | T15 완료 |
| `common` | 공용 코드 (아래) | T03 |

`common/` 구성: `config/DhProperties`(`dh.*` 설정), `config/HttpJsonConfig`(kotlinx 컨버터), `config/ClockConfig`, `api/ApiError`(docs/03 오류 형식 + `ApiException` + 전역 예외 처리), `id/Ids`(접두사 ULID), `security/SecurityConfig`(무상태·JWT 리소스 서버·역할 규칙·JSON 401/403), `security/SecureTokens`(불투명 토큰 난수·SHA-256 — refresh·디바이스 코드·초대 토큰 공용), `jdbc/JdbcSupport`(timestamptz ↔ Instant), `tx/afterCommit`(커밋 뒤 부수 효과 — Redis·MQTT), `web/LandingController`(`/pair`, `/j/{token}` 웹 폴백), `web/TimeController`(`GET /v1/time` 서버 시각 — T31). JWKS 는 `identity/web/JwksController` 로 옮겼다.

패키지 간 호출은 `XxxApi` 인터페이스로만 한다. 메시지·REST 스키마는 `protocol/` 에만 정의한다 (합성 빌드로 의존).

## 운영 메모 — 서버 재기동과 EMQX
- 서버 컨테이너를 재시작하면 EMQX 의 HTTP 인가 커넥터(`server:8080`)가 잠시 끊겨 그 사이의 인가 요청이 실패한다(`http_server_query_failed` → 거부, 10초 캐시). 차량의 텔레메트리 publish 몇 건이 거부되고, **서버 자신의 공유 구독도 0x87 로 거부될 수 있다** → `ServerMqttClient` 는 SUBACK 오류 코드를 보고 2·4·…·30초 간격으로 구독을 다시 건다(2026-10-27 실측: 재시도 없던 버전은 구독 없이 떠서 텔레메트리가 한 건도 안 들어왔다).
- 빈 순환 주의: `ServerMqttClient → TopicRouter → (inbound 핸들러) → …` 경로의 핸들러가 다시 `MqttPublisher`(= ServerMqttClient)를 쓰면 기동이 실패한다(T28 실측: `RouteSyncService`). 그런 서비스는 `@Lazy private val publisher: MqttPublisher` 로 받는다.
- 서버 MQTT 세션은 clean start + 만료 0 이다(T26 에서 변경). 이전에는 만료 300초 세션이 남아 공유 구독 메시지의 절반이 죽은 세션으로 가 재배포 뒤 5분간 이벤트·텔레메트리가 유실됐다(실측). 옛 이미지가 남긴 세션이 보이면 EMQX API `DELETE /api/v5/clients/{clientid}` 로 지운다.

## 데이터베이스

- Flyway: `src/main/resources/db/migration/V1__init.sql` — `docs/04_데이터모델.md` §1 DDL 그대로. `V2__identity.sql` — provider 주석, 조회 인덱스, 사용자당 대표 차량 1대 부분 유니크 인덱스(docs/04 '## V2'). 스키마를 바꾸면 새 버전 파일을 추가하고 문서도 함께 고친다.
- ID: 접두사 + ULID (`usr_`, `veh_`, `grp_`, `inv_`, `evt_`, `prp_`) — `common/id/Ids`.

## 테스트

```bash
./gradlew test
```

| 테스트 | 종류 | 비고 |
|---|---|---|
| `common/id/IdsTest` | 단위 | ULID 길이·문자 집합·시간 순서·같은 ms 단조 증가 |
| `common/web/WebLayerTest` | `@WebMvcTest` | JWKS 에 RSA 키 1개(개인키 성분 없음), 미인증 401 / 깨진 Bearer 401 `INVALID_TOKEN` / 유효 JWT 통과, 역할 규칙(차량 토큰 → `/v1/me/**` 403 등), 404 오류 형식, 웹 폴백 이스케이프 |
| `identity/UserCodesTest` · `CarIdHasherTest` · `SecureTokensTest` · `jwt/JwtIssuerTest` | 단위 | 사용자 코드 알파벳·정규화, CarInfo 해시 결정성, refresh 토큰 난수·SHA-256, JWT 클레임·kid·왕복 검증·PEM 로드 |
| `HealthEndpointIT` | `@SpringBootTest` + Testcontainers | postgres:16 + redis:7 로 부팅, `/actuator/health` 200 "UP", Flyway 적용·`travel_group` 존재 확인. **Docker 가 없으면 자동으로 건너뜀**(`disabledWithoutDocker`) |
| `identity/IdentityFlowIT` | `@SpringBootTest` + Testcontainers | docs/03 §1 전체 흐름: login → device/code → token 428 → 429 → approve 200 → token 200(JWKS 로 서명 검증) → me/vehicles → exchange(dev) → refresh 회전(이전 토큰 401) → approve 재시도 409; 소유권 이전·대표 차량 규칙; 만료/없는 코드 410/404; 미인증 401 JSON; kotlinx 컨버터 우선순위; V2 인덱스 |
| `mqtt/MqttIT` | `@SpringBootTest`(고정 포트 18089) + Testcontainers(postgres·redis·**emqx 5.8**) | EMQX 가 이 서버의 JWKS 로 인증하고 `/internal/mqtt/acl` 로 인가한다: 서버 자기 토큰 접속·공유 구독, 차량 토큰 접속, 자기 cmd 만 구독(남의 cmd·와일드카드·`$share` 거부), 비멤버의 그룹 토픽 거부 → 합류 뒤 허용, 합류 시 cmd `GROUP_JOINED` 수신, `MEMBER_JOINED`·`PASSENGER_MESSAGE` 팬아웃, 남의 telemetry 토픽 발행·봉투 vid 위조는 핸들러에 닿지 않음, ACL 훅의 내부 토큰 403 |
| `mqtt/MqttAclServiceTest` | 단위 | docs/03 §4 ACL 표 전체(차량 publish/subscribe, 그룹 소속, 와일드카드·공유 구독 거부, 서버 허용, 모르는 주체 거부) |
| `group/GroupFlowIT` | `@SpringBootTest` + Testcontainers | docs/03 §2 전체 흐름: 차량 토큰 생성 201(초대 URL·토픽·Redis 소속 키) → 사용자 합류(대표 차량·명시 차량·색·역할) → ALREADY_MEMBER/GROUP_FULL → 조회(멤버·소유자 200, 남 404, 이벤트 최신순) → 내 그룹 → 동승자 메시지 201/400/404 → 방장 위임 → 초대 재발급(이전 토큰 410) → 나가기(차량·사용자, 방장 승계, 마지막 멤버 → 종료) → 종료(Redis 삭제, 이후 409 GROUP_ENDED); 그룹 전환 자동 이탈·재합류; 초대 만료·사용 횟수·형식 오류 410; 생성 검증 400; 해제 차량 403; V3 컬럼·인덱스 |
| `event/JosaTest` · `SpeechComposerTest` · `group/InviteTokensTest` · `MemberColorsTest` | 단위 | 받침 판별(한글·숫자·영문·꼬리 기호), docs/06 §4 문구, 초대 토큰 형식·해시, 팔레트 색 배정 |

- Testcontainers 는 Spring Boot BOM 버전(1.19.x) 대신 `testcontainers.version=1.21.4` 로 올려 쓴다. 1.19.x 는 Docker API 1.32 를 고정 요청해 **Docker Engine 29(최소 API 1.40)** 에서 `400 Bad Request` 로 Docker 를 못 찾은 것으로 판단해 IT 를 건너뛴다.
- Windows 에서는 Docker Desktop 의 `\\.\pipe\docker_engine` 파이프로 붙는다(별도 설정 불필요).

## 규칙 요약

- 응답·주석·문서는 한국어, 식별자는 영어. 실명·학교·연락처 등 개인정보 금지(블라인드 심사).
- 로그에 좌표·닉네임·토큰 원문 금지. 비밀 값 커밋 금지.

## JDK 21 전제 (툴체인)
`kotlin { jvmToolchain(21) }` 은 **로컬에 설치된 JDK 21** 을 찾는다(자동 다운로드 비활성). JAVA_HOME 이 JDK 21 이거나 표준 위치(IntelliJ `~/.jdks`, Temurin 설치 경로 등)에 있으면 추가 설정 없이 감지되고, 비표준 경로라면 `~/.gradle/gradle.properties` 의 `org.gradle.java.installations.paths` 에 등록한다(커밋 금지). JDK 21 이 전혀 없으면 `infra/` 에서 `docker compose build server` 로 컨테이너 안에서 빌드한다.
