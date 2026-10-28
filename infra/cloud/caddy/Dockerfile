# syntax=docker/dockerfile:1
# 동행 클라우드(T32) Caddy 이미지 — 표준 Caddy 2 + layer4 앱(github.com/mholt/caddy-l4).
#
# layer4 는 443 의 HTTP 사이트가 자동 발급한 <IP>.sslip.io 인증서로 8883 의 raw TCP 를 TLS 종단해
# emqx:1883 으로 넘긴다(MQTT over TLS). 인증서 소유자가 Caddy 하나라 EMQX 설정은 로컬과 같다(docs/06 §7).
#
# 빌드는 로컬 PC 에서 한다(VM 에서 Go 빌드 안 함). infra/cloud/deploy.sh 가
#   docker compose -f docker-compose.yml -f cloud/docker-compose.cloud.yml build caddy
# 로 만들어 docker save | gzip | ssh docker load 로 VM 에 올린다.
# caddy-l4 는 기본 브랜치를 따라가므로 재현이 필요하면 커밋(또는 태그)을 고정한다:
#   --build-arg CADDY_L4_VERSION=<커밋 해시>   (결과 확인: docker run --rm donghaeng/caddy:l4 caddy build-info)
FROM caddy:2-builder AS builder
ARG CADDY_L4_VERSION=
RUN xcaddy build --with "github.com/mholt/caddy-l4${CADDY_L4_VERSION:+@${CADDY_L4_VERSION}}"

FROM caddy:2
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
# layer4 가 실제로 들어갔는지 이미지 빌드 단계에서 확인한다(빠지면 빌드 실패).
RUN caddy list-modules | grep -q '^layer4$'
