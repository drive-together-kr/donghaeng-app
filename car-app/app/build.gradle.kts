import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 서버 주소 (T32) — 빌드 타입별 기본값. 앱 안에서는 설정 화면 숨은 메뉴(제목 5번 탭)로 바꿀 수 있다(docs/05 §7).
//   debug  : dhPublicBaseUrl / dhMqttPublicUri (gradle.properties 기본값 = 에뮬레이터 → 호스트, -P 로 덮어쓰기)
//   release: dhCloudHost 가 있으면 https://{dhCloudHost} / ssl://{dhCloudHost}:8883 (클라우드 기본).
//            비어 있으면 debug 값을 그대로 쓰고 경고만 낸다(빌드는 계속).
//   .\gradlew.bat :app:assemblePleosRelease -PdhCloudHost=1-2-3-4.sslip.io
fun dhProperty(name: String): String? = (findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }

val dhPublicBaseUrl: String = dhProperty("dhPublicBaseUrl") ?: "http://10.0.2.2:8080"
val dhMqttPublicUri: String = dhProperty("dhMqttPublicUri") ?: "tcp://10.0.2.2:1883"
val dhCloudHost: String? = dhProperty("dhCloudHost")?.removePrefix("https://")?.trimEnd('/')?.also { host ->
    if (!host.matches(Regex("[A-Za-z0-9.-]+"))) {
        throw GradleException("dhCloudHost 는 호스트 이름만 넣는다(스킴·포트·경로 없이). 예: 1-2-3-4.sslip.io — 받은 값: $host")
    }
}
val releasePublicBaseUrl: String = dhCloudHost?.let { "https://$it" } ?: dhPublicBaseUrl
val releaseMqttPublicUri: String = dhCloudHost?.let { "ssl://$it:8883" } ?: dhMqttPublicUri

// 평문 http 정책: 기본 서버 주소가 http:// 면 평문 전부 허용(로컬 개발), https:// 면 에뮬레이터 호스트·localhost 만 허용(숨은 메뉴의 현장 로컬 서버 폴백, docs/10 R9).
//   res/xml/network_security_config_{open,cloud}.xml 을 매니페스트 placeholder 로 고른다. HiveMQ(Netty) 에는 적용되지 않으므로 MQTT 평문은 MqttUriPolicy 가 막는다.
//   cleartextLocalHosts 는 network_security_config_cloud.xml 의 domain 목록과 같아야 한다(숨은 메뉴의 주소 검증이 BuildConfig.DH_CLEARTEXT_HOSTS 로 읽는다).
//   어긋나면 메뉴는 통과시키는데 OkHttp 가 'CLEARTEXT not permitted' 로 실패(또는 그 반대)하므로 구성 단계에서 비교해 빌드를 멈춘다(T32 리뷰).
val cleartextLocalHosts = listOf("10.0.2.2", "localhost", "127.0.0.1")
run {
    val xml = file("src/main/res/xml/network_security_config_cloud.xml")
    val body = xml.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    val domains = Regex("""<domain\b[^>]*>\s*([^<\s]+)\s*</domain>""").findAll(body).map { it.groupValues[1].lowercase() }.toList()
    if (domains.sorted() != cleartextLocalHosts.sorted()) {
        throw GradleException(
            "cleartextLocalHosts $cleartextLocalHosts and ${xml.name} domains $domains differ - keep both lists the same (T32)",
        )
    }
}
fun cleartextHostsFor(baseUrl: String): String = if (baseUrl.startsWith("http://")) "*" else cleartextLocalHosts.joinToString(",")
fun networkSecurityConfigFor(baseUrl: String): String =
    if (baseUrl.startsWith("http://")) "@xml/network_security_config_open" else "@xml/network_security_config_cloud"

gradle.taskGraph.whenReady {
    if (allTasks.none { it.name.contains("Release") }) return@whenReady
    if (dhCloudHost == null) {
        // 콘솔 인코딩(Windows CP949)에서 깨지지 않게 영어로 남긴다
        logger.warn("w: [donghaeng] dhCloudHost is empty - release keeps the debug server ($releasePublicBaseUrl, $releaseMqttPublicUri). Set -PdhCloudHost=<VM-IP>.sslip.io (or dhCloudHost in gradle.properties) for the cloud default.")
    }
}

android {
    namespace = "app.donghaeng.car"
    compileSdk = 35
    // AAOS Car API(android.car) — pleos 소스셋의 CarApiSignals 만 참조. 런타임은 pleos 매니페스트 uses-library(required=false)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.donghaeng.car"
        minSdk = 28          // 공모전 필수 (CLAUDE.md 규칙 11)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 서버 주소·평문 정책은 buildTypes 에서 정한다(debug = 로컬, release = 클라우드)
    }

    // 어댑터 구현 선택: pleos = 실 SDK (Pleos Connect AVD), fake = 전부 Fake (일반 AVD 에서도 실행)
    flavorDimensions += "adapter"
    productFlavors {
        create("pleos") {
            dimension = "adapter"
            versionNameSuffix = "-pleos"
            buildConfigField("String", "ADAPTER", "\"pleos\"")
        }
        create("fake") {
            dimension = "adapter"
            versionNameSuffix = "-fake"
            buildConfigField("String", "ADAPTER", "\"fake\"")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "DH_PUBLIC_BASE_URL", "\"$dhPublicBaseUrl\"")
            buildConfigField("String", "DH_MQTT_PUBLIC_URI", "\"$dhMqttPublicUri\"")
            buildConfigField("String", "DH_CLEARTEXT_HOSTS", "\"${cleartextHostsFor(dhPublicBaseUrl)}\"")
            manifestPlaceholders["networkSecurityConfig"] = networkSecurityConfigFor(dhPublicBaseUrl)
        }
        release {
            // 축소·난독화와 서명은 T40 에서 결정한다 (Pleos SDK keep 규칙 확인 필요)
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // T32: 클라우드 기본 주소(dhCloudHost). 비어 있으면 debug 값 + 경고(위 taskGraph.whenReady)
            buildConfigField("String", "DH_PUBLIC_BASE_URL", "\"$releasePublicBaseUrl\"")
            buildConfigField("String", "DH_MQTT_PUBLIC_URI", "\"$releaseMqttPublicUri\"")
            buildConfigField("String", "DH_CLEARTEXT_HOSTS", "\"${cleartextHostsFor(releasePublicBaseUrl)}\"")
            manifestPlaceholders["networkSecurityConfig"] = networkSecurityConfigFor(releasePublicBaseUrl)
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // HiveMQ / Netty 중복 리소스
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
        jniLibs {
            // F16 Vosk: ABI 당 약 10 MB(4개 = APK +40 MB). 대상 기기는 64비트(Pleos AVD x86_64, 차량 arm64)라 32비트 사본만 뺀다.
            // 32비트 기기에서는 Vosk 적재가 실패(UnsatisfiedLinkError)하고 인식 체인이 그 단계를 건너뛴다 — 다른 라이브러리의 ABI 지원은 그대로
            excludes += "lib/x86/libvosk.so"
            excludes += "lib/armeabi-v7a/libvosk.so"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true   // android.util.Log 등을 호출해도 단위 테스트가 죽지 않게
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 공용 메시지 스키마 (합성 빌드)
    implementation(libs.donghaeng.protocol)

    // Pleos SDK — pleos flavor 에만 (fake flavor 는 일반 에뮬레이터에서 SDK 없이 실행)
    "pleosImplementation"(libs.pleos.vehicle)
    "pleosImplementation"(libs.pleos.navihelper)
    "pleosImplementation"(libs.pleos.fusedlocation)
    // F16 무전 '네/아니' 1순위 — 준비 중 SDK 라 선택적(CLAUDE.md 규칙 5): CaaS ≥ 4.1.29·STT_SERVICE 권한·초기화가 모두 될 때만 체인 앞에 붙는다.
    // universal-access(registerApp UA 경로 — 쓰지 않음)가 끌어오는 security-crypto-ktx 1.1.0-alpha06·appcompat·material 은 뺀다:
    // UA 가 참조하는 MasterKey·EncryptedSharedPreferences 는 우리 security-crypto 1.0.0 에 있고, 토큰 저장소 라이브러리가 몰래 올라가지 않게 한다
    "pleosImplementation"(libs.pleos.speechtotext) {
        exclude(group = "androidx.security")
        exclude(group = "androidx.appcompat")
        exclude(group = "com.google.android.material")
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)   // T12 EncryptedTokenStore

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.netty.codec.http)   // T32 MQTT over WebSocket(wss) 전송 — HiveMQ POM 에서 optional
    implementation(libs.zxing.core)

    // F16 무전 '네/아니' 오프라인 인식(두 flavor 공통). 모델(소형 한국어 82 MB zip / 풀면 253 MB)은 APK 에 넣지 않는다 —
    // tools/demo 가 앱 files/vosk-ko-small 로 넣었을 때만 쓰고, 없으면 인식 체인이 다음 단계(카운트다운)로 간다(docs/05 § 무전)
    implementation(libs.vosk.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)   // F16 무전 업로드·다운로드 클라이언트(multipart) 단위 테스트
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
