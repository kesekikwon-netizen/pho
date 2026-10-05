# 0.1.27 릴리스 빌드 (versionCode 28) — 출시 게이트 판정 전 (UX-0127-1 수정본)

> **현재 APK SHA-256 `4a755abba02c04c5fb673ddc91b1b06cf94ae2d206803007222a05137aa8d155`** (UX-0127-1 수정, 07:31 KST). 아래 §APK 의 264e2e72… 는 수정 전 = **폐기**.

작성 2026-10-06 07:2x KST · 측량앱 안드로이드. **설치·Drive 업로드 안 함** (survey-release-gate: 출시게이트 「출시 가능」 판정 전).

## 범위 (동결)
- iTwin Capture SurveysData XML 내보내기 (app git `befe773`, 측량앱 내보내기 작업 — 상세 `build-reports/itwin-capture-xml-export-0.1.26.md`)
- 버전 범프 27/0.1.26 → **28 / 0.1.27**
- 버전 고정 시험 2개만 0.1.27/28로 갱신 (`HeaderModelTest.versionIs0_1_27Build28`, `PhoneGps0120Tests.versionIs0_1_27Build28`)
- 그 밖의 코드 변경 없음. 유구면·그리드·VRS 스파이크(T14 보류) 미포함.

## befe773 검토 (짧게)
- 바뀐 파일 12개 모두 내보내기 영역: `AndroidExportPlatform.kt`, `ExportService.kt`, `ItwinCaptureXml.kt`(새), `MoreFormats.kt`(enum 1줄), `ExportModel.kt`, `ExportScreen.kt` + 시험·표본 리소스.
- **안 건드림 확인:** BT-130 치유, `RtcmMountCompat`, 소리(`Feedback`/`FixSoundGate`), 품질 게이트·엄격 모드(`StatusJudge`/`SurveyController`/`QualityGate0126`), NMEA/Link/Ntrip — befe773 파일 목록에 없음.
- `ExportFormat.DEFAULT`/`HGIS` 집합 그대로 → 기존 기본 내보내기·hgis 원탭에 iTwin 안 섞임.
- `ExportPlatform.exportFormats(4인자)` 기본 구현이 3인자로 위임 → 기존 시험용 가짜 호환.
- 차단 없음. 비차단 메모는 아래 「우려」.

## 시험
- 명령: `gradle --offline cleanTestDebugUnitTest testDebugUnitTest` (범프 후)
- 1차: 621/2 실패 — 버전 고정 시험 2개(0.1.26 기대). 시험만 0.1.27/28로 고침.
- 2차(최종, 디스크 07:22 KST): `app/build/test-results/testDebugUnitTest/*.xml` 90개 합산
  **tests 623 · 통과 623 · 실패 0 · 오류 0 · 건너뜀 0**
  (ItwinCaptureXmlTest 11개 + ExportControllerTest.itwinKeepNamesIsPassedThrough 포함)

## APK
- 경로: `dist/ExcavationSurvey-0.1.27-release-signed.apk` (10,028,352 B, `gradle --offline assembleRelease`, lintVitalRelease 통과)
- SHA-256: ~~`264e2e72ccedf01a24b491c03cc2929adea7b58ed6d27696271c85e7ddcec55f`~~ (폐기, 수정 전) → §수정 UX-0127-1
- aapt badging: `package: name='kr.archsurvey.field' versionCode='28' versionName='0.1.27'` · compileSdk 35 (`dist/aapt-0.1.27.txt`)
- apksigner verify: v1 true · v2 true · v3 true (`dist/apksigner-0.1.27.txt`)
- 인증서 SHA-1 `9dac6ce35f6bde0b75832fa8ac12aed750d173a8` = 0.1.26과 **같은 키** (DN CN=Survey Field App, O=photomaster kwon, C=KR; cert SHA-256 2f210fd4…acdf3)
- META-INF 미보호 경고는 0.1.25와 같은 일반 AGP 경고.

## iTwin XML 확인
임시 단위 시험(실행 후 삭제·커밋 안 함)으로 `ItwinCaptureXml.write/namesCsv` 출력을 파일로 씀 → `build-reports/itwin-capture-xml-sanity-0.1.27/`
- `xmllint --noout`: gen-sample1 / gen-mixed / gen-mixed-keep + 표본 sample-1..4 **모두 well-formed**
- `gen-sample1.xml` == `sample-1.xml` **바이트 동일** (cmp)
- 태그 경로 골격(SurveysData/SpatialReferenceSystems/SRS/{Id,Name,Definition}, ControlPoints/ControlPoint/{Id,SRSId,Name,Category,Position/{x,y,z},HorizontalAccuracy,VerticalAccuracy,CheckPoint}): gen-mixed == sample-1..4 **모두 같음**
- 섞은 경우(10호·2호·「기준점A & <B>」(검사점 메모)·2호 재측·4호(높이 없음)·2-1호): Target #2, #10 → 이름 그대로 뒤로, `&`/`<>` 이스케이프, 검사점 true, σ 0.023/0.041 반영·나머지 0.01 하한, 높이 없는 4호는 XML에서 빠지고 대조표에 「높이가 없어 XML에서 뺌」. UTF-8, BOM 없음.

## 우려 (비차단)
1. **git 이력 뒤처짐:** app git HEAD는 0.1.14 + hgis(e07b10f) + befe773뿐. 0.1.15~0.1.26 작업(파일 ~44개 수정 + 새 파일 다수: Drive, PhoneGps, FixSoundGate, VWorldDefaults 등)이 **커밋 안 된 채** 작업 트리에만 있음. APK는 작업 트리 기준 빌드. 이번 커밋은 범위만(버전 줄·HeaderModelTest·보고서). 이력 정리는 별도 지시 필요.
2. 검사점 판정이 부분 문자열 `check`(소문자) → 메모 「rechecked」 등도 검사점 true. 제안값 [현장검증].
3. 높이 없는 점은 XML에서 빠져 Target 번호 비게 됨(예: 4호) — 대조표에만 기록. 문서화돼 있음.
4. iTwin Capture Modeler 실제 불러오기 미시험(박스에 없음). 표본 비교만.

## 미시험
- 실기기: 작업 전송 → iTwin Capture XML 체크 → 적용 → ZIP 안 XML 열기 (게이트 판정 후 설치)
- R10/SP60/VRS 실기 (이번 범위 아님, 변화 없음)

## 게이트 체크리스트 (이번 버전)
- [x] versionCode/versionName 범프 · 같은 키
- [x] 시험 623/0/0/0 디스크 확인
- [x] build-reports
- [ ] QA·UX리뷰·출시게이트 회의(내보내기 전문 차단만) — **대기**
- [ ] 실기기 내보내기 확인 — 판정 후

## §수정 UX-0127-1 (출시 게이트 차단, 2026-10-06 KST)
**문제:** 높이 없는 점이 iTwin XML 에서 화면 안내 없이 빠짐(대조표 CSV `itwin_capture_names_<EPSG>.csv` 에만 기록).

**변경 (최소, 내보내기 영역만 · 버전 그대로 0.1.27 / 28):**
- `export/ItwinCaptureXml.kt`: `droppedNoHeight(points)` — XML 쓰기와 같은 규칙(`MoreFormats.groundZ == null`)으로 빠지는 점 번호(측정 순서).
- `export/ExportService.kt`: `ExportResult.itwinDroppedCount` / `itwinDroppedNames` (기본 0/빈 목록). ITWIN_XML 을 골랐을 때만 채움.
- `ui/ExportModel.kt`: `ExportOutput` 에 같은 두 필드(기본값 → 기존 가짜·호출 호환), `ExportSummary.noHeightPoints`(기본 0). 완료 멘트 끝에 `ExportController.itwinDroppedNotice()` 결과를 덧붙임: 「· iTwin XML에서 높이 없는 점 n개 빠짐(4호, 7호, 9호 …)」 — 앞 3개 이름, 더 있으면 「 …」. iTwin 안 골랐거나 0개면 안 붙임.
- `engine/AndroidExportPlatform.kt`: 위 필드 전달 + summary 에 높이 없는 점 수.
- `ui/ExportScreen.kt`: (1) 결과 화면 개수 줄 아래 `export_itwin_dropped` 띠, (2) 내보내기 전 iTwin 옵션 아래 `export_itwin_no_height` 「높이 없는 점 n개는 iTwin XML에서 빠져요 (대조표 CSV에만 적힘)」. 둘 다 **주의 노랑 바탕(t.caution) + onCaution 글자** — 노랑 글자를 아이보리 바탕에 바로 쓰면 7:1 고대비 규칙을 못 맞춰서 상태 띠와 같은 바탕색 방식.
- 시험 3개(`ExportTests.kt`): `ExportServiceTest.itwinDroppedNoHeightPointsAreReported`(실제 ZIP — 4호 빠짐·Target #4 없음·CSV만 고르면 0), `ExportControllerTest.itwinDroppedPointsAppendedToCompletionMessage`(멘트 끝 문구·미선택 시 없음·summary 수), `ExportControllerTest.itwinDroppedNoticeText`.

**한계:** 완료 토스트 자체는 기존 OK(✓) 종류 그대로 — `FeedbackHub` 에 주의 종류가 없어 새로 만들면 공용 알림·햅틱 코드를 건드리므로 범위 밖. 주의색은 작업 전송 화면 띠로 보임. 토스트 주의 종류는 UX 판단 필요(비차단 후보).

**확인:**
- `ItwinDumpTmpTest` 소스에 없음(`rg` 0건).
- APK: `dist/ExcavationSurvey-0.1.27-release-signed.apk` 10,030,831 B, 07:31 KST · **SHA-256 `4a755abba02c04c5fb673ddc91b1b06cf94ae2d206803007222a05137aa8d155`**
- aapt: `kr.archsurvey.field` versionCode='28' versionName='0.1.27' · apksigner v1/v2/v3 true · cert SHA-1 `9dac6ce35f6bde0b75832fa8ac12aed750d173a8`(같은 키)
- 시험(마지막 gradle 실행, 그 뒤 단일 시험 안 돌림): `gradle --offline cleanTestDebugUnitTest testDebugUnitTest` BUILD SUCCESSFUL, 끝 **07:33:03 KST**. 디스크 XML 90개 합산 **626 · 통과 626 · 실패 0 · 오류 0 · 건너뜀 0** (623 + 새 3).
- 실기기·Drive: 안 함(게이트 판정 전).
