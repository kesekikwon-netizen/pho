# 사진 기준점 0.1.0 (versionCode 1) — release gate

- 패키지 `kr.archsurvey.gcp` · 이름 「사진 기준점」(부제 iTwin Capture 표적 측점) · minSdk 21 / target 35
- APK: `/workspace/survey-app/dist/PhotoGCP-0.1.0-release-signed.apk` · 9,479,599 B
- SHA-256 `52b6820706b7809007968be085ca94831bab73cae5982762d722b52aebf14592` (dist/SHA256SUMS, SHA256SUMS.txt)
- 서명: 측량앱과 같은 키 (cert SHA-256 2f210fd4…20acdf3, ExcavationSurvey-0.1.37과 동일), v1+v2+v3 Verifies. keystore.properties 는 빌드 후 삭제.
- aapt: package kr.archsurvey.gcp, versionCode 1, versionName 0.1.0, label 사진 기준점.
- 릴리스 dex 에 FakeGnss 클래스 0개(가짜 GNSS 없음). VWorldDefaults.kt 는 git 제외(.gitignore), 템플릿만 커밋.

## 범위 (0.1.0)
유적 목록·새 유적(이름·약호·좌표계 5185–5188) / 대상 관리판(측점·사진·전달 필요·끝 4칸 = 거르기, 전체·유구·토층·기타, 검색) / ＋유구·＋토층 자동 번호, 고정 ID(GN-001, 작업자 글자 선택) / 측점 화면(위성+지적 지도, 표적 2×2, ＋표적 추가(실물 번호), 길게 눌러 다시 재기·번호 바꾸기, 차수 칩·＋차수) / 저장마다 XML+TXT 자동 저장 `Documents/iTwinGCP/<유적>/<ID>_<대상명>/<ID>_<대상명>_<n>차_EPSG<code>.xml` / ID 카드(QR `ID|n차|EPSG|T범위`, 화면 밝기 최대) / 「사진 다 찍었어요」 / 점검(3개 미만 막음, 다시 잰 값 2 cm 초과 → 움직였나요, 20 m 안 다른 대상 같은 번호 경고·빈 번호 제안) / 유적 지도 / PC로 넘기기 zip(전달 필요·오늘·전체, 목록.csv UTF-8 BOM) Android 공유 / 설정(수신기, VRS, 폴 R10 1.6·SP60 1.8 0.1 m, 평균, 고급: 진단·V월드 키 가림) / 2단계 종료 「수신기 연결 끊기」.
수신기·VRS·지도 코드는 측량앱 release/0.1.37(2b98fab)에서 로직 그대로 복사 (목록 reused-files-0.1.0.txt).

## 미룸
폰 촬영·사진 자동 분류, 폰 안 3D/AprilTag 인식, 구글 드라이브 업로드, 여러 폰 병합, 대상 삭제/보관, CSV 외 내보내기.

## 시험
215개 중 214 통과. 실패 1: NtripSocketTests.unknownHostIsDns — 샌드박스 DNS(측량앱에서도 늘 실패하던 것, 코드 문제 아님).
- 앱 고유 47: ModelTest 9, ItwinXmlTest 5(강릉 1호 − Measurement 바이트 비교 포함), HandoffTest 5, MeasureTest 5(엄격 FIX), ControllerTest 10(Room), SnapshotTest 8
- 측량앱에서 가져온 수신기 코어 시험 168 (LinkSupervisor, NTRIP, RTCM/NMEA, FIX 흔들림, 안테나, 연결 UX 등)
- 화면: build-reports/screens/01–21 · 아이콘: build-reports/icon/

## 체크리스트
- 시스템 뒤로: 위 창부터 닫음(GcpController.back) ✓ · 진동/소리: 측량앱 FeedbackPlayer 그대로 ✓ · 가짜 GNSS 없음 ✓ · 같은 키 ✓ · 이 문서 ✓
- 회의(QA/UX 에이전트)는 따로 열지 않음 — 자체 점검만.

## 기기 확인 필요(미검증)
R10/SP60 실제 연결·VRS FIX, V월드 지도 표시(키 주입 빌드), Documents/iTwinGCP 공개 폴더 저장(Android 10+ MediaStore, 9 이하), 공유 시트 zip 전달, iTwin Capture 에서 XML 불러와 Target #N 자동 매칭, ID 카드 QR 인식, 측량앱과 나란히 설치.
