import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :demo-host — 공식 앱(블루링크·마이현대·기아 커넥트 등)을 흉내 낸 시연용 호스트 앱.
// 실제 공식 앱이 하는 일(로그인·내 차 목록·QR 스캐너)을 최소로 구현하고 :sdk 의 공개 API만 호출한다.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 서버 공개 주소: -PdhPublicBaseUrl=... 또는 gradle.properties 의 dhPublicBaseUrl. 기본은 에뮬레이터 → 호스트 PC
val dhPublicBaseUrl: String = providers.gradleProperty("dhPublicBaseUrl")
    .orElse("http://10.0.2.2:8080")
    .get()
    .trimEnd('/')

// dev 로그인 접속 키: 클라우드 서버(T32)는 DH_DEV_LOGIN_KEY 를 요구한다 → -PdhDevLoginKey=... 또는 ~/.gradle/gradle.properties 의 dhDevLoginKey.
// 값은 VM 의 infra/.env 에만 있다 — 저장소의 gradle.properties 에 적지 않는다(CLAUDE.md 규칙 9). 기본 빈 값 = 헤더 안 보냄(로컬 서버는 검사하지 않음).
val dhDevLoginKey: String = providers.gradleProperty("dhDevLoginKey")
    .orElse("")
    .get()
    .trim()
require(dhDevLoginKey.matches(Regex("[A-Za-z0-9._~-]*"))) { "dhDevLoginKey 는 영문·숫자·._~- 만 쓸 수 있어요(remote-env.sh 가 만든 hex 값)" }

android {
    namespace = "app.donghaeng.companion.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.donghaeng.companion.demo"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DH_PUBLIC_BASE_URL", "\"$dhPublicBaseUrl\"")
        buildConfigField("String", "DH_DEV_LOGIN_KEY", "\"$dhDevLoginKey\"")

        // 평문 HTTP 허용 여부를 서버 주소 스킴에 묶는다(car-app 과 같은 규칙): http://(로컬 개발) → true, https://(클라우드) → false
        //   AndroidManifest.xml 의 android:usesCleartextTraffic="${usesCleartext}" 로 들어간다
        manifestPlaceholders["usesCleartext"] = dhPublicBaseUrl.startsWith("http://").toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 동행 모듈 — 실제 공식 앱은 AAR 로 받는다. protocol 타입은 sdk 가 api 로 전이한다.
    implementation(project(":sdk"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 호스트 앱 자신의 dev 로그인(POST /v1/auth/login) — 동행 모듈 밖의 일이라 호스트가 직접 호출한다 (DevAuthClient)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // QR 스캔: CameraX 미리보기 + ML Kit 바코드(번들 모델, Play 서비스 다운로드 불필요)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // 지도 탭: osmdroid(OpenStreetMap 타일). 그룹 차량 마커(동행 D)도 이 위에 그린다
    implementation(libs.osmdroid)
}
