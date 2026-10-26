# syntax=docker/dockerfile:1
# 동행 서버 이미지 — 멀티 스테이지 (빌드: JDK 21, 실행: JRE 21). infra/docker-compose 의 server 서비스가 빌드한다.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# 의존성 레이어 캐시 (소스 변경 시 재다운로드 방지)
RUN chmod +x gradlew && ./gradlew --no-daemon --console=plain dependencies > /dev/null || true
COPY src src
RUN ./gradlew --no-daemon --console=plain bootJar -x test

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /src/build/libs/server.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
