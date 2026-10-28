#!/usr/bin/env bash
# 동행 클라우드(T32) 배포 — 로컬 PC 에서 실행한다(Windows Git Bash · macOS · Linux). infra/cloud/README.md
#
#   bash infra/cloud/deploy.sh <user>@<VM-IP> [-i <ssh 키>] [--skip-build] [--email <ACME 메일>] [--ip <공인 IPv4>]
#   bash infra/cloud/deploy.sh --check-only <host>          # 배포 없이 헬스체크만 (예: 1-2-3-4.sslip.io)
#
#   -i <키>        ssh 개인키 (ssh -i 그대로)
#   --skip-build   이미지 빌드·전송을 건너뛴다(설정 파일·.env 만 바꿀 때). VM 에 이미지가 이미 있어야 한다.
#   --email <메일>  ACME 계정 메일. VM 에 .env 가 처음 만들어질 때만 반영된다.
#   --ip <IPv4>    공인 IP. <user>@<host> 의 host 가 ssh 별칭일 때 지정(기본: host 자체).
#
# 단계(재실행 안전 — 이미 돼 있는 것은 건너뛴다):
#   1 ssh·sudo·OS → 2 Docker(get.docker.com)·docker 그룹·Compose ≥ 2.24.4 → 3 ufw 22/80/443/8883 tcp → 4 스왑 2 GB
#   → 5 로컬 이미지 빌드(server·caddy) + docker save | gzip | ssh docker load (VM 과 같은 이미지면 생략)
#   → 6 infra 파일 전송(tar | ssh, .env 제외) → 7 cloud/remote-env.sh(.env 없을 때만) → 8 compose up -d --wait
#   → 9 헬스체크(HTTPS·리다이렉트·/internal 차단·8883 TLS·8883 MQTT CONNACK·WSS 101·WSS MQTT CONNACK) → 요약
#
# 실패하면 ERR trap 이 원격 compose ps·logs(caddy·server·emqx)를 찍고 멈춘다.
# 헬스체크 환경 변수(로컬 스모크 테스트용, 평소에는 쓰지 않음):
#   DH_CHECK_ADDR=<IP>     DNS 대신 이 주소로 접속(curl --resolve, openssl -connect)
#   DH_CHECK_INSECURE=1    인증서 검증 생략      DH_CHECK_CAFILE=<root.crt>  이 CA 로 검증(Caddy local_certs 시험용)
#   DH_CHECK_HTTP_PORT / DH_CHECK_HTTPS_PORT / DH_CHECK_MQTTS_PORT   기본 80 / 443 / 8883
#   DH_CHECK_WAIT_S        첫 HTTPS 응답 대기 상한(초, 기본 240 — 인증서 발급 + 서버 기동)
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$INFRA_DIR/.." && pwd)"
REMOTE_DIR="donghaeng/infra"   # 원격 $HOME 기준
REMOTE_COMPOSE_FILES="-f docker-compose.yml -f cloud/docker-compose.cloud.yml"
SERVER_IMAGE="donghaeng/server:cloud"
CADDY_IMAGE="donghaeng/caddy:l4"

say()  { printf '%s\n' "$*"; }
CURRENT_STEP="준비"
step() { CURRENT_STEP="[$1] $2"; printf '\n\033[1m== %s\033[0m\n' "$CURRENT_STEP"; }
warn() { printf '\033[33m!! %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[31mdeploy: %s\033[0m\n' "$*" >&2; exit 1; }
usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"; }
with_timeout() { if command -v timeout >/dev/null 2>&1; then timeout "$@"; else shift; "$@"; fi; }

# ------------------------------------------------------------------ 인자
TARGET=""; SSH_KEY=""; SKIP_BUILD=0; EMAIL=""; PUBLIC_IP=""; CHECK_ONLY=""
while (($#)); do
  case "$1" in
    -i) SSH_KEY="${2:?-i 다음에 ssh 키 경로가 필요해요}"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --email) EMAIL="${2:?--email 다음에 메일 주소가 필요해요}"; shift 2 ;;
    --ip) PUBLIC_IP="${2:?--ip 다음에 IPv4 가 필요해요}"; shift 2 ;;
    --check-only) CHECK_ONLY="${2:?--check-only 다음에 호스트 이름이 필요해요}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) die "모르는 옵션: $1 (--help)" ;;
    *) [ -z "$TARGET" ] || die "대상(<user>@<VM-IP>)은 하나만 줄 수 있어요"; TARGET="$1"; shift ;;
  esac
done

is_ipv4() {
  local o
  [[ "$1" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
  for o in "${BASH_REMATCH[@]:1}"; do [ "$o" -le 255 ] || return 1; done
}

# ================================================================== 헬스체크 (이 PC → 공개 주소)
# errexit/ERR trap 없이 돈다(실패는 결과표에 모은다).
HOST=""
CHECK_ADDR="${DH_CHECK_ADDR:-}"
CHECK_INSECURE="${DH_CHECK_INSECURE:-0}"
CHECK_CAFILE="${DH_CHECK_CAFILE:-}"
HTTP_PORT="${DH_CHECK_HTTP_PORT:-80}"
HTTPS_PORT="${DH_CHECK_HTTPS_PORT:-443}"
MQTTS_PORT="${DH_CHECK_MQTTS_PORT:-8883}"
CHECK_WAIT_S="${DH_CHECK_WAIT_S:-240}"
WSS_KEY="ZG9uZ2hhZW5nLXByb2JlIQ=="
RESULTS=()
FAILED=0

record() { # record PASS|FAIL <항목> <상세>
  RESULTS+=("$1  $2 — $3")
  [ "$1" = PASS ] || FAILED=$((FAILED + 1))
}

port_suffix() { [ "$1" = "$2" ] || printf ':%s' "$1"; }   # port_suffix <port> <기본 port>

to_hex() { od -An -v -tx1 | tr -d ' \n'; }

tls_open() { # tls_open <port> [s_client 추가 인자...] — stdin 을 TLS 로 보내고 받은 바이트를 stdout 으로
  local port="$1"; shift
  local args=(s_client -connect "${CHECK_ADDR:-$HOST}:$port" -servername "$HOST")
  [ "$CHECK_INSECURE" = 1 ] || args+=(-verify_return_error -verify_hostname "$HOST")
  [ -z "$CHECK_CAFILE" ] || args+=(-CAfile "$CHECK_CAFILE")
  with_timeout 15 openssl "${args[@]}" "$@"
}

# MQTT 3.1.1 CONNECT(clean session, keepalive 60 s, 사용자 정보 없음). 브로커는 CONNACK 0x05(not authorized)로 답하고 끊는다 —
# 어떤 CONNACK 이든 오면 "Caddy TLS 종단 → EMQX" 경로가 살아 있다는 뜻이다(차량 앱은 vid + JWT 로 접속).
mqtt_connect_packet() { # mqtt_connect_packet <client-id>
  local id="$1"
  # shellcheck disable=SC2059  # 형식 문자열에 바이트 이스케이프를 조립한다
  printf "\\x10\\x$(printf '%02x' $((12 + ${#id})))\\x00\\x04MQTT\\x04\\x02\\x00\\x3c\\x00\\x$(printf '%02x' "${#id}")%s" "$id"
}

connack_meaning() {
  case "$1" in
    00) echo "accepted" ;; 01) echo "bad protocol" ;; 02) echo "id rejected" ;; 03) echo "server unavailable" ;;
    04) echo "bad username/password" ;; 05) echo "not authorized (익명 거부 = 정상)" ;; *) echo "code 0x$1" ;;
  esac
}

run_checks() {
  local base http_base code body loc hex rc waited=0 tls_out cert pkt frame help
  CURL=(curl -sS --max-time 15)
  if [ "$CHECK_INSECURE" = 1 ]; then
    CURL+=(-k)
  else
    help="$(curl --help all 2>/dev/null || true)"
    # Windows(Schannel) curl: 폐기 목록 서버에 못 닿는 것을 검증 실패로 보지 않는다
    [[ "$help" != *--ssl-revoke-best-effort* ]] || CURL+=(--ssl-revoke-best-effort)
  fi
  [ -z "$CHECK_CAFILE" ] || CURL+=(--cacert "$CHECK_CAFILE")
  [ -z "$CHECK_ADDR" ] || CURL+=(--resolve "$HOST:$HTTPS_PORT:$CHECK_ADDR" --resolve "$HOST:$HTTP_PORT:$CHECK_ADDR")
  base="https://$HOST$(port_suffix "$HTTPS_PORT" 443)"
  http_base="http://$HOST$(port_suffix "$HTTP_PORT" 80)"
  say "대상 $base · MQTT TLS :$MQTTS_PORT${CHECK_ADDR:+ · 접속 주소 $CHECK_ADDR}$([ "$CHECK_INSECURE" != 1 ] || echo ' · 인증서 검증 생략')"

  # 1) HTTPS — 첫 배포는 인증서 발급(수십 초) + 서버 기동을 기다린다
  while :; do
    body="$("${CURL[@]}" -f "$base/actuator/health" 2>/dev/null)"
    [[ "$body" != *'"status":"UP"'* ]] || break
    if [ "$waited" -ge "$CHECK_WAIT_S" ]; then body=""; break; fi
    [ "$waited" -gt 0 ] || say "  HTTPS 응답 대기(인증서 발급·서버 기동, 최대 ${CHECK_WAIT_S}s) …"
    sleep 5; waited=$((waited + 5))
  done
  if [ -n "$body" ]; then
    record PASS "HTTPS /actuator/health" "UP (대기 ${waited}s)"
  else
    record FAIL "HTTPS /actuator/health" "$("${CURL[@]}" -o /dev/null -w 'HTTP %{http_code}' "$base/actuator/health" 2>&1 | tail -1)"
  fi

  # 2) REST 시각 API(인증 없음)
  body="$("${CURL[@]}" -f "$base/v1/time" 2>/dev/null)"
  if [[ "$body" == *serverTimeMs* ]]; then record PASS "HTTPS /v1/time" "$body"; else record FAIL "HTTPS /v1/time" "${body:-응답 없음}"; fi

  # 3) HTTP → HTTPS 리다이렉트(Caddy 자동)
  code="$("${CURL[@]}" -o /dev/null -w '%{http_code} %{redirect_url}' "$http_base/v1/time" 2>/dev/null)"
  loc="${code#* }"; code="${code%% *}"
  if [[ "$code" =~ ^30[178]$ && "$loc" == "https://$HOST/"* ]]; then
    record PASS "HTTP → HTTPS 리다이렉트" "$code → $loc"
  else
    record FAIL "HTTP → HTTPS 리다이렉트" "${code:-응답 없음} ${loc}"
  fi

  # 4) /internal/* 외부 차단(EMQX ACL 훅은 compose 네트워크 전용)
  code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' "$base/internal/mqtt/acl" 2>/dev/null)"
  if [ "$code" = 404 ]; then record PASS "/internal/mqtt/acl 차단" "404"; else record FAIL "/internal/mqtt/acl 차단" "${code:-응답 없음} (404 기대)"; fi

  # 5) 8883 TLS 핸드셰이크 + 인증서(443 사이트가 관리하는 같은 호스트 인증서를 SNI 로 고른다)
  tls_out="$(tls_open "$MQTTS_PORT" -brief </dev/null 2>&1)"
  cert="$(tls_open "$MQTTS_PORT" </dev/null 2>/dev/null | openssl x509 -noout -ext subjectAltName -issuer -enddate 2>/dev/null | tr -s ' \n' ' ')"
  if [[ "$tls_out" == *"CONNECTION ESTABLISHED"* && "$cert" == *"DNS:$HOST"* ]]; then
    record PASS "8883 TLS 핸드셰이크" "$(printf '%s\n' "$tls_out" | grep -E '^(Protocol version|Verification)' | tr '\n' ' ')· $cert"
  else
    record FAIL "8883 TLS 핸드셰이크" "$(printf '%s\n' "$tls_out" | tail -2 | tr '\n' ' ')· ${cert:-인증서 없음}"
  fi

  # 6) 8883 위 MQTT CONNECT → CONNACK (TLS 종단 → emqx:1883)
  hex="$( { mqtt_connect_packet dh-probe; sleep 3; } | tls_open "$MQTTS_PORT" -quiet 2>/dev/null | to_hex)"
  if [[ "$hex" == 2002* ]]; then
    rc="${hex:6:2}"; record PASS "8883 MQTT CONNACK" "0x$rc $(connack_meaning "$rc")"
  else
    record FAIL "8883 MQTT CONNACK" "받은 바이트: ${hex:0:24}"
  fi

  # 7) WSS 핸드셰이크(443 /mqtt → emqx:8083) — 101 Switching Protocols
  code="$("${CURL[@]}" --http1.1 --max-time 5 -o /dev/null -w '%{http_code}' \
    -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
    -H "Sec-WebSocket-Key: $WSS_KEY" -H 'Sec-WebSocket-Protocol: mqtt' "$base/mqtt" 2>/dev/null)"
  if [ "$code" = 101 ]; then record PASS "WSS /mqtt 핸드셰이크" "101"; else record FAIL "WSS /mqtt 핸드셰이크" "${code:-응답 없음} (101 기대)"; fi

  # 8) WSS 위 MQTT CONNECT → CONNACK (클라이언트 프레임: FIN+binary, 마스크 키 0)
  pkt="$(mqtt_connect_packet dh-wss | to_hex)"
  frame="\\x82\\x$(printf '%02x' $((0x80 | ${#pkt} / 2)))\\x00\\x00\\x00\\x00$(printf '%s' "$pkt" | sed 's/../\\x&/g')"
  hex="$( {
      printf 'GET /mqtt HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Protocol: mqtt\r\n\r\n' "$HOST" "$WSS_KEY"
      sleep 1
      # shellcheck disable=SC2059  # frame 은 바이트 이스케이프 문자열
      printf "$frame"
      sleep 3
    } | tls_open "$HTTPS_PORT" -quiet 2>/dev/null | to_hex)"
  # "HTTP/1.1 101" 로 시작하고, 서버 프레임 82 04 + CONNACK 20 02 xx rc 가 따라온다
  if [[ "$hex" == 485454502f312e3120313031* && "$hex" == *82042002* ]]; then
    rc="${hex#*82042002}"; rc="${rc:2:2}"
    record PASS "WSS MQTT CONNACK" "0x$rc $(connack_meaning "$rc")"
  else
    record FAIL "WSS MQTT CONNACK" "받은 바이트: ${hex:0:32}"
  fi

  printf '\n%s\n' "---------------------------------------------------------------- 헬스체크 결과"
  printf '%s\n' "${RESULTS[@]}"
  printf 'PASS %d / %d\n' $(( ${#RESULTS[@]} - FAILED )) "${#RESULTS[@]}"
}

if [ -n "$CHECK_ONLY" ]; then
  [ -z "$TARGET" ] || die "--check-only 와 배포 대상은 함께 쓰지 않아요"
  HOST="$CHECK_ONLY"
  set +e
  run_checks
  [ "$FAILED" -eq 0 ] || exit 1
  exit 0
fi

# ================================================================== 배포
[ -n "$TARGET" ] || { usage; exit 2; }
[[ "$TARGET" == *@* ]] || die "대상은 <user>@<VM-IP> 꼴이어야 해요: $TARGET"
[ -n "$PUBLIC_IP" ] || PUBLIC_IP="${TARGET#*@}"
is_ipv4 "$PUBLIC_IP" || die "공인 IPv4 를 알 수 없어요: '$PUBLIC_IP' — ssh 별칭을 쓰면 --ip <공인 IPv4> 를 함께 주세요"
EXPECTED_HOST="${PUBLIC_IP//./-}.sslip.io"
HOST="$EXPECTED_HOST"

for tool in ssh tar gzip curl openssl od; do command -v "$tool" >/dev/null 2>&1 || die "$tool 이 필요해요"; done
cd "$REPO_DIR"   # 아래 docker compose 는 저장소 루트 기준 상대 경로(Windows docker.exe 에 MSYS 경로를 넘기지 않게)
if [ "$SKIP_BUILD" = 0 ]; then
  # Git Bash 는 PATH 에 Docker Desktop 이 없을 수 있다 → %ProgramFiles%\Docker\Docker\resources\bin\docker.exe
  desktop_docker=""
  if [ -n "${PROGRAMFILES:-}" ] && command -v cygpath >/dev/null 2>&1; then
    desktop_docker="$(cygpath -u "$PROGRAMFILES")/Docker/Docker/resources/bin/docker.exe"
  fi
  if command -v docker >/dev/null 2>&1; then
    DOCKER=(docker)
  elif [ -n "$desktop_docker" ] && [ -x "$desktop_docker" ]; then
    DOCKER=("$desktop_docker")
  else
    die "docker CLI 를 찾지 못했어요(Docker Desktop 실행 확인) — 이미지가 VM 에 이미 있으면 --skip-build"
  fi
fi

SSH=(ssh -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30 -o ServerAliveCountMax=6)
[ -z "$SSH_KEY" ] || SSH+=(-i "$SSH_KEY")
SSH+=("$TARGET")
rsh() { "${SSH[@]}" "$@"; }            # 원격 명령 문자열
rscript() { "${SSH[@]}" "bash -s"; }   # stdin(heredoc) 스크립트를 원격 bash 로
RDOCKER="docker"
RCOMPOSE="docker compose $REMOTE_COMPOSE_FILES"
REMOTE_READY=0

remote_diagnostics() {
  [ "$REMOTE_READY" = 1 ] || return 0
  say ""
  say "---------------------------------------------------------------- 원격 진단 (compose ps · logs --tail=60)"
  rsh "cd $REMOTE_DIR 2>/dev/null && $RCOMPOSE ps -a; $RCOMPOSE logs --no-color --tail=60 caddy server emqx" || true
}

on_err() {
  local rc="$1" line="$2" cmd="$3"
  trap - ERR
  set +e
  printf '\n\033[31m!! 실패 — 단계 %s (exit %s, deploy.sh:%s): %s\033[0m\n' "$CURRENT_STEP" "$rc" "$line" "$cmd" >&2
  remote_diagnostics
  say "같은 명령으로 다시 실행해도 된다(끝난 단계는 건너뛴다). 롤백·문제 해결: infra/cloud/README.md §6·§7"
  exit "$rc"
}
trap 'on_err $? $LINENO "$BASH_COMMAND"' ERR

say "동행 클라우드 배포 → $TARGET  (공개 주소 https://$EXPECTED_HOST)"

# ------------------------------------------------------------------ 1. ssh · sudo · OS
step 1/9 "ssh 접속 · sudo · OS 확인"
rscript <<'EOF'
set -Eeuo pipefail
# shellcheck source=/dev/null
. /etc/os-release
echo "host=$(hostname) os=${PRETTY_NAME} arch=$(uname -m) cpus=$(nproc) mem_mb=$(free -m | awk '/^Mem:/{print $2}')"
[ "$(uname -m)" = x86_64 ] || { echo "x86_64 VM 이 아니에요(이미지가 linux/amd64)" >&2; exit 1; }
sudo -n true 2>/dev/null || { echo "비밀번호 없는 sudo 가 필요해요(클라우드 기본 사용자는 보통 가능)" >&2; exit 1; }
[ "${ID:-}" = ubuntu ] || echo "주의: Ubuntu 가 아니에요(${PRETTY_NAME}) — Ubuntu 24.04 기준 스크립트"
EOF

# ------------------------------------------------------------------ 2. Docker
step 2/9 "Docker Engine · docker 그룹 · Compose 버전"
rscript <<'EOF'
set -Eeuo pipefail
if command -v docker >/dev/null 2>&1; then
  echo "docker 이미 설치됨: $(docker --version)"
else
  echo "docker 설치(get.docker.com) …"
  curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
  # shellcheck disable=SC2024  # 설치 로그는 사용자 권한으로 /tmp 에 남긴다(의도)
  sudo sh /tmp/get-docker.sh </dev/null >/tmp/get-docker.log 2>&1 || { tail -30 /tmp/get-docker.log >&2; exit 1; }
  rm -f /tmp/get-docker.sh
  echo "설치 완료: $(docker --version)"
fi
sudo systemctl enable --now docker >/dev/null 2>&1 || true
if id -nG "$USER" | tr ' ' '\n' | grep -qx docker; then
  echo "$USER 는 docker 그룹"
else
  sudo usermod -aG docker "$USER"
  echo "$USER 를 docker 그룹에 추가(다음 ssh 접속부터 sudo 없이 docker)"
fi
v="$(sudo docker compose version --short 2>/dev/null || true)"
[ -n "$v" ] || { echo "docker compose 플러그인이 없어요: sudo apt-get install -y docker-compose-plugin" >&2; exit 1; }
v="${v#v}"; IFS=. read -r ma mi pa _ <<<"$v"; pa="${pa%%[!0-9]*}"
# docker-compose.cloud.yml 의 !override / !reset 태그는 Compose 2.24.4 이상
if [ "$ma" -lt 2 ] || { [ "$ma" -eq 2 ] && { [ "$mi" -lt 24 ] || { [ "$mi" -eq 24 ] && [ "${pa:-0}" -lt 4 ]; }; }; }; then
  echo "docker compose $v 는 2.24.4 미만이에요 — sudo apt-get install -y docker-compose-plugin 으로 올려 주세요" >&2; exit 1
fi
echo "docker compose $v"
EOF
if rsh 'docker info >/dev/null 2>&1'; then
  RDOCKER="docker"
else
  RDOCKER="sudo docker"; warn "이번 접속에는 docker 그룹이 아직 안 보여 sudo docker 로 진행"
fi
RCOMPOSE="$RDOCKER compose $REMOTE_COMPOSE_FILES"

# ------------------------------------------------------------------ 3. 방화벽
step 3/9 "ufw: 22(ssh)·80·443·8883 tcp 만 허용"
rscript <<'EOF'
set -Eeuo pipefail
if ! command -v ufw >/dev/null 2>&1; then
  echo "ufw 설치 …"
  sudo apt-get update -qq </dev/null
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ufw </dev/null
fi
# 지금 이 ssh 가 들어온 포트도 연다(22 가 아닌 VM 에서 잠기지 않게)
ssh_port="$(printf '%s' "${SSH_CONNECTION:-}" | awk '{print $4}')"
for p in 22 "${ssh_port:-22}" 80 443 8883; do sudo ufw allow "$p/tcp" >/dev/null; done
sudo ufw --force enable >/dev/null
sudo ufw status | sed -n '1,20p'
echo "※ Docker 가 publish 한 포트는 ufw 를 우회한다 — 비공개 포트는 compose 에서 loopback/미공개로 막는다(README §3)."
EOF

# ------------------------------------------------------------------ 4. 스왑
step 4/9 "스왑 2 GB (없을 때만) · swappiness 10"
rscript <<'EOF'
set -Eeuo pipefail
if [ -n "$(swapon --show --noheadings 2>/dev/null)" ]; then
  echo "스왑 이미 있음"
else
  if [ ! -f /swapfile ]; then
    sudo fallocate -l 2G /swapfile 2>/dev/null || sudo dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile >/dev/null
  fi
  sudo swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
  echo "스왑 /swapfile 2 GB 켬"
fi
# RAM 이 남아 있는 동안은 스왑을 거의 쓰지 않는다(JVM 힙이 스왑으로 밀리면 GC 가 멈춘다)
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-donghaeng.conf >/dev/null
sudo sysctl -q -p /etc/sysctl.d/99-donghaeng.conf
swapon --show
free -m | sed -n '1,3p'
EOF

# ------------------------------------------------------------------ 5. 이미지
if [ "$SKIP_BUILD" = 1 ]; then
  step 5/9 "이미지 빌드·전송 건너뜀(--skip-build)"
  rsh "$RDOCKER image inspect $SERVER_IMAGE $CADDY_IMAGE >/dev/null" || die "VM 에 $SERVER_IMAGE / $CADDY_IMAGE 가 없어요 — --skip-build 없이 실행"
else
  step 5/9 "로컬 이미지 빌드(server·caddy) → VM 으로 전송"
  REV="$(git -C "$REPO_DIR" rev-parse --short=10 HEAD 2>/dev/null || echo unknown)"
  if [ -n "$(git -C "$REPO_DIR" status --porcelain -- server protocol infra/cloud/caddy 2>/dev/null || true)" ]; then REV="$REV-dirty"; fi
  # 빌드는 기존 compose 정의(server: context .. + server/Dockerfile)를 그대로 쓰고, 이미지 이름만 오버라이드의 cloud 태그다.
  # 프로비넌스 attestation 을 끄면 save/load 가 단일 플랫폼 이미지로 단순해진다.
  BUILDX_NO_DEFAULT_ATTESTATIONS=1 "${DOCKER[@]}" compose --env-file infra/cloud/.env.example \
    -f infra/docker-compose.yml -f infra/cloud/docker-compose.cloud.yml build server caddy
  "${DOCKER[@]}" tag "$SERVER_IMAGE" "donghaeng/server:cloud-$REV"
  say "server 이미지 태그: $SERVER_IMAGE + donghaeng/server:cloud-$REV (롤백용)"

  SHIP=()
  for img in "$SERVER_IMAGE" "$CADDY_IMAGE"; do
    local_id="$("${DOCKER[@]}" image inspect -f '{{.Id}}' "$img")"
    remote_id="$(rsh "$RDOCKER image inspect -f '{{.Id}}' $img 2>/dev/null" || true)"
    if [ "$local_id" = "$remote_id" ]; then say "  $img: VM 과 같음 → 전송 생략"; else SHIP+=("$img"); fi
  done
  if [ "${#SHIP[@]}" -gt 0 ]; then
    save_help="$("${DOCKER[@]}" save --help 2>/dev/null || true)"
    SAVE_ARGS=(save)
    [[ "$save_help" != *--platform* ]] || SAVE_ARGS+=(--platform linux/amd64)
    say "  전송: ${SHIP[*]} (gzip, 회선에 따라 수 분)"
    "${DOCKER[@]}" "${SAVE_ARGS[@]}" "${SHIP[@]}" | gzip -1 | rsh "gunzip | $RDOCKER load"
  fi
  rsh "$RDOCKER tag $SERVER_IMAGE donghaeng/server:cloud-$REV"
  rsh "$RDOCKER image ls --format '{{.Repository}}:{{.Tag}}  {{.Size}}' | grep -E '^donghaeng/' | head -12 || true"
fi

# ------------------------------------------------------------------ 6. 파일
step 6/9 "infra 파일 전송 → ~/$REMOTE_DIR (.env 는 보내지 않음)"
# Git Bash 에 rsync 가 없어 tar | ssh 로 보낸다. 아래 목록만 — infra/.env 같은 비밀 파일은 포함하지 않는다.
COPYFILE_DISABLE=1 tar -C "$INFRA_DIR" -czf - \
  docker-compose.yml \
  cloud/docker-compose.cloud.yml cloud/Caddyfile cloud/.env.example cloud/remote-env.sh cloud/README.md cloud/caddy/Dockerfile \
  | rsh "mkdir -p $REMOTE_DIR && tar -C $REMOTE_DIR -xzf - && ls -l $REMOTE_DIR/docker-compose.yml $REMOTE_DIR/cloud"
REMOTE_READY=1

# ------------------------------------------------------------------ 7. .env
step 7/9 "VM .env (없을 때만 생성 — cloud/remote-env.sh)"
rsh "cd $REMOTE_DIR && bash cloud/remote-env.sh $(printf '%q' "$PUBLIC_IP") $(printf '%q' "$EMAIL")"
HOST="$(rsh "grep -E '^DH_PUBLIC_HOST=' $REMOTE_DIR/.env | cut -d= -f2-")"
[ -n "$HOST" ] || die "VM .env 에 DH_PUBLIC_HOST 가 없어요"
[ "$HOST" = "$EXPECTED_HOST" ] || warn "VM .env 의 DH_PUBLIC_HOST=$HOST 가 이번 IP($EXPECTED_HOST)와 달라요 — IP 가 바뀌었으면 README §5"
rsh "stat -c '권한 %a  %n' $REMOTE_DIR/.env"

# ------------------------------------------------------------------ 8. 기동
step 8/9 "DB 백업(재배포일 때) → docker compose up -d --wait (바뀐 서비스만 다시 만든다)"
# Flyway 는 전진 전용이라 마이그레이션이 든 버전을 되돌릴 때는 이 백업이 필요하다(README §6). 최근 5개만 남긴다.
{ printf 'RCOMPOSE=%q\nREMOTE_DIR=%q\n' "$RCOMPOSE" "$REMOTE_DIR"; cat <<'EOF'
set -Eeuo pipefail
cd "$REMOTE_DIR"
$RCOMPOSE config -q
if [ -n "$($RCOMPOSE ps -q --status running postgres 2>/dev/null || true)" ]; then
  umask 077
  mkdir -p ../backups
  f="../backups/pg-$(date +%Y%m%d-%H%M%S).sql.gz"
  # shellcheck disable=SC2016  # $POSTGRES_* 는 postgres 컨테이너 안에서 풀린다(의도)
  $RCOMPOSE exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | gzip > "$f"
  echo "DB 백업: ~/donghaeng/backups/$(basename "$f") ($(du -h "$f" | cut -f1))"
  # shellcheck disable=SC2012  # 파일 이름은 이 스크립트가 만든 pg-<시각>.sql.gz 뿐
  ls -1t ../backups/pg-*.sql.gz | tail -n +6 | xargs -r rm -f
else
  echo "DB 백업 생략(postgres 가 아직 없음 — 첫 배포)"
fi
EOF
} | rscript
rsh "cd $REMOTE_DIR && $RCOMPOSE up -d --no-build --remove-orphans --wait --wait-timeout 300 && $RCOMPOSE ps"
LISTEN="$(rsh "sudo ss -Htln" | awk '{print $4}' | grep -vE '^(127\.|\[::1\]|::1:)' | sort -u || true)"
say "인터넷 쪽 TCP 리스너(기대: ssh·80·443·8883):"
printf '%s\n' "$LISTEN" | sed 's/^/  /'
UNEXPECTED="$(printf '%s\n' "$LISTEN" | sed -E 's/.*:([0-9]+)$/\1/' | grep -vxE '22|80|443|8883' | sort -u || true)"
[ -z "$UNEXPECTED" ] || warn "예상 밖 공개 포트: $(printf '%s' "$UNEXPECTED" | tr '\n' ' ') — ssh 포트가 아니라면 확인(README §3)"

# ------------------------------------------------------------------ 9. 헬스체크
step 9/9 "헬스체크 (이 PC → https://$HOST)"
trap - ERR
set +e
run_checks
if [ "$FAILED" -gt 0 ]; then
  remote_diagnostics
  die "헬스체크 $FAILED 개 실패 — README §7(문제 해결). 인증서가 안 나오면 클라우드 보안 그룹의 80/443 인바운드부터 확인"
fi

cat <<EOT

================================================================ 배포 완료
  서버 공개 주소   DH_PUBLIC_BASE_URL = https://$HOST
  MQTT 기본        DH_MQTT_PUBLIC_URI = ssl://$HOST:8883
  MQTT 대체        wss://$HOST/mqtt  (443 — 8883 이 막힌 망)
  차량 앱 빌드     cd car-app && ./gradlew :app:assemblePleosDebug -PdhPublicBaseUrl=https://$HOST -PdhMqttPublicUri=ssl://$HOST:8883
  시연 스크립트    python tools/demo/demo.py --server https://$HOST …
  EMQX 대시보드    ssh -L 18083:127.0.0.1:18083 $TARGET  → http://localhost:18083 (admin / VM .env 의 EMQX_DASHBOARD_PASSWORD)
  롤백             README §6 — VM .env 의 DH_SERVER_TAG=cloud-<이전 커밋> 후 up -d server
EOT
