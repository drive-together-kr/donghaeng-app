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
`POST /groups/{groupId}/invites` → 초대 토큰 재발급 (만료 시)

### 2.2 그룹 합류 (공식 앱의 companion 모듈이 QR 스캔 후 — `joinGroup`)
`POST /groups/join` (사용자 JWT)
```json
// req
{ "inviteToken": "inv_...", "vehicleId": "veh_01H..." }   // vehicleId 생략 시 대표 차량
// res 200
{ "groupId": "grp_01H...", "name": "추석 귀성길", "members": [ ...Member ] }
```
오류: `INVITE_EXPIRED`, `GROUP_FULL`, `NO_PAIRED_VEHICLE`, `ALREADY_MEMBER`

### 2.3 조회·관리
- `GET /groups/{groupId}` → Group + members + 최신 스냅샷
- `GET /me/groups?active=true`
- `POST /groups/{groupId}/leave` (차량 또는 사용자)
- `POST /groups/{groupId}/end` (방장) → 그룹 종료, 위치 데이터 삭제
- `POST /groups/{groupId}/leader` `{ vehicleId }` (방장 위임)
- `POST /groups/{groupId}/messages` (폰 동승자) `{ "text": "앞에 휴게소 들르자", "fromVehicleId": "veh_..." }` → 그룹 이벤트 `PASSENGER_MESSAGE`

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
그룹 이벤트 봉투의 `type`은 공통값 **`EVENT`**(`MessageTypes.EVENT`)이고, 구체 종류는 `data.type`(문자열, `GroupEventType` 이름)으로 구분한다. STATE 봉투의 `type`은 `STATE`, ACK는 `ACK`, 차량 EVENT·CMD 봉투의 `type`은 각 표의 type 값을 그대로 쓴다.

서버 생성 그룹 이벤트 type: 위 차량 이벤트 전부 + `MEMBER_JOINED`, `MEMBER_LEFT`, `MEMBER_OFFLINE`, `REST_STOP_PROPOSAL`, `REST_STOP_VOTE_RESULT`, `DEVIATION`, `PASSENGER_MESSAGE`, `GROUP_ENDED`

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
