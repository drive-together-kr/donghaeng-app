// 동행 서버 — 단독 Gradle 프로젝트 (protocol/ 은 합성 빌드로 포함)
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "donghaeng-server"

// 공용 메시지·REST DTO 스키마: publish 없이 소스 프로젝트를 그대로 연결한다 (CLAUDE.md 규칙 7)
includeBuild("../protocol")
