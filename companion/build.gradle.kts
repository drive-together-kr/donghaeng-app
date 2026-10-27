// 루트 빌드 스크립트 — 플러그인 버전만 선언(apply false). 버전은 gradle/libs.versions.toml 에서 관리.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
