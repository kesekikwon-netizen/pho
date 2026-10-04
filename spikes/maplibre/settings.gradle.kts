// WP-9 스파이크 전용 독립 프로젝트: 본 앱 빌드(루트 settings)에 포함되지 않는다. 실행: 이 폴더에서 gradle assembleDebug assembleRelease
pluginManagement {
    val kv = (extra.properties["kotlinVer"] as String?) ?: "2.0.21"   // -PkotlinVer=2.2.10 로 바꿔 시험
    plugins { id("org.jetbrains.kotlin.android") version kv }
    repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "MapLibreSpike"
