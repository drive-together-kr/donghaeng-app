#!/usr/bin/env bash
# 동행 클라우드(T32) — VM 쪽 .env 생성. deploy.sh 가 ssh 로 부른다(직접 실행도 가능).
#
#   사용: bash cloud/remote-env.sh <VM 공인 IPv4> [ACME 메일]      (VM 의 ~/donghaeng/infra 기준, 어디서 불러도 됨)
#
# - infra/.env 가 이미 있으면 기존 값은 건드리지 않는다(멱등 — 재배포해도 비밀·JWT 키가 바뀌지 않아 발급 토큰·DB 가 유지된다).
#   단, 템플릿에 나중에 추가된 CHANGE_ME 항목 중 볼륨·발급 토큰과 무관한 것(DH_DEV_LOGIN_KEY)이 없으면 그 줄만 덧붙인다.
# - 없으면 cloud/.env.example 을 읽어 <IP> 를 VM IP(점 표기 그대로)로 바꾸고, 값이 CHANGE_ME 인 항목을 openssl 로 만든 값으로 채운다.
#   비밀은 전부 hex(compose 보간 `$`, EMQX HOCON `"`·`}`, redis-server 인자 공백 회피), JWT 키는 RSA 2048 PKCS#8 PEM 한 줄(\n 두 글자).
# - 결과는 chmod 600. 값은 화면·로그에 찍지 않는다(키 이름만).
set -Eeuo pipefail

die() { printf 'remote-env: %s\n' "$*" >&2; exit 1; }

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$INFRA_DIR/cloud/.env.example"
ENV_FILE="$INFRA_DIR/.env"

IP="${1:-}"
EMAIL="${2:-}"

hex() { openssl rand -hex "$1"; }

# 기존 .env 에 덧붙여도 되는 항목(값을 새로 만들어도 DB·EMQX 볼륨·발급 토큰에 영향이 없는 것)과 생성 규칙
appendable_secret() {
  case "$1" in
    DH_DEV_LOGIN_KEY) hex 16 ;;
    *) return 1 ;;
  esac
}

if [ -e "$ENV_FILE" ]; then
  [ -r "$TEMPLATE" ] || { echo "remote-env: $ENV_FILE 이 이미 있어 그대로 둔다."; exit 0; }
  added=()
  while IFS= read -r key; do
    grep -q "^${key}=" "$ENV_FILE" && continue
    if value="$(appendable_secret "$key")"; then
      command -v openssl >/dev/null || die "openssl 이 필요해요 (sudo apt-get install -y openssl)"
      # 마지막 줄에 줄바꿈이 없으면 새 줄이 그 값 뒤에 붙지 않게 먼저 줄을 바꾼다
      [ -z "$(tail -c 1 "$ENV_FILE")" ] || echo >> "$ENV_FILE"
      printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
      added+=("$key")
    else
      echo "remote-env: 주의 — $ENV_FILE 에 $key 가 없어요. 자동으로 채우지 않는 항목이라 cloud/.env.example 의 생성 명령으로 직접 넣어 주세요." >&2
    fi
  done < <(grep -E '^[A-Z0-9_]+=CHANGE_ME$' "$TEMPLATE" | cut -d= -f1)
  if [ "${#added[@]}" -gt 0 ]; then
    chmod 600 "$ENV_FILE"
    echo "remote-env: $ENV_FILE 은 그대로 두고 새 항목만 덧붙였다: ${added[*]}"
  else
    echo "remote-env: $ENV_FILE 이 이미 있어 그대로 둔다(재생성하려면 백업 후 지우고 다시 실행)."
  fi
  exit 0
fi

[[ "$IP" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || die "VM 공인 IPv4 가 필요해요 (예: bash cloud/remote-env.sh 203.0.113.10)"
for octet in "${BASH_REMATCH[@]:1}"; do
  [ "$octet" -le 255 ] || die "IPv4 형식이 아니에요: $IP"
done
# 메일에는 공백·따옴표·$ 를 받지 않는다(.env 한 줄 값 + compose 보간 + Caddyfile 토큰).
email_re='^[^[:space:]@"$]+@[^[:space:]@"$]+\.[^[:space:]@"$]+$'
if [ -n "$EMAIL" ] && ! [[ "$EMAIL" =~ $email_re ]]; then
  die "메일 형식이 아니에요: $EMAIL"
fi
[ -r "$TEMPLATE" ] || die "템플릿이 없어요: $TEMPLATE"
command -v openssl >/dev/null || die "openssl 이 필요해요 (sudo apt-get install -y openssl)"

umask 077
tmp="$(mktemp "$INFRA_DIR/.env.tmp.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

# PKCS#8 PEM(-----BEGIN PRIVATE KEY-----) → 한 줄. awk 의 ORS "\\n" 은 역슬래시+n 두 글자다.
# sed 치환문에 PEM 을 넣으면 GNU sed 가 \n 을 실제 줄바꿈으로 바꾸므로 awk ENVIRON 으로 그대로 옮긴다(이스케이프 해석 없음).
pem="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null | awk 'BEGIN { ORS = "\\n" } { print }')"
case "$pem" in
  '-----BEGIN PRIVATE KEY-----\n'*) ;;
  *) die "RSA 키 생성 결과가 PKCS#8 PEM 이 아니에요 (openssl $(openssl version))" ;;
esac

export RE_LABEL="$IP"   # 점 표기 그대로(1.2.3.4.sslip.io) — sslip.io 는 하이픈형(1-2-3-4)도 같은 IP 로 풀어 준다
export RE_CADDY_EMAIL="$EMAIL"
export RE_POSTGRES_PASSWORD; RE_POSTGRES_PASSWORD="$(hex 24)"
export RE_DH_REDIS_PASSWORD; RE_DH_REDIS_PASSWORD="$(hex 24)"
export RE_EMQX_DASHBOARD_PASSWORD; RE_EMQX_DASHBOARD_PASSWORD="$(hex 12)"
export RE_EMQX_NODE_COOKIE; RE_EMQX_NODE_COOKIE="$(hex 16)"
export RE_DH_INTERNAL_TOKEN; RE_DH_INTERNAL_TOKEN="$(hex 32)"
export RE_DH_CAR_ID_SALT; RE_DH_CAR_ID_SALT="$(hex 32)"
export RE_DH_DEV_LOGIN_KEY; RE_DH_DEV_LOGIN_KEY="$(appendable_secret DH_DEV_LOGIN_KEY)"
export RE_DH_JWT_PRIVATE_KEY_PEM="$pem"

# 주석·빈 줄은 그대로 두고, 값 줄에서만 <IP> → 1.2.3.4, KEY=CHANGE_ME → RE_KEY 값, 빈 CADDY_EMAIL= → 메일(있으면).
awk '
  /^[[:space:]]*(#|$)/ { print; next }
  {
    gsub(/<IP>/, ENVIRON["RE_LABEL"])
    eq = index($0, "=")
    if (eq > 0) {
      key = substr($0, 1, eq - 1)
      val = substr($0, eq + 1)
      if (val == "CHANGE_ME") {
        if (!(("RE_" key) in ENVIRON) || ENVIRON["RE_" key] == "") { print "remote-env: 생성 규칙이 없는 CHANGE_ME 항목: " key > "/dev/stderr"; failed = 1; next }
        print key "=" ENVIRON["RE_" key]; next
      }
      if (key == "CADDY_EMAIL" && val == "") { print key "=" ENVIRON["RE_CADDY_EMAIL"]; next }
    }
    print
  }
  END { exit failed }
' "$TEMPLATE" > "$tmp"

if grep -vE '^[[:space:]]*#' "$tmp" | grep -qE '=CHANGE_ME$|<IP>'; then
  die "치환되지 않은 값이 남았어요(CHANGE_ME 또는 <IP>) — 템플릿을 확인하세요"
fi

chmod 600 "$tmp"
mv "$tmp" "$ENV_FILE"
trap - EXIT

printf 'remote-env: %s 생성(chmod 600). 공개 주소 https://%s.sslip.io\n' "$ENV_FILE" "$RE_LABEL"
printf 'remote-env: 채운 비밀: %s\n' "$(grep -oE '^(POSTGRES_PASSWORD|DH_REDIS_PASSWORD|EMQX_DASHBOARD_PASSWORD|EMQX_NODE_COOKIE|DH_INTERNAL_TOKEN|DH_CAR_ID_SALT|DH_JWT_PRIVATE_KEY_PEM|DH_DEV_LOGIN_KEY)=' "$ENV_FILE" | tr -d '=' | tr '\n' ' ')"
