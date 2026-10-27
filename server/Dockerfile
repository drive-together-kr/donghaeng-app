# syntax=docker/dockerfile:1
# 동행 서버 이미지 — 멀티 스테이지 (빌드: JDK 21, 실행: JRE 21). infra/docker-compose 의 server 서비스가 빌드한다.
#
# 빌드 컨텍스트는 저장소 루트다 (compose: context .., dockerfile server/Dockerfile).
# server/settings.gradle.kts 가 includeBuild("../protocol") 로 공용 스키마를 합성 빌드하므로 protocol/ 을 같은 상대 경로에 둔다.
# 컨텍스트에 들어가는 파일은 server/Dockerfile.dockerignore 가 server/·protocol/ 로 제한한다 (BuildKit).
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src/server
# 공용 스키마 — /src/protocol (= ../protocol)
COPY protocol /src/protocol
COPY server/gradlew server/gradlew.bat server/settings.gradle.kts server/build.gradle.kts ./
COPY server/gradle gradle
# 의존성 레이어 캐시 (소스 변경 시 재다운로드 방지)
RUN chmod +x gradlew && ./gradlew --no-daemon --console=plain dependencies > /dev/null || true
COPY server/src src
RUN ./gradlew --no-daemon --console=plain bootJar -x test

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /src/server/build/libs/server.jar app.jar
# 루트가 아닌 사용자로 실행
RUN useradd --system --uid 10001 --no-create-home app
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
