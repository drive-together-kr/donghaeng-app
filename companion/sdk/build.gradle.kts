import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :sdk — 동행 모듈(AAR). 현대 블루링크·마이현대 / 기아 커넥트 / 제네시스 커넥티드 서비스 앱에 내장된다.
// 공개 API 는 DongHaeng 객체와 그 인자·결과 타입뿐이며, explicitApi() 로 모든 public 선언을 명시한다.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.donghaeng.companion.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // android.util.Log 같은 프레임워크 호출이 JVM 단위 테스트에서 예외 대신 기본값을 돌려주게 한다
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    // 공개 심볼은 전부 의도된 것이어야 한다 — public 수식어·반환 타입 누락 시 컴파일 오류
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 공용 스키마(MemberState, GroupEvent 등)가 공개 API(GroupSnapshot)에 그대로 노출되므로 api 로 전이한다.
    // 합성 빌드(includeBuild("../protocol"))가 ../protocol 소스로 치환한다 — 클래스 복사 금지(CLAUDE.md 규칙 7)
    api("app.donghaeng:protocol")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // HttpDongHaengApiTest — 실제 서버 없이 REST 교환·401 재교환·오류 봉투를 검증한다
    testImplementation(libs.okhttp.mockwebserver)
}
