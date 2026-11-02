import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :demo-host — 공식 앱(블루링크·마이현대·기아 커넥트 등)을 흉내 낸 시연용 호스트 앱.
// 실제 공식 앱이 하는 일(로그인·내 차 목록·QR 스캐너)을 최소로 구현하고 :sdk 의 공개 API만 호출한다.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 서버 공개 주소 — car-app/app/build.gradle.kts 와 같은 규칙(D077: 예전엔 dhCloudHost 를 무시해 차는 클라우드, 폰은 10.0.2.2 를 가리킬 수 있었다):
//   debug  : dhPublicBaseUrl (기본 http://10.0.2.2:8080 = 에뮬레이터 → 호스트 PC, -P 로 덮어쓰기)
//   release: dhCloudHost 가 있으면 https://{dhCloudHost} (클라우드 기본), 없으면 dhPublicBaseUrl — http:// 면 경고만(빌드는 계속)
//   두 APK 를 같은 서버로: car-app `:app:assemblePleosRelease -PdhCloudHost=H` / companion `:demo-host:assembleRelease -PdhCloudHost=H -PdhDevLoginKey=<키>`
fun dhProperty(name: String): String? = (findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }

val dhPublicBaseUrl: String = (dhProperty("dhPublicBaseUrl") ?: "http://10.0.2.2:8080").trimEnd('/')
val dhCloudHost: String? = dhProperty("dhCloudHost")?.removePrefix("https://")?.trimEnd('/')?.also { host ->
    if (!host.matches(Regex("[A-Za-z0-9.-]+"))) {
        throw GradleException("dhCloudHost 는 호스트 이름만 넣는다(스킴·포트·경로 없이). 예: 1-2-3-4.sslip.io — 받은 값: $host")
    }
}
val releasePublicBaseUrl: String = dhCloudHost?.let { "https://$it" } ?: dhPublicBaseUrl

// dev 로그인 접속 키: 클라우드 서버(T32)는 DH_DEV_LOGIN_KEY 를 요구한다 → -PdhDevLoginKey=... 또는 ~/.gradle/gradle.properties 의 dhDevLoginKey.
// 값은 VM 의 infra/.env 에만 있다 — 저장소의 gradle.properties 에 적지 않는다(CLAUDE.md 규칙 9). 기본 빈 값 = 헤더 안 보냄(로컬 서버는 검사하지 않음).
val dhDevLoginKey: String = providers.gradleProperty("dhDevLoginKey")
    .orElse("")
    .get()
    .trim()
require(dhDevLoginKey.matches(Regex("[A-Za-z0-9._~-]*"))) { "dhDevLoginKey 는 영문·숫자·._~- 만 쓸 수 있어요(remote-env.sh 가 만든 hex 값)" }

gradle.taskGraph.whenReady {
    if (allTasks.none { it.project == project && it.name.contains("Release") }) return@whenReady
    // 콘솔 인코딩(Windows CP949)에서 깨지지 않게 영어로 남긴다
    if (!releasePublicBaseUrl.startsWith("https://")) {
        logger.warn("w: [donghaeng] demo-host release points at $releasePublicBaseUrl (dhCloudHost is empty). Set -PdhCloudHost=<VM-IP>.sslip.io (same as car-app) for the cloud server.")
    } else if (dhDevLoginKey.isEmpty()) {
        logger.warn("w: [donghaeng] demo-host release points at $releasePublicBaseUrl but dhDevLoginKey is empty - cloud dev login will be rejected (403). Pass -PdhDevLoginKey=<key>.")
    }
}

android {
    namespace = "app.donghaeng.companion.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.donghaeng.companion.demo"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DH_DEV_LOGIN_KEY", "\"$dhDevLoginKey\"")
        // 서버 주소·평문 정책은 buildTypes 에서 정한다(debug = 로컬, release = dhCloudHost 클라우드)
    }

    buildTypes {
        debug {
            buildConfigField("String", "DH_PUBLIC_BASE_URL", "\"$dhPublicBaseUrl\"")
            // 평문 HTTP 허용 여부를 서버 주소 스킴에 묶는다(car-app 과 같은 규칙): http://(로컬 개발) → true, https://(클라우드) → false
            //   AndroidManifest.xml 의 android:usesCleartextTraffic="${usesCleartext}" 로 들어간다
            manifestPlaceholders["usesCleartext"] = dhPublicBaseUrl.startsWith("http://").toString()
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DH_PUBLIC_BASE_URL", "\"$releasePublicBaseUrl\"")
            manifestPlaceholders["usesCleartext"] = releasePublicBaseUrl.startsWith("http://").toString()
        }
    }

    testOptions {
        // DongHaengStore·HostTokenCache 단위 테스트가 android.util.Log 를 불러도 예외 대신 기본값
        unitTests.isReturnDefaultValues = true
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

    // 순수 로직 단위 테스트(무전 목록 VoiceItems·시연 신호음 FakeVoiceTone·그룹 관찰 재시도·호스트 토큰 갱신·합류 차 고르기·스토어)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
