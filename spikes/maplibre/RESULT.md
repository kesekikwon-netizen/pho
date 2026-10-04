# WP-9 MapLibre Native(Android) 스파이크 결과

작성: 2026-10-04 (Asia/Seoul), 박스(리눅스)에서 빌드·APK 분석만 수행. **실기기·R10·에뮬레이터 시험 없음** (KVM 권한 없어 에뮬레이터 불가).
코드: `app-wt/spikes/maplibre/` (독립 프로젝트, 본 앱 빌드와 분리). 브랜치 `feat/wp9-11`.

## 1. 확인한 것 (직접 측정/확인)
### 1.1 버전·minSdk (Maven Central, AAR 매니페스트 직접 확인)
| 버전 | minSdk | Kotlin 메타데이터 | 비고 |
|---|---|---|---|
| 11.13.5 `android-sdk` | **21** | (Kotlin 2.0.21 로 컴파일 성공) | 본 앱 minSdk 21 과 같음 |
| 12.3.1, 13.0.0 | 23 | 2.0.0 (Kotlin 2.0.21 로 컴파일 성공) | |
| 13.3.1, 13.5.2 | 23 | 2.2.0 | Kotlin ≥2.2 필요 |
| 13.6.1 (최신 안정) | **23** | 2.2.0 | `-opengl`/기본(Vulkan)/`-vulkan` 변형 |
- 경계: minSdk 21 → 23 은 11.13.5 → 12.0.0 사이에서 바뀜. (research-tech 의 "13.4.0 = minSdk 21" 은 실제 13.4.0 AAR 매니페스트(23)와 **맞지 않음**.)
- 13.4.0~13.5.0 AAR 은 약 163~197 MB(!)였다가 13.5.2 이후 15~18 MB 로 돌아옴 (plan 의 "~156MB" 는 이 구간으로 보임). 13.6.1 에서는 해당 없음.
- **13.6.1 을 쓰려면 앱의 Kotlin 컴파일러를 2.2 이상으로 올려야 함**: Kotlin 2.0.21 로 컴파일하면 "metadata 2.2.0, 컴파일러는 2.1.0 까지 읽음" 오류(+ 컴파일러 내부 오류 `source must not be null`). `-Xskip-metadata-version-check` 로는 컴파일됨(안전성 미확인). Kotlin 2.2.10 으로는 정상 컴파일. → Compose 컴파일러 플러그인·KSP 버전을 같이 올려야 하므로 별도 결정 사항.

### 1.2 APK 크기 (스파이크 앱, ABI arm64-v8a+armeabi-v7a, minify 끔, 바이트)
| flavor | debug | release | 베이스 대비 release 증가 |
|---|---|---|---|
| base (MapLibre 없음) | 844,200 | 652,017 | – |
| ml11 (11.13.5, minSdk 21) | 12,506,303 | 11,061,192 | +10.4 MB |
| ml13gl (13.6.1 OpenGL, minSdk 23) | 22,391,421 | 21,594,091 | +20.9 MB |
| ml13vk (13.6.1 Vulkan, minSdk 23) | 25,926,352 | 25,129,025 | +24.5 MB |
| ml130gl (13.0.0 OpenGL, minSdk 23) | 26,598,236 | 25,800,922 | +25.1 MB |
- 주의: minSdk≥23 은 AGP 가 `.so` 를 **압축하지 않고** 저장(ml13 계열 libmaplibre.so 가 raw 크기 그대로: arm64 10.8MB + v7a 7.9MB), minSdk 21 은 압축 저장(ml11 arm64 13.1→4.4MB). 그래서 위 크기 차이의 일부는 버전이 아니라 포장 방식 차이다. 설치 후 실제 디스크 사용량은 측정하지 않음.
- 본 앱(현재 release 11,286,102 B 기준)에 얹으면 대략 +10 MB(ml11) ~ +21 MB(ml13gl)일 것으로 **추정**(본 앱에 붙여 측정한 값은 아래 §4 참고, 있을 때만).

### 1.3 병합 매니페스트 (aapt2/병합 결과 직접 확인)
- 모든 MapLibre flavor 가 **ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_WIFI_STATE, INTERNET, ACCESS_NETWORK_STATE** 를 앱에 병합시킨다 → 본 앱의 "위치 권한 요청 안 함" 방침과 충돌.
- 앱 매니페스트에 `tools:node="remove"` 로 위치 2개 + WIFI_STATE 를 제거하면 병합 결과에서 사라짐을 **확인함**(`spikes/maplibre/src/maplibre/AndroidManifest.xml`; 남는 것은 INTERNET, ACCESS_NETWORK_STATE — 본 앱은 이미 선언).
- 13.6.1 기본/`-vulkan` 변형: `uses-feature android.hardware.vulkan.version 0x400003 required=true` → Vulkan 미지원 기기 설치 제외. **OpenGL ES 변형(`android-sdk-opengl`)에는 없음.** 현장 단말(R10 등) 보호를 위해 쓴다면 OpenGL 변형이 안전.
- 의존성: kotlin-stdlib 2.2.10(13.6.1), okhttp 4.12.0, androidx fragment/annotation, timber, geojson, gestures, turf.

### 1.4 API 존재·컴파일 (javap + 실제 컴파일)
Kotlin 코드가 다음을 호출해 **컴파일됨**: `MapLibre.getInstance`, `MapView` 생명주기, `Style.Builder().fromJson`, `RasterSource(id, TileSet("2.2.0","mbtiles://…"), 256)` / `RasterSource(id,"pmtiles://file://…",256)`, `RasterLayer(...).withProperties(PropertyFactory.rasterOpacity(f))`, 런타임 `setProperties(rasterOpacity)`, `OfflineManager.setMaximumAmbientCacheSize(0, cb)`, `clearAmbientCache(cb)`, `HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().cache(null).build())`.
- 바이트코드 확인: 기본 HTTP 클라이언트(`HttpRequestImpl`)는 `OkHttpClient.Builder().dispatcher(...)` 만 설정 — **OkHttp 디스크 캐시 설정 호출 없음**. 그러나 네이티브 쪽 SQLite 캐시(ambient cache)는 별도로 존재하며 그 동작은 아래 미검증.
- `libmaplibre.so`(11.13.5, 12.3.1, 13.0.0, 13.6.1 모두) 안에 `MBTilesFileSource`("only supports absolute path urls"), `mbtiles://`, `PMTilesFileSource`, `pmtiles://` 문자열/기호가 있음. **문자열 존재일 뿐 동작 증거가 아님.**

## 2. 확인하지 못한 것 (실기기/렌더 필요) — 전부 미검증
1. 지도가 실제로 그려지는지(스타일 로드·GL 컨텍스트·Compose `AndroidView` 안에서의 동작) – 아무 기기에서도 실행하지 않음.
2. MBTiles/PMTiles 로컬 래스터가 실제로 표시되는지, 좌표계(웹메르카토르 타일) 요구, 파일 접근 경로 제약(절대경로만).
3. **디스크 캐시 끄기 실제 동작**: `setMaximumAmbientCacheSize(0)`/`clearAmbientCache` 호출 후 타일이 디스크에 남지 않는지(캐시 DB 파일 생성 여부) – 호출이 컴파일되는 것만 확인.
4. 래스터 투명도 슬라이더 실시간 반응·성능, 메모리, 배터리, 오프라인 상태 동작.
5. 글자(SymbolLayer 텍스트)는 글리프(폰트) 소스가 필요 – 오프라인 폰트를 번들하지 않았으므로 점 이름 표시는 MapLibre 레이어가 아니라 Compose 오버레이로 하는 것이 현실적(설계 판단, 미시험).
6. 13.6.1 + Kotlin 2.2 로 본 앱 전체(Compose 컴파일러, KSP/Room)를 올렸을 때의 호환성 – 스파이크는 Compose/Room 없음.

## 3. 판단 (사실과 구분되는 의견)
- **구성 수준에서 "가능"**: 11.13.5(minSdk 21, Kotlin 2.0.21 호환, 위치 권한은 `tools:node=remove` 로 제거 가능, OpenGL, 약 +10MB)는 본 앱 설정을 바꾸지 않고 의존성만 추가하면 컴파일 가능함을 스파이크로 확인. 13.6.1 은 minSdk 23 + Kotlin 2.2 + 약 +21MB 이므로 별도 결정(최저 Android 버전 상향, 컴파일러 업그레이드)이 필요.
- 렌더링·캐시 비활성은 확인하지 못했으므로 **기본 지도는 계속 Canvas** 로 두고, MapLibre 는 옵트인 빌드(`-PwithMapLibre`)로만 시도하는 것을 권장. 실기기 확인 항목은 `FIELD_CHECKLIST.md` 에 넣음.
