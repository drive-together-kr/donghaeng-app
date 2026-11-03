import java.util.Properties
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
//   .\gradlew.bat :app:assemblePleosRelease -PdhCloudHost=1.2.3.4.sslip.io   (점 표기 — CLAUDE.md 규칙 12)
fun dhProperty(name: String): String? = (findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }

val dhPublicBaseUrl: String = dhProperty("dhPublicBaseUrl") ?: "http://10.0.2.2:8080"
val dhMqttPublicUri: String = dhProperty("dhMqttPublicUri") ?: "tcp://10.0.2.2:1883"
val dhCloudHost: String? = dhProperty("dhCloudHost")?.removePrefix("https://")?.trimEnd('/')?.also { host ->
    if (!host.matches(Regex("[A-Za-z0-9.-]+"))) {
        throw GradleException("dhCloudHost 는 호스트 이름만 넣는다(스킴·포트·경로 없이). 예: 1.2.3.4.sslip.io — 받은 값: $host")
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


// 릴리스 서명(T40) — 키는 저장소 밖에만 둔다(CLAUDE.md 규칙 9). 서명 정보 파일(storeFile·storePassword·keyAlias·keyPassword)의 경로를
//   -PdhReleaseSigning=<경로> 또는 ~/.gradle/gradle.properties 의 dhReleaseSigning, 또는 환경 변수 DH_RELEASE_SIGNING 으로 준다.
//   없으면 release 는 서명되지 않은 APK(*-unsigned.apk)로 나온다(다른 개발자·CI 빌드는 그대로 된다).
val dhReleaseSigningFile: File? = ((findProperty("dhReleaseSigning") as String?) ?: System.getenv("DH_RELEASE_SIGNING"))
    ?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }?.takeIf { it.isFile }
val dhReleaseSigning: Properties? = dhReleaseSigningFile?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

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
        versionName = "1.0.0"

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

    signingConfigs {
        dhReleaseSigning?.let { p ->
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "DH_PUBLIC_BASE_URL", "\"$dhPublicBaseUrl\"")
            buildConfigField("String", "DH_MQTT_PUBLIC_URI", "\"$dhMqttPublicUri\"")
            buildConfigField("String", "DH_CLEARTEXT_HOSTS", "\"${cleartextHostsFor(dhPublicBaseUrl)}\"")
            manifestPlaceholders["networkSecurityConfig"] = networkSecurityConfigFor(dhPublicBaseUrl)
            // 개발 정보(홈 빌드 카드·페어링 상세·지연 표시 기본값) — 시연 영상 녹화 때만 -PdhVideo=true 로 숨긴다(release 는 늘 숨김)
            buildConfigField("boolean", "DH_SHOW_DEV_INFO", if (dhProperty("dhVideo") == "true") "false" else "true")
        }
        release {
            // T40: 축소·난독화는 하지 않는다 — Pleos SDK·Hilt·kotlinx.serialization 의 리플렉션 keep 규칙을 기기에서 다 검증할 시간이 없고, 크기(약 71 MB)는 제출 한도 안
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // T32: 클라우드 기본 주소(dhCloudHost). 비어 있으면 debug 값 + 경고(위 taskGraph.whenReady)
            buildConfigField("String", "DH_PUBLIC_BASE_URL", "\"$releasePublicBaseUrl\"")
            buildConfigField("String", "DH_MQTT_PUBLIC_URI", "\"$releaseMqttPublicUri\"")
            buildConfigField("String", "DH_CLEARTEXT_HOSTS", "\"${cleartextHostsFor(releasePublicBaseUrl)}\"")
            manifestPlaceholders["networkSecurityConfig"] = networkSecurityConfigFor(releasePublicBaseUrl)
            buildConfigField("boolean", "DH_SHOW_DEV_INFO", "false")
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

// Pleos SDK(SpeechToText·TextToSpeech 3.3.1)가 universal-access 0.6.0 을 거쳐 끌어오는 security-crypto-ktx 1.1.0-alpha06 을 **모든 구성**에서 뺀다 —
// debug·release, 어느 Pleos SDK 줄이든 토큰 저장소 라이브러리가 security-crypto 1.0.0(EncryptedTokenStore, MasterKeys API) 하나로 같게.
// 주의: UA 의 보안 저장소(`ai.pleos.playground.security.ua.s` — MasterKey·MasterKey$Builder·EncryptedSharedPreferences.create(…, MasterKey, …))는
// **1.1.x API 라 1.0.0 에는 없다**(javap 확인 — 1.0.0 은 MasterKeys 만). 이 클래스는 UniversalAccess.create 에서만 만들어지고, STT SDK 는 registerApp(UA 변형)으로
// useUaSdk 를 켤 때만 그 경로를 탄다 → 우리는 registerApp 을 부르지 않고 ON_DEVICE 만 쓰므로 닿지 않는다. registerApp(SERVER 모드)을 켜려면
// security-crypto 를 1.1.x 로 올리고(토큰 저장소 검증) 이 exclude 를 함께 바꾼다 — 안 그러면 NoClassDefFoundError(docs/10 Q9).
configurations.configureEach {
    exclude(group = "androidx.security", module = "security-crypto-ktx")
}

dependencies {
    // 공용 메시지 스키마 (합성 빌드)
    implementation(libs.donghaeng.protocol)

    // Pleos SDK — pleos flavor 에만 (fake flavor 는 일반 에뮬레이터에서 SDK 없이 실행)
    "pleosImplementation"(libs.pleos.vehicle)
    "pleosImplementation"(libs.pleos.navihelper)
    "pleosImplementation"(libs.pleos.fusedlocation)
    // Pleos TextToSpeech SDK(준비 중 SDK — CLAUDE.md 규칙 5: 가능하면 쓰고 실패하면 다음 수단) — 음성 체인 2순위 PleosTtsVoiceAdapter(R19 해결).
    // pleos debug 의 날것 프로브(PleosTtsProbe)도 같은 의존성을 쓴다. appcompat·material 은 빼지 않는다 — 이 구성으로 기기 실측(깨끗한 AVD release)했다.
    // (아래 STT 줄의 exclude 는 STT 경로에서만 빠지고, TTS 경로로 같은 모듈이 들어오므로 최종 APK 에는 남는다)
    "pleosImplementation"(libs.pleos.tts)
    // F16 무전 '네/아니' 1순위 — 준비 중 SDK 라 선택적(CLAUDE.md 규칙 5): CaaS ≥ 4.1.29·STT_SERVICE 권한·초기화가 모두 될 때만 체인 앞에 붙는다.
    // universal-access 0.6.0(UA)이 끌어오는 appcompat·material 은 뺀다(Compose 앱 — 쓰지 않는다). security-crypto-ktx 는 아래 configurations 에서 모두 뺀다.
    "pleosImplementation"(libs.pleos.speechtotext) {
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
