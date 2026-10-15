import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

// 릴리스 서명: 측량 앱·사진 기준점 앱과 같은 키 (keystore.properties, Git 제외)
val ksProps = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "kr.archsurvey.pg"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "kr.archsurvey.pg"
        // 어느 폰에나 설치(안드로이드 5+). ARCore 는 7.0+ 에서만 켜고, 없으면 사진·표적·내보내기만.
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-p0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=none") } }
    }
    externalNativeBuild { cmake { path = file("native/CMakeLists.txt"); version = "3.22.1" } }

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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"; jniLibs.useLegacyPackaging = false }
    lint { abortOnError = false; checkReleaseBuilds = false }
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // ARCore (Apache-2.0). 선택 설치(manifest: com.google.ar.core = optional)
    implementation("com.google.ar:core:1.46.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}

// 단위시험용: 같은 AprilTag 3 C 소스를 PC(box)용 JNI 라이브러리로 빌드해 진짜 검출기를 시험한다
val hostApriltag by tasks.registering(Exec::class) {
    val out = file("native/host/libpgapriltag.so")
    inputs.dir("native"); inputs.dir("third_party/apriltag"); outputs.file(out)
    val jh = System.getProperty("java.home")
    val at = "third_party/apriltag"
    doFirst { out.parentFile.mkdirs() }
    commandLine("bash", "-c", "gcc -O2 -shared -fPIC -std=gnu99 -w -I$jh/include -I$jh/include/linux -I$at -I$at/common " +
        "native/pg_apriltag_jni.c $at/apriltag.c $at/apriltag_quad_thresh.c $at/tag36h11.c $at/tag25h9.c $at/tag16h5.c $at/tagStandard41h12.c $at/common/*.c -lm -lpthread -o ${out.path}")
}
tasks.withType<Test>().configureEach {
    dependsOn(hostApriltag)
    systemProperty("java.library.path", file("native/host").absolutePath)
    systemProperty("pg.root", projectDir.absolutePath)
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
