# infra/cloud — 클라우드 VM 배포 (T32)

VM 1대에 로컬과 같은 compose 스택(postgres · redis · emqx · server)을 올리고 앞에 **Caddy(+layer4)** 를 둔다.
도메인 없이 `https://<IP>.sslip.io` 로 Let's Encrypt 인증서를 자동 발급받는다. 설계 근거 `docs/06_서버_설계.md` §7, 주소 규칙 `docs/03` 머리말.

```
인터넷 ──► Caddy (공개 포트는 이것뿐)
            ├─ :80    ACME HTTP-01 + http→https 308
            ├─ :443   https://<IP>.sslip.io
            │           /mqtt        → emqx:8083   MQTT over WebSocket (WSS, 8883 이 막힌 망의 대체 경로)
            │           /internal/*  → 404         EMQX 인가 훅은 compose 네트워크 전용
            │           그 밖         → server:8080 REST · JWKS · QR 웹 폴백
            └─ :8883  layer4: TLS 종단(같은 인증서, SNI) → emqx:1883   MQTT over TLS (차량 앱 기본)
compose 네트워크 안:  emqx ──JWKS·ACL 훅──► http://server:8080   server ──► postgres:5432 · redis:6379 · emqx:1883
loopback 전용(ssh -L):  127.0.0.1:15432 postgres · 127.0.0.1:6379 redis · 127.0.0.1:18083 EMQX 대시보드
```

| 파일 | 역할 |
|---|---|
| `docker-compose.cloud.yml` | `infra/docker-compose.yml` 오버라이드 — caddy 추가, 포트 비공개(`!override`/`!reset`), 개발용 기본 비밀 금지(`${VAR:?}`), 메모리 상한 |
| `Caddyfile` | 443 사이트 + 전역 `layer4 { :8883 … }` |
| `caddy/Dockerfile` | `caddy:<CADDY_VERSION>-builder` 에서 `xcaddy build --with github.com/mholt/caddy-l4@<CADDY_L4_VERSION>` → `caddy:<CADDY_VERSION>`. **버전 고정** `CADDY_VERSION=2.11.7`·`CADDY_L4_VERSION=v0.1.2`(빌드 단계에서 `caddy version`·`build-info` 로 확인, 다르면 빌드 실패) — 올릴 때는 두 값을 함께 바꾸고 §8 의 `caddy validate` 를 다시 돌린다 |
| `.env.example` | VM `.env` 템플릿(`<IP>`, `CHANGE_ME`, 생성 명령 주석) |
| `remote-env.sh` | VM 쪽 `.env` 생성 — 없을 때만(멱등), 비밀은 openssl hex, JWT 키는 RSA 2048 PKCS#8 한 줄, chmod 600 |
| `deploy.sh` | 로컬에서 실행하는 배포 스크립트(아래 §2) |

## 1. 준비물

**VM** — Ubuntu 24.04 LTS · x86_64 · RAM 4 GB(2 vCPU 권장) · 디스크 20 GB 이상 · 공인 IPv4 · 비밀번호 없는 `sudo` 사용자(클라우드 기본 사용자 `ubuntu` 등).

**클라우드 콘솔의 방화벽(보안 그룹·네트워크 ACL)** 인바운드 TCP **22 · 80 · 443 · 8883** 을 연다. VM 안의 ufw 는 deploy.sh 가 같은 4개로 맞춘다.

**Let's Encrypt 발급 조건** — Caddy 가 첫 기동 때 `<IP>.sslip.io` 인증서를 받는다.
- `<a-b-c-d>.sslip.io` 는 sslip.io 공개 DNS 가 `a.b.c.d` 로 풀어 준다(설정할 것 없음). 표기는 점 표기(`1.2.3.4.sslip.io`, CLAUDE.md 규칙 12)를 쓴다 — deploy.sh·remote-env.sh 가 이 꼴로 만든다. 하이픈형(`1-2-3-4.sslip.io`)도 같은 IP 로 풀리지만 인증서 이름이 달라 섞어 쓰지 않는다.
- Let's Encrypt 검증 서버가 **인터넷에서 VM 의 80(HTTP-01) 또는 443(TLS-ALPN-01)** 에 닿아야 한다. 둘 다 막혀 있으면 발급이 계속 실패하고 HTTPS·8883 이 모두 안 열린다.
- 발급은 보통 수십 초. 인증서·ACME 계정은 `caddy-data` 볼륨에 남고 만료 30일 전쯤 자동 갱신된다.
- 한도: 같은 이름 인증서 **주 5회**, 같은 호스트 검증 실패 **시간당 5회**. `caddy-data` 볼륨을 지우지 말 것(`down -v` 금지). 설정을 여러 번 시험할 때만 Caddyfile 전역 블록에 `acme_ca https://acme-staging-v02.api.letsencrypt.org/directory` 를 임시로 넣는다 — 스테이징 인증서는 Android 가 신뢰하지 않으므로 시연 전에 빼고 `caddy-data` 의 스테이징 인증서를 지운다.
- `CADDY_EMAIL`(선택): ACME 계정 메일. 주면 Caddy 가 ZeroSSL 을 예비 발급자로 붙인다.

**로컬 PC** — Docker Desktop(이미지 빌드), Git Bash(Windows) 또는 macOS/Linux 셸의 `ssh` · `tar` · `gzip` · `curl` · `openssl`. rsync 는 필요 없다(tar | ssh 로 보냄).

## 2. 배포

```bash
# 저장소 루트에서 (Windows 는 Git Bash)
bash infra/cloud/deploy.sh ubuntu@203.0.113.10 -i ~/.ssh/donghaeng-vm.pem [--email you@example.org]
```

| 단계 | 하는 일 | 재실행 시 |
|---|---|---|
| 1 | ssh 접속, x86_64·Ubuntu·`sudo -n` 확인 | 그대로 |
| 2 | Docker Engine 설치(`get.docker.com`), 사용자 docker 그룹, Compose ≥ 2.24.4 확인 | 설치돼 있으면 건너뜀 |
| 3 | ufw 22(+현재 ssh 포트)·80·443·8883 tcp 허용 후 enable | 같은 규칙은 무시됨 |
| 4 | 스왑 `/swapfile` 2 GB + `vm.swappiness=10` | 스왑 있으면 건너뜀 |
| 5 | VM `.env` 의 `DH_SERVER_TAG`·`DH_CADDY_TAG` 가 롤백 태그로 고정돼 있으면 **멈춤**(§6, `--keep-tag` 로 무시) → 로컬에서 `docker compose … build server caddy`(기존 server 빌드 정의 그대로, 이미지 `donghaeng/server:cloud` + `cloud-<커밋>`, `donghaeng/caddy:l4` + `l4-<커밋>`) → `docker save \| gzip \| ssh docker load` | VM 이미지 ID 가 같거나, 지난 전송 기록(`~/donghaeng/.shipped-images` 의 `<이미지> <로컬 ID> <VM ID>`)과 맞으면 전송 생략 |
| 6 | `infra/docker-compose.yml` + `infra/cloud/*`(`.env` 제외)를 `~/donghaeng/infra` 로 | 덮어씀 |
| 7 | `cloud/remote-env.sh` — VM `infra/.env` 생성 | **있으면 그대로**(비밀·JWT 키 유지). 템플릿에 나중에 생긴 `DH_DEV_LOGIN_KEY` 가 없으면 그 줄만 덧붙임 |
| 8 | 재배포면 DB 백업(`~/donghaeng/backups/pg-*.sql.gz`, 최근 5개) → `compose up -d --no-build --remove-orphans --wait` → **Caddyfile 반영 확인**(아래) → 공개 리스너 점검 | 바뀐 서비스만 다시 만듦. Caddyfile 이 caddy 시작 뒤 바뀌었으면 caddy 만 재생성 |
| 9 | 헬스체크(아래) → 앱 설정값 요약 | 그대로 |

헬스체크(이 PC → VM, 9개): `GET /actuator/health` UP(첫 배포는 인증서·서버 기동을 최대 240초 기다림) · `GET /v1/time` · `http://` → 308 `https://` ·
`POST /internal/mqtt/acl` → 404 · 접속 키 없는 `POST /v1/auth/login` → 403(§3) · `openssl s_client -connect <host>:8883 -servername <host>` 검증 OK + SAN 확인 · 8883 위 MQTT CONNECT → CONNACK ·
`/mqtt` WebSocket 핸드셰이크 101(서브프로토콜 `mqtt`) · WSS 위 MQTT CONNECT → CONNACK. 익명 CONNECT 라 CONNACK 은 `0x05 not authorized` 가 정상이다(경로가 EMQX 까지 살아 있다는 뜻).

- 설정 파일만 바꿨을 때: `--skip-build`. 헬스체크만 다시: `bash infra/cloud/deploy.sh --check-only 203.0.113.10.sslip.io`.
- **Caddyfile 반영(8단계)**: Caddyfile 은 단일 파일 바인드 마운트라 ① 내용만 바뀌면 compose 가 caddy 를 다시 만들지 않고 ② 6단계 tar(GNU)가 파일을 지우고 새로 만들면
  실행 중인 컨테이너는 옛 inode(옛 내용)를 계속 보며(`caddy reload` 도 옛 내용을 다시 읽는다) ③ 제자리 편집이면 파일은 새것이어도 Caddy 는 시작 때 읽은 설정 그대로다.
  그래서 deploy.sh 가 `~/donghaeng/.caddyfile-applied` 에 "caddy 컨테이너 ID + 그 컨테이너가 읽은 Caddyfile sha256" 을 남기고, 지금 VM 파일과 다르면
  `up -d --force-recreate caddy` 로 caddy 만 다시 만든다(인증서는 `caddy-data` 볼륨에 남아 재발급 없음, MQTT 연결은 한 번 끊겼다 다시 붙는다).
- 실패하면 ERR trap 이 원격 `compose ps` 와 `logs --tail=60 caddy server emqx` 를 출력하고 멈춘다. 고친 뒤 같은 명령을 다시 실행하면 된다.
- 앱·스크립트 쪽 값(배포 끝에 출력됨): 차량 앱 `-PdhPublicBaseUrl=https://<host> -PdhMqttPublicUri=ssl://<host>:8883`, 시연 스크립트 `--server https://<host>`.
  WSS 대체 주소 `wss://<host>/mqtt` 는 docs/03 머리말 규칙(앱이 `DH_PUBLIC_BASE_URL` 호스트에서 만든다) — 차량 앱 자동 전환은 T32 앱 작업에서 붙인다.

## 3. 포트 · 보안

| 포트 | 바인딩 | 용도 |
|---|---|---|
| 22 | VM sshd | 관리(키 인증) |
| 80 · 443 · 8883 | **0.0.0.0 (caddy)** | 인터넷 공개는 이 셋뿐 |
| 15432 · 6379 · 18083 | 127.0.0.1 | postgres · redis · EMQX 대시보드 — `ssh -L` 로만 |
| server 8080 · emqx 1883/8083 | publish 안 함 | compose 네트워크 안에서 caddy·서로만 |

- **Docker 가 publish 한 포트는 iptables DOCKER 체인을 타서 ufw 를 우회한다.** 그래서 비공개 포트는 ufw 가 아니라 compose 의 "loopback 바인딩 / publish 안 함" 으로 막는다.
  오버라이드에서 `ports` 는 병합(추가)되므로 지울 때는 `!override`(emqx: 18083 만 남김)·`!reset []`(server) 를 쓴다(Compose ≥ 2.24.4). deploy.sh 8단계가 `ss -tln` 으로 공개 리스너가 22·80·443·8883 뿐인지 확인한다.
- EMQX 의 JWT 인증(`http://server:8080/.well-known/jwks.json`)·HTTP 인가 훅(`http://server:8080/internal/mqtt/acl`)은 기본 compose 그대로 내부 주소를 본다.
  훅은 서버에서 `permitAll` + `X-Internal-Token` 검사라 Caddy 가 `/internal/*` 를 404 로 끊고, `DH_INTERNAL_TOKEN` 은 클라우드에서 필수(비면 compose 가 기동을 거부).
- 개발용 기본 비밀(`dh/dh`, `admin/public`, 빈 JWT 키·내부 토큰·salt)은 클라우드에서 쓰지 않는다 — 오버라이드가 `${VAR:?}` 로 요구하고 remote-env.sh 가 랜덤 값으로 채운다.
  Redis 는 `requirepass`(서버는 `DH_REDIS_PASSWORD` → `spring.data.redis.password`), EMQX 는 Erlang 쿠키도 바꾼다. TLS 는 Caddy 가 종단하므로 EMQX 자체 ssl/wss 리스너는 끈다.
- `.env` 는 VM `~/donghaeng/infra/.env`(chmod 600)에만 있다. 로컬로 가져오거나 커밋·공유하지 않는다. 대시보드 비밀번호 확인: `ssh <vm> "grep ^EMQX_DASHBOARD_PASSWORD= donghaeng/infra/.env"`.
- Caddy 액세스 로그는 켜지 않는다(경로에 초대 토큰 `/j/{token}`·페어링 코드·좌표 쿼리가 들어감 — CLAUDE.md 규칙 8). 오류 로그(업스트림 502 등)는 요청 uri·헤더를 함께 남기므로
  Caddyfile 전역 `log` 의 `filter` 가 **쿼리 전체 → `?***`, `/j/<토큰>` → `/j/***`, `Referer`·`X-Dev-Login-Key` 헤더 삭제** 로 가린다(`Authorization`·`Cookie` 는 Caddy 기본 마스킹). 서버 프로필은 `cloud`(로컬의 DEBUG 로그 없음).
- **dev 간편 로그인 접속 키(필수)** — `POST /v1/auth/login {provider:dev, email}` 은 비밀번호 없이 이메일만 보는 MVP 로그인이고(docs/03 §1.1), 이메일만 알면 그 계정이 된다 — 예전 demo-host 고정 기본값(`rider01@dev.local`)은 저장소 기록에 남아 있다.
  키가 없으면 인터넷의 누구나 시연 계정 토큰을 받아 `/v1/me/vehicles` 조회·차량 해제(`DELETE`), 그룹 메시지(차량에서 음성으로 읽힘), `/v1/device/approve` 를 호출할 수 있다(docs/10 R20).
  그래서 클라우드 서버는 `DH_DEV_LOGIN_KEY`(remote-env.sh 가 hex 32자로 생성, 비면 compose 가 기동 거부)를 요구하고, 헤더 `X-Dev-Login-Key` 가 없거나 다르면 **403 `FORBIDDEN`** 으로 끊는다(헬스체크 5번이 확인).
  - 키 확인: `ssh <vm> "grep ^DH_DEV_LOGIN_KEY= donghaeng/infra/.env"` — 화면·채팅·스크린샷에 올리지 않는다.
  - demo-host: `cd companion && ./gradlew :demo-host:assembleDebug -PdhPublicBaseUrl=https://<host> -PdhDevLoginKey=<키>`(또는 `~/.gradle/gradle.properties` 의 `dhDevLoginKey`, 저장소에는 적지 않음).
  - 시연 스크립트: `DH_DEV_LOGIN_KEY=<키> python tools/demo/demo.py --server https://<host> --email <이메일> …`(`tools/demo/api.py` 도 같은 환경변수).
  - 한계: 키는 그 서버용으로 빌드한 demo-host APK 안에 들어간다 — 그 APK 는 시연 기기·심사 제출 외에 공개 배포하지 않는다. demo-host 로그인 기본값은 이제 **설치마다 무작위 `judge-<8hex>@dev.local`**
    (2026-11 D026·D031 — 예전 고정 기본값 `rider01@dev.local` 이 아니다)이라 심사 기기끼리 계정이 섞이지 않는다. 시연 계정을 정해 쓸 때는 저장소에 없는 이메일을 demo-host 로그인 화면에 넣고 `tools/demo --email` 에도 같은 값을 준다.
    키가 새면 `.env` 의 값을 `openssl rand -hex 16` 으로 바꾸고 `dc up -d server` → demo-host 다시 빌드. 이미 발급된 토큰은 만료(사용자 1시간·refresh 30일)까지 유효하다.
  - companion 의 dev 교환(`POST /v1/auth/exchange`)은 키를 보지 않는다 — 로그인으로 받은 토큰만 인정하므로 로그인을 막으면 같이 막힌다.
- 대시보드: `ssh -L 18083:127.0.0.1:18083 ubuntu@<IP>` → <http://localhost:18083> (admin / `.env` 의 `EMQX_DASHBOARD_PASSWORD`, 첫 로그인 때 변경 강제).
  DB: `ssh -L 15432:127.0.0.1:15432 …` → `psql -h localhost -p 15432 -U dh donghaeng`.

## 4. 메모리 예산 (VM 4 GB)

| 서비스 | `mem_limit` | 내부 상한 | 실측 (로컬 격리 스택, 이 설정 그대로) |
|---|---:|---|---|
| server (JVM 21) | 1024 MB | `JAVA_TOOL_OPTIONS`: `-Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m -XX:MaxDirectMemorySize=128m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError`, Tomcat 스레드 64 | 353 MiB (RSS 374 MB, 스레드 43 — 기동 + 페어링 1회 후) |
| emqx 5.8 | 512 MB | `node.process_limit` 131072 · `node.max_ports` 65536 · ssl/wss 리스너 off · sysmem 경보 92% | 208 ~ 254 MiB |
| postgres 16 | 384 MB | `shared_buffers=128MB` · `effective_cache_size=256MB` · `work_mem=4MB` · `maintenance_work_mem=32MB` · `max_connections=40` | 51 MiB |
| redis 7 | 128 MB | `maxmemory 64mb` · `volatile-lru` · AOF | 6 MiB |
| caddy + l4 | 128 MB | `GOMEMLIMIT=96MiB` | 15 MiB |
| **컨테이너 상한 합계** | **2176 MB** | | 합 ≈ 0.65 GB |
| OS · dockerd · containerd · sshd | — | | 0.4 ~ 0.6 GB |
| **상한을 다 써도 남는 RAM** | **≈ 1.7 ~ 1.9 GB** | 4 GB VM 의 실사용 가능량(≈ 3.8 GB) 기준 | OS 여유 ≥ 1 GB 조건 충족 |
| 스왑 | `/swapfile` 2 GB | `vm.swappiness=10` — 비상용 | |

- 서버는 힙 512 MB 외에 메타스페이스·코드 캐시·스레드·Netty 다이렉트 버퍼를 합쳐 실사용 700 ~ 850 MB 를 넘지 않게 잡았다(상한 1024 MB). OOM 이면 JVM 이 바로 끝나고 `restart: unless-stopped` 가 다시 띄운다.
- 컨테이너 상한에는 페이지 캐시도 들어가지만 회수 가능한 메모리라 postgres 가 상한에 닿아도 캐시를 먼저 비운다.
- Redis `volatile-lru`: TTL 있는 presence·dedup·제안 키만 내보낸다. TTL 없는 그룹 소속 캐시(`veh:{vid}:group`, `grp:{gid}:members`)는 MQTT ACL 훅이 먼저 보는 키라 `allkeys-*` 정책을 쓰지 않는다.

## 5. 운영

```bash
# VM 에서 (ssh ubuntu@<IP>)
cd ~/donghaeng/infra
alias dc='docker compose -f docker-compose.yml -f cloud/docker-compose.cloud.yml'
dc ps                                  # 상태(healthy)
dc logs -f --tail=100 server           # 로그 (caddy / emqx / server)
docker stats --no-stream               # 메모리 — §4 상한과 비교
dc restart server                      # 서버만 재시작(토큰·DB 유지 — JWT 키가 .env 에 고정)
```

- **Caddyfile 을 고친 뒤**: 로컬에서 고쳐 `deploy.sh --skip-build`(8단계가 caddy 를 다시 만든다). VM 에서 직접 고쳤으면 `dc up -d --force-recreate caddy`.
  **`caddy reload`(`dc exec caddy caddy reload …`)는 쓰지 않는다** — 단일 파일 바인드 마운트라 파일을 새로 만든 경우 컨테이너는 옛 내용을 읽는다(§2).
- **.env 를 고친 뒤**: `dc up -d` (바뀐 변수를 쓰는 서비스만 다시 만든다). 단 `POSTGRES_PASSWORD`·`EMQX_DASHBOARD_PASSWORD` 는 **빈 볼륨에 처음 뜰 때만** 적용된다 — 이후 바꾸려면 DB 안에서 `ALTER USER`, 대시보드에서 변경.
- **VM IP 가 바뀌었을 때**: `.env` 의 `DH_PUBLIC_HOST`·`DH_PUBLIC_BASE_URL`·`DH_MQTT_PUBLIC_URI` 세 줄을 새 `<IP>` 로 고치고 `bash infra/cloud/deploy.sh <user>@<새 IP> --skip-build`. 새 이름으로 인증서가 다시 발급되고, 차량 앱은 새 주소로 다시 빌드·페어링해야 한다(QR 주소가 바뀜).
- **JWT 키(`DH_JWT_PRIVATE_KEY_PEM`)를 바꾸면** 발급된 차량·사용자 토큰이 전부 무효가 된다 → 차량 재페어링 필요. `DH_CAR_ID_SALT` 는 바꾸지 않는다.

### 5.1 2026-11-02 정밀점검 반영 서버 배포 메모

2026-10-28 배포(§9) 뒤 점검 반영(배치 C) 서버를 처음 올릴 때 달라지는 것:

- **Flyway V7**(`server/src/main/resources/db/migration/V7__review_fixes.sql`, docs/04 'V7') — 서버가 시작할 때 자동 적용된다. 열·인덱스 추가와 새 표(`idempotency_record`)뿐이라
  기존 그룹·차량·토큰은 그대로다. deploy.sh 8단계가 재배포 직전에 DB 를 백업하므로(`~/donghaeng/backups/pg-<시각>.sql.gz`) 되돌릴 때는 §6 의 DB 복원을 쓴다(Flyway 는 전진 전용).
  확인: `dc logs server | grep -i "migration"` 에 V7 적용 줄(`… now at version v7`), 그 뒤 `deploy.sh --check-only` 9/9.
- **빈도 제한**(docs/03 머리말·§2.3) — `POST /v1/auth/login`·`POST /v1/device/code` 는 **IP 당 분당 30회**(`DH_RATE_LIMIT_IP_PER_MINUTE`, 0 이면 끔), 동승자 메시지는 **그룹당 2 s 에 1건**
  (서버 기본값, `DH_RATE_LIMIT_MESSAGE_INTERVAL_MS` — compose 로는 넘기지 않는다). 넘으면 429 `RATE_LIMITED` + `Retry-After`.
  기존 VM `.env` 에 `DH_RATE_LIMIT_IP_PER_MINUTE` 줄이 없어도 compose 기본값 30 이 들어간다 — 바꾸려면 `.env` 에 줄을 넣고 `dc up -d server`.
  클라우드는 Caddy 뒤라 서버가 `X-Forwarded-For` 의 마지막 값(Caddy 가 본 클라이언트 주소)으로 IP 를 센다 — 시연장에서 여러 기기가 같은 공인 IP(NAT)로 나가도
  로그인·페어링 몇 번은 충분하다. 리허설 스크립트(`tools/demo`)를 빠르게 되풀이하다 429 가 나면 1분 기다린다(스크립트는 토큰을 서버별로 캐시해 로그인을 줄인다).
- 차량 앱·demo-host·tools/demo 쪽 변화는 docs/09 §4.4.

## 6. 롤백

| 대상 | 방법 |
|---|---|
| 서버 이미지 | VM 에서 `docker image ls donghaeng/server` 로 `cloud-<커밋>` 태그 확인 → 시험: `DH_SERVER_TAG=cloud-<커밋> dc up -d server`(셸 변수가 .env 보다 우선, 다음 배포 때 자동으로 풀림) · 고정: `.env` 의 `DH_SERVER_TAG=cloud-<이전 커밋>` → `dc up -d server` |
| Caddy 이미지 | 같은 규칙 — `docker image ls donghaeng/caddy` 의 `l4-<커밋>` → `DH_CADDY_TAG=l4-<커밋> dc up -d caddy` 또는 `.env` 에 고정 |
| 설정(compose·Caddyfile) | 로컬에서 이전 커밋을 체크아웃하고 `bash infra/cloud/deploy.sh <user>@<IP> --skip-build` — Caddyfile 이 달라졌으면 8단계가 caddy 를 다시 만든다(§2) |
| DB | Flyway 는 전진 전용 — 마이그레이션이 든 버전을 되돌리면 이전 서버가 새 스키마를 못 읽을 수 있다. deploy.sh 가 재배포 직전에 남긴 백업(`~/donghaeng/backups/pg-<시각>.sql.gz`)으로 복원(아래) |
| 전체 중지 | `dc down` (볼륨 유지). **`dc down -v` 금지** — DB·EMQX 계정·Caddy 인증서가 지워지고 인증서 재발급 한도(주 5회)를 쓴다 |

**롤백 고정을 끝낼 때**: `.env` 를 `DH_SERVER_TAG=cloud`·`DH_CADDY_TAG=l4` 로 되돌린 뒤 deploy.sh. 고정된 채로 새 이미지를 배포하면 compose 가 고정 태그(옛 이미지)로 띄우고
헬스체크는 그대로 통과해 알아채기 어렵다 → deploy.sh 5단계는 고정을 보면 **멈춘다**(`--skip-build` 이거나 `--keep-tag` 를 주면 경고만 하고 고정 이미지로 진행). 배포 끝 요약의 "실행 중 이미지" 로도 확인한다.

```bash
# DB 복원 + 이전 서버 이미지 (VM, ~/donghaeng/infra)
dc stop server
dc exec -T postgres psql -U dh -d postgres -c 'DROP DATABASE donghaeng' -c 'CREATE DATABASE donghaeng OWNER dh'
gunzip -c ../backups/pg-<시각>.sql.gz | dc exec -T postgres psql -q -U dh -d donghaeng
# Redis 캐시 비우기 — 소속 캐시(veh:{vid}:group·grp:{gid}:members)를 MQTT ACL·presence 가 DB 보다 먼저 믿는다(AOF 볼륨이라 재시작으로도 안 지워짐).
# 백업 이후의 합류·이탈이 남아 있으면 복원된 DB 와 다른 그룹 토픽을 허용·거부한다. Redis 에는 캐시·presence·dedup 뿐이라(refresh 토큰은 DB) 비워도 된다.
dc exec -T redis redis-cli FLUSHALL                   # REDISCLI_AUTH 가 컨테이너에 설정돼 있다
DH_SERVER_TAG=cloud-<이전 커밋> dc up -d server      # 이 버전에 머물 거면 .env 에 고정(다음 deploy.sh 는 고정을 보면 멈춘다 — 위)
```

## 7. 문제 해결

| 증상 | 확인 |
|---|---|
| `/actuator/health` 가 240초 넘게 실패, caddy 로그에 `challenge failed`·`timeout` | 클라우드 보안 그룹의 80/443 인바운드, `curl http://<IP>.sslip.io` 가 VM 에 닿는지. 검증 실패 한도(시간당 5회)에 걸리면 1시간 뒤 `dc restart caddy` |
| HTTPS 는 되는데 8883 TLS 실패 | 보안 그룹 8883, `dc logs caddy` 의 layer4 오류, `ss -tln \| grep 8883` |
| 8883 은 되는데 CONNACK 이 없음 | `dc ps emqx`(healthy?), `dc logs emqx` |
| WSS 가 101 이 아님 | 400 이면 `Sec-WebSocket-Protocol: mqtt` 누락(EMQX 는 서브프로토콜 필수), 502 면 emqx 상태 |
| 차량 MQTT `not authorized` | 서버 기동 직후 최대 `EMQX_JWKS_REFRESH_INTERVAL`(30초) 동안은 정상. 계속되면 차량을 이 서버로 다시 페어링(다른 서버의 토큰) |
| server 가 `unhealthy`·재시작 반복 | `dc logs server` — `NOAUTH`(Redis 비밀번호), `password authentication failed`(POSTGRES_PASSWORD 가 볼륨 생성 때 값과 다름), `OutOfMemoryError`(§4) |
| `docker compose` 가 `!override` 를 모름 | Compose 2.24.4 미만 — `sudo apt-get install -y docker-compose-plugin` |
| deploy.sh 가 "이미지 태그를 고정하고 있어요" 로 멈춤 | 롤백 고정 중(§6). 끝내려면 VM `.env` 를 `DH_SERVER_TAG=cloud`·`DH_CADDY_TAG=l4` 로, 유지하려면 `--keep-tag` |
| demo-host·tools/demo 로그인이 403 `FORBIDDEN` | dev 로그인 접속 키가 없거나 다름(§3) — `-PdhDevLoginKey=<키>` 로 demo-host 재빌드 / `DH_DEV_LOGIN_KEY=<키>` |
| `required variable DH_DEV_LOGIN_KEY is missing` | 키 도입 전에 만든 `.env` — deploy.sh 를 다시 실행하면 7단계(remote-env.sh)가 그 줄만 덧붙인다 |

## 8. 로컬 검증 기록 (VM 없이, 2026-10-28)

- `docker compose --env-file infra/cloud/.env.example -f infra/docker-compose.yml -f infra/cloud/docker-compose.cloud.yml config` 통과 — 병합 결과 공개 포트는 caddy 80/443/8883 뿐, emqx 는 `127.0.0.1:18083`, server 는 없음. 필수 변수(`DH_INTERNAL_TOKEN` 등)를 비우면 `required variable … is missing` 으로 거부.
- `remote-env.sh` 로 만든 `.env`: 비밀 6개 hex, PEM 한 줄 1732자 → `\n` 복원 시 `openssl pkey -check` OK(RSA 2048). 두 번째 실행은 파일 해시 그대로(멱등). compose 는 PEM 의 `\n` 을 두 글자 그대로 넘기고 서버가 `JWT 서명 키 로드 완료 bits=2048` 로 읽는다.
- caddy 이미지 빌드(Caddy v2.11.7 + caddy-l4 v0.1.2) + `caddy validate --adapter caddyfile`(메일 있음/없음) 통과. EMQX: ssl·wss 리스너 `running false`, `process_limit 131072`·`port_limit 65536`.
- 스모크 ①: 같은 Caddyfile + `local_certs` 로 로컬 스택 네트워크(`donghaeng_default`)에 붙여 `deploy.sh --check-only` **8/8 PASS**(Caddy 로컬 CA 로 인증서·호스트 이름 검증 포함). 검증 없이 붙으면 TLS 항목이 실패하는 것도 확인. 서버에 직접 `POST /internal/mqtt/acl` 은 200 → Caddy 의 404 가 차단이다.
- 스모크 ②: Caddy 를 거친 MQTT 연결(8883·WSS)이 **150초 무통신**(Caddy `idle 2m` 초과) 뒤에도 PINGREQ → PINGRESP 로 살아 있음.
- 스모크 ③: 별도 compose 프로젝트로 **클라우드 구성 전체**(이 오버라이드 + remote-env.sh 가 만든 .env + 이 브랜치로 빌드한 `donghaeng/server:cloud`, 호스트 포트만 바꾼 시험용 오버라이드)를 띄움 —
  `up --wait` 18초에 5개 healthy, 설정이 같으면 다시 `up` 해도 재생성 없음(※ Caddyfile 내용만 바뀐 경우도 compose 는 재생성하지 않는다 — 그래서 8단계의 Caddyfile 확인이 있다, 아래 리뷰 반영). `deploy.sh --check-only` 8/8 PASS. Redis 는 인증 없이 `NOAUTH`, 서버는 `DH_REDIS_PASSWORD` 로 붙음, 프로필 `cloud`, Postgres `shared_buffers 128MB / max_connections 40`.
  디바이스 페어링(QR `https://<host>/pair?c=…`, `mqtt.uri=ssl://<host>:8883`) → 차량 JWT 로 **8883·WSS 둘 다 CONNACK 0x00, 자기 cmd 구독 허용(0x01)·남의 cmd 거부(0x80)** — EMQX JWKS 인증과 `X-Internal-Token` ACL 훅이 클라우드 구성에서 동작. 8단계 DB 백업 스니펫도 같은 스택에서 확인.
- 이미지 전송 크기(gzip -1): server ≈ 139 MB, caddy ≈ 41 MB. Git Bash 에서 `docker save | gzip | gunzip | docker load` 파이프 무손상(이미지 ID 동일 — **같은 데몬에 다시 load 한 결과**라
  저장소 종류가 다른 VM 에서도 ID 가 같다는 증거는 아니다. 그래서 5단계는 ID 비교에 더해 지난 전송 기록으로도 같은 이미지를 알아본다).

### 리뷰 반영 검증 (2026-10-28, VM 없이)

- **Caddyfile 반영**: Debian 컨테이너(GNU tar 1.35 — Ubuntu 24.04 와 같은 버전)에서 단일 파일 `mount --bind` 후 tar 추출로 교체 → inode 가 바뀌고 마운트 쪽은 옛 내용(재마운트하면 새 내용) — 리뷰 지적 재현.
  8단계 원격 스니펫을 그대로 꺼내 시험용 compose(caddy 하나)에 7개 상황으로 돌림: 첫 배포·변경 없음(재생성 없음) / 파일 교체·제자리 편집(재생성 → 응답이 새 설정) / 손으로 재생성 뒤(재생성 없음) / 손으로 재생성 + 그 뒤 제자리 편집(재생성) — 모두 기대대로.
  이 과정에서 파이프로 읽히는 원격 스크립트 안의 `docker compose` 가 남은 스크립트 줄을 stdin 으로 먹는 것을 확인해 8단계 스크립트(DB 백업 포함)의 docker 명령에 `</dev/null` 을 붙였다.
- **오류 로그 마스킹**: 필터 없는 Caddy 는 502 오류 로그에 `/j/<토큰>?c=<코드>` uri 원문을 남긴다(지적 재현). 이 Caddyfile 의 전역 `log` 필터로는 uri 가 `/j/***`·`/pair?***`·`/v1/poi/rest-areas?***` 로 남고
  Referer·`X-Dev-Login-Key` 헤더 없음, 원문(토큰·코드·좌표·키) 0건 — 단독 Caddy 와 아래 스모크 스택(서버를 멈춰 502) 둘 다. `caddy validate`(메일 있음/없음)·`caddy fmt` 통과.
- **버전 고정 빌드**: `caddy:2.11.7-builder` + caddy-l4 `v0.1.2` 로 빌드 → `caddy version` v2.11.7, `build-info` caddy-l4 v0.1.2(빌드 단계 확인 통과).
- **dev 로그인 키**: 서버 `DevLoginKeyIT`(키 없음·빈 값·틀림 → 403 `FORBIDDEN`, 키 검사가 본문 검증보다 먼저, 맞으면 200 + dev 교환 200) · 키 없는 기본 설정은 `IdentityFlowIT` 그대로 통과.
  `remote-env.sh`: 새 `.env` 에 `DH_DEV_LOGIN_KEY` hex 32자, 키 없는 기존 `.env` 는 그 줄만 덧붙이고 다른 줄 무변경(마지막 줄 줄바꿈 없음도 처리), 다시 실행하면 해시 그대로.
  compose `config`: 키가 server 환경으로 전달, `.env` 에서 빼면 `required variable DH_DEV_LOGIN_KEY is missing` 으로 거부.
- **스모크(클라우드 구성 전체)**: 별도 compose 프로젝트에 이 오버라이드 + remote-env.sh 가 만든 `.env` + 이 브랜치로 빌드한 server·caddy(버전 고정) 이미지, 호스트 포트만 바꾸고 `local_certs` —
  `up --wait` 18초에 5개 healthy, `deploy.sh --check-only` **9/9 PASS**(새 5번 "dev 로그인 키 없이 거부 403" 포함). Caddy 를 거쳐 키 없음·틀림 403 / 맞는 키 200 → 그 토큰의 dev 교환(키 없이) 200,
  `DH_DEV_LOGIN_KEY=<키> python tools/demo/api.py … vehicles` 로그인 성공(키 없으면 안내와 함께 403).
- **VM 실배포 때 확인할 것**: ① 같은 커밋으로 두 번째 배포 → 5단계 "VM 과 같은 이미지 → 전송 생략"(server·caddy 둘 다) ② Caddyfile 만 바꿔 `--skip-build` → 8단계 "caddy 재생성" 후 헬스체크 9/9
  ③ `.env` 에 `DH_SERVER_TAG=cloud-<커밋>` 을 두고 배포 → 5단계에서 멈춤 ④ 헬스체크 5번 "dev 로그인 키 없이 거부 403".

## 9. 운영 기록 — AWS EC2 시드니 (2026-10-28 배포)

| 항목 | 값 |
|---|---|
| 인스턴스 | EC2 ap-southeast-2(시드니) `c7i-flex.large` — x86_64, 2 vCPU, RAM 3.8 GB(가용), Ubuntu 24.04.5, 루트 EBS 30 GB(가용 29 GB) |
| 주소 | 탄력적 IP → **`https://3.24.206.147.sslip.io`**(점 표기, Let's Encrypt 인증서 2027-01-26 만료·자동 갱신), MQTT `ssl://3.24.206.147.sslip.io:8883`, WSS `wss://3.24.206.147.sslip.io/mqtt` |
| 보안 그룹 | 인바운드 TCP 22·80·443·8883(ICMP 없음 — ping 은 안 된다). VM ufw 도 같은 4개 |
| 배포 | `bash infra/cloud/deploy.sh ubuntu@3.24.206.147 -i <키>` → 9/9 PASS(첫 배포: Docker 설치·스왑·이미지 전송 포함 약 6분) |
| 재부팅 | `sudo systemctl reboot` → ssh 21 s, HTTPS health UP 31 s, 5개 컨테이너 healthy, 헬스체크 9/9 — compose `restart: unless-stopped` + docker·containerd `enabled` |
| 메모리(시험 뒤) | 사용 1.15 GB / 3.8 GB(가용 2.66 GB), 스왑 2 GB 중 0. 컨테이너: server 374 MiB / 1 GiB · emqx 173 / 512 · postgres 70 / 384 · caddy 54 / 128 · redis 16 / 128 MiB(§4 상한 안) |
| 디스크(시험 뒤) | 루트 6.6 GB / 29 GB(24%) — 이미지 1.84 GB, 볼륨 51 MB(DB 8 MB), journald 27 MB |
| 시계 | chrony NTP 동기(오차 µs) |
| 지연(한국 PC·에뮬레이터 ↔ 서버) | 왕복 약 270 ms(KT 회선이 미국 쪽을 거쳐 시드니로 감), 메시지 종단 P50 287 ms — docs/09 §4.2 |

### 9.1 비용 — $100 크레딧 기준 (2026-10-28 계산)

가정: `c7i-flex.large` **$0.11/시간**(사용자 제공 단가), EBS gp3 30 GB **$0.096/GB-월**(시드니 공시가) → $2.88/월, 공인 IPv4(탄력적 IP) **$0.005/시간** — 2024-02 부터 연결돼 있어도 과금, 인스턴스를 멈춰도 과금.
데이터 전송은 인터넷 방향 월 100 GB 무료 안이라 0 으로 본다(시연 트래픽은 MB 단위, 이미지 전송은 들어오는 방향이라 무료).

| 항목 | 1일 | 1개월(30일) |
|---|---:|---:|
| 인스턴스 $0.11 × 24 h | $2.64 | $79.20 |
| EBS gp3 30 GB | $0.095 | $2.88 |
| 탄력적 IP $0.005 × 24 h | $0.12 | $3.60 |
| **켜 둔 상태 합계** | **$2.855** | **$85.68** (730 h 기준 $86.83) |
| 멈춘 상태(EBS + IP 만) | $0.215 | $6.48 |

- **$100 로 24시간 켜 두면 약 35.0일** — 시작(2026-10-28 17:46 KST)부터 **2026-12-02 오후** 쯤 바닥난다(이 계정에서 오늘 전에 쓴 크레딧이 있으면 더 이르다). **PT(12/03~04) 전날이다.**
- PT 끝(12/04 24:00 KST, 시작부터 37.3일)까지 켜 두려면 $106.4 → **$6.4 가 모자란다.** 맞추는 방법(하나만 해도 된다):
  - 쓰지 않는 시간에 인스턴스를 멈춘다 — 멈춘 하루마다 $2.64 절약 → **합계 약 2.4일(58시간)** 멈추면 PT 까지 간다(예: 시험이 없는 밤 01~07시 × 10일). 탄력적 IP 라 다시 켜도 주소·인증서·데이터는 그대로이고 compose 는 부팅 31 s 뒤 자동으로 뜬다.
  - 또는 시간당 **$0.10 이하** 인스턴스로 바꾼다(콘솔: 중지 → 인스턴스 유형 변경 → 시작, 이미지가 x86_64 라 x86 유형만, RAM 4 GB 권장 §4).
  - 심사 기간(11/14 제출 ~ 12/02)에 심사위원이 release APK 로 이 서버를 쓴다면 그 기간은 켜 둬야 한다 — 그러면 제출 전(지금 ~ 11/13)에 멈추는 시간을 모은다.
- AWS 결제 콘솔 → Credits 에서 잔액·만료일을 확인한다. 새 계정의 무료 플랜이면 크레딧이 떨어지거나 기간이 끝날 때 계정·리소스가 닫힐 수 있으니(유료 플랜 전환 전) PT 일정과 같이 확인한다. AWS Budgets 에 $80 알림을 걸어 두면 바닥나기 전에 메일을 받는다.

