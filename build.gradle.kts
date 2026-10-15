import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

// 릴리스 서명: keystore.properties (Git 제외) 가 있을 때만. 측량 앱과 같은 키(사용자가 같은 개발자로 믿을 수 있게)
val ksProps = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // 측량 앱에서 가져온 수신기·VRS·지도 코드(kr.archsurvey.*)를 고치지 않고 쓰려고 R 패키지는 같게 둔다. 설치 id 는 따로.
    namespace = "kr.archsurvey"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.archsurvey.gcp"
        minSdk = 21
        targetSdk = 35
        versionCode = 21
        versionName = "0.1.20"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
            isMinifyEnabled = true // 0.1.52 R8 full mode (AGP 8 기본) + 리소스 축소
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.findByName("release")
            if (rel != null) signingConfig = rel
            else {
                signingConfig = signingConfigs.getByName("debug")
                logger.warn("WARNING: keystore.properties 없음 -> release APK 를 debug 키로 서명합니다 (배포 금지).")
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
    val composeBom = platform("androidx.compose:compose-bom:2025.07.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    // 0.1.52 #2: adb 설치에서도 Baseline Profile 을 설치(첫 실행/백그라운드)
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // ID 카드 QR (Apache-2.0). core 만 — 카메라·스캔 없음
    implementation("com.google.zxing:core:3.5.3")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    testImplementation("junit:junit:4.13.2")
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

// 0.1.1: 0.1.0 릴리스에 V월드 내장 키가 빠져 지도가 안 나왔다 → 릴리스 빌드는 키가 없으면 멈춘다(tools/inject-vworld-key.sh)
val checkVWorldKey by tasks.registering {
    doLast {
        val f = file("src/main/kotlin/kr/archsurvey/map/VWorldDefaults.kt")
        val ok = f.exists() && Regex("BUILTIN_API_KEY = \"[A-Za-z0-9-]{20,}\"").containsMatchIn(f.readText())
        if (!ok) throw GradleException("V월드 내장 키가 없어요 — tools/inject-vworld-key.sh 를 먼저 실행하세요")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkVWorldKey) }
