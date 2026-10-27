// 동행 차량 앱 — 단독 Gradle 빌드 (protocol/ 은 합성 빌드로 포함)
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Playground SDK (Vehicle / NaviHelper / FusedLocation) — 익명 접근
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}

rootProject.name = "donghaeng-car"

// 공용 메시지 스키마: publish 없이 소스 프로젝트를 그대로 연결한다 (CLAUDE.md 규칙 7)
includeBuild("../protocol")

include(":app")
