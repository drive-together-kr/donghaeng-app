// protocol — 동행 공용 메시지 스키마 (독립 Gradle 빌드)
// car-app / server 에서는 includeBuild("../protocol") 로 합성 빌드(composite build)한다.
rootProject.name = "protocol"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
