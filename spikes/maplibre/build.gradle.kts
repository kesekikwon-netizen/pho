plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.archsurvey.spike"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.archsurvey.spike"
        minSdk = 23            // 21 이 필요한 flavor 는 아래에서 덮어씀
        targetSdk = 35
        versionCode = 1; versionName = "0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }   // 본 앱과 같은 ABI
    }
    flavorDimensions += "v"
    productFlavors {
        create("base") { dimension = "v"; minSdk = 21 }                       // MapLibre 없음(비교 기준)
        create("ml13gl") { dimension = "v" }                                   // android-sdk-opengl 13.6.1
        create("ml13vk") { dimension = "v" }                                   // android-sdk 13.6.1 (= Vulkan 변형)
        create("ml130gl") { dimension = "v" }                                  // android-sdk-opengl 13.0.0 (메타데이터 Kotlin 2.0 → 본 앱 Kotlin 2.0.21 로 컴파일 가능)
        create("ml11") { dimension = "v"; minSdk = 21 }                        // android-sdk 11.13.5
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" ; if (project.hasProperty("skipMeta")) freeCompilerArgs += "-Xskip-metadata-version-check" }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }  // 본 앱 release 와 같은 설정(minify 끔, debug 키)
    sourceSets {
        for (f in listOf("ml13gl", "ml13vk", "ml130gl", "ml11")) { getByName(f).java.srcDir("src/maplibre/kotlin"); getByName(f).manifest.srcFile("src/maplibre/AndroidManifest.xml") }
    }
    lint { abortOnError = false }
}

dependencies {
    "ml13glImplementation"("org.maplibre.gl:android-sdk-opengl:13.6.1")
    "ml13vkImplementation"("org.maplibre.gl:android-sdk:13.6.1")
    "ml130glImplementation"("org.maplibre.gl:android-sdk-opengl:13.0.0")
    "ml11Implementation"("org.maplibre.gl:android-sdk:11.13.5")
    for (f in listOf("ml13gl", "ml13vk", "ml130gl", "ml11")) "${f}Implementation"("com.squareup.okhttp3:okhttp:4.12.0")
}
