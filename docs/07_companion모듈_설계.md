# 동행 companion 모듈 설계 (공식 앱 내장 SDK)

> 2026-10-27 설계 변경. 폰 쪽은 별도 Flutter 앱이 아니라 **현대 블루링크·마이현대, 기아 커넥트, 제네시스 커넥티드 서비스 앱에 내장되는 "동행 모듈"**이다.
> 사용자는 이미 쓰는 공식 앱 안의 "동행" 메뉴에서 그룹 QR 을 찍고, 계정·차량 정보는 공식 앱 것을 그대로 쓴다.

## 1. 위치와 역할
차량에는 카메라·키보드가 없으므로 **"사람 확인이 필요한 일"**을 폰이 맡는다. 다만 그 폰 기능은 공식 앱의 일부다.

| 누가 | 제공하는 것 |
|---|---|
| 호스트 앱(공식 앱) | 계정(OAuth 2.0 토큰), 계정에 연결된 차량 목록, QR 스캐너 화면, 푸시 채널, 브랜드 UI |
| 동행 모듈(`companion/sdk`) | 동행 서버 연동(그룹 합류·메시지·그룹 상태·무전 오디오 받기), 공개 API 6개, QR 문자열 해석 규칙, 대체 수단인 차량 페어링 승인 |
| 시연용 호스트 앱(`companion/demo-host`) | 공식 앱을 흉내 낸 데모 앱 — dev 로그인, 내 차 목록, QR 스캔 버튼 → sdk 호출 |

모듈에는 **자체 로그인 화면·계정 저장소가 없다.** 토큰은 호스트 앱이 `TokenProvider`로 넘긴다.

## 2. 디렉터리
```
companion/
 ├─ sdk/         Android Kotlin 라이브러리(AAR). minSdk 28, Kotlin explicitApi, protocol/ 재사용
 │   └─ src/main/kotlin/app/donghaeng/companion/sdk/
 │       ├─ DongHaeng.kt            공개 진입점(object) — 초기화 검사·QR 해석 후 internal 전송 계층에 위임
 │       ├─ DongHaengConfig.kt      DongHaengConfig · AccountProvider · TokenProvider
 │       ├─ DongHaengException.kt   오류 봉인 클래스
 │       ├─ Models.kt               MyVehicle · GroupMember · MemberRole · GroupJoinResult · PairResult · GroupSnapshot
 │       └─ internal/
 │           ├─ DongHaengApi.kt     전송 추상화
 │           ├─ FakeDongHaengApi.kt 메모리 구현 — T13 전까지 DEV 가 임시로 사용, 이후 테스트·데모 전용(§4.2)
 │           ├─ HttpDongHaengApi.kt REST 구현(T13): /v1/auth/exchange 로 동행 JWT 교환·캐시 → Bearer, 15s 폴링 → 푸시
 │           ├─ VoiceInbox.kt       F16 무전 수신: VOICE_MESSAGE 오디오를 미리 받아 캐시 + payload 상태 키(§3.1)
 │           └─ QrParser.kt         경로 기준 QR 판별 (§5)
 └─ demo-host/   시연용 호스트 앱(application) — 기아 앱 5탭 재현(§6). Compose, osmdroid 지도, CameraX + ML Kit 바코드 스캔
     └─ src/main/kotlin/app/donghaeng/companion/demo/
         ├─ ui/theme, ui/components   디자인 토큰·공용 조각(KiaTopBar·KiaBottomBar·KiaCard·CarArt·OsmMap)
         ├─ feature/{login,home,map,control,store,mycar}   정적 재현 화면(누르면 "시연용 화면입니다")
         └─ donghaeng/                공식 앱에 붙일 부분 전부: DongHaengGateway(Sdk/Fake)·DongHaengStore·A~G 화면
```
`internal/` 을 `api/`·`qr/`·`auth/` 하위 패키지로 나누는 것은 T13(토큰 교환·JWT 캐시 컴포넌트 추가) 때의 리팩터링 메모다 — 공개 표면(§3)은 바뀌지 않는다.
차량 앱·서버와 같은 AGP 8.8.x / Gradle 8.10.x / Kotlin 2.0.x / JDK 17. 메시지 스키마는 `protocol/`만 쓴다(CLAUDE.md 규칙 7).

## 3. 공개 API (최소 6개 — 이 외는 전부 `internal`)
```kotlin
public object DongHaeng {
    public fun init(config: DongHaengConfig, tokenProvider: TokenProvider)
    public suspend fun listMyVehicles(): List<MyVehicle>                                  // 계정에 연결된 내 차
    public suspend fun joinGroup(qrPayload: String, vehicleId: String? = null): GroupJoinResult  // 이미 이 그룹이면 지금 그룹(멱등)
    public suspend fun pairVehicle(qrPayload: String, nickname: String): PairResult       // 대체 수단 (§4.3)
    public suspend fun sendPassengerMessage(
        groupId: String, text: String,
        fromVehicleId: String? = null,     // 동승자가 탄 내 차(2026-11 추가)
        idempotencyKey: String? = null,    // 사용자 동작 한 번의 키 → 헤더 Idempotency-Key (2026-11 추가)
    )
    public fun observeGroup(groupId: String): Flow<GroupSnapshot>                         // 그룹 종료 시 정상 종료
}
public data class DongHaengConfig(val publicBaseUrl: String, val provider: AccountProvider, val appVersion: String)
public enum class AccountProvider { BLUELINK, MYHYUNDAI, KIA_CONNECT, GENESIS_CONNECTED, DEV }
public fun interface TokenProvider { public suspend fun accessToken(): String }         // 호스트 앱의 OAuth 2.0 access token
```

| API | 하는 일 | 서버 호출 (docs/03) |
|---|---|---|
| `init` | 서버 공개 주소(`DH_PUBLIC_BASE_URL`)·계정 제공자·토큰 공급자 등록 | 없음 |
| `listMyVehicles` | 내 차 목록 | `GET /v1/me/vehicles` |
| `joinGroup` | 그룹 QR(`{base}/j/{token}`) → 내 차를 그룹에 합류. **멱등**: 그 차가 이미 이 그룹이면(서버 409 `ALREADY_MEMBER`) 모듈이 `GET /v1/me/groups?active=true` 로 그 차의 그룹을 찾아 `GET /v1/groups/{id}` 로 멤버를 채워 성공으로 돌려준다(못 찾으면 409 그대로) — 합류 응답 유실 뒤 재시도·앱 재시작 뒤 같은 QR 재스캔이 그룹으로 돌아온다 | `POST /v1/groups/join` (+ 409 때 `GET /v1/me/vehicles`(생략 시 대표 차)·`/me/groups`·`/groups/{id}`) |
| `pairVehicle` | 페어링 QR(`{base}/pair?c=…`) 승인 — 계정에 차가 없을 때만 | `POST /v1/device/approve` |
| `sendPassengerMessage` | 동승자 메시지 → 그룹 차량에서 "{보낸 차}에서 온 메시지"로 읽힘. `fromVehicleId` = 동승자가 탄 내 차(생략 시 이 프로세스에서 그 그룹에 마지막으로 합류할 때 고른 차, 차를 생략하고 합류했거나 기억이 없으면 서버 기본 = 그룹의 내 첫 차 — 한 계정에 차가 둘이면 엉뚱한 차 이름이 된다). `idempotencyKey` = 사용자 동작 한 번(같은 메시지 보내기)의 키 `[A-Za-z0-9_-]{8,64}` — 응답이 사라져 다시 보낼 때 같은 키면 서버가 한 번만 처리(생략 시 호출마다 새 키, 형식이 틀리면 `IllegalArgumentException`) | `POST /v1/groups/{id}/messages` + 헤더 `Idempotency-Key`(docs/03) |
| `observeGroup` | 그룹 스냅샷·이벤트 스트림(`protocol.MemberState`·`GroupEvent` 재사용). 발행 주기는 전송 구현이 정한다(T13 전 DEV 메모리 Fake 는 2s 틱). 무전(`VOICE_MESSAGE`)은 오디오를 모듈이 미리 받아 payload 에 `localAudioStatus`·`localAudioPath` 를 붙여 내보낸다(§3.1). **그룹 상태 `ENDED` 면 그 스냅샷을 내보낸 뒤 흐름이 정상 종료**(더 폴링하지 않음). 오류는 흐름 예외 — **일시 오류 뒤 다시 구독하는 것은 호스트 몫**(§3.2) | `GET /v1/groups/{id}` 폴링 15s → 푸시(P1), 무전 `GET /v1/groups/{id}/voice/{voiceId}` |

반환 타입: `MyVehicle(vehicleId, nickname, brand, model, isPrimary)`, `GroupJoinResult(groupId, name, members)`, `GroupMember(vehicleId, nickname, color, role: MemberRole)`, `MemberRole { LEADER, MEMBER }`, `PairResult(vehicleId, nickname)`, `GroupSnapshot(groupId, members: List<protocol.MemberState>, events: List<protocol.GroupEvent>, leaderVehicleId: String? = null)` — `leaderVehicleId` 는 매 폴링의 서버 값(`GroupDetailResponse.leaderVehicleId`, 위임·승계 반영, 2026-11 추가). 방장 표시는 합류 결과의 역할이 아니라 이 값을 쓴다.
오류: `DongHaengException` 봉인 클래스 — `NotInitialized`, `Unauthorized`, `InvalidQr(reason: 사용자에게 그대로 보여 줄 한 문장)`, `OtherServer(host)`, `Api(code, message)`. 호스트 `TokenProvider` 가 던진 예외도 이 안으로 옮긴다(`IOException` → `Api("NETWORK")`, 그 밖 → `Unauthorized`). 호스트 프로그래밍 오류 — `init` 의 설정 오류(`publicBaseUrl` 이 비었거나 `http://`·`https://` 가 아님)와 `sendPassengerMessage` 의 키 형식 — 만 `IllegalArgumentException`.

### 3.2 호스트가 할 일 — 관찰 재구독·토큰 갱신·진행 중 그룹 (2026-11, demo-host 가 예)
- **관찰 재구독(`GroupObservation`)**: 모듈은 오류를 숨기지 않고 흐름 예외로 끝낸다(호스트가 끊김을 화면에 보이게). 호스트는 일시 오류(`NETWORK`·`HTTP_5xx`·`HTTP_429`·`BAD_RESPONSE`·`INTERNAL_ERROR`)면 백오프(2 s → 4 → 8 → 16 → 30 s 상한, 스냅샷을 받으면 처음부터)로 `observeGroup` 을 다시 부르고 그동안 '다시 연결하는 중 · 마지막 갱신 n분 전 · [다시 시도]'를 보인다. `NOT_FOUND`(내 차가 나감) → '그룹에서 나갔어요', `Unauthorized` → '다시 로그인', 흐름 정상 종료나 `GROUP_ENDED` 이벤트 → '그룹이 끝났어요'(메시지 막기, [기록으로 옮기기]), 그 밖(403 등) → '연결이 끊겼어요 [다시 시도]'. 예전 demo-host 는 오류 한 번에 폴링이 영구히 멈추고 화면은 '동행 중'으로 남았다.
- **호스트 토큰 갱신(`HostTokenCache`)**: 모듈은 동행 JWT 를 교환할 때만 `TokenProvider` 를 부른다. demo-host 의 dev 로그인 토큰은 1시간이라, 부를 때마다 JWT `exp` 를 보고 만료 2분 안이면 같은 이메일로 다시 로그인해 바꾼다(공식 앱이면 자기 OAuth 갱신). 실패 시 아직 유효하면 그 토큰, 만료됐고 일시 오류(연결 실패·타임아웃, 서버 5xx — 재배포 중 Caddy 502 등, 429 요청 제한 — `DevAuthException.status`)면 `IOException`(→ 모듈 `NETWORK`, 백오프 재구독 대상), 서버 거부(403·400 등)면 빈 문자열(→ `Unauthorized`). 예전에는 5xx·429 도 거부로 보아 서버가 살아나도 '다시 로그인' 안내에서 멈췄다. 예전에는 로그인 약 2시간 뒤 두 번째 교환이 만료 토큰으로 401 → 모든 호출이 멈췄다.
- **진행 중 그룹 복원**: demo-host 는 진행 중 그룹(groupId·이름·내 차·목적지·방장)을 앱 전용 SharedPreferences 에 두고, **같은 계정(userId)** 으로 다시 로그인하면 관찰을 이어 간다(다른 계정이면 지운다, 그룹이 끝나거나 '그룹 보기 끝내기'면 지운다). 프로세스가 죽은 뒤 복원된 화면은 로그인으로 되돌린다(`Routes.needsLoginRedirect`), 로그인 전 게이트웨이는 모든 호출이 `NotInitialized`(예전 기본값 FakeGateway 는 복원된 화면에서 가짜 그룹을 실데이터처럼 보였다).

### 3.1 무전 수신 (F16, 2026-10-28)
차량이 보낸 녹음형 무전(`VOICE_MESSAGE`, docs/03 §2.6·§5.4)을 폰(공식 앱의 동행 메뉴)에서도 듣는다. **공개 API 는 늘리지 않는다** — 무전은 `observeGroup` 의 `GroupSnapshot.events` 로 다른 이벤트와 똑같이 나간다(`GroupSnapshot` KDoc 에 규칙을 적었다).

**왜 모듈이 받아 두나:** `GET /groups/{id}/voice/{voiceId}` 는 동행 JWT(멤버 차량 소유자의 사용자 토큰)가 필요한데, 호스트 앱은 공식 계정 토큰만 갖고 동행 JWT 는 모듈 안에만 있다(§4.1). 그래서 모듈(`internal/VoiceInbox`)이 폴링 결과에서 무전을 보면 같은 토큰으로 오디오를 내려받아 앱 전용 캐시에 두고, 그 이벤트의 payload 에 두 키를 **덧붙인다**(원래 키는 그대로 — `DhJson.payloadAs<VoiceMessagePayload>` 해석이 그대로 된다).

| payload 키 | 값 |
|---|---|
| `voiceId`·`durationMs`·`mime`·`sizeBytes`·`expiresAtMs` | 서버 원문(`protocol.VoiceMessagePayload`). `sourceVehicleId` = 보낸 차, `speech` = "{닉네임} 무전" |
| `localAudioStatus` (모듈) | `READY` 재생 가능 · `PENDING` 받는 중(다음 스냅샷에서 바뀐다) · `EXPIRED` 보관 끝(만료·404·그룹 종료) · `FAILED` 받을 수 없음(형식 오류·1 MB 초과·403 등·일시 오류 3회) |
| `localAudioPath` (모듈) | `READY` 일 때만 — 내려받은 파일의 절대 경로(`MediaPlayer.setDataSource(path)`) |

- **저장:** `{java.io.tmpdir}/donghaeng-voice/{groupId}/{voiceId}.{m4a|aac|wav}`. 안드로이드는 앱 시작 때 `java.io.tmpdir` 를 앱 캐시 디렉터리(`Context.getCacheDir()`)로 맞춰 두므로 모듈이 Context 없이도 앱 전용 저장소에 쓴다(다른 앱은 못 읽는다). `.part` 로 쓰고 이름을 바꾼다. 프로세스가 다시 떠도 파일이 있으면 다시 받지 않는다. 권한은 추가하지 않는다.
- **스레드:** 캐시의 파일 작업(쓰기·이름 바꾸기·목록·삭제)과 오디오 내려받기는 호출(수집) 스레드와 무관하게 `Dispatchers.IO` 에서 돈다 — 호스트가 `observeGroup` 을 메인(`viewModelScope` 등)에서 수집해도 메인에서 디스크를 건드리지 않는다(StrictMode `detectDiskWrites` 안전).
- **만료(서버 시계):** 응답 `Date` 헤더로 서버 시계 차이를 맞춰 `expiresAtMs`(업로드 + 24시간)가 지나면 요청 없이 `EXPIRED` + 그 캐시 경로의 파일 삭제(이전 프로세스가 받아 둔 파일 포함) — 폰 시계가 틀려도 서버 보관 기간과 어긋나지 않는다. 한 번 본 무전은 `recentEvents`(20건) 밖으로 밀려나도 폴링마다 만료를 확인해 지운다. 404·410 도 `EXPIRED`(서버: 만료·삭제 404). 그룹 상태가 `ENDED`(`GET /groups/{id}` 의 `status` — 종료 시점 멤버는 계속 볼 수 있다, docs/03 §2.3)이거나 이벤트에 `GROUP_ENDED` 가 보이면 그 그룹 캐시 폴더를 바로 지운다(서버가 그룹 종료 때 무전을 지우는 것과 같은 원칙). 나간 뒤 그룹이 404 `NOT_FOUND` 면(더 볼 수 없음) 그 그룹 캐시도 지우고 예외는 그대로 흐름으로 전달한다.
- **부하:** 폴링(15 s)당 최신 3건까지 받고 나머지는 `PENDING` 으로 다음 폴링에. 일시 오류(네트워크·5xx·429·401 재교환 실패)는 3번까지, 그 밖의 4xx·크기 초과는 바로 `FAILED`. 응답 상한 1 MB(서버 상한 512 KB). 전체 30개를 넘으면 오래된 것부터 지운다. 무전 받기가 실패해도 스냅샷은 그대로 나온다.
- **더 보지 않는 그룹의 캐시(시간 기준 정리):** 앱을 다시 띄운 뒤 그 그룹을 관찰하지 않거나 다른 그룹으로 옮기면 모듈은 그 그룹의 종료·만료 이벤트를 볼 수 없다. 그래서 **이벤트와 상관없이** 시작 후 첫 처리 때(폴링 또는 `listMyVehicles` — 동행 메뉴·합류 화면을 열 때)와 그 뒤 1시간마다, 받은 지 25시간(서버 보관 24시간 + 여유) 넘은 파일·1분 넘게 남은 `.part`·빈 폴더를 지운다. 즉 그룹 종료를 모듈이 알면 바로, 모르면 늦어도 받은 지 25시간 뒤(모듈이 동작할 때) 지워진다. 그 사이에도 앱 캐시 디렉터리라 저장 공간이 부족하면 시스템이 지울 수 있다.
- **보안·로그:** `voiceId`·`groupId` 는 `[A-Za-z0-9_-]{1,128}` 만 경로에 쓴다(경로 조작 방지, 아니면 `FAILED`). 로그는 `voice: cached voiceId=… bytes=… durMs=… mime=…` / `voice: expired … basis=clock|404|group_ended` / `voice: download failed … reason=… attempt=n/3` — 닉네임·토큰·내용 없음(CLAUDE.md 규칙 8).
- **재생은 호스트 몫:** demo-host `donghaeng/VoicePlayer` — `MediaPlayer`, `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`, 포커스 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`(못 받으면 재생하지 않음, LOSS·LOSS_TRANSIENT 면 멈춤). `LOSS_TRANSIENT_CAN_DUCK`(폰 내비 길안내처럼 짧게 끼어드는 소리)이면 **일시정지했다가 `GAIN` 에 이어 재생**한다(30초 안에 안 돌아오면 멈춤, `setWillPauseWhenDucked(true)`) — 말소리(`CONTENT_TYPE_SPEECH`)는 프레임워크가 자동으로 줄이지 않아 그대로 두면 길안내와 같은 크기로 겹친다(정책은 `VoiceFocusPolicy`, 단위 테스트). **폰은 자동 재생하지 않는다** — 차량 앱이 자동 재생하므로 같은 차에 탄 폰과 겹치고, 주머니 속 폰이 갑자기 말하면 안 된다. 새 무전은 알림(F)과 목록의 [재생] 버튼으로. 보낸 시각은 이벤트에 시각이 없어 `expiresAtMs − 24시간`(docs/03 §2.6 보관 규칙, 서버 시계 그대로)으로 표시한다.
- **호스트 목록의 상태(`VoiceItems.merge`):** 이번 스냅샷에 있는 무전은 모듈이 서버 시계로 정한 `localAudioStatus` 를 **그대로** 쓴다 — 호스트가 폰 시계로 다시 만료를 판정하면 서버 시계 보정이 무력화된다(폰 시계가 이틀 빨라도 READY 는 READY). `recentEvents` 밖으로 밀려나 모듈이 더 상태를 주지 않는 항목만 폰 시계로 `만료 + 1시간` 이 지나면 보관 끝으로 표시한다(실제 파일은 모듈이 서버 시계로 지우고, 그 사이 [재생] 은 "보관 기간이 지났거나 다시 받는 중" 안내).
- **대안 검토:** 공개 함수 `suspend fun voiceAudio(groupId, voiceId): File`(필요할 때만 받기)이 대역에는 낫지만 §3 의 6개 원칙을 깨므로 보류했다. 지금 방식의 비용은 폴링당 최대 3건 × 512 KB 이하(실측 AAC 16 kHz 24 kbps ≈ 3 s 12 KB).
- **한계·다음 단계:** 폴링이라 최대 15초 늦게 보인다(푸시 전환 시 즉시). **폰에서 녹음해 보내기는 범위 밖** — 업로드 `POST /groups/{id}/voice` 는 차량 JWT 전용(docs/03 §2.6)이라, 사용자 JWT 업로드(보낼 내 차 지정) 계약과 공개 API(`sendVoiceMessage`) 추가가 필요하다(결정 필요).

## 4. 인증 설계
### 4.1 실서비스 — 공식 앱 계정 OAuth 2.0 토큰 검증
```
호스트 앱 로그인(공식 계정) ─► TokenProvider.accessToken()
   ─► sdk: POST /v1/auth/exchange { provider, accessToken }
   ─► 서버 identity: ExternalTokenVerifier.verify(provider, accessToken) → ExternalIdentity(provider, sub, displayName)
   ─► 동행 JWT 발급(사용자 토큰 1h) → sdk 가 캐시, 이후 모든 호출에 Bearer (401 이면 교환 1회 재시도 후 Unauthorized)
차량 목록: 서버 VehicleDirectory(차량 소유 조회) — 실서비스는 OEM 차량 API(계정에 연결된 차), MVP 는 동행 DB. 4.3 의 페어링으로 등록한 차는 VehicleRegistry(동행 DB 등록)에 쌓인다
```
서버에는 **검증 인터페이스만** 둔다: `ExternalTokenVerifier` (MVP 구현 `DevTokenVerifier`; 구현 자리: Bluelink/MyHyundai, KiaConnect, GenesisConnected). 실제 IdP 연동은 OEM 협의 대상이며 공모전 범위 밖이다.

### 4.2 MVP — dev 로그인
`provider = DEV`. demo-host 가 `POST /v1/auth/login {provider:"dev", email, displayName}`(클라우드 서버면 헤더 `X-Dev-Login-Key` — 빌드 속성 `dhDevLoginKey`, docs/03 §1.1) 로 받은 토큰(accessToken JWT, 1시간 — 만료 2분 전 재발급 §3.2)을 `TokenProvider` 로 넘기고, sdk 는 다른 제공자와 똑같이 `POST /v1/auth/exchange {provider:"dev", accessToken}` 으로 동행 JWT 를 받는다 — 서버의 `DevTokenVerifier` 가 이를 그대로 인정한다(docs/03 §1.1). 모듈은 토큰 형식을 해석하지 않는다. 계정에 연결된 차량은 4.3 의 페어링으로 만든다.

**T13(2026-10-27) 이후:** `provider = DEV` 도 `HttpDongHaengApi` + `/v1/auth/exchange` 로 실서버와 통신한다. 메모리 Fake(`internal/FakeDongHaengApi`)는 단위 테스트용 `internal` 훅(`DongHaeng.initWithApi`)으로만 남았다(공개 API 변경 없음). 실서버 검증: demo-host dev 로그인 → 차량 페어링 QR 승인(`/device/approve` 200) → `listMyVehicles` 에 차량 표시.

### 4.3 차량 페어링(Device Authorization Grant)은 대체 수단
공식 앱 계정에 연결된 차량이 없을 때(렌터카, 가족 차, 데모용 에뮬레이터)만 쓴다. 차 화면 QR(`{base}/pair?c=CODE`)을 호스트 앱 스캐너로 찍으면 `pairVehicle` 이 `POST /v1/device/approve` 로 승인한다. 계정에 차가 있으면 이 단계는 건너뛴다.

## 5. QR 판별 규칙
QR 문자열은 서버가 `{DH_PUBLIC_BASE_URL}` + 경로로 만든다(docs/03 머리말). 호스트 앱 스캐너가 읽은 문자열을 모듈이 **경로로** 판별하고, 호스트는 `init` 에 준 서버와 같은지만 확인한다.
```
{DH_PUBLIC_BASE_URL}/j/inv_xxxxx     → 그룹 합류  → joinGroup      (예: http://10.0.2.2:8080/j/inv_abc)
{DH_PUBLIC_BASE_URL}/pair?c=XXXXXX   → 차량 페어링 → pairVehicle    (예: https://1-2-3-4.sslip.io/pair?c=KQ74MZ)
경로가 위 둘이 아님                  → InvalidQr  "동행 QR이 아니에요"
호스트가 설정된 서버와 다름          → OtherServer "다른 서버의 QR이에요"
차량 등록 QR 을 joinGroup 에(또는 반대) → InvalidQr  reason = "차량 등록용 QR이에요. '차량 등록'에서 스캔해 주세요" (사용자용 문구; 어느 API 를 부를지는 호스트가 경로로 먼저 고른다)
```
호스트 앱은 같은 모양의 경로 규칙(경로가 정확히 `/j/{token}`, 또는 정확히 `/pair` + `c=` 코드)으로 어느 API 를 부를지 고른다 — demo-host 의 `QrPathRule` 이 예다.
**카메라 없이(직접 입력)**: 차 페어링 화면은 "또는 공식 앱에 이 코드를 입력하세요 KQ7-4MZ" 를 보인다. 호스트는 입력을 서버가 만드는 QR 과 같은 URL 로 바꿔 같은 흐름에 태운다(demo-host `ManualQrInput`) — 코드 6자(하이픈·공백·소문자 허용) → `{base}/pair?c=KQ74MZ`, 초대 토큰 `inv_…` → `{base}/j/inv_…`, `http(s)://` 로 시작하면 QR 문자열 그대로. 공개 API 는 그대로다(URL 은 서버 공개 주소 + 경로, CLAUDE.md 규칙 12).
웹 폴백: 앱이 없는 폰 기본 카메라로 찍으면 서버가 같은 경로에서 "앱(공식 앱의 동행 메뉴) 안내" 정적 페이지를 돌려준다(서버 내장, 도메인 비의존).

## 6. demo-host (시연용 호스트 앱 — 기아 앱 화면 재현)
공식 앱의 "동행" 메뉴를 흉내 낸 앱이다. 제품이 아니라 **통합 절차를 그대로 보여주는 시연 장치**다.
2026-10-27 부터 화면 구조를 **기아 앱(Kia Connect 앱)과 같은 5탭(홈/지도/제어/스토어/마이카)** 으로 재현했다. 기아 앱 전체를 구현하는 것이 아니라
화면은 실제 앱처럼 보이게 **정적으로 재현**(누르면 "시연용 화면입니다" 토스트)하고, 동행이 들어가는 지점(아래 §6.1 A~G)만 실제로 동작한다.

- 디자인 토큰(스크린샷에서 추출, `ui/theme/KiaTheme.kt`): 배경 `#08131B`, 카드 `#18222B`, 서브카드·구분선 `#2A343D`, 포인트 라임 `#8CC63F`, 본문 흰색, 보조 `#8A949C`, 카드 라운드 24dp, 좌우 여백 20dp.
- 브랜드 자산 미사용: 폰트는 Pretendard(OFL, `res/font`), 아이콘은 Material Symbols Outlined(Apache-2.0, `res/drawable/ms_*.xml` 로 변환), 차량은 벡터 실루엣(`ui/components/CarArt.kt`), 로고 자리는 "KIA (시연)" 텍스트. 모든 화면 하단에 "본 앱은 공모전 시연용 재현 화면이며 기아 공식 앱이 아닙니다".
- 더미 데이터: 사용자 "홍길동", 차량 "쏘렌토 HEV(시연)", 주소는 공공장소(서울시청 주차장). 참고 스크린샷(실명·주소 포함)은 저장소 밖(`C:\dev\donghaeng-ref\kia\`)에만 두고 커밋하지 않는다.
- 지도: osmdroid(OpenStreetMap 타일, API 키 불필요, 어둡게 반전). 패키지: `feature/{home,map,control,store,mycar,login}` = 정적 재현, `donghaeng/` = **공식 앱에 붙일 부분 전부**(게이트웨이·상태·A~G 화면), `ui/{theme,components}` = 디자인 시스템.
- 모드: 로그인(서버 dev 로그인 → `SdkGateway` = companion/sdk 공개 API 6개) 또는 "서버 없이 둘러보기"(`FakeGateway` — 호스트 쪽 가짜 그룹: 서울시청에서 동쪽으로 움직이는 3대 + 시간표 이벤트). sdk 의 Fake 는 단위 테스트 전용 internal 이므로 호스트가 자기 가짜를 둔다. 로그인 전에는 `NotConfiguredGateway`(모든 호출 `NotInitialized`). '지난 그룹' 예시 2건은 시연 모드에만 보인다.
- **로그인 이메일(심사위원 격리)**: 같은 이메일 = 같은 서버 계정이라, 모두가 같은 기본값을 쓰면 다른 사람의 차가 '내 차'에 섞이고 합류 시트가 그 차를 고를 수 있었다. 그래서 처음 실행 때 설치마다 `judge-<8hex>@dev.local` 을 만들어 기본값으로 두고(앱 전용 SharedPreferences), 로그인 화면에서 바꿀 수 있으며 로그인한 이메일을 다음 기본값으로 기억한다. 이메일은 로그에 남기지 않는다. 시연 리허설에서 `tools/demo` 의 `--email` 과 같은 계정을 쓰려면 화면의 이메일을 그 값으로 바꾼다.
- **서버 주소(빌드)**: car-app 과 같은 규칙 — debug = `dhPublicBaseUrl`(기본 `http://10.0.2.2:8080`), release = `dhCloudHost` 가 있으면 `https://{dhCloudHost}`, 없으면 `dhPublicBaseUrl`(http 면 빌드 경고, https 인데 `dhDevLoginKey` 가 비면 빌드 경고). 두 APK 를 같은 서버로: car-app `:app:assemblePleosRelease -PdhCloudHost=H` / companion `:demo-host:assembleRelease -PdhCloudHost=H -PdhDevLoginKey=<키>`. 다른 서버 QR 오류 문구에 이 앱의 서버 주소를 함께 보인다.

### 6.1 기아 앱 내 삽입 지점 A~G (동행이 실제로 동작하는 곳)
| 지점 | 기아 앱 화면의 위치 | 동작 | sdk / 서버 호출 | 코드 |
|---|---|---|---|---|
| **A** QR 스캔 | 모든 탭 상단바의 QR 아이콘(라임 가로줄) | CameraX+ML Kit 스캐너. 동행 QR(`/j/{token}`)이면 "그룹 합류" 바텀시트: 그룹명·방장·목적지·인원(초대 미리보기) + "어떤 차로 함께 갈까요?"(내 차 목록, 태그 '대표 차량'/'내 차'). **참여를 꺼 둔 차는 고를 수 없다. 이 그룹의 방장 차는 태그 '이 그룹 방장 차' + '이 차에 타고 있으면 고르세요'로 고를 수 있고**(그 차에 탄 채 폰으로 그룹 보기 — 서버 409 `ALREADY_MEMBER` 를 모듈이 성공으로 바꾸고 메시지는 그 차에서 온 것으로 읽힘. 차 1대 계정이 폰에서 자기 그룹을 보는 유일한 길), **다른 차가 있으면 미리 고르지 않는다. 미리 고르기는 고를 차가 한 대일 때만**, 목록·미리보기가 늦게 오면 미리 고른 값만 다시 정하고 사용자가 누른 차는 그대로(`JoinVehiclePolicy` — 예전엔 대표 차 = 방장 차를 기본으로 골라 409). 시트를 열 때마다 내 차 목록을 다시 불러오고 불러오는 중·실패([다시 불러오기])를 '차 없음'과 구분(마이카 탭 '연결된 차량'·동행 설정의 차량별 참여도 같은 규칙 `VehicleListStatus` — 실패를 'QR 스캔으로 등록' 안내로 보이지 않는다) → [함께 가기]. 차량 등록 QR 은 등록 대화상자. 그 밖의 QR 은 기존 동작 흉내(토스트). 본문에 늘 보이는 **"코드·주소 직접 입력"** 카드(차 화면 코드·초대 주소·QR 문자열, 클립보드 붙여넣기 — §5, 예시 칩은 시연 모드에만). 같은 QR 이 프레임마다 다시 잡혀 닫은 시트가 다시 뜨지 않게 2.5 s 조용한 창(`QrScanGate`) | `GET {base}/j/{token}`(JSON 미리보기, docs/03 §2.2) → `joinGroup(payload, vehicleId)` / `pairVehicle` | `donghaeng/ScanScreen.kt`, `JoinGroupSheet.kt`, `ScanInput.kt`, `JoinVehiclePolicy.kt` |
| **B** 동행 중 카드 | 홈, "내 차 위치" 카드 **바로 위** — 그룹 진행 중일 때만 | 그룹명, 멤버별 차량·ETA·잔량 배지, 최근 이벤트 1줄, [메시지 보내기]. 머리줄은 관찰 연결 상태(동행 중 / 연결 중… / 다시 연결하는 중 / 그룹이 끝났어요 / 그룹에서 나갔어요 / 다시 로그인이 필요해요 / 연결이 끊겼어요)와 안내 줄(마지막 갱신 n분 전, [다시 시도] 또는 [기록으로 옮기기]) — §3.2. 끝난 그룹은 [메시지 보내기]가 꺼진다 | `observeGroup` | `donghaeng/ActiveGroupCard.kt`, `GroupObservation.kt` |
| **C** 동행 메인 | 홈 서비스 바로가기 그리드의 "동행" 아이콘(첫 칸, NEW) | 진행 중 그룹 / 지난 그룹 / 사용법 탭. 진행 중 탭에 **무전 카드**(F16): 보낸 차(색·이름)·길이·보낸 시각·[재생]/[멈춤]·진행 바, 받는 중·보관 끝 표시(§3.1) | `observeGroup` (무전 오디오는 모듈이 받아 둔 파일) | `donghaeng/DongHaengMainScreen.kt`, `VoiceMessagesCard.kt`, `VoicePlayer.kt`, `VoiceItems.kt` |
| **D** 지도 그룹 주행 | 지도 카테고리 칩 **맨 앞** "동행" 칩 | 켜면 그룹 차량 마커(멤버 색 원 + 이름표, 선두 라임 테두리) 실시간 표시, 바텀시트가 "그룹 주행" 목록(ETA·선두와 거리·잔량)으로 전환. 선두는 스냅샷의 `leaderVehicleId`(위임·승계 반영), 끊김·종료면 '실시간' 대신 그 상태 | `observeGroup` | `donghaeng/GroupDriveSheet.kt`, `ActiveGroupCard.kt#groupMarkers`, `ui/components/OsmMap.kt` |
| **E** 설정 | 마이카 > Kia Connect subscription > 연동 서비스 **첫 행** "동행 그룹 주행" | 공유 항목(위치·연료·급제동) — **켜진 채 비활성 + "시연용 — 항목별 공유 끄기는 준비 중이라 아직 저장되지 않아요"**(항목별 끄기는 차량·서버 어디에도 없다: PRD §7 미구현 — 예전엔 끄면 "멤버에게 보이지 않아요"라고 했지만 그대로 공유됐다), 차량별 참여 설정(**실제 동작** — 끈 차는 이 폰의 합류 시트에서 고를 수 없다, 이 설치에 저장), 지난 그룹 기록([전체 삭제]는 이 폰의 기록을 실제로 지운다) | `listMyVehicles` | `donghaeng/DongHaengSettingsScreen.kt` |
| **F** 알림 | 상단바 알림 벨(빨간 점 = 안 읽음) | 동행 그룹 이벤트 목록(합류·휴게소 도착·저잔량·목적지 도착·동승자 메시지·무전 — 무전 행은 "아빠 차 무전 · 0:07" + 그 자리 [재생]). 내 차가 대상이 아닌 이벤트(내 차보다 뒤에 있는 차의 급제동 — docs/03 §5.4 `audience`)는 차량 앱과 같은 `protocol.EventAudience.isForMe(event, myVehicleId, snapshot.members)` 로 거른다. 앱이 백그라운드면 시스템 알림(채널 `donghaeng_group`, API 33+ `POST_NOTIFICATIONS`) | `observeGroup` 의 이벤트 | `donghaeng/NotificationsScreen.kt`(+`DongHaengNotifier`) |
| **G** 동승자 메시지 | B 카드·D 시트의 [메시지 보내기] | 프리셋 칩 4개(앞에 휴게소 들르자 / 천천히 와 / 먼저 가 있을게 / 배고파요) + 60자 입력 → 전송(차에서 "{이 폰이 합류시킨 차}에서 온 메시지"로 읽힘). 보낼 문장마다 멱등 키(UUID) — 같은 문장으로 다시 누르면 같은 키(타임아웃 뒤 재시도가 두 번 읽히지 않음), 문장을 바꾸거나 보낸 뒤 다시 보내면 새 키(`MessageKeys`) | `sendPassengerMessage(groupId, text, fromVehicleId, idempotencyKey)` | `donghaeng/PassengerMessageSheet.kt` |

제어·스토어 탭에는 동행 기능을 넣지 않는다(차량 제어와 섞지 않음). 상태는 `donghaeng/DongHaengStore.kt` 하나가 들고(활성 그룹·스냅샷·연결 상태·알림·설정), 모든 서버/SDK 호출은 `donghaeng/DongHaengGateway.kt` 로만 나간다 — 공식 앱 팀은 이 두 파일과 A~G 화면만 가져가면 된다.
그 밖의 호스트 규칙: 다른 그룹에 합류하면 이전 그룹은 '지난 그룹'(합류 때 목적지·마지막 인원)으로 옮기고 그 그룹의 소식은 지운다. 그룹마다 첫 스냅샷의 지난 이벤트는 읽은 것(시스템 알림 없음)으로, 알림 시각은 이벤트 ID(ULID)의 서버 생성 시각(`EventTimes`). '이 폰에서 그룹 보기 끝내기'는 확인 대화상자를 거친다(차는 그룹에 그대로, 같은 QR 재스캔으로 돌아온다). 시스템 알림 출구는 `DemoApplication` 이 Application 컨텍스트로 등록한다(Activity 를 붙잡지 않음).
알려진 한계: 합류 시트는 고른 차가 **다른 그룹에 들어가 있는지** 모른다(공개 `MyVehicle` 에 현재 그룹이 없음) — 그 차는 서버가 이전 그룹에서 자동으로 뺀다(방장이면 승계·혼자면 종료)는 안내 문구만 보인다. 차마다 현재 그룹을 보이려면 서버 `MyVehicleDto` 와 공개 모델에 필드를 더해야 한다(다음 단계).

### 6.2 화면 캡처 (Medium_Phone 에뮬레이터, 기획서 7번 화면 시안으로도 사용)
`docs/images/demo-host/` — `01_home.png`(홈 상단: 주행가능거리·빠른 제어·검색·날씨·즐겨찾기), `02_home_donghaeng.png`(B 동행 중 카드 + 내 차 위치), `03_map.png`(지도 + D 동행 칩 켬: 그룹 마커·그룹 주행 시트), `04_control.png`, `05_store.png`, `06_mycar.png`(E 연동 서비스의 동행 그룹 주행), `07_scan_join.png`(A 그룹 합류 시트), `08_message.png`(G 메시지 시트), `09_notifications.png`(F), `10_donghaeng_main.png`(C). 참고 스크린샷과의 나란히 비교 이미지는 저장소 밖에서만 본다(원본에 개인정보 포함).

권한: `CAMERA`(런타임), `POST_NOTIFICATIONS`(API 33+, 알림 화면 진입 시 요청), `INTERNET`. 패키지 `app.donghaeng.companion.demo`. 실행 대상: `Medium_Phone` AVD 또는 실기기.

## 7. 호스트 앱 통합 절차 (공식 앱 팀 관점)
1. `companion/sdk` AAR 의존성 추가(`protocol` 포함).
2. 앱 시작 시 `DongHaeng.init(DongHaengConfig(base, provider, version)) { 공식 계정 access token }`.
3. 공식 앱 QR 스캐너 결과를 `joinGroup(payload)` 로 넘긴다(`pair` 경로면 `pairVehicle`).
4. "동행" 메뉴 화면에서 `listMyVehicles`·`observeGroup`·`sendPassengerMessage` 로 UI 를 그린다(브랜드 UI 는 호스트 소유).
   무전(`VOICE_MESSAGE`)은 payload 의 `localAudioStatus == READY` 일 때 `localAudioPath` 를 앱의 오디오 정책(포커스·출력)대로 재생한다(§3.1, demo-host `VoicePlayer`).
5. (선택) 호스트 푸시 채널로 그룹 이벤트 수신 → P1.

## 8. 제약과 확장
- 공식 앱 내장은 **OEM 협의·SDK 배포 채널이 필요한 가정**이다(docs/10 R11, 설명회 Q2). 모듈은 호스트 앱 없이도 AAR 로 독립 빌드·테스트되며, demo-host 가 통합 절차를 대신 보여준다.
- Pleos ID 로그인이 열리면 `AccountProvider` 하나 추가로 차량 앱·모듈 계정을 통합하고 페어링 단계를 생략한다.
- iOS 공식 앱용은 같은 API 를 Swift 패키지로 제공(추후). 메시지 스키마는 `protocol/` 를 기준으로 생성한다.
- 모듈은 위치를 수집하지 않는다(차량이 보낸다). 수집·보관 원칙은 docs/01 §7 과 같다.
