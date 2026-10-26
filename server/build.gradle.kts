plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    id("org.springframework.boot") version "3.3.13"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "app.donghaeng"
version = "0.1.0"

// Spring Boot 3.3 BOM 의 Testcontainers(1.19.x)는 Docker API 1.32 를 고정 요청해 Docker Engine 29(최소 API 1.40)에서 400 이 난다.
// 1.21.4 부터 기본 API 1.44 → BOM 의 버전 속성만 덮어쓴다 (아티팩트 좌표는 그대로).
extra["testcontainers.version"] = "1.21.4"

repositories {
    mavenCentral()
}

kotlin {
    // JDK 21 toolchain — 로컬 JDK 경로는 ~/.gradle/gradle.properties 의
    // org.gradle.java.installations.paths 로 지정한다 (저장소에 커밋하지 않음).
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.bootJar {
    archiveFileName = "server.jar"
}
