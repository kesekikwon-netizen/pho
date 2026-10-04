# WP-9 MapLibre 스파이크 (2026-10-04, 박스에서 빌드만 해 봄)

**실기기·에뮬레이터 시험 없음.** 아래 "확인함"은 *빌드·APK 분석·바이너리 문자열 확인* 까지이고, 지도가 실제로 그려지는지는 확인하지 못했다.

독립 Gradle 프로젝트(루트 `settings.gradle.kts` 에 포함되지 않음). 이 폴더에서:

    gradle -PkotlinVer=2.2.10 assembleDebug assembleRelease   # 전 flavor (13.6.x 는 Kotlin 2.2 필요)
    gradle compileMl130glDebugKotlin compileMl11DebugKotlin   # 본 앱과 같은 Kotlin 2.0.21 로 컴파일되는 flavor

flavor: `base`(MapLibre 없음, 비교 기준) / `ml13gl`(android-sdk-opengl 13.6.1) / `ml13vk`(android-sdk 13.6.1, Vulkan 변형) /
`ml130gl`(android-sdk-opengl 13.0.0) / `ml11`(android-sdk 11.13.5). abiFilters = arm64-v8a + armeabi-v7a (본 앱과 동일).
결과 전체와 판단은 `/workspace/survey-app/plan/spike-maplibre.md` 와 같은 내용이다(아래 표 참고).
