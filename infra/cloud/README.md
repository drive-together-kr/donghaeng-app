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
| `caddy/Dockerfile` | `caddy:2-builder` 에서 `xcaddy build --with github.com/mholt/caddy-l4` → `caddy:2`. 2026-10-28 빌드: Caddy v2.11.7 + caddy-l4 v0.1.2 |
| `.env.example` | VM `.env` 템플릿(`<IP>`, `CHANGE_ME`, 생성 명령 주석) |
| `remote-env.sh` | VM 쪽 `.env` 생성 — 없을 때만(멱등), 비밀은 openssl hex, JWT 키는 RSA 2048 PKCS#8 한 줄, chmod 600 |
| `deploy.sh` | 로컬에서 실행하는 배포 스크립트(아래 §2) |

## 1. 준비물

**VM** — Ubuntu 24.04 LTS · x86_64 · RAM 4 GB(2 vCPU 권장) · 디스크 20 GB 이상 · 공인 IPv4 · 비밀번호 없는 `sudo` 사용자(클라우드 기본 사용자 `ubuntu` 등).

**클라우드 콘솔의 방화벽(보안 그룹·네트워크 ACL)** 인바운드 TCP **22 · 80 · 443 · 8883** 을 연다. VM 안의 ufw 는 deploy.sh 가 같은 4개로 맞춘다.

**Let's Encrypt 발급 조건** — Caddy 가 첫 기동 때 `<IP>.sslip.io` 인증서를 받는다.
- `<a-b-c-d>.sslip.io` 는 sslip.io 공개 DNS 가 `a.b.c.d` 로 풀어 준다(설정할 것 없음). 표기는 하이픈형(`1-2-3-4.sslip.io`)을 쓴다.
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
| 5 | 로컬에서 `docker compose … build server caddy`(기존 server 빌드 정의 그대로, 이미지 `donghaeng/server:cloud` + `cloud-<커밋>`, `donghaeng/caddy:l4`) → `docker save \| gzip \| ssh docker load` | VM 이미지 ID 가 같으면 전송 생략 |
| 6 | `infra/docker-compose.yml` + `infra/cloud/*`(`.env` 제외)를 `~/donghaeng/infra` 로 | 덮어씀 |
| 7 | `cloud/remote-env.sh` — VM `infra/.env` 생성 | **있으면 그대로**(비밀·JWT 키 유지) |
| 8 | 재배포면 DB 백업(`~/donghaeng/backups/pg-*.sql.gz`, 최근 5개) → `compose up -d --no-build --remove-orphans --wait` → 공개 리스너 점검 | 바뀐 서비스만 다시 만듦 |
| 9 | 헬스체크(아래) → 앱 설정값 요약 | 그대로 |

헬스체크(이 PC → VM, 8개): `GET /actuator/health` UP(첫 배포는 인증서·서버 기동을 최대 240초 기다림) · `GET /v1/time` · `http://` → 308 `https://` ·
`POST /internal/mqtt/acl` → 404 · `openssl s_client -connect <host>:8883 -servername <host>` 검증 OK + SAN 확인 · 8883 위 MQTT CONNECT → CONNACK ·
`/mqtt` WebSocket 핸드셰이크 101(서브프로토콜 `mqtt`) · WSS 위 MQTT CONNECT → CONNACK. 익명 CONNECT 라 CONNACK 은 `0x05 not authorized` 가 정상이다(경로가 EMQX 까지 살아 있다는 뜻).

- 설정 파일만 바꿨을 때: `--skip-build`. 헬스체크만 다시: `bash infra/cloud/deploy.sh --check-only 203-0-113-10.sslip.io`.
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
- Caddy 액세스 로그는 켜지 않는다(경로에 초대 토큰 `/j/{token}`·페어링 코드가 들어감 — CLAUDE.md 규칙 8). 서버 프로필은 `cloud`(로컬의 DEBUG 로그 없음).
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

- **.env 를 고친 뒤**: `dc up -d` (바뀐 변수를 쓰는 서비스만 다시 만든다). 단 `POSTGRES_PASSWORD`·`EMQX_DASHBOARD_PASSWORD` 는 **빈 볼륨에 처음 뜰 때만** 적용된다 — 이후 바꾸려면 DB 안에서 `ALTER USER`, 대시보드에서 변경.
- **VM IP 가 바뀌었을 때**: `.env` 의 `DH_PUBLIC_HOST`·`DH_PUBLIC_BASE_URL`·`DH_MQTT_PUBLIC_URI` 세 줄을 새 `<IP>` 로 고치고 `bash infra/cloud/deploy.sh <user>@<새 IP> --skip-build`. 새 이름으로 인증서가 다시 발급되고, 차량 앱은 새 주소로 다시 빌드·페어링해야 한다(QR 주소가 바뀜).
- **JWT 키(`DH_JWT_PRIVATE_KEY_PEM`)를 바꾸면** 발급된 차량·사용자 토큰이 전부 무효가 된다 → 차량 재페어링 필요. `DH_CAR_ID_SALT` 는 바꾸지 않는다.

## 6. 롤백

| 대상 | 방법 |
|---|---|
| 서버 이미지 | VM 에서 `docker image ls donghaeng/server` 로 `cloud-<커밋>` 태그 확인 → `.env` 의 `DH_SERVER_TAG=cloud-<이전 커밋>` → `dc up -d server`. 한 번만 시험할 때는 `DH_SERVER_TAG=cloud-<커밋> dc up -d server`(셸 변수가 .env 보다 우선) |
| 설정(compose·Caddyfile) | 로컬에서 이전 커밋을 체크아웃하고 `bash infra/cloud/deploy.sh <user>@<IP> --skip-build` |
| DB | Flyway 는 전진 전용 — 마이그레이션이 든 버전을 되돌리면 이전 서버가 새 스키마를 못 읽을 수 있다. deploy.sh 가 재배포 직전에 남긴 백업(`~/donghaeng/backups/pg-<시각>.sql.gz`)으로 복원(아래) |
| 전체 중지 | `dc down` (볼륨 유지). **`dc down -v` 금지** — DB·EMQX 계정·Caddy 인증서가 지워지고 인증서 재발급 한도(주 5회)를 쓴다 |

```bash
# DB 복원 + 이전 서버 이미지 (VM, ~/donghaeng/infra)
dc stop server
dc exec -T postgres psql -U dh -d postgres -c 'DROP DATABASE donghaeng' -c 'CREATE DATABASE donghaeng OWNER dh'
gunzip -c ../backups/pg-<시각>.sql.gz | dc exec -T postgres psql -q -U dh -d donghaeng
DH_SERVER_TAG=cloud-<이전 커밋> dc up -d server      # 확인 뒤 .env 의 DH_SERVER_TAG 도 같은 값으로
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

## 8. 로컬 검증 기록 (VM 없이, 2026-10-28)

- `docker compose --env-file infra/cloud/.env.example -f infra/docker-compose.yml -f infra/cloud/docker-compose.cloud.yml config` 통과 — 병합 결과 공개 포트는 caddy 80/443/8883 뿐, emqx 는 `127.0.0.1:18083`, server 는 없음. 필수 변수(`DH_INTERNAL_TOKEN` 등)를 비우면 `required variable … is missing` 으로 거부.
- `remote-env.sh` 로 만든 `.env`: 비밀 6개 hex, PEM 한 줄 1732자 → `\n` 복원 시 `openssl pkey -check` OK(RSA 2048). 두 번째 실행은 파일 해시 그대로(멱등). compose 는 PEM 의 `\n` 을 두 글자 그대로 넘기고 서버가 `JWT 서명 키 로드 완료 bits=2048` 로 읽는다.
- caddy 이미지 빌드(Caddy v2.11.7 + caddy-l4 v0.1.2) + `caddy validate --adapter caddyfile`(메일 있음/없음) 통과. EMQX: ssl·wss 리스너 `running false`, `process_limit 131072`·`port_limit 65536`.
- 스모크 ①: 같은 Caddyfile + `local_certs` 로 로컬 스택 네트워크(`donghaeng_default`)에 붙여 `deploy.sh --check-only` **8/8 PASS**(Caddy 로컬 CA 로 인증서·호스트 이름 검증 포함). 검증 없이 붙으면 TLS 항목이 실패하는 것도 확인. 서버에 직접 `POST /internal/mqtt/acl` 은 200 → Caddy 의 404 가 차단이다.
- 스모크 ②: Caddy 를 거친 MQTT 연결(8883·WSS)이 **150초 무통신**(Caddy `idle 2m` 초과) 뒤에도 PINGREQ → PINGRESP 로 살아 있음.
- 스모크 ③: 별도 compose 프로젝트로 **클라우드 구성 전체**(이 오버라이드 + remote-env.sh 가 만든 .env + 이 브랜치로 빌드한 `donghaeng/server:cloud`, 호스트 포트만 바꾼 시험용 오버라이드)를 띄움 —
  `up --wait` 18초에 5개 healthy, 다시 `up` 하면 재생성 없음. `deploy.sh --check-only` 8/8 PASS. Redis 는 인증 없이 `NOAUTH`, 서버는 `DH_REDIS_PASSWORD` 로 붙음, 프로필 `cloud`, Postgres `shared_buffers 128MB / max_connections 40`.
  디바이스 페어링(QR `https://<host>/pair?c=…`, `mqtt.uri=ssl://<host>:8883`) → 차량 JWT 로 **8883·WSS 둘 다 CONNACK 0x00, 자기 cmd 구독 허용(0x01)·남의 cmd 거부(0x80)** — EMQX JWKS 인증과 `X-Internal-Token` ACL 훅이 클라우드 구성에서 동작. 8단계 DB 백업 스니펫도 같은 스택에서 확인.
- 이미지 전송 크기(gzip -1): server ≈ 139 MB, caddy ≈ 41 MB. Git Bash 에서 `docker save | gzip | gunzip | docker load` 파이프 무손상(이미지 ID 동일).
