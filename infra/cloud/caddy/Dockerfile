# syntax=docker/dockerfile:1
# 동행 클라우드(T32) Caddy 이미지 — 표준 Caddy 2 + layer4 앱(github.com/mholt/caddy-l4).
#
# layer4 는 443 의 HTTP 사이트가 자동 발급한 <IP>.sslip.io 인증서로 8883 의 raw TCP 를 TLS 종단해
# emqx:1883 으로 넘긴다(MQTT over TLS). 인증서 소유자가 Caddy 하나라 EMQX 설정은 로컬과 같다(docs/06 §7).
#
# 빌드는 로컬 PC 에서 한다(VM 에서 Go 빌드 안 함). infra/cloud/deploy.sh 가
#   docker compose -f docker-compose.yml -f cloud/docker-compose.cloud.yml build caddy
# 로 만들어 docker save | gzip | ssh docker load 로 VM 에 올리고, donghaeng/caddy:l4 + l4-<커밋>(롤백용) 태그를 붙인다.
#
# 버전 고정(재현·롤백): 기본 브랜치·최신 태그를 따라가지 않는다. caddy-l4 는 Caddyfile(layer4 블록) 문법이 바뀌어 온 모듈이라
# 다음 빌드가 다른 버전을 받으면 파싱이 깨질 수 있다. 올릴 때는 두 값을 함께 바꾸고 README §8 의 caddy validate 를 다시 돌린다.
#   CADDY_VERSION     Docker Hub caddy 태그 — caddy:<버전>-builder 의 xcaddy 가 같은 Caddy 버전을 빌드한다
#   CADDY_L4_VERSION  github.com/mholt/caddy-l4 의 태그(또는 커밋)
# 결과 확인: docker run --rm donghaeng/caddy:l4 caddy build-info
ARG CADDY_VERSION=2.11.7
ARG CADDY_L4_VERSION=v0.1.2

FROM caddy:${CADDY_VERSION}-builder AS builder
ARG CADDY_L4_VERSION
RUN xcaddy build --with "github.com/mholt/caddy-l4@${CADDY_L4_VERSION}"

FROM caddy:${CADDY_VERSION}
ARG CADDY_VERSION
ARG CADDY_L4_VERSION
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
# layer4 가 들어갔는지, 고정한 버전으로 빌드됐는지 이미지 빌드 단계에서 확인한다(다르면 빌드 실패).
RUN caddy list-modules | grep -q '^layer4$' \
 && caddy version | grep -q "^v${CADDY_VERSION} " \
 && caddy build-info | grep -qE "^dep[[:space:]]+github.com/mholt/caddy-l4[[:space:]]+${CADDY_L4_VERSION}[[:space:]]"
