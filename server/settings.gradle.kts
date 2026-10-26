// 동행 서버 — 단독 Gradle 프로젝트 (protocol/ 의존은 T10에서 추가)
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "donghaeng-server"
