# 동행 API · 프로토콜 명세 (v1)

- 공개 주소 설정값 **`DH_PUBLIC_BASE_URL`** (서버 환경 변수, 끝에 `/` 없음): 서버가 QR·딥링크·응답 URL을 만들 때 쓰는 공개 base URL. **고정 도메인을 쓰지 않는다.**
  - 로컬(에뮬레이터) `http://10.0.2.2:8080` · 로컬(실기기, 같은 Wi-Fi) `http://<PC-IP>:8080` · 클라우드 `https://<VM-IP>.sslip.io` (예: `https://1.2.3.4.sslip.io` — 점 표기, Caddy 자동 인증서)
- REST Base URL: `{DH_PUBLIC_BASE_URL}/v1` (로컬: `http://10.0.2.2:8080/v1` — 에뮬레이터 기준)
- MQTT 공개 주소 설정값 **`DH_MQTT_PUBLIC_URI`**: 로컬 `tcp://10.0.2.2:1883`, 클라우드 `ssl://<VM-IP>.sslip.io:8883`. MQTT 5.0
  - **WSS 폴백 주소 규칙 (T32)**: `DH_PUBLIC_BASE_URL` 이 `https://{host}[:{port}]` 이면 같은 서버가 **`wss://{host}[:{port}]/mqtt`**(포트 생략 시 443, WebSocket 서브프로토콜 `mqtt`)에서도 MQTT 를 받아야 한다(클라우드: Caddy 가 `/mqtt` 를 EMQX WebSocket 리스너 8083 으로 프록시). 별도 설정값·응답 필드는 없다 — 클라이언트가 베이스 URL 에서 파생한다. 차량 앱은 `mqtt.uri`(ssl 8883)로 브로커에 닿지 못한 시도가 연속 2회이거나(CONNACK 응답·망 없음은 세지 않음) 첫 연결이 10초 안에 안 되면 이 주소로 바꾼다(docs/05 §7). `http://` 베이스(로컬)에는 파생 주소가 없다.
  - 허용 스킴: `tcp://`·`mqtt://`(1883) · `ssl://`·`mqtts://`·`tls://`(8883) · `ws://`(80) · `wss://`(443). 베이스 URL 이 https 면 평문(`tcp://`·`ws://`)은 클라이언트가 거부한다. 인증·토픽·ACL 은 1차 주소와 같다(infra/cloud Caddy → EMQX WebSocket).
- QR·딥링크 URL은 항상 `{DH_PUBLIC_BASE_URL}` + 경로(`/pair?c=…`, `/j/{token}`)로 **서버가 생성**하고, companion 모듈은 호스트가 아니라 **경로로 종류를 판별**한다(07 문서 §5)
- 직렬화: JSON (UTF-8), 필드명 camelCase, 시간은 epoch milliseconds(`ts`), 좌표는 WGS84 소수점 7자리
- 시각 기준: 모든 `ts` 는 **서버 시계**가 기준이다. `GET /v1/time`(인증 없음, protocol `ApiPaths.TIME`) → 200 `ServerTimeResponse { "serverTimeMs": 1730000000000 }`. 차량 앱은 3회 호출 중 왕복이 가장 짧은 표본으로 `offset = serverTime − (t0 + rtt/2)` 를 잡아(T31 `ClockSync`, 5분마다) 메시지 지연(ms) 표시와 음성 신선도(5 s) 판정에 쓴다 — 차량 시계가 틀려도 동작한다(에뮬레이터 실측: 호스트보다 1.2~1.9 s 늦어 보정 없이는 지연이 음수).
  - **차량이 보내는 봉투 `ts`(2026-11, 계약 4)**: `ClockSync` 가 동기화돼 있으면 서버 시계(`serverNow`), 아니면 기기 시계. 서버는 이 값을 **참고만** 한다 — 신선도·`OFFLINE`·정차 판정(§5.3)은 **서버 수신 시각**으로 하고, 봉투 `ts` 는 지연 측정과 늦은 위험 이벤트 판정(§5.2)에만 쓴다. 서버가 보내는 봉투 `ts` 는 늘 서버 시계다.
- 재시도 안전 키(2026-11, 계약 3): `POST /groups/{id}/messages`(사용자)·`POST /groups/{id}/voice`(차량)는 선택 헤더 **`Idempotency-Key`**(`[A-Za-z0-9_-]{8,64}`, protocol `ApiHeaders.IDEMPOTENCY_KEY`·`IdempotencyKey.isValid`, 형식이 틀리면 400 `VALIDATION_FAILED`)를 받는다. 서버는 (호출자 ID, 경로, 키) → **첫 성공(2xx) 응답**(상태·본문)을 24시간 기억하고, 같은 키가 다시 오면 저장·팬아웃 없이 그 응답을 그대로 돌려준다(그 사이 그룹이 끝났어도 같은 응답). 4xx·5xx 는 기억하지 않는다 — 같은 키로 다시 보내면 다시 처리한다(예: 429 뒤 재시도). 같은 키의 동시 요청은 첫 요청이 끝날 때까지 기다렸다가 그 응답을 받는다. 본문이 달라도 첫 응답이다.
- 빈도 제한(2026-11, C094·C106): `POST /auth/login`·`POST /device/code` 는 **IP 당 분당 30회**, `POST /groups/{id}/messages` 는 **그룹당 2초에 1건** — 넘으면 429 `RATE_LIMITED` + 헤더 `Retry-After`(초). IP 는 서버 앞 프록시(Caddy)가 붙인 `X-Forwarded-For` 의 마지막 값(직접 연결이 사설·루프백 주소일 때만). 폴링(`/device/token`)은 따로 429 `SLOW_DOWN`, 무전은 429 `VOICE_RATE_LIMITED`(§2.6).
- 인증 헤더: `Authorization: Bearer <JWT>`
- 오류 형식:
```json
{ "error": { "code": "INVITE_EXPIRED", "message": "초대 코드가 만료됐어요.", "traceId": "..." } }
```

---

## 1. 인증 · 페어링 (identity)

### 1.1 사용자 인증 (companion 모듈 — 공식 앱 계정)
`POST /auth/login` — MVP 개발용 간편 로그인
```json
// req
{ "provider": "dev", "email": "user@example.com", "displayName": "홍길동" }
// res 200
{ "accessToken": "jwt", "refreshToken": "opaque", "user": { "userId": "usr_01H...", "displayName": "홍길동" } }
```
companion 모듈은 제공자와 무관하게 아래 교환 한 가지 경로만 쓴다(호스트 공식 앱의 OAuth 2.0 access token → 동행 JWT).
`POST /auth/exchange` `{ "provider": "dev" | "bluelink" | "myhyundai" | "kia_connect" | "genesis_connected", "accessToken": "<호스트 앱 토큰>" }` → 위와 같은 토큰 쌍.
`dev` 의 `accessToken` 은 `/auth/login` 응답의 `accessToken`(JWT) 을 그대로 넘긴다 — 서버 `DevTokenVerifier` 가 이를 인정한다. 모듈은 토큰 형식을 해석하지 않는다.
서버는 provider 별 `ExternalTokenVerifier.verify(provider, token) → ExternalIdentity(provider, sub, displayName)` 로 검증한다(인터페이스만 두고 MVP 구현은 `DevTokenVerifier`; Bluelink/MyHyundai/KiaConnect/GenesisConnected 는 `NotSupportedTokenVerifier` 자리). 실제 IdP 연동은 OEM 협의 대상.

| `/auth/exchange` 상태 | 조건 |
|---|---|
| 200 `TokenResponse` | 검증 성공. `(provider, sub)` 로 `app_user` 를 찾거나 만든다(표시 이름은 최신값으로 갱신) |
| 400 `VALIDATION_FAILED` | 알 수 없는 `provider` |
| 401 `INVALID_TOKEN` | `accessToken` 이 비었거나 검증 실패 (`dev`: 서명·만료·발급자 불일치, `typ ≠ user`, 사용자 없음) |
| 501 `PROVIDER_NOT_SUPPORTED` | `bluelink` · `myhyundai` · `kia_connect` · `genesis_connected` (MVP 미구현) |

`POST /auth/login` 은 `provider` 가 `dev` 일 때만 200 (그 외 400 `VALIDATION_FAILED`). `provider_sub` 는 소문자 이메일 — 대소문자가 달라도 같은 사용자다. 같은 IP 에서 분당 30회를 넘으면 접속 키 검사보다 먼저 429 `RATE_LIMITED`(+`Retry-After`) — 키를 맞히려는 반복도 막는다.
**접속 키**: 서버에 `DH_DEV_LOGIN_KEY` 가 설정돼 있으면(클라우드 필수, 로컬은 비움) 요청 헤더 `X-Dev-Login-Key`(protocol `ApiHeaders.DEV_LOGIN_KEY`)가 같아야 하고, 없거나 다르면 본문을 보기 전에 **403 `FORBIDDEN`** 이다 — 비밀번호 없는 dev 로그인이 공개 서버에서 이메일만으로 남의 계정을 열지 않게(docs/10 R20).
demo-host 는 빌드 속성 `dhDevLoginKey`, `tools/demo` 는 환경변수 `DH_DEV_LOGIN_KEY` 로 넣는다. `/auth/exchange`·`/auth/refresh` 는 이 키를 보지 않는다(dev 교환은 로그인이 발급한 토큰만 인정).

`POST /auth/refresh` `{ "refreshToken": "..." }` → 200 새 토큰 쌍. **회전**: 이전 refresh 는 즉시 폐기되고 재사용하면 401. 무효·폐기·만료는 모두 401 `INVALID_TOKEN`(구분하지 않음).
**재사용 유예 60 s (T33 리뷰)**: 서버가 회전을 커밋한 뒤 응답이 닿지 못하면(읽기 타임아웃·네트워크 단절·프로세스 종료) 클라이언트는 이전 refresh 만 가진 채 남는다. 그래서 이전 refresh 가 **회전된 지 60 s 안에** 다시 오고 **그 다음 토큰이 아직 한 번도 쓰이지 않았으면**(해제·이전으로 폐기되지도 않았으면) 응답 유실 재시도로 보고 다음 토큰을 폐기한 뒤 새 쌍을 돌려준다(살아 있는 refresh 는 계속 하나). 다음 토큰을 이미 썼거나, 해제·이전으로 폐기됐거나, 60 s 가 지났으면 401. 유예는 처음 회전 시각부터 재므로 재시도를 거듭해도 늘지 않는다(`refresh_token.replaced_by`, docs/04 V4). 유예 안에서는 도난된 이전 토큰도 한 번 통할 수 있다는 점을 받아들인 절충이다(차량은 single-flight 라 정상 흐름에서 같은 refresh 를 동시에 두 번 보내지 않는다).
refresh 토큰은 불투명 문자열(랜덤 32바이트 Base62, 30일, 서버엔 SHA-256 해시만). 차량 refresh 토큰이면 `accessToken` 은 **차량 토큰**이고 `user` 는 소유자다(차량 앱도 같은 엔드포인트를 쓴다). 페어링이 해제된 차량의 refresh 는 거부.
**차량 앱의 사용(T33 구현)**: 차량 JWT(24h)의 `exp` 를 보고(서명 검증 없이 payload 만) 남은 시간이 2시간 이하면 선제 갱신(앱 시작 + 30분 점검), 인증 경로 REST 가 401 이면 OkHttp `Authenticator` 가 한 번 갱신하고 같은 요청을 새 Bearer 로 재시도(`/v1/auth/*`·`/v1/device/*` 제외), MQTT CONNACK 이 `NOT_AUTHORIZED`/`BAD_USER_NAME_OR_PASSWORD` 면 갱신 뒤 새 토큰으로 다시 접속한다. 동시 요청은 한 번만 갱신(single-flight)하고 회전된 `accessToken`·`refreshToken` 은 한 번에 저장한다. 갱신 401 은 본문 `code` 가 `INVALID_TOKEN` 일 때만 자격 증명 문제로 본다(프록시·캡티브 포털의 401 은 실패로만 세고 토큰 유지). **선제 갱신**이 401 인데 access token 이 아직 유효하면 지우지 않고 '다시 연결 필요(HH:mm 까지 동작)' 배너만 띄운 뒤, 만료 직후 점검이나 실제 거부(REST 401·MQTT 인증 거부)에서 지운다 — 바로 지우면 그룹 세션까지 사라진다. 실제 거부 뒤의 갱신이 401 이면 보낸 refresh 가 아직 저장돼 있을 때만(compare-and-set) 자격 증명을 지우고(`REFRESH_REJECTED`) "폰에서 다시 연결해 주세요" 를 띄운다. 선제 갱신이 네트워크·5xx 로 실패하면 20 s 뒤 한 번 더 시도한다(위 재사용 유예 안). MQTT 인증 거부로 회전한 새 토큰도 다시 거부되면(브로커 JWKS·설정 문제) MQTT 발 회전 간격을 1→2→4→8→16→30분으로 늘리고 접속에 성공하면 처음부터 — 진단 카드·health `mqttRot` 에 단계가 보인다. 해제된 차량의 남은 access token 으로 부른 그룹 API 는 401 이 아니라 403 이라 갱신 대상이 아니다.

**JWT 형식 (RS256)**: 헤더 `kid`, `iss = "donghaeng"`. 사용자 토큰 `sub = usr_…`, `typ = "user"`, 1시간. 차량 토큰 `sub = veh_…`, `typ = "vehicle"`, `owner = usr_…`, 24시간. 공개키는 `GET /.well-known/jwks.json`(JWK Set — `kty RSA`, `use sig`, `alg RS256`, `kid`, `n`, `e`; EMQX 도 같은 키로 검증). 보호된 API 에 토큰이 없으면 401 `UNAUTHORIZED`, 무효하면 401 `INVALID_TOKEN`, 역할(사용자/차량)이 맞지 않으면 403 `FORBIDDEN`.

### 1.2 차량 페어링 — Device Authorization Grant (RFC 8628 변형)
공식 앱 계정에 연결된 차량이 없을 때의 **대체 수단**이다. 계정에 차량이 있으면(실서비스: `VehicleDirectory` 가 OEM 차량 API 조회) 페어링 없이 `GET /me/vehicles` 로 바로 쓴다. MVP(dev 계정)는 이 흐름으로 에뮬레이터 차량을 등록한다.

`POST /device/code` (차량 앱, 인증 없음)
```json
// req
{ "vehicleId": "CarInfo에서 읽은 차량 ID", "model": "GRANDEUR", "brand": "HYUNDAI", "appVersion": "0.1.0" }
// res 200
{
  "deviceCode": "dc_8f3...",          // 차량만 보관
  "userCode": "KQ7-4MZ",              // 화면 표시용 6자리
  "verifyUri": "http://10.0.2.2:8080/pair?c=KQ74MZ",   // QR 내용 = {DH_PUBLIC_BASE_URL}/pair?c={userCode}
  "expiresIn": 600,
  "interval": 3
}
```
`POST /device/token` (차량 앱 폴링)
```json
// req
{ "deviceCode": "dc_8f3..." }
// res 428 { "error": { "code": "AUTHORIZATION_PENDING" } }
// res 200
{
  "vehicleAccessToken": "jwt(sub=veh_..., ownerId=usr_...)",
  "refreshToken": "opaque",
  "vehicle": { "vehicleId": "veh_01H...", "nickname": "홍길동 차", "ownerId": "usr_01H..." },
  "mqtt": { "uri": "tcp://10.0.2.2:1883", "clientId": "veh_01H...", "username": "veh_01H..." }   // uri = DH_MQTT_PUBLIC_URI
}
```
`POST /device/approve` (companion 모듈 `pairVehicle`, 사용자 JWT — 대체 수단)
```json
// req — userCode 는 대소문자·하이픈·공백을 무시한다 ("kq7-4mz" 도 됨)
{ "userCode": "KQ74MZ", "nickname": "홍길동 차" }
// res 200 — 승인 시점에 차량 행이 만들어진다(또는 호출자에게 이전된다)
{ "vehicleId": "veh_01H...", "nickname": "홍길동 차", "ownerId": "usr_01H..." }
```
승인 규칙: `car_info_id_hash` 가 처음이면 새 차량(호출자에게 활성 대표 차량이 없으면 `isPrimary = true`). 이미 있으면 **호출자에게 소유권 이전** — 닉네임 갱신, 해제 상태 복원, 이전 소유자가 쥔 차량 refresh 토큰 폐기. 이미 발급된 차량 access token 은 만료까지 서명상 유효하므로, **소유자가 바뀌면** 서버가 승인 트랜잭션 커밋 뒤 이전 vehicleId(같은 차량 행)의 `dh/v1/vehicle/{vid}/cmd` 로 `TOKEN_REVOKED`(QoS 1, retain 없음, §5.5)를 보낸다(T33 구현). 같은 소유자의 재페어링은 보내지 않는다. 소유권 이전이면 그 차는 이전 소유자의 활성 그룹에서 빠진다 — `MEMBER_LEFT` reason **`OWNER_CHANGED`**, 문구·payload 의 `nickname` 은 **이전 별명**(새 소유자가 붙인 별명은 옛 그룹에 나가지 않는다).
`nickname` 은 제어·서식 문자(양방향 재정렬 U+202E 등)를 공백으로 바꾸고 공백을 접은 뒤 1~30자(`PATCH /me/vehicles` 도 같다). 같은 사용자의 승인·대표 지정은 서버가 사용자 단위로 줄 세운다(동시 승인 두 건도 대표는 하나, 500 없음).
`POST /device/code` 는 인증이 없으므로 같은 IP 에서 분당 30회를 넘으면 429 `RATE_LIMITED`(+`Retry-After`). 만료된 코드는 만료 1시간 뒤 정리된다.
**마지막 폴링 응답 유실 재수령(2026-11, C112)**: 토큰을 내준 200 응답이 차량에 닿지 못하면 그 코드는 이미 CONSUMED 다. 처음 내준 지 **60 s 안**에 같은 `deviceCode` 로 다시 폴링하고, 그때 내준 refresh 가 **한 번도 쓰이지 않았고** 차량이 같은 소유자에게 연결돼 있으면 그 refresh 를 폐기하고 **새 토큰 쌍을 다시 준다**(200, 살아 있는 refresh 는 하나). 그 밖(이미 refresh 를 썼음·60 s 지남·해제·이전)은 409 `ALREADY_APPROVED` — 차량 앱은 이 409 를 '새 코드 받기'로 처리한다. 유예는 처음 발급 시각부터 재므로 늘지 않는다.
차량은 페어링 응답과 refresh 응답의 차량 refresh 토큰으로 `POST /auth/refresh`(§1.1)를 써서 24h 차량 토큰을 갱신한다.

| 엔드포인트 | 상태 | 조건 |
|---|---|---|
| `POST /device/token` | 200 `DeviceTokenResponse` | APPROVED → 차량 토큰·refresh 발급 후 CONSUMED (한 번만) |
| | 404 `CODE_NOT_FOUND` | 없는 `deviceCode` |
| | 429 `SLOW_DOWN` | 마지막으로 받아 준 폴링 뒤 `interval`(3s) 안에 다시 폴링 (Redis `devcode:poll:{deviceCode}`, 15분 TTL) |
| | 410 `CODE_EXPIRED` | `expiresIn`(600s) 지남, 또는 승인 뒤 폰에서 그 차량이 해제됨 |
| | 428 `AUTHORIZATION_PENDING` | 아직 승인 전 |
| | 200 `DeviceTokenResponse`(재수령) | CONSUMED 지만 처음 발급 60 s 안 + 그때 준 refresh 미사용 → 새 쌍(이전 refresh 폐기) |
| | 409 `ALREADY_APPROVED` | 이미 토큰을 받아 간 코드(CONSUMED) — 위 재수령 조건이 아님 |
| `POST /device/code` | 429 `RATE_LIMITED` | 같은 IP 에서 분당 30회 초과(`Retry-After`) |
| `POST /device/approve` | 200 `VehicleSummary` | 승인 완료 |
| | 401 / 403 | 사용자 토큰 없음·무효 / 차량 토큰으로 호출 |
| | 400 `VALIDATION_FAILED` | `userCode` 비었음, `nickname` 이 1~30자가 아님 |
| | 404 `CODE_NOT_FOUND` | 없는(또는 형식이 틀린) `userCode` |
| | 410 `CODE_EXPIRED` | 만료 |
| | 409 `ALREADY_APPROVED` | 이미 승인·소비된 코드 |

검사 순서: `/device/token` 은 404 → 429 → 410 → 상태별(428/200/409), `/device/approve` 는 인증(401/403) → 400 → 404 → 410 → 409.

### 1.3 내 차 (사용자 JWT)
`GET /me/vehicles` → 200 `[{ vehicleId, nickname, brand, model, isPrimary }]` — `VehicleDirectory` 조회(MVP: 동행 DB 의 해제되지 않은 차량), 대표 차량이 먼저. 없으면 `[]`.
`PATCH /me/vehicles/{vehicleId}` `{ nickname?, isPrimary? }` → 200 `MyVehicleDto`. 둘 다 없으면 400. `isPrimary: true` 면 같은 사용자의 다른 대표 차량은 자동 해제된다(사용자당 하나 — DB 부분 유니크 인덱스). 내 차가 아니거나 해제된 차는 404 `NOT_FOUND`.
`DELETE /me/vehicles/{vehicleId}` → 204 페어링 해제: `vehicle.revoked_at` 기록 + 그 차량의 refresh 토큰 폐기 + 활성 그룹에서 제외(`MEMBER_LEFT` reason **`UNPAIRED`**). 이미 발급된 차량 access token 은 만료(24h)까지 서명상 유효하지만 그룹 API 는 403 이고(재접속 동기화 `GET /v1/vehicles/me/group` 만 204 — §2.3), 서버가 커밋 뒤 그 차량에 cmd `TOKEN_REVOKED`(QoS 1, retain 없음)를 보내 차량 앱이 스스로 자격 증명을 지운다(T33 구현). 같은 차를 다시 페어링하면 같은 `vehicleId` 가 복원된다. 없으면 404(이때는 보내지 않는다).

`DELETE /v1/vehicles/me` (**차량 JWT 전용**, 2026-11 계약 2, protocol `ApiPaths.VEHICLES_ME`) → 204. 차량 설정 '연결 해제'가 **먼저** 부른다 — 차가 스스로 페어링을 해제한다. 폰의 해제와 **같은 서비스 경로**: `revoked_at` 기록, 이 차량의 refresh 전부 폐기, 활성 그룹에서 `MEMBER_LEFT` reason `UNPAIRED`(방장이면 승계·마지막이면 종료), 커밋 뒤 `TOKEN_REVOKED`. 멱등이다 — 이미 해제됐거나, **토큰 발급 뒤 다른 계정으로 소유권이 넘어간 차**(토큰 `owner` ≠ 현재 소유자)는 아무것도 바꾸지 않고 204(옛 토큰으로 새 소유자의 연결을 끊지 못하게). access token 이 만료·무효면 401 — 차량 앱은 **204·401 을 모두 성공**으로 보고 로컬 자격 증명을 지운다(요청이 실패해도 로컬 정리는 한다). 사용자 토큰이면 403.

**한계(T33 리뷰)**: `TOKEN_REVOKED` 는 retain 이 없고 차량 MQTT 영속 세션은 300 s 에 만료되므로, **차가 300 s 넘게 오프라인일 때 해제하면 그 cmd 는 사라진다**. 그 뒤 차는 서명상 유효한 access token 으로 MQTT 에 다시 붙어 '연결됨' 으로 보이고, 해제 때의 `MEMBER_LEFT`/`GROUP_ENDED` 도 놓쳐 옛 그룹 세션이 남을 수 있다(그룹 API 는 403 `FORBIDDEN`). **보완(2026-11)**: 차량 앱은 앱 시작·MQTT 재접속 때 `GET /v1/vehicles/me/group`(§2.3)으로 세션을 서버와 맞춘다 — 해제·소유권 이전된 차의 옛 토큰도, 끝난·나간 그룹도 **204**('진행 중 그룹 없음')라 차가 옛 세션을 지운다(403 이 아니다 — 403 이면 차량 앱이 결과를 모름으로 보고 세션을 남긴다, RC-2). 그 밖의 경로로는 다음 갱신 시점에 정리된다 — 선제 갱신(만료 2시간 전)의 refresh 가 401 이면 '다시 연결 필요' 배너, access token 만료 뒤 점검·인증 거부에서 자격 증명 삭제(`REFRESH_REJECTED`). MQTT 브로커 쪽 구독·세션 회수는 하지 않는다(docs/10 R22).

## 2. 그룹 (group)

### 2.1 그룹 생성 (차량 앱, 정차 중)
`POST /groups` (차량 JWT)
```json
// req
{
  "name": "추석 귀성길",
  "destination": { "lat": 35.1595454, "lon": 126.8526012, "name": "광주 할머니댁", "poiId": null, "address": "..." },
  "maxMembers": 10
}
// res 201
{
  "groupId": "grp_01H...",
  "leaderVehicleId": "veh_01H...",
  "invite": { "token": "inv_...", "url": "http://10.0.2.2:8080/j/inv_...", "expiresAt": 1793000000000 },   // url = {DH_PUBLIC_BASE_URL}/j/{token}
  "mqttTopics": { "state": "dh/v1/group/grp_01H/state", "event": "dh/v1/group/grp_01H/event" }
}
```
검증: `name` 1~30자, `maxMembers` 2~10, `destination.name` 1~60자, `lat` -90~90, `lon` -180~180 (그 외 400 `VALIDATION_FAILED`). 이름·장소 문자열은 제어·서식 문자를 공백으로 바꾸고 공백을 접은 뒤(trim 포함) 길이를 본다. 토큰의 차량이 페어링 해제 상태면 403 `FORBIDDEN`.
생성한 차량은 `LEADER`(색 `#2E7DFF`)가 된다. 이미 다른 활성 그룹에 있었으면 그 그룹에서 자동으로 나간다(§2.5).

`POST /groups/{groupId}/invites` (방장 — 방장 차량 JWT 또는 그 차량 소유자의 사용자 JWT) → 200 `InviteDto` `{ token, url, expiresAt }`. **이전 초대는 즉시 만료**된다. 방장이 아니면 403, 종료된 그룹이면 409 `GROUP_ENDED`.
초대 토큰: `inv_` + 랜덤 32바이트 Base62(≈47자). 10분 유효, 사용 횟수 `3 × (maxMembers - 1)`(정원은 `GROUP_FULL` 이 지키고 이 한도는 나가고 다시 들어오는 여유를 둔 재생 상한). 서버엔 SHA-256 해시만 저장(docs/06 §5).

### 2.2 그룹 합류 (공식 앱의 companion 모듈이 QR 스캔 후 — `joinGroup`)
**초대 미리보기(호스트 앱용, 인증 없음):** 그룹 QR 의 URL `{DH_PUBLIC_BASE_URL}/j/{token}` 을 `Accept: application/json` 으로 읽으면 200 `InvitePreviewResponse` `{ name, leaderVehicleId, leaderNickname, destination, memberCount, maxMembers, expiresAt }` 을 돌려준다(브라우저의 `text/html` 요청은 기존 안내 페이지). 호스트 앱이 "함께 가기" 전에 그룹명·방장·목적지·인원을 보여 주는 용도이며 사용 횟수에 포함되지 않는다. 토큰이 없음·형식 오류·만료·사용 횟수 초과·그룹 종료면 410 `INVITE_EXPIRED`. companion 모듈 공개 API 는 그대로 6개다(미리보기는 모듈을 거치지 않는 공개 URL 조회).

`POST /groups/join` (사용자 JWT)
```json
// req
{ "inviteToken": "inv_...", "vehicleId": "veh_01H..." }   // vehicleId 생략 시 대표 차량(없으면 첫 활성 차량)
// res 200
{ "groupId": "grp_01H...", "name": "추석 귀성길", "members": [ ...Member ] }
```
| 상태 | 조건 |
|---|---|
| 200 `GroupJoinResponse` | 합류. `MEMBER` 역할, 팔레트에서 비어 있는 첫 색. 그룹 이벤트 `MEMBER_JOINED`, 차량에 cmd `GROUP_JOINED`(T15) |
| 401 / 403 | 사용자 토큰 없음·무효 / 차량 토큰으로 호출 |
| 404 `NO_PAIRED_VEHICLE` | 계정에 활성 차량이 없음, 또는 `vehicleId` 가 내 활성 차량이 아님 |
| 409 `ALREADY_MEMBER` | 그 차량이 이미 **이** 그룹의 멤버 (다른 그룹에 있었으면 거기서 자동으로 나간 뒤 합류 — §2.5) |
| 409 `GROUP_FULL` | 현재 멤버 수 ≥ `maxMembers` |
| 410 `INVITE_EXPIRED` | 토큰이 없음·형식 오류·만료·사용 횟수 초과·그룹 종료 (열거 방지를 위해 구분하지 않음) |

검사 순서: 인증 → 차량 결정(404) → 토큰·그룹 존재(410) → 같은 그룹(409 ALREADY_MEMBER) → 만료·종료(410) → 정원(409 GROUP_FULL) → 사용 횟수 초과(410).

### 2.3 조회·관리
사용자 JWT 는 "현재 멤버 차량의 소유자", 차량 JWT 는 "현재 멤버 차량 자신"일 때만 그룹을 볼 수 있다. 그 외(나간 차량·남)는 404 `NOT_FOUND`(존재를 숨김). 종료된 그룹은 종료 시점의 멤버가 계속 볼 수 있다(멤버 행 유지).

- `GET /groups/{groupId}` (사용자·차량) → 200 `GroupDetailResponse` `{ groupId, name, leaderVehicleId, status: ACTIVE|ENDED, destination, members: [Member], snapshot: GroupState|null, recentEvents: [GroupEvent 최신순 ≤20] }`. `snapshot` 은 presence(T24)가 채우기 전까지 null.
- `GET /me/groups?active=` (사용자) → 200 `[MyGroupDto]` `{ groupId, name, leaderVehicleId, status, destination, memberCount, myVehicleIds, createdAt, endedAt? }` — 내 활성 차량이 멤버인 그룹. `active` 생략 → 전부(ACTIVE 먼저, 최근 생성순), `true` → 진행 중만, `false` → 종료된 것만.
- `GET /v1/vehicles/me/group` (**차량 JWT 전용**, 2026-11 계약 1, protocol `ApiPaths.VEHICLES_ME_GROUP`) → 차량이 지금 들어가 있는 **진행 중** 그룹이 있으면 200 + `GroupJoined`(cmd `GROUP_JOINED` 본문과 같은 모양: `{ groupId, name, leaderVehicleId, destination, topics }` — `destination` 은 최신 그룹 목적지, §2.5), 없으면 **204**(본문 없음). 해제·소유권 이전된 차량의 (서명상 아직 유효한) 옛 토큰도 **204** — 차가 옛 세션을 지우게 하고(§1.3 한계 보완), 이전된 차의 옛 토큰에는 새 소유자의 그룹을 알려 주지 않는다(자격 증명 정리는 refresh 401 경로). 다른 그룹 API 는 이런 토큰에 403 그대로. 사용자 토큰은 403. DB(진실) 기준으로 답하고 Redis 소속 캐시가 다르면 고친다. 차량 앱은 앱 시작·MQTT 재접속 때 이것으로 로컬 세션을 맞춘다(놓친 `GROUP_ENDED`·`MEMBER_LEFT`·`GROUP_JOINED` 복구). 이 경로를 모르는 옛 서버(404·405)면 차량은 조용히 건너뛰고 `GET /groups/{id}` 의 `status == ENDED` 또는 members 에 내 차가 없음으로 옛 세션만 정리한다.
- `POST /groups/{groupId}/leave` (차량 또는 사용자) → 204. 본문 선택 `{ vehicleId? }`: 차량 JWT 는 자기 자신(본문 무시), 사용자 JWT 는 `vehicleId`(내 차) 또는 생략 시 이 그룹의 내 차 전부. 멤버가 아니면 404(`NOT_FOUND` / 사용자는 `NO_PAIRED_VEHICLE`), 종료된 그룹 409 `GROUP_ENDED`. 그룹 이벤트 `MEMBER_LEFT`(payload `reason` — §5.4 표: `LEFT`(나가기) | `SWITCHED_GROUP`(다른 그룹 생성·합류, §2.5) | `UNPAIRED`(페어링 해제, §1.3) | `OWNER_CHANGED`(소유권 이전, §1.2), 방장 승계 시 `newLeaderVehicleId`; protocol `MemberLeftReason`. 클라이언트는 모르는 값도 '나감'으로 다룬다). **차량 앱은 `sourceVehicleId` 가 자기 자신인 `MEMBER_LEFT` 를 받으면 GROUP_ENDED 와 똑같이 로컬 세션을 지우고 그룹 토픽 구독을 해제한다**(T30 — 폰에서 내보낸 차가 그룹을 계속 보고 있지 않도록). 방장이 나가면 가장 먼저 합류한 남은 멤버가 방장(`LEADER_CHANGED`), 마지막 멤버가 나가면 그룹 종료(reason `ALL_LEFT`).
- `POST /groups/{groupId}/end` (방장) → 204. 그룹 `ENDED`, 초대 만료, Redis 소속·위치 키 삭제(docs/04 §2), 그룹 이벤트 `GROUP_ENDED`(reason `LEADER_ENDED`), 멤버 차량에 cmd `GROUP_ENDED`(T15). 방장 아니면 403, 이미 종료 409 `GROUP_ENDED`. 멤버 행·이벤트 로그는 남는다 — 이벤트는 **24h 뒤 배치 삭제**(1시간마다, 진행 중 그룹도 같다), 종료 커밋 뒤 위치성 payload(`WAYPOINT_ARRIVED` 의 `lat`·`lon`, `ROUTE_CHANGED` 의 `destination`·`waypoints`)는 바로 지운다(문구·이름은 남는다). 그룹 경유지(§2.5)도 지운다.
- `POST /groups/{groupId}/leader` `{ vehicleId }` (방장) → 200 `GroupDetailResponse`. 대상이 현재 멤버가 아니면 404, 이미 방장이면 400. 그룹 이벤트 `LEADER_CHANGED` `{ leaderVehicleId, previousLeaderVehicleId }`.
- `POST /groups/{groupId}/messages` (사용자 JWT — 멤버 차량 소유자) `{ "text": "앞에 휴게소 들르자", "fromVehicleId": "veh_..." }` → 201 `GroupEvent`(type `PASSENGER_MESSAGE`, `speech` = `{닉네임}에서 온 메시지. {text}`, payload `{ text }`). `fromVehicleId` 생략 시 이 그룹의 내 첫 차, 내 멤버 차가 아니면 404 `NO_PAIRED_VEHICLE`. `text` 는 공백 정리 후 1~60자(아니면 400 `INVALID_MESSAGE`). 멤버가 아니면 404, 종료된 그룹 409 `GROUP_ENDED`. **같은 그룹은 2초에 1건**(보낸 사람·차와 무관) — 넘으면 429 `RATE_LIMITED`(+`Retry-After`, 운전자에게 TTS 연타 방지). 선택 헤더 `Idempotency-Key`(머리말) — companion 은 보내기 한 번(사용자 동작 1회)마다 UUID 를 만들어 재시도에 재사용한다. 같은 키의 재시도는 첫 201 응답(같은 `eventId`)을 받고 다시 저장·팬아웃되지 않는다. 검사 순서: 키 형식(400) → (같은 키 재생) → 본문(400) → 그룹·멤버(404) → 종료(409) → 보낸 차(404) → 빈도(429).

### 2.6 무전(F16, 녹음형 PTT)
- `POST /groups/{groupId}/voice` (차량 JWT — 멤버 차량만) `multipart/form-data`: `audio`(파일, `Content-Type` ∈ `audio/mp4`(AAC-LC m4a 16 kHz 모노, 기본) · `audio/aac` · `audio/wav`(디버그 주입)), `durationMs`(정수) → 201 `VoiceUploadResponse { voiceId, eventId, durationMs, expiresAtMs }` + 그룹 이벤트 `VOICE_MESSAGE`(§5.4).
  - 검증: 크기 ≤ 512 KB(아니면 413 `VOICE_TOO_LARGE`), `durationMs` 1~16 000(아니면 400 `INVALID_VOICE`), mime 이 목록 밖이면 415 `UNSUPPORTED_VOICE_TYPE`, 같은 차 3초 안 두 번째 업로드 429 `VOICE_RATE_LIMITED`, 멤버 아님 404, 종료된 그룹 409 `GROUP_ENDED`.
  - 보관 상한(2026-11, C113): 같은 차 **최근 1시간 60건**, 그룹의 **만료 전 무전 합계 50 MB** — 넘으면 같은 429 `VOICE_RATE_LIMITED`(문구로 구분). 그룹 합계는 서로 다른 차의 동시 업로드만큼 조금 넘을 수 있다.
  - 재시도 안전(2026-11, 계약 3): 선택 헤더 `Idempotency-Key`(머리말) — 차량은 보내지 못한 무전의 다시 보내기·업로드 재시도에 그 무전의 고정 키(보관 ID 기반)를 보낸다. 같은 키의 재시도는 3초 규칙·종료와 무관하게 첫 201 응답(같은 `voiceId`·`eventId`)을 받고, 무전이 두 번 저장·재생되지 않는다. 키 형식이 틀리면 400 `VALIDATION_FAILED`.
  - 보관: 업로드 + 24시간(`expiresAtMs`), 그룹이 끝나면(`GROUP_ENDED`) 그 그룹 무전을 바로 지운다. 서버는 내용을 해석하지 않는다(음성 인식·변환 없음).
- `GET /groups/{groupId}/voice/{voiceId}` (차량 JWT — 멤버 차량, 또는 사용자 JWT — 멤버 차량 소유자) → 200 오디오 바이트(`Content-Type` = 올린 mime, `Cache-Control: private, max-age=86400`). 만료·삭제 404, 멤버 아님 404.

### 2.5 소속 규칙 (T14 확정)
- 차량 하나는 **활성 그룹 하나**에만 속한다(Redis `veh:{vid}:group` 이 문자열 하나인 이유). 다른 그룹을 만들거나(`POST /groups`) 다른 초대로 합류하면(`POST /groups/join`) 이전 그룹에서 자동으로 나간다(`MEMBER_LEFT` reason `SWITCHED_GROUP`, 방장 승계·그룹 종료 규칙 동일). 같은 그룹 재합류만 409 `ALREADY_MEMBER`.
- 서버는 합류·생성을 **차량 행(`FOR KEY SHARE`) → 차량 단위 advisory lock** → 그룹 행 잠금(ID 순) → 초대 행 잠금 순서로, 나가기를 advisory → 그룹 행 순서로 직렬화한다. 합류·생성은 잠금 뒤 차량을 다시 읽어 그 사이 커밋된 해제·소유권 이전이면 거부한다(합류 404 `NO_PAIRED_VEHICLE`, 생성 403) — 해제와 엇갈린 합류로 해제된 차가 멤버가 되지 않고, 재페어링과 엇갈려도 교착(500)이 나지 않는다(C108).
- **그룹 경로(2026-11, C032)**: 그룹 목적지는 생성 때 값으로 고정되지 않는다. 방장의 `ROUTE_CHANGED`(§5.2)를 받으면 서버가 그룹 목적지(`travel_group.dest_*`)와 경유지(`route_waypoints`)를 그 경로로 바꾼다 — 늦게 합류한 차의 `GROUP_JOINED.destination`·초대 미리보기·`GET /v1/vehicles/me/group`·스냅샷 `ARRIVED` 판정(§5.3)이 최신 값을 쓴다. 경유지가 있는 그룹에 합류한 차에는 `GROUP_JOINED` 를 보낸 **뒤** 그 차에만 `ROUTE_FOLLOW_PROPOSAL`(§5.5, 새 `proposalId`)을 다시 보낸다(선두는 같은 경로를 한 번만 보내므로). 경유지는 그룹이 끝나면 지운다.
  - **합류 직후 두 cmd 가 연달아 온다**(같은 cmd 토픽 QoS 1 — `GROUP_JOINED` 다음 곧바로 `ROUTE_FOLLOW_PROPOSAL`). 차량 앱의 합류 직후 로컬 제안("선두차 목적지 {이름}까지 안내할까요?", T30 — 경유지 없음)과 겹치지 않게 한다: 로컬 제안은 합류 뒤 3 s(`RouteSyncEngine.JOIN_PROPOSAL_DELAY_MS`, RC-3 — 2026-11-02 구현) 늦게 내고, 그 안에 이 그룹의 서버 제안이 오면 로컬 제안은 말하지 않고 서버 제안(목적지 + 경유지)으로 대체한다(서버 제안이 세션 처리보다 먼저 처리돼도 그룹·시각으로 가리고, 3 s 가 끝나는 순간 와도 로컬 제안이 서버 제안을 밀어내지 않는다 — 서버 제안에 ACK false 가 가지 않음). 서버 제안이 3 s 보다 늦게 오면 로컬 제안 뒤에 와서 그것을 대체한다(질문 2회, 로컬 제안은 ACK 없음). 경유지가 없는 그룹에는 서버가 보내지 않으므로 로컬 제안만 나온다.
- 멤버 색: 10색 팔레트(`#2E7DFF #FF6B2E #2EC27E #FFC42E #B22EFF #2EC9FF #FF2E7D #8A5A2E #0B8A8A #6B7280`)에서 그룹 안에 비어 있는 첫 색. 나간 차량의 색은 재사용된다.
- Redis 소속 캐시(`grp:{gid}:members`, `veh:{vid}:group`)와 차량 cmd 는 DB 커밋 뒤에만 갱신·발행한다. 진실은 PostgreSQL.

### 2.4 Member 객체
```json
{
  "vehicleId": "veh_01H...", "nickname": "아빠 차", "color": "#2E7DFF",
  "role": "LEADER" | "MEMBER", "joinedAt": 1793000000000,
  "brand": "KIA", "model": "EV9"
}
```

## 3. POI (poi) — P1(T50) 예정, **미구현**(서버 매핑 없음 — 호출하면 404)
`GET /poi/rest-areas?lat=..&lon=..&heading=..&radiusKm=50` → 진행 방향 앞쪽 휴게소 목록
```json
[{ "poiId": "ra_0012", "name": "OO휴게소(부산방향)", "lat": 0, "lon": 0, "distanceKm": 18.2, "hasEvCharger": true }]
```
데이터: 한국도로공사 휴게소 공공 API를 서버에서 캐시(MVP는 수도권~호남 주요 노선 JSON 시드).

---

## 4. MQTT 토픽

| 토픽 | 방향 | QoS | 설명 |
|---|---|---|---|
| `dh/v1/vehicle/{vid}/telemetry` | 차량 → 서버 | 0 | 위치·속도 1Hz, ETA 30s |
| `dh/v1/vehicle/{vid}/event` | 차량 → 서버 | 1 | 차량 이벤트 |
| `dh/v1/vehicle/{vid}/ack` | 차량 → 서버 | 1 | 제안 응답(경로 따라가기 수락 등) |
| `dh/v1/vehicle/{vid}/cmd` | 서버 → 차량 | 1 | 차량별 명령(합류, 경로 제안) |
| `dh/v1/group/{gid}/state` | 서버 → 그룹 | 0 | 그룹 스냅샷 2s 주기 (retained) |
| `dh/v1/group/{gid}/event` | 서버 → 그룹 | 1 | 그룹 이벤트 |
| `dh/v1/vehicle/{vid}/presence` | 브로커 LWT | 1 | 비정상 종료 시 `{"online":false}` |

- presence 진단(계약 변경 없음, docs/05 §3.4): debug 빌드의 `net_mqtt_rtt` 는 접속 중인 차가 **자기 presence 에 지금과 같은 `{"online":true}` 를 QoS 1·retain 없이** 보내 PUBACK 까지의 브로커 왕복을 잰다. 서버(`PresenceIngestor`)는 같은 online 값을 다시 적을 뿐이고 retained 값·다른 차·그룹에는 변화가 없다. 같은 값만 보내므로 이 토픽의 뜻은 그대로다.

ACL: 차량 `{vid}`는 자신의 `telemetry/event/ack/presence`에만 publish, 자신의 `cmd`와 소속 그룹의 `state/event`만 subscribe. 서버 클라이언트만 `cmd`·`group/*` publish. 서버는 `$share/server/dh/v1/vehicle/+/#` 공유 구독.

**구현(T15):**
- 인증: EMQX JWT(JWKS) + `verify_claims {sub = "${username}"}` → MQTT username 이 곧 신원이다. 차량은 `username = clientId = veh_…`, password = 차량 JWT. 서버 자신은 `username = clientId = srv_<ULID>`, password = 자기 키로 서명한 서버 토큰(`typ = server`, 12h, 재연결 때마다 새로 발급).
- 인가: EMQX 의 유일한 authorization 소스는 서버의 HTTP 훅 `POST /internal/mqtt/acl` `{ username, clientid, topic, action: publish|subscribe }` → `{ "result": "allow" | "deny" }`. `no_match = deny`. 판정(`mqtt/MqttAclService`): `veh_…` 는 publish 자기 `telemetry/event/ack/presence`, subscribe 자기 `cmd` + 현재 소속 그룹(Redis `veh:{vid}:group` → DB)의 `state/event`; 와일드카드·`$share`·`$SYS`·남의 토픽은 거부. `srv_…` 는 `dh/v1/` 아래 전부 + 공유 구독. 그 외 username 거부. 훅은 `X-Internal-Token`(`DH_INTERNAL_TOKEN`, 비우면 검사 안 함)으로 보호한다.
- 서버 수신: 공유 구독 → `TopicRouter` 가 토픽의 `{vid}` 와 봉투 `vid` 가 같을 때만 종류별 핸들러(`MqttInboundHandler`)에 전달한다(불일치·형식 오류는 버리고 WARN). 서버 발행 봉투의 `vid` 는 서버 clientId(`srv_…`), `seq` 는 토픽별 단조 증가. 서버 자신이 보낸 `cmd` 가 공유 구독(`…/+/#`, No Local 불가)으로 되돌아온 것은 해석·WARN 없이 버린다(C111 — 차량은 ACL 상 `cmd` 에 발행할 수 없다).
- ACL 의 그룹 구독 판정은 Redis 소속 캐시를 먼저 보고, '아니다'일 때 DB 로 한 번 더 확인해 캐시를 고친다(C101 read-repair). 인가는 SUBSCRIBE 시점 판정이며 나가기·해제 뒤 브로커에 남은 구독은 서버가 회수하지 않는다(알려진 한계, docs/10 R22).
- 그룹 STATE retained 스냅샷은 MQTT 5 **Message Expiry 30 s** 로 발행한다(2 s 마다 새로 나가므로 정상 동작은 같다) — 종료 때의 삭제를 놓쳐도(종료와 틱의 경합, 재연결 중, 서버 중단) 끝난 그룹의 좌표가 브로커에 남지 않는다. 종료 뒤에는 그 그룹을 발행 대상에서 빼고, 한 틱(3 s) 뒤 retained 를 한 번 더 지운다(C071·C109).
- cmd `GROUP_JOINED`/`GROUP_ENDED` 는 그룹 변경이 커밋된 뒤 `dh/v1/vehicle/{vid}/cmd` 에 QoS 1 로, 그룹 이벤트는 저장 커밋 뒤 `dh/v1/group/{gid}/event` 에 QoS 1 로 발행된다(봉투 type `EVENT`).

## 5. 메시지 스키마

공통 봉투(envelope):
```json
{ "v": 1, "type": "TELEMETRY", "vid": "veh_01H...", "ts": 1793000000000, "seq": 1024, "data": { } }
```

### 5.1 TELEMETRY (차량 → 서버, 1Hz)
```json
"data": {
  "lat": 37.4854472, "lon": 127.0363666, "heading": 182.5,
  "speedKph": 87.0,            // 위치 변화로 계산 or Car API
  "locSource": "NAVI" | "FUSED" | "ANDROID" | "FAKE",
  "gear": "D" | null,
  "eta": { "destTs": 1793003600000, "remainM": 182000, "remainS": 6600 } | null,   // 30s마다 또는 변화 시
  "energy": { "fuelPct": 34, "socPct": null } | null                              // 1%p 변화 시
}
```

### 5.2 차량 EVENT (차량 → 서버)
| type | data | 생성 조건 |
|---|---|---|
| `LOW_ENERGY` | `{ kind: "FUEL"\|"EV", pct }` | 15% 이하 최초 진입, 이후 5%p마다 |
| `HARD_BRAKE` | `{ decelMps2, speedBeforeKph }` | 브레이크 ON & 1초 내 감속 ≥ 4 m/s² (위치 기반 속도로 판정). 그룹 이벤트로 나갈 때 **출처보다 뒤에 있는 차만** 대상(§5.4 `audience`) |
| `HAZARD_ON` | `{}` | TurnSignal 상태에서 좌우 동시 점멸 판정 시 (지원 확인 후) |
| `AIRBAG_DEPLOYED` | `{}` | Safety airbag deployed 콜백 |
| `ROUTE_CHANGED` | `{ destination, waypoints:[...] }` | 선두차 목적지·경유지 변경 |
| `WAYPOINT_ARRIVED` | `{ name, lat, lon }` | NaviHelper `onWayPointArrived` |
| `DESTINATION_ARRIVED` | `{ name }` | NaviHelper `onDestinationArrived` |
| `STOPPED` / `RESUMED` | `{ durationS? }` | 속도 0 지속 60s / 재출발 |
| `PRESET_MESSAGE` | `{ presetId }` | 정차 중 프리셋 버튼 (T53 예정 — 서버는 아직 그룹 이벤트로 만들지 않는다) |

**서버 검증·정리(2026-11, C105)** — 차량이 보낸 값이 그룹 전원의 음성 문구에 들어가므로:
- `LOW_ENERGY.pct` 가 0~100 밖이면 버린다(기록하지 않음).
- `WAYPOINT_ARRIVED`·`DESTINATION_ARRIVED` 의 `name` 은 제어·서식 문자를 공백으로 바꾸고 공백을 접어 **40자**로 자른다(저장되는 payload 도 정리한 값, 비면 문구는 '경유지').
- `ROUTE_CHANGED` 는 좌표(위도 -90~90·경도 -180~180, NaN 불가)와 경유지 **10개 이하**를 확인하고 아니면 버린다. 장소 이름은 같은 정리 뒤 **60자**(비면 '목적지'/'경유지', 문구에는 40자), `poiId` 64자·`address` 200자를 넘으면 버린다.
- `ROUTE_CHANGED` 는 방장일 때만 그룹 경로(§2.5)를 바꾸고 멤버별 제안을 만든다.

**늦은 위험 이벤트(2026-11, 계약 4 — C017)**: 망이 끊긴 동안 차량 MQTT 클라이언트가 쌓았다가 재연결 뒤 보낸 `HARD_BRAKE`·`AIRBAG_DEPLOYED` 는 '지금' 경고가 아니다. 서버는 지연 = 서버 수신 시각 − 봉투 `ts` − (그 차의 시계 지연 기준값: 최근 10~20분 텔레메트리 '수신 − ts' 의 최솟값, **양수일 때만**)을 구해 **60 s 를 넘으면 기록만** 한다 — `speech` 없음, `audience: []`(§5.4 — 어느 화면·음성에도 안 나온다), 로그 `vehicle event late`. 늦은 이벤트는 중복 억제(120 s) 창을 잡지 않으므로 재연결 직후의 새 급제동은 평소대로 알린다. 기준값을 빼는 이유: 동기화 안 된 늦은 시계 차의 새 이벤트를 늦다고 오판하지 않게(빠른 시계는 빼지 않는다 — 안전 쪽).
**기록 시점의 소속 확인(C107)**: 차량 이벤트는 저장 트랜잭션 안에서 DB 의 현재 그룹 행을 `FOR SHARE` 로 잡고 진행 중·멤버인지 다시 본 뒤 기록한다 — 종료가 먼저 커밋됐거나 Redis 소속 캐시가 옛 값이면 기록하지 않는다. 기록이 실패하면 중복 억제 창을 푼다.

### 5.3 그룹 STATE (서버 → 그룹, 2s, retained)
```json
"data": {
  "groupId": "grp_01H...", "leaderVehicleId": "veh_A",
  "members": [{
    "vehicleId": "veh_A", "nickname": "아빠 차", "color": "#2E7DFF", "online": true,
    "lat": 0, "lon": 0, "heading": 0, "speedKph": 92, "ageMs": 820,
    "eta": { "destTs": 0, "remainM": 0 }, "energy": { "fuelPct": 34 },
    "distanceToLeaderM": 0, "status": "DRIVING" | "STOPPED" | "OFFLINE" | "ARRIVED"
  }]
}
```

### 5.4 그룹 EVENT (서버 → 그룹)
```json
"data": {
  "eventId": "evt_01H...", "type": "LOW_ENERGY", "sourceVehicleId": "veh_B",
  "severity": "INFO" | "WARN" | "ALERT",
  "speech": "엄마 차 연료가 12% 남았어요.",       // 서버가 생성한 음성 문구(한국어)
  "payload": { "kind": "FUEL", "pct": 12 }
}
```
```json
"data": {
  "eventId": "evt_01H...", "type": "HARD_BRAKE", "sourceVehicleId": "veh_B",
  "severity": "WARN",
  "speech": "앞쪽 둘째 차가 급제동했어요. 주의하세요.",
  "payload": { "decelMps2": 5.6, "speedBeforeKph": 72.0 },
  "audience": ["veh_C"]                           // 선택. 알릴 차량 — 없으면(null) 그룹 전체
}
```
**수신 대상 `audience` (선택, 2026-10-28 추가):** 그룹 이벤트는 그룹 토픽 하나로 모든 멤버에게 가지만, 이벤트마다 **알릴 차량**을 정할 수 있다.
- 필드 없음(null) = 그룹 전체(지금까지의 모든 이벤트와 같다). 문자열 배열 = 그 `vehicleId` 의 차만 음성·알림 카드·패널 소식 줄·피드에 띄운다. **빈 배열 `[]` = 아무도 알리지 않음** — 서버 기록(`group_event`·`GET /groups/{id}` 의 `recentEvents` 원문)으로만 남고, 차량 앱(음성·카드·패널·이벤트 피드·스트립)·companion(알림 벨) **어느 화면에도 나오지 않는다**. 급제동한 차 자신도 `audience` 에 없으므로 자기 피드에서 자기 급제동을 보지 않는다(문구 "앞쪽 ○○ 차가 급제동"이 뒤차용이라 — 내 차 감지는 차량 로그 `vehicle event HARD_BRAKE … published=` 로 확인). `DhJson` 규칙(`explicitNulls = false`)상 null 은 직렬화에서 빠지므로 옛 클라이언트·옛 메시지와 호환된다(모르는 키는 무시).
- 지금 채우는 종류는 **`HARD_BRAKE` 하나** — 문구가 "앞쪽 {nickname}이 급제동했어요"라 **급제동한 차보다 경로상 뒤에 있는 차**만 들어야 한다. 서버(`VehicleEventService`)가 그 순간의 스냅샷(`PresenceApi.snapshotOf`, §5.3 과 같은 판정)에서 `distanceToLeaderM` 이 출처보다 **큰** 멤버를 고른다 — 플로팅 패널 간격(상대 − 나, 양수 = "뒤")과 같은 기준이다. 출처는 제외, 같은 값은 뒤가 아니다. 선두가 밟으면 나머지 전원, 맨 뒤 차가 밟으면 `[]`.
- **가정:** 선두가 맨 앞이다(`distanceToLeaderM` 은 선두까지 직선 거리라 선두를 앞지른 차·굽은 길은 근사 — 패널 간격과 같은 한계). 출처나 어떤 멤버의 값을 모르면(위치 없음) 그 멤버는 **대상에 넣는다** — 경고를 놓치는 것보다 한 번 더 듣는 편이 낫다. 그룹 스냅샷이 없거나 명단(DB)을 못 읽으면 `audience` 없이(그룹 전체) 보내고 차량이 아래 폴백으로 다시 판정한다 — 스냅샷 읽기는 이벤트 저장 트랜잭션 **밖에서** 하므로 읽기가 실패해도 급제동은 저장·발행된다(같은 트랜잭션이면 PostgreSQL 이 뒤따르는 INSERT 를 거부한다, docs/06 §3.2). Redis 장애면 멤버 위치가 모두 '모름'이 되어 **출처를 뺀 전원**이 대상이 된다(null 아님).
- 받는 쪽(차량 앱·companion): `protocol.EventAudience.isForMe(event, myVehicleId, members)` 하나로 판정한다. ① `audience` 가 있으면 `myVehicleId ∈ audience` ② 없는데 `HARD_BRAKE` 면(옛 서버·디버그 주입) 자기 최신 스냅샷으로 "출처 `distanceToLeaderM` < 내 값"일 때만, 내 id·출처·스냅샷·거리 중 하나라도 모르면 대상 ③ 그 밖은 그룹 전체. 서버의 대상 고르기(`EventAudience.behind`)와 같은 비교를 쓴다.
- `GET /groups/{id}` 의 `recentEvents` 도 같은 `audience` 를 돌려준다(`group_event.audience` JSONB, docs/04 V5). 같은 이벤트를 다시 받는 경우(QoS1 재전송·재접속 시 브로커가 보관한 메시지)에도 본문이 같으므로 대상이 유지된다.
**STATE 판정 규칙 (presence, T24 구현):** 서버는 2초마다(`dh.presence.snapshot-interval-ms`) 활성 그룹마다 스냅샷을 **retained·QoS 0** 으로 발행하고, 그룹이 끝나면 빈 payload(retained) 로 지운다.
- `online`: `…/presence` 토픽 값(접속 시 `{"online":true}`, LWT `{"online":false}`)이 있으면 그 값, 없으면 "15초 안에 텔레메트리가 왔는가". 텔레메트리 수신도 online 으로 친다(retained presence 를 놓친 경우 대비).
- `ageMs`: 마지막 텔레메트리를 **서버가 받은 시각** 기준 경과(2026-11 C099 — 예전에는 봉투 `ts` 라 시계가 1분 넘게 늦은 차가 달리는 중에도 OFFLINE, 빠른 차는 끊긴 뒤에도 DRIVING 으로 보였다). 위치 없으면 null. `eta`·`energy` 는 차량이 마지막으로 보낸 값을 유지한다(변화 시에만 오므로).
- `status`: 위치 없음·`online=false`·`ageMs > 60s` → `OFFLINE`; **그룹의 최신 목적지**(§2.5) 반경 150 m 안 + 속도 < 3 km/h → `ARRIVED`; 속도 < 1 km/h 가 60초 이상 지속(정차 시작도 서버 수신 시각) → `STOPPED`; 그 외 `DRIVING`. (판정 기준은 `dh.presence.*`.)
- `distanceToLeaderM`: 선두차 자신은 0, 선두차·멤버 위치가 둘 다 있을 때 Haversine, 아니면 null.
- `GET /groups/{id}` 의 `snapshot` 도 같은 규칙으로 요청 시점에 만든다(활성 그룹만, 끝난 그룹은 null).

그룹 이벤트 봉투의 `type`은 공통값 **`EVENT`**(`MessageTypes.EVENT`)이고, 구체 종류는 `data.type`(문자열, `GroupEventType` 이름)으로 구분한다. STATE 봉투의 `type`은 `STATE`, ACK는 `ACK`, 차량 EVENT·CMD 봉투의 `type`은 각 표의 type 값을 그대로 쓴다.

서버 생성 그룹 이벤트 type: 위 차량 이벤트 전부(`PRESET_MESSAGE` 는 T53 예정) + `MEMBER_JOINED`, `MEMBER_LEFT`, `LEADER_CHANGED`, `PASSENGER_MESSAGE`, `VOICE_MESSAGE`, `GROUP_ENDED` — 그리고 **예정(P1 T50/T51, 아직 발행하지 않음)** `MEMBER_OFFLINE`, `REST_STOP_PROPOSAL`, `REST_STOP_VOTE_RESULT`, `DEVIATION`(지금 오프라인은 STATE `status = OFFLINE` 으로만 보인다)

T14 가 만드는 수명주기 이벤트의 payload:
| type | payload | speech |
|---|---|---|
| `MEMBER_JOINED` | `{ vehicleId, nickname, color, role }` | `{nickname}이 그룹에 들어왔어요.` |
| `MEMBER_LEFT` | `{ vehicleId, nickname, reason: LEFT\|SWITCHED_GROUP\|UNPAIRED\|OWNER_CHANGED, newLeaderVehicleId? }` — `UNPAIRED` = 페어링 해제(폰 `DELETE /me/vehicles/{id}`·차량 `DELETE /vehicles/me`, §1.3), `OWNER_CHANGED` = 다른 계정이 같은 차를 승인(§1.2, `nickname` 은 이전 별명). 값은 protocol `MemberLeftReason` | `{nickname}이 그룹에서 나갔어요.` (+ ` 이제 {new}이 선두예요.`) — 마지막 멤버면 null |
| `ROUTE_CHANGED` | `{ proposalId, destination, waypoints, targets }` (sourceVehicleId = 방장, 정리된 경로 — §5.2. 그룹이 끝나면 `destination`·`waypoints` 를 지운다) | **null**(피드·companion 은 라벨 '경로 변경'). 멤버가 듣는 말은 멤버별 cmd `ROUTE_FOLLOW_PROPOSAL` 의 `speech` 하나뿐 — 차량 앱은 문구가 있는 그룹 이벤트를 멤버 차에서 읽으므로 여기에 문구를 넣으면 경로 변경 하나를 두 문장으로 듣는다(RC-1) |
| `LEADER_CHANGED` | `{ leaderVehicleId, previousLeaderVehicleId }` | 위임: `이제 {nickname}이 선두예요.` / 이탈 승계: null(MEMBER_LEFT 문구에 포함) |
| `PASSENGER_MESSAGE` | `{ text }` (sourceVehicleId = 보낸 차) | `{nickname}에서 온 메시지. {text}` |
| `VOICE_MESSAGE` | `VoiceMessagePayload { voiceId, durationMs, mime, sizeBytes, expiresAtMs }` (sourceVehicleId = 보낸 차, `audience` 없음 = 그룹 전체, F16 §2.6) | `{nickname} 무전` — 받는 차는 이 짧은 알림 뒤 오디오를 재생한다(문구만 읽고 끝내지 않는다) |
| `GROUP_ENDED` | `{ groupId, reason: LEADER_ENDED\|ALL_LEFT }` (= cmd `GroupEnded`; `ALL_ARRIVED` 는 예약값 — 아직 발행하지 않음) | `방장이 그룹을 종료했어요.` / `그룹이 종료됐어요.` — 남은 멤버가 없으면 null |

### 5.5 CMD (서버 → 차량)
| type | data |
|---|---|
| `GROUP_JOINED` | `{ groupId, name, leaderVehicleId, destination, topics }` — `destination` 은 그룹의 최신 목적지(§2.5). `GET /v1/vehicles/me/group` 응답과 같은 모양 |
| `ROUTE_FOLLOW_PROPOSAL` | `{ proposalId, destination, waypoints, speech }` — 방장 `ROUTE_CHANGED` 커밋 뒤 방장 외 멤버마다, 그리고 경유지가 있는 그룹에 합류한 차에는 `GROUP_JOINED` 바로 뒤 그 차에만(§2.5 — 합류 직후 로컬 제안과 겹치지 않게) |
| `REST_STOP_PROPOSAL` | `{ proposalId, poi:{poiId,name,lat,lon,distanceKm}, reason, speech }` (P1 T50 예정 — 아직 보내지 않음) |
| `GROUP_ENDED` | `{ groupId, reason }` |
| `TOKEN_REVOKED` | `{}` — 페어링 해제·소유권 이전 커밋 뒤(§1.2·§1.3), retain 없음. 차량은 **봉투 `ts`(epoch ms, 서버 시계)를 초 단위로 내린 값 ≤ 지금 차량 토큰의 `iat`(초)** 이면 재페어링 전 MQTT 영속 세션(같은 clientId)에 남아 있던 것으로 보고 무시한다. 같은 초도 무시(`<=`)하므로 해제 직전 같은 초에 갱신·발급된 토큰은 지워지지 않고 남을 수 있다(그 토큰도 다음 갱신 때 401 로 정리된다). 300 s 넘게 오프라인이면 사라진다(§1.3 한계) |

### 5.6 ACK (차량 → 서버)
```json
"data": { "proposalId": "prp_01H...", "accepted": true }
```
서버는 `proposalId` 형식(`prp_` + ULID)이 아니거나, 모르는(없거나 15분이 지난) 제안이거나, 그 제안의 대상이 아닌 차의 ACK 는 버리고 로그만 남긴다(기록·Redis 키를 만들지 않음, C076·C110).

## 6. 규칙 엔진 기본값 (insight) — P1(T50/T51) 예정, **미구현**
아래 규칙은 아직 서버에 없다(이벤트·cmd 를 만들지 않는다). 지금 오프라인은 STATE `status = OFFLINE`(§5.3)으로만, 중복 억제는 차량 이벤트의 `evt:dedup` 120 s(정차·재출발 20 s, 그 사이 같은 종류는 기록하지 않음)로만 동작한다.

| 규칙 | 조건 | 결과 |
|---|---|---|
| 휴게소 제안 | 멤버 중 `LOW_ENERGY` 발생 OR 그룹 연속 주행 ≥ 120분 OR 프리셋 "쉬어가자" | 진행 방향 50km 내 첫 휴게소(EV 차량 포함 시 충전기 있는 곳 우선) → `REST_STOP_PROPOSAL` |
| 투표 | 제안 후 60s | 과반 수락 시 모든 차량에 `addWaypoint` 제안 |
| 이탈 감지 | 선두와 거리 ≥ 5km AND 선두 경로 폴리라인과의 거리 ≥ 1km 가 120s 지속 | `DEVIATION` (해당 차량+선두에 안내) |
| 오프라인 | 위치 미수신 ≥ 60s | `MEMBER_OFFLINE` |
| 중복 억제 | 같은 차량·같은 type 이벤트 120s 내 재발생 | 음성 생략(상태만 갱신) |
