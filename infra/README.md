# infra — 로컬·클라우드 실행 환경

`docker compose` 로 동행 서버와 그 의존 서비스(PostgreSQL · Redis · EMQX)를 한 번에 띄운다.
설계 근거는 `docs/06_서버_설계.md` §6~§7, 작업 ID 는 T02(로컬) / T32(클라우드 배포, `docs/08_구현계획서.md`).

```
infra/
├── docker-compose.yml   # postgres 16 · redis 7 · emqx 5.8 · server
├── .env.example         # 환경 변수 예시 → 복사해서 .env 로 사용 (.env 는 커밋 금지)
└── README.md
```

## 1. 사전 준비
- Docker Desktop (Compose v2 — `docker compose version` 으로 확인)
- 처음 한 번: `Copy-Item .env.example .env` (PowerShell) / `cp .env.example .env` (Git Bash).
  기본값 그대로 써도 되므로 로컬 개발은 .env 없이도 동작한다.

## 2. 시작 · 중지
모든 명령은 `infra/` 디렉터리에서 실행한다.

| 작업 | 명령 |
|---|---|
| 전체 시작 (서버 이미지 빌드 포함) | `docker compose up -d --build` |
| 인프라만 시작 (서버는 IDE에서 직접 실행) | `docker compose up -d postgres redis emqx` |
| 상태 확인 (healthy 여부) | `docker compose ps` |
| 로그 보기 | `docker compose logs -f emqx` / `docker compose logs -f server` |
| 중지 (데이터 유지) | `docker compose down` |
| 중지 + 데이터 초기화 | `docker compose down -v` |
| 서버 이미지만 다시 빌드 | `docker compose build server` |

서버 이미지 `donghaeng/server:dev` 는 `../server` (`server/Dockerfile`) 에서 빌드된다. 서버 코드를 바꾸면
`docker compose up -d --build server` 로 다시 빌드·재시작한다. 서버는 postgres·redis 가 healthy 가 된 뒤에 뜬다.

IDE(`cd server && ./gradlew bootRun`)에서 서버를 직접 띄울 때는 호스트 포트 기준 값을 쓴다 —
`DH_DB_URL=jdbc:postgresql://localhost:15432/donghaeng`, `DH_REDIS_HOST=localhost`, `DH_MQTT_URI=tcp://localhost:1883`
(`.env.example` 하단 참고).

## 3. 포트
| 서비스 | 호스트 포트 | 컨테이너 포트 | 용도 |
|---|---|---|---|
| postgres | **127.0.0.1:15432** | 5432 | PostgreSQL — 개발 PC 의 로컬 PostgreSQL 이 5432 를 쓰는 경우가 많아 15432 사용 (`DH_PG_HOST_PORT` 로 변경). PC 밖으로 열지 않음 |
| redis | 127.0.0.1:6379 | 6379 | Redis (PC 밖으로 열지 않음) |
| emqx | 1883 | 1883 | MQTT (tcp) — 실기기 접속용으로 공개 |
| emqx | 127.0.0.1:8083 | 8083 | MQTT over WebSocket (로컬 전용) |
| emqx | 127.0.0.1:18083 | 18083 | EMQX 대시보드 (로컬 전용) |
| server | 8080 | 8080 | 동행 REST API (`/v1/...`, `/actuator/health`, `/.well-known/jwks.json`) — 실기기 접속용으로 공개 |

1883(MQTT)·8080(REST)만 모든 인터페이스에 공개하고 나머지는 loopback 에만 바인딩한다. 에뮬레이터의 `10.0.2.2` 는 호스트 loopback 이라 그대로 접근된다.

접속 정보(기본값): DB `donghaeng` / 사용자 `dh` / 비밀번호 `dh` → `psql -h localhost -p 15432 -U dh -d donghaeng`

## 4. EMQX 대시보드
- 주소: <http://localhost:18083>
- 로그인: `admin` / `public` (`.env` 의 `EMQX_DASHBOARD_PASSWORD` 로 초기값 변경 가능).
  **첫 로그인 시 비밀번호 변경을 강제**한다. 바꾼 비밀번호는 `emqx-data` 볼륨에 저장되므로
  `docker compose down -v` 로 초기화하면 다시 `public` 으로 돌아간다.
- 인증: JWT(JWKS) — EMQX 가 `http://server:8080/.well-known/jwks.json` 에서 공개키를 받아 토큰을 검증한다
  (클라이언트는 username = 차량/사용자 ID, password = JWT). 서버(T10)가 아직 없거나 떠 있지 않으면
  EMQX 로그에 JWKS 를 가져오지 못했다는 경고가 반복되는데, 브로커 자체는 정상 동작하며 서버가 뜨면 자동으로 복구된다.
  JWKS 는 `EMQX_JWKS_REFRESH_INTERVAL`(기본 30초) 주기로만 다시 가져오므로, 서버가 뜬 뒤 최대 그 시간 동안은 MQTT 접속이 `not authorized` 로 거부될 수 있다.
- 토큰 바인딩: `verify_claims { sub = "${username}" }` — 토큰의 `sub` 와 MQTT username(vid)이 같아야 접속을 허용한다.
  서버 자신의 MQTT 클라이언트도 username = 자기 토큰의 sub 로 접속해야 한다 (T15).
  Access Control(ACL)은 서버의 `/internal/mqtt/acl` 훅으로 판정한다 (`docs/06` §5, 이후 작업에서 연결).

## 5. 앱에서 서버 주소 잡기
서버가 QR·딥링크 URL 을 만들 때 쓰는 공개 주소는 **`DH_PUBLIC_BASE_URL`**(REST)·**`DH_MQTT_PUBLIC_URI`**(MQTT) 두 설정값이다
(`docs/03` 헤더, CLAUDE.md 규칙 12). 고정 도메인은 쓰지 않는다.

| 접속 주체 | DH_PUBLIC_BASE_URL | DH_MQTT_PUBLIC_URI | 비고 |
|---|---|---|---|
| Android 에뮬레이터 (Pleos AVD 포함) | `http://10.0.2.2:8080` | `tcp://10.0.2.2:1883` | 기본값. `10.0.2.2` = 에뮬레이터에서 본 호스트 PC |
| 실제 폰 (PC 와 같은 Wi-Fi) | `http://<PC-IP>:8080` | `tcp://<PC-IP>:1883` | PC IP 는 `ipconfig` 의 IPv4 주소. 아래 방화벽 설정 필요 |
| 클라우드 VM (T32) | `https://<VM-IP>.sslip.io` | `ssl://<VM-IP>.sslip.io:8883` | Caddy 가 Let's Encrypt 인증서 자동 발급 |

실기기 테스트 절차:
1. `.env` 에 `DH_PUBLIC_BASE_URL=http://<PC-IP>:8080`, `DH_MQTT_PUBLIC_URI=tcp://<PC-IP>:1883` 설정 후 `docker compose up -d server` 로 서버 재생성.
2. Windows 방화벽에서 8080(REST)·1883(MQTT) 인바운드 허용 — 관리자 PowerShell, 세 단계:
   1. 네트워크 프로필 확인: `Get-NetConnectionProfile`. Wi-Fi/LAN 어댑터가 `Public`(Windows 11 기본값)이면 신뢰하는 네트워크일 때만
      `Set-NetConnectionProfile -InterfaceAlias "<어댑터명>" -NetworkCategory Private` 로 바꾼다 (Public 인 채로 두려면 아래 규칙에 `-Profile Private,Public`).
   2. Docker 차단 규칙 확인: Docker Desktop 첫 실행 때 보안 경고 창을 "취소"하면 `com.docker.backend.exe` 의 모든 포트를 막는 Block 규칙이 생기고,
      Windows 방화벽은 Block 을 Allow 보다 먼저 적용하므로 아래 Allow 규칙이 무효가 된다.
      `Get-NetFirewallRule -DisplayName "Docker Desktop Backend" | Where-Object Action -eq Block | Disable-NetFirewallRule`
      (Allow 로 바꾸지 말 것 — 6379/15432/18083 까지 열린다.)
   3. 포트 규칙 추가:
      ```powershell
      New-NetFirewallRule -DisplayName "DongHaeng dev (8080,1883)" -Direction Inbound -Protocol TCP -LocalPort 8080,1883 -Action Allow -Profile Private
      ```
3. 확인은 반드시 **다른 기기(폰)** 에서: 폰 브라우저에서 `http://<PC-IP>:8080/actuator/health` 가 열리면 연결 OK.
   PC 자신에서 여는 `http://<PC-IP>:8080` 은 방화벽을 거치지 않아 검증이 되지 않는다.

에뮬레이터와 실기기를 번갈아 쓸 때는 `.env` 값을 바꾸고 `docker compose up -d server` 만 다시 실행하면 된다
(QR 에 들어가는 주소가 바뀌므로 차량 앱에서 QR 을 다시 띄워야 한다).

## 6. 데이터 초기화 (볼륨 리셋)
| 범위 | 명령 |
|---|---|
| 전부 (DB + EMQX) | `docker compose down -v` |
| DB 만 | `docker compose stop postgres; docker compose rm -f postgres; docker volume rm donghaeng_pgdata; docker compose up -d postgres` |
| EMQX 만 (대시보드 비밀번호 등) | `docker compose stop emqx; docker compose rm -f emqx; docker volume rm donghaeng_emqx-data; docker compose up -d emqx` |

볼륨 이름은 compose 프로젝트명(`donghaeng`) 접두사가 붙는다: `donghaeng_pgdata`, `donghaeng_emqx-data`.
Flyway 마이그레이션은 서버 기동 시 자동 적용되므로 DB 를 지운 뒤에는 서버만 다시 띄우면 된다.

## 7. 자주 겪는 문제
- `port is already allocated` — 해당 포트를 쓰는 프로세스 확인: `Get-NetTCPConnection -LocalPort 15432,6379,1883,8083,18083,8080 -State Listen`.
  PostgreSQL 은 로컬 서비스 때문에 이미 15432 로 피해 두었다. 다른 포트가 겹치면 `.env`/compose 의 호스트 포트만 바꾼다.
- 서버가 `dependency failed to start` 로 안 뜸 — `docker compose ps` 에서 postgres/redis 가 `healthy` 인지 확인하고 `docker compose logs postgres`.
- EMQX 로그의 `jwks` / `server:8080` 연결 실패 경고 — 서버가 아직 안 떠서 생기는 정상 경고 (§4).
- 에뮬레이터에서 `10.0.2.2:8080` 이 안 열림 — 서버 컨테이너가 떠 있는지(`docker compose ps server`) 와 `http://localhost:8080/actuator/health` 를 먼저 확인.
- 폰에서 `<PC-IP>:8080` 이 안 열림 — §5 2-1 네트워크 프로필과 2-2 Docker Block 규칙을 먼저 확인 (`Get-NetConnectionProfile`, `Get-NetFirewallRule -DisplayName "Docker Desktop Backend"`).

## 8. 클라우드 배포 (T32 예정)
VM 1대에 같은 compose 를 올리고 Caddy 를 앞에 둔다: HTTPS 443 → `server:8080`, TLS 8883 → `emqx:1883`.
도메인 없이 `https://<VM-IP>.sslip.io` 로 인증서를 자동 발급받는다. `.env` 에
`DH_PUBLIC_BASE_URL=https://<VM-IP>.sslip.io`, `DH_MQTT_PUBLIC_URI=ssl://<VM-IP>.sslip.io:8883` 을 넣고, `.env` 는 절대 커밋하지 않는다.
클라우드에서는 `.env` 의 `POSTGRES_PASSWORD`·`EMQX_DASHBOARD_PASSWORD` 를 반드시 바꾸고(개발용 기본값 `dh`/`public` 금지), Redis 에 `requirepass` 를 건다.
15432/6379/8083/18083 은 loopback 바인딩을 유지해 외부에 열지 않는다 — 인터넷에 노출되는 것은 Caddy 의 443/8883 뿐이어야 한다.
