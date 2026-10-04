import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

// 릴리스 서명: keystore.properties (Git 제외) 가 있을 때만 release 서명 설정
val ksProps = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "kr.archsurvey"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.archsurvey.field"
        minSdk = 21
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.4"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("boolean", "USE_MAPLIBRE", project.hasProperty("withMapLibre").toString()) // 옵트인 MapLibre 지도(기본 false)
    }

    signingConfigs {
        if (ksProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(ksProps["storeFile"] as String)
                storePassword = ksProps["storePassword"] as String
                keyAlias = ksProps["keyAlias"] as String
                keyPassword = ksProps["keyPassword"] as String
                enableV1Signing = true; enableV2Signing = true; enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = false // 현장시험 빌드: minify 끔
            // keystore.properties 가 있으면 release 키로 서명.
            // 없으면 debug 키로 서명(설치/시험용, 현장 배포·업데이트용 아님) — 빌드 로그에 경고.
            val rel = signingConfigs.findByName("release")
            if (rel != null) signingConfig = rel
            else {
                signingConfig = signingConfigs.getByName("debug")
                logger.warn("WARNING: keystore.properties 없음 -> release APK 를 debug 키로 서명합니다 (현장 업데이트 배포 금지).")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    compileOptions.isCoreLibraryDesugaringEnabled = true
    kotlinOptions { jvmTarget = "17" }
    lint {
        // minSdk 21 호환: 상위 API 무분기 사용을 빌드 오류로 취급
        error += listOf("NewApi", "InlinedApi")
        abortOnError = true
        checkReleaseBuilds = true
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
    }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    testImplementation("junit:junit:4.13.2")
    // 화면 렌더링 시험(에뮬레이터 없이 JVM 에서 Compose 를 그려 PNG 로 저장)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// WP-9 후속(옵트인): -PwithMapLibre 로 빌드하면 MapLibre 지도 구현(src/maplibre)을 포함한다. 기본 빌드는 영향 없음(자체 Canvas 지도).
// 11.13.5 = minSdk 21·Kotlin 2.0 호환 마지막 계열(12 이상은 minSdk 23). 근거와 미검증 목록: spikes/maplibre/RESULT.md
if (project.hasProperty("withMapLibre")) {
    android.sourceSets.getByName("main").java.srcDir("src/maplibre/kotlin")
    dependencies { add("implementation", "org.maplibre.gl:android-sdk:11.13.5") }
}
