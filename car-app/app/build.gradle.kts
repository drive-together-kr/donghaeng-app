import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 서버 주소: gradle.properties 기본값(에뮬레이터 → 호스트) 또는 -P 로 덮어쓰기 (클라우드 빌드)
//   .\gradlew.bat :app:assemblePleosRelease -PdhPublicBaseUrl=https://1-2-3-4.sslip.io -PdhMqttPublicUri=ssl://1-2-3-4.sslip.io:8883
val dhPublicBaseUrl: String = (findProperty("dhPublicBaseUrl") as String?)?.takeIf { it.isNotBlank() } ?: "http://10.0.2.2:8080"
val dhMqttPublicUri: String = (findProperty("dhMqttPublicUri") as String?)?.takeIf { it.isNotBlank() } ?: "tcp://10.0.2.2:1883"

android {
    namespace = "app.donghaeng.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.donghaeng.car"
        minSdk = 28          // 공모전 필수 (CLAUDE.md 규칙 11)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DH_PUBLIC_BASE_URL", "\"$dhPublicBaseUrl\"")
        buildConfigField("String", "DH_MQTT_PUBLIC_URI", "\"$dhMqttPublicUri\"")

        // 평문 HTTP 허용 여부를 서버 주소 스킴에 묶는다: http://(로컬 개발) → true, https://(클라우드) → false
        //   AndroidManifest.xml 의 android:usesCleartextTraffic="${usesCleartext}" 로 들어간다
        manifestPlaceholders["usesCleartext"] = dhPublicBaseUrl.startsWith("http://").toString()
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
        }
        release {
            // 축소·난독화와 서명은 T40 에서 결정한다 (Pleos SDK keep 규칙 확인 필요)
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // HiveMQ / Netty 중복 리소스
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
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
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
