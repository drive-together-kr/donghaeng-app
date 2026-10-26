# infra — 로컬·클라우드 실행 환경

`docker compose`: postgres 16 (호스트 15432) · redis 7 (6379) · emqx 5.8 (1883 / 8083 / 18083) · server (8080). 클라우드는 동일 compose + Caddy(HTTPS, sslip.io).

- 설계: `docs/06_서버_설계.md` §6~§7
- 생성: T02 (`docs/08_구현계획서.md`), 클라우드 배포: T32
