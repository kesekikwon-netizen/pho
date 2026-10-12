# 사진 기준점 0.1.1 (versionCode 2) — 릴리스 점검

- APK: `/workspace/survey-app/dist/PhotoGCP-0.1.1-release-signed.apk` (9,482,557 B)
- SHA-256: `19c3fc94ba98eded2c5ec75ec97f36375b448375ae3d6255dd9b079f05664999` (dist/SHA256SUMS 갱신, `sha256sum -c` OK)
- 패키지 `kr.archsurvey.gcp` · 이름 「사진 기준점」 · 0.1.1 / 2
- 서명: 측량 앱과 같은 키 (cert SHA-256 2f210fd4…20acdf3 = ExcavationSurvey 0.1.34–0.1.39, PhotoGCP 0.1.0) · v1+v2+v3 Verifies. keystore.properties 는 빌드 뒤 지움
- V월드 내장 키: `tools/inject-vworld-key.sh` 로 넣음(파일은 git 무시), APK dex 안에 있음 확인. 키 없으면 릴리스 빌드가 멈춤(`checkVWorldKey`)
- 가짜 GNSS: APK 안에 없음
- 시험: 220개 중 219 통과. 실패 1 = `NtripSocketTests.unknownHostIsDns` (상자 DNS 제한, 측량 앱에서도 같음)
  - gcp 시험 47개: Model 12 · Handoff 5 · ItwinXml 5(사용자 견본과 바이트 비교) · Measure 5 · Controller 13 · Snapshot 7
- 지도: 측량 앱과 같은 코드(CanvasMapSurface · TileImages · MapLayers.SATELLITE_CADASTRAL, 바꾸지 않음)
  - 스냅샷 01 = 실제 V월드 위성 타일을 받아서 그림(Robolectric, 강릉 좌표)
  - 지적(2d.vworld.kr:8895 레거시 WMS → 공식 WMS 대체): 이 상자에서는 8895 포트가 막혀 시간 초과 → 스냅샷에 지적선이 없고 오류 문구가 보임. 측량 앱과 같은 주소·코드(폰에서는 나왔음). 실기기에서 확인 필요
  - 시험 `mapState_satelliteCadastralLayers…`: 레이어가 sat + cad 둘 다, 키 없으면 배경 없음

## 스냅샷 (build-reports/screens, 360×800dp xxhdpi, JVM 렌더 — 실기기 아님)
- 01-main-map-satellite-cadastral.png — 메인 지도 화면(실제 위성)
- 02-main-map-zoomed-out.png
- 03-map-measure-fix.png · 04-map-float-blocked.png · 05-remeasure-confirm.png · 06-target-moved.png · 07-map-photo-stage.png
- 고르기 창: 08-picker-objects · 09-picker-kind-tiles · 10-picker-number · 11-picker-numpad-127 · 12-picker-section-letters · 13-picker-session · 14-picker-new-session · 15-picker-target-number · 17-picker-new-site · 17b-picker-new-site-district-epsg
- 16-sites-empty · 18-sites-one · 19-id-card · 20-menu · 21-handoff · 22-pole · 23-settings · 24-settings-advanced · 25-exit-confirm

## 자판이 남은 곳 (전부 감사함)
| 곳 | 왜 남김 |
|---|---|
| 새 유적 › 「직접 입력…」 | 맨 마지막 방법. 기본은 위치 주소(V월드 getAddress, 지도와 같은 키)로 만든 이름 · 최근 유적 이름 · 「유적 날짜」 타일 + 지구 번호 타일/번호판 |
| 설정 › 고급 › V월드 키 바꾸기 | 앱에 기본 키가 들어 있음. 다른 키를 쓸 때만 |
| 수신기·VRS 화면: 서버 주소 · 포트 · 아이디 · 비밀번호 | 측량 앱 수신기 화면 그대로(바꾸지 말라는 요청). 한 번 넣으면 기억. 주소·포트는 기본값이 들어 있음 |
| 수신기·VRS 화면: 마운트 포인트 | 서버에서 받은 목록에서 고름. 「검색」·「직접 입력」 칸은 선택 사항 |

없앤 자판: 유적 이름(기본), 약호, 작업자 글자, 대상 이름, 이름 바꾸기, 대상 검색(두 곳), 표적 번호 입력.
