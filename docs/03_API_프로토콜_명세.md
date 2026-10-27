# 동행 API · 프로토콜 명세 (v1)

- 공개 주소 설정값 **`DH_PUBLIC_BASE_URL`** (서버 환경 변수, 끝에 `/` 없음): 서버가 QR·딥링크·응답 URL을 만들 때 쓰는 공개 base URL. **고정 도메인을 쓰지 않는다.**
  - 로컬(에뮬레이터) `http://10.0.2.2:8080` · 로컬(실기기, 같은 Wi-Fi) `http://<PC-IP>:8080` · 클라우드 `https://<VM-IP>.sslip.io` (예: `https://1-2-3-4.sslip.io`, Caddy 자동 인증서)
- REST Base URL: `{DH_PUBLIC_BASE_URL}/v1` (로컬: `http://10.0.2.2:8080/v1` — 에뮬레이터 기준)
- MQTT 공개 주소 설정값 **`DH_MQTT_PUBLIC_URI`**: 로컬 `tcp://10.0.2.2:1883`, 클라우드 `ssl://<VM-IP>.sslip.io:8883`. MQTT 5.0
- QR·딥링크 URL은 항상 `{DH_PUBLIC_BASE_URL}` + 경로(`/pair?c=…`, `/j/{token}`)로 **서버가 생성**하고, companion 모듈은 호스트가 아니라 **경로로 종류를 판별**한다(07 문서 §5)
- 직렬화: JSON (UTF-8), 필드명 camelCase, 시간은 epoch milliseconds(`ts`), 좌표는 WGS84 소수점 7자리
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

`POST /auth/login` 은 `provider` 가 `dev` 일 때만 200 (그 외 400 `VALIDATION_FAILED`). `provider_sub` 는 소문자 이메일 — 대소문자가 달라도 같은 사용자다.

`POST /auth/refresh` `{ "refreshToken": "..." }` → 200 새 토큰 쌍. **회전**: 이전 refresh 는 즉시 폐기되고 재사용하면 401. 무효·폐기·만료는 모두 401 `INVALID_TOKEN`(구분하지 않음).
refresh 토큰은 불투명 문자열(랜덤 32바이트 Base62, 30일, 서버엔 SHA-256 해시만). 차량 refresh 토큰이면 `accessToken` 은 **차량 토큰**이고 `user` 는 소유자다(차량 앱도 같은 엔드포인트를 쓴다). 페어링이 해제된 차량의 refresh 는 거부.

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
승인 규칙: `car_info_id_hash` 가 처음이면 새 차량(호출자에게 활성 대표 차량이 없으면 `isPrimary = true`). 이미 있으면 **호출자에게 소유권 이전** — 닉네임 갱신, 해제 상태 복원, 이전 소유자가 쥔 차량 refresh 토큰 폐기(이미 발급된 차량 access token 은 만료까지 유효).

| 엔드포인트 | 상태 | 조건 |
|---|---|---|
| `POST /device/token` | 200 `DeviceTokenResponse` | APPROVED → 차량 토큰·refresh 발급 후 CONSUMED (한 번만) |
| | 404 `CODE_NOT_FOUND` | 없는 `deviceCode` |
| | 429 `SLOW_DOWN` | 마지막으로 받아 준 폴링 뒤 `interval`(3s) 안에 다시 폴링 (Redis `devcode:poll:{deviceCode}`, 15분 TTL) |
| | 410 `CODE_EXPIRED` | `expiresIn`(600s) 지남, 또는 승인 뒤 폰에서 그 차량이 해제됨 |
| | 428 `AUTHORIZATION_PENDING` | 아직 승인 전 |
| | 409 `ALREADY_APPROVED` | 이미 토큰을 받아 간 코드(CONSUMED) |
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
`DELETE /me/vehicles/{vehicleId}` → 204 페어링 해제: `vehicle.revoked_at` 기록 + 그 차량의 refresh 토큰 폐기. 이미 발급된 차량 access token 은 만료(24h)까지 유효하다(T15 의 `TOKEN_REVOKED` cmd 로 보완). 같은 차를 다시 페어링하면 같은 `vehicleId` 가 복원된다. 없으면 404.

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
검증: `name` 1~30자(trim), `maxMembers` 2~10, `destination.name` 1~60자, `lat` -90~90, `lon` -180~180 (그 외 400 `VALIDATION_FAILED`). 토큰의 차량이 페어링 해제 상태면 403 `FORBIDDEN`.
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
- `POST /groups/{groupId}/leave` (차량 또는 사용자) → 204. 본문 선택 `{ vehicleId? }`: 차량 JWT 는 자기 자신(본문 무시), 사용자 JWT 는 `vehicleId`(내 차) 또는 생략 시 이 그룹의 내 차 전부. 멤버가 아니면 404(`NOT_FOUND` / 사용자는 `NO_PAIRED_VEHICLE`), 종료된 그룹 409 `GROUP_ENDED`. 그룹 이벤트 `MEMBER_LEFT`(payload `reason: LEFT | SWITCHED_GROUP`, 방장 승계 시 `newLeaderVehicleId`). **차량 앱은 `sourceVehicleId` 가 자기 자신인 `MEMBER_LEFT` 를 받으면 GROUP_ENDED 와 똑같이 로컬 세션을 지우고 그룹 토픽 구독을 해제한다**(T30 — 폰에서 내보낸 차가 그룹을 계속 보고 있지 않도록). 방장이 나가면 가장 먼저 합류한 남은 멤버가 방장(`LEADER_CHANGED`), 마지막 멤버가 나가면 그룹 종료(reason `ALL_LEFT`).
- `POST /groups/{groupId}/end` (방장) → 204. 그룹 `ENDED`, 초대 만료, Redis 소속·위치 키 삭제(docs/04 §2), 그룹 이벤트 `GROUP_ENDED`(reason `LEADER_ENDED`), 멤버 차량에 cmd `GROUP_ENDED`(T15). 방장 아니면 403, 이미 종료 409 `GROUP_ENDED`. 멤버 행·이벤트 로그는 남는다(이벤트는 24h 뒤 배치 삭제).
- `POST /groups/{groupId}/leader` `{ vehicleId }` (방장) → 200 `GroupDetailResponse`. 대상이 현재 멤버가 아니면 404, 이미 방장이면 400. 그룹 이벤트 `LEADER_CHANGED` `{ leaderVehicleId, previousLeaderVehicleId }`.
- `POST /groups/{groupId}/messages` (사용자 JWT — 멤버 차량 소유자) `{ "text": "앞에 휴게소 들르자", "fromVehicleId": "veh_..." }` → 201 `GroupEvent`(type `PASSENGER_MESSAGE`, `speech` = `{닉네임}에서 온 메시지. {text}`, payload `{ text }`). `fromVehicleId` 생략 시 이 그룹의 내 첫 차, 내 멤버 차가 아니면 404 `NO_PAIRED_VEHICLE`. `text` 는 공백 정리 후 1~60자(아니면 400 `INVALID_MESSAGE`). 멤버가 아니면 404, 종료된 그룹 409 `GROUP_ENDED`.

### 2.5 소속 규칙 (T14 확정)
- 차량 하나는 **활성 그룹 하나**에만 속한다(Redis `veh:{vid}:group` 이 문자열 하나인 이유). 다른 그룹을 만들거나(`POST /groups`) 다른 초대로 합류하면(`POST /groups/join`) 이전 그룹에서 자동으로 나간다(`MEMBER_LEFT` reason `SWITCHED_GROUP`, 방장 승계·그룹 종료 규칙 동일). 같은 그룹 재합류만 409 `ALREADY_MEMBER`.
- 서버는 합류·생성·나가기를 차량 단위 advisory lock → 그룹 행 잠금(ID 순) → 초대 행 잠금 순서로 직렬화한다.
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

## 3. POI (poi)
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

ACL: 차량 `{vid}`는 자신의 `telemetry/event/ack/presence`에만 publish, 자신의 `cmd`와 소속 그룹의 `state/event`만 subscribe. 서버 클라이언트만 `cmd`·`group/*` publish. 서버는 `$share/server/dh/v1/vehicle/+/#` 공유 구독.

**구현(T15):**
- 인증: EMQX JWT(JWKS) + `verify_claims {sub = "${username}"}` → MQTT username 이 곧 신원이다. 차량은 `username = clientId = veh_…`, password = 차량 JWT. 서버 자신은 `username = clientId = srv_<ULID>`, password = 자기 키로 서명한 서버 토큰(`typ = server`, 12h, 재연결 때마다 새로 발급).
- 인가: EMQX 의 유일한 authorization 소스는 서버의 HTTP 훅 `POST /internal/mqtt/acl` `{ username, clientid, topic, action: publish|subscribe }` → `{ "result": "allow" | "deny" }`. `no_match = deny`. 판정(`mqtt/MqttAclService`): `veh_…` 는 publish 자기 `telemetry/event/ack/presence`, subscribe 자기 `cmd` + 현재 소속 그룹(Redis `veh:{vid}:group` → DB)의 `state/event`; 와일드카드·`$share`·`$SYS`·남의 토픽은 거부. `srv_…` 는 `dh/v1/` 아래 전부 + 공유 구독. 그 외 username 거부. 훅은 `X-Internal-Token`(`DH_INTERNAL_TOKEN`, 비우면 검사 안 함)으로 보호한다.
- 서버 수신: 공유 구독 → `TopicRouter` 가 토픽의 `{vid}` 와 봉투 `vid` 가 같을 때만 종류별 핸들러(`MqttInboundHandler`)에 전달한다(불일치·형식 오류는 버리고 WARN). 서버 발행 봉투의 `vid` 는 서버 clientId(`srv_…`), `seq` 는 토픽별 단조 증가.
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
| `HARD_BRAKE` | `{ decelMps2, speedBeforeKph }` | 브레이크 ON & 1초 내 감속 ≥ 4 m/s² (위치 기반 속도로 판정) |
| `HAZARD_ON` | `{}` | TurnSignal 상태에서 좌우 동시 점멸 판정 시 (지원 확인 후) |
| `AIRBAG_DEPLOYED` | `{}` | Safety airbag deployed 콜백 |
| `ROUTE_CHANGED` | `{ destination, waypoints:[...] }` | 선두차 목적지·경유지 변경 |
| `WAYPOINT_ARRIVED` | `{ name, lat, lon }` | NaviHelper `onWayPointArrived` |
| `DESTINATION_ARRIVED` | `{ name }` | NaviHelper `onDestinationArrived` |
| `STOPPED` / `RESUMED` | `{ durationS? }` | 속도 0 지속 60s / 재출발 |
| `PRESET_MESSAGE` | `{ presetId }` | 정차 중 프리셋 버튼 |

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
**STATE 판정 규칙 (presence, T24 구현):** 서버는 2초마다(`dh.presence.snapshot-interval-ms`) 활성 그룹마다 스냅샷을 **retained·QoS 0** 으로 발행하고, 그룹이 끝나면 빈 payload(retained) 로 지운다.
- `online`: `…/presence` 토픽 값(접속 시 `{"online":true}`, LWT `{"online":false}`)이 있으면 그 값, 없으면 "15초 안에 텔레메트리가 왔는가". 텔레메트리 수신도 online 으로 친다(retained presence 를 놓친 경우 대비).
- `ageMs`: 마지막 텔레메트리 봉투 `ts` 기준 경과. 위치 없으면 null. `eta`·`energy` 는 차량이 마지막으로 보낸 값을 유지한다(변화 시에만 오므로).
- `status`: 위치 없음·`online=false`·`ageMs > 60s` → `OFFLINE`; 목적지 반경 150 m 안 + 속도 < 3 km/h → `ARRIVED`; 속도 < 1 km/h 가 60초 이상 지속 → `STOPPED`; 그 외 `DRIVING`. (판정 기준은 `dh.presence.*`.)
- `distanceToLeaderM`: 선두차 자신은 0, 선두차·멤버 위치가 둘 다 있을 때 Haversine, 아니면 null.
- `GET /groups/{id}` 의 `snapshot` 도 같은 규칙으로 요청 시점에 만든다(활성 그룹만, 끝난 그룹은 null).

그룹 이벤트 봉투의 `type`은 공통값 **`EVENT`**(`MessageTypes.EVENT`)이고, 구체 종류는 `data.type`(문자열, `GroupEventType` 이름)으로 구분한다. STATE 봉투의 `type`은 `STATE`, ACK는 `ACK`, 차량 EVENT·CMD 봉투의 `type`은 각 표의 type 값을 그대로 쓴다.

서버 생성 그룹 이벤트 type: 위 차량 이벤트 전부 + `MEMBER_JOINED`, `MEMBER_LEFT`, `LEADER_CHANGED`, `MEMBER_OFFLINE`, `REST_STOP_PROPOSAL`, `REST_STOP_VOTE_RESULT`, `DEVIATION`, `PASSENGER_MESSAGE`, `GROUP_ENDED`

T14 가 만드는 수명주기 이벤트의 payload:
| type | payload | speech |
|---|---|---|
| `MEMBER_JOINED` | `{ vehicleId, nickname, color, role }` | `{nickname}이 그룹에 들어왔어요.` |
| `MEMBER_LEFT` | `{ vehicleId, nickname, reason: LEFT\|SWITCHED_GROUP, newLeaderVehicleId? }` | `{nickname}이 그룹에서 나갔어요.` (+ ` 이제 {new}이 선두예요.`) — 마지막 멤버면 null |
| `LEADER_CHANGED` | `{ leaderVehicleId, previousLeaderVehicleId }` | 위임: `이제 {nickname}이 선두예요.` / 이탈 승계: null(MEMBER_LEFT 문구에 포함) |
| `PASSENGER_MESSAGE` | `{ text }` (sourceVehicleId = 보낸 차) | `{nickname}에서 온 메시지. {text}` |
| `GROUP_ENDED` | `{ groupId, reason: LEADER_ENDED\|ALL_LEFT\|ALL_ARRIVED }` (= cmd `GroupEnded`) | `방장이 그룹을 종료했어요.` / `그룹이 종료됐어요.` — 남은 멤버가 없으면 null |

### 5.5 CMD (서버 → 차량)
| type | data |
|---|---|
| `GROUP_JOINED` | `{ groupId, name, leaderVehicleId, destination, topics }` |
| `ROUTE_FOLLOW_PROPOSAL` | `{ proposalId, destination, waypoints, speech }` |
| `REST_STOP_PROPOSAL` | `{ proposalId, poi:{poiId,name,lat,lon,distanceKm}, reason, speech }` |
| `GROUP_ENDED` | `{ groupId, reason }` |
| `TOKEN_REVOKED` | `{}` |

### 5.6 ACK (차량 → 서버)
```json
"data": { "proposalId": "prp_01H...", "accepted": true }
```

## 6. 규칙 엔진 기본값 (insight)

| 규칙 | 조건 | 결과 |
|---|---|---|
| 휴게소 제안 | 멤버 중 `LOW_ENERGY` 발생 OR 그룹 연속 주행 ≥ 120분 OR 프리셋 "쉬어가자" | 진행 방향 50km 내 첫 휴게소(EV 차량 포함 시 충전기 있는 곳 우선) → `REST_STOP_PROPOSAL` |
| 투표 | 제안 후 60s | 과반 수락 시 모든 차량에 `addWaypoint` 제안 |
| 이탈 감지 | 선두와 거리 ≥ 5km AND 선두 경로 폴리라인과의 거리 ≥ 1km 가 120s 지속 | `DEVIATION` (해당 차량+선두에 안내) |
| 오프라인 | 위치 미수신 ≥ 60s | `MEMBER_OFFLINE` |
| 중복 억제 | 같은 차량·같은 type 이벤트 120s 내 재발생 | 음성 생략(상태만 갱신) |
