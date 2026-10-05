# 0.1.19 — hgis(Strata)용 SHP 내보내기 점검·보완

- versionName `0.1.19` / versionCode `20` (**버전 범프 없음** · 출시게이트·안드로이드와 맞춤)
- 패키지 `kr.archsurvey.field`
- APK: `dist/ExcavationSurvey-0.1.19-hgis-shp-release-signed.apk`
  - 서명 v1/v2/v3 OK, cert SHA-1 `9dac6ce35f6bde0b75832fa8ac12aed750d173a8` (0.1.16~0.1.19와 같은 키)
  - APK SHA-256 `bffd1fa818c474aaad6735fb76d4a34673f819810e8375285e16b083f1639735`
- 시험: `gradle --offline testDebugUnitTest` → **575 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL
  - 신규 `HgisShpExportTest` 8개 + `ExportControllerTest.runHgis` 1개 포함
- 박스 GDAL 3.10: `ogrinfo`/`gdalsrsinfo` 로 표본 `points_5186.shp` → **EPSG:5186**, `points_5187.shp`(PointZ) → **EPSG:5187**, `.cpg` = UTF-8 확인
- Drive 업로드: 안 함 (위임 지시)
- git: 내보내기 관련 파일만 `app/.git` 커밋(아래 해시). 0.1.15~0.1.19 다른 WIP는 워킹트리에 그대로.

## hgis가 기대하는 것 (근거 파일)

저장소: https://github.com/kesekikwon-netizen/hgis (클론 `/workspace/tmp/hgis`, 커밋 `a4337b1`)

| 항목 | 내용 | 근거 |
|---|---|---|
| 끌어 놓기 | 지도·레이어 트리에 **로컬 `.shp` URL** (`tryAddDroppedPaths`). **ZIP은 받지 않음**(수치지형도 ZIP만 별도). `.dxf/.dwg/.gpkg/.geojson`도 가능 | `src/app/MainWindowFiles.cpp` `tryAddDroppedPaths`, `src/app/MainWindow.cpp` drop 필터 |
| 사이드카 | GDAL/OGR이 같은 폴더의 `.shx/.dbf/.prj` 필요. `.cpg` 있으면 `SHAPE_ENCODING`으로 씀 | `LayerOps::prepareShapefileEncoding` (`src/core/LayerOps.cpp` ~358) |
| 인코딩 | `.cpg`에 UTF-8/CP949. 없으면 DBF 샘플로 자동 판별(기본 CP949) 후 `.cpg` 생성 | 위 |
| 좌표계 | `.prj` WKT → QGIS CRS. 작업 CRS는 **5186/5187**(제출 **5179**는 별도). OTF로 화면 CRS에 맞춤 | README, `addVectorFromPath` → `ensureOtfEnabled` |
| 라벨 | `detectNameField`: 유적명/번호/name… 후보 없으면 **첫 문자 칸** | `LayerOps.cpp` ~238 |
| PointZ | 일반 벡터 추가 시 Z 유지(CAD만 dropZ). 등고선 읽기는 PointZ 사용 | `SurveyPointReader`, `CadDrawingReader` |
| CSV 기준점 | 메뉴 「기타 › CSV 기준점」. 열 `point_id,x,y,z,datum,ellipsoid,projection,origin,accuracy_m,pdop,fix_type`. **x=동거 y=북거**. 좌표계 글은 속성만(투영은 조사 CRS) | `ControlPointCsv.cpp`, `samples/demo_survey/gcp.csv`, `docs/domain/data-model.md` |

## 앱에서 고친 것

| 파일 | 변경 |
|---|---|
| `export/MoreFormats.kt` | PointZ(유형 11, 모든 점에 지면 높이 있을 때) / Point 폴백. DBF 칸 23개(≤10자, UTF-8). `.prj` = GDAL ESRI WKT(`KGD2002_*_Belt_2010`)로 5185–5188·5179. `.cpg` UTF-8. 좌표계별 분리. `hgisControlCsv` 추가. `ExportFormat.HGIS` / `HGIS_CSV` |
| `export/ExportService.kt` | `points_<EPSG>.shp` 한 벌 + `hgis_control_points_<EPSG>.csv`. README에 hgis 사용법. MediaStore로 `Documents/ExcavationSurvey/hgis/<작업>_<시각>/` 복사(API 29+) |
| `ui/ExportScreen.kt` / `ExportModel.kt` | 「**hgis(Strata)용 SHP 바로 만들기**」 원탭(`export_hgis_quick`). 공용 폴더 안내 카드 |
| `engine/AndroidExportPlatform.kt` | `publicFolder` 전달 |
| 시험 | `export/HgisShpExportTest.kt`, `ExportTests.kt` 갱신 |

**건드리지 않음:** BT-130·연결·NTRIP·효과음·폴 띠·배터리·버전 범프.

## 폰에서 쓰는 법

1. 작업 전송 → **「hgis(Strata)용 SHP 바로 만들기」** (또는 양식에서 Shape / hgis 기준점 CSV 체크).
2. 폰 **내장 저장공간 › Documents › ExcavationSurvey › hgis › 〈작업〉_〈시각〉** 에 `.shp/.shx/.dbf/.prj/.cpg` (+ CSV) 복사됨(Android 10+). ZIP 공유/파일로 저장도 그대로.
3. USB로 PC에 폴더를 **통째로 복사**(ZIP이면 먼저 압축 해제 — hgis는 ZIP 드롭 안 함).
4. `points_5186.shp`(또는 5187…)를 hgis 지도/레이어에 끌어 놓기. 같은 이름 사이드카가 옆에 있어야 함.
5. 기준점만: 기타 › CSV 기준점 → `hgis_control_points_<EPSG>.csv` (**같은 좌표계 조사**에).

## 갭·미시험

- 실기기 MediaStore 공용 폴더·MTP 경로 미확인(단위 시험은 플랫폼 가짜).
- hgis 실실행으로 드래그 드롭·라벨·CRS 범례 미확인(박스에 QGIS/hgis GUI 없음). GDAL만 확인.
- PDOP: 앱이 저장하지 않아 CSV `pdop` 빈칸(HDOP로 대체하지 않음).
- Android 9 이하: 공용 폴더 생략 → ZIP 공유/저장만.
- GPKG 내보내기: 다음 단계(의도적 보류).
- 제출용 5179 SHP: hgis 「검수·제출」 몫. 앱은 작업 CRS만.

## 커밋

- 범위: 내보내기·관련 시험만 (다른 0.1.15~0.1.19 WIP 제외).
