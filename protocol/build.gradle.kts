import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    `java-library`
}

group = "app.donghaeng"
version = "0.1.0"

// 툴체인을 쓰지 않는다: Gradle 데몬 JVM 이 17 이든 21 이든 17 바이트코드로 빌드된다.
// (car-app 은 JDK 17, server 는 JDK 21 툴체인 — 둘 다 이 모듈을 그대로 포함한다)
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

dependencies {
    // 소비자(car-app, server)도 JsonObject/JsonElement 타입을 직접 다루므로 api 로 노출
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
