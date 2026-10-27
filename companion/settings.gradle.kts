// companion — 동행 모듈(OEM 공식 앱 내장 SDK) + 시연용 호스트 앱 (독립 Gradle 빌드)
// 공용 스키마(protocol)는 합성 빌드(composite build)로 소스 연결한다 — CLAUDE.md 규칙 7.
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
    }
}

rootProject.name = "donghaeng-companion"

include(":sdk")
include(":demo-host")

// app.donghaeng:protocol → ../protocol 소스 프로젝트로 치환
includeBuild("../protocol")
