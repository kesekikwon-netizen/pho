# 깨끗한 트리 출시 빌드 (2026-10-06~)
- 출시 APK는 커밋된 깨끗한 트리에서만 빌드한다. V월드 내장키 파일은 git 밖: `cp src/main/kotlin/kr/archsurvey/map/VWorldDefaults.kt.template src/main/kotlin/kr/archsurvey/map/VWorldDefaults.kt` 후 실키를 `BUILTIN_API_KEY`에 넣거나(또는 보관본 VWorldDefaults.kt를 복사), `keystore.properties`·`local.properties`도 git 밖에서 복사해 빌드한다. 실키는 어느 ref에도 커밋 금지(.gitignore 등록).

# BUILD_STATUS (2026-10-04 18:40 KST, 1차 라운드: WP-0~5 일부)

## 빌드·시험 결과 (박스에서 실제 실행)
- `gradle testDebugUnitTest lintDebug assembleDebug`: **성공**. 단위 테스트 **119개 통과 / 0 실패 / 0 건너뜀** (기존 64개 유지·갱신 + 신규).
- 화면 렌더링 시험(Robolectric 4.14.1, JVM, 360x800dp·xxhdpi 가정) 8개 포함. 스크린샷 17장: `/workspace/survey-app/build-reports/`.
- APK(디버그 서명, 시험용): `app/build/outputs/apk/debug/ExcavationSurvey-debug.apk` (11.9 MB), 사본 `build-reports/ExcavationSurvey-debug-round1.apk`. 패키지 `kr.archsurvey.field.debug`, minSdk 21 / targetSdk 35.
- 환경: JDK 21, Gradle 8.9 (/workspace/tools/gradle-8.9), SDK 35 (/workspace/tools/sdk). 온라인 필요.

## 하지 못한 것 (미실행)
- **에뮬레이터 실행 불가**: `/dev/kvm` 은 있으나 box 사용자에게 접근 권한이 없고(root:103 crw-rw----), SDK 에 emulator·system-images 도 없음. 하드웨어 가속 없이 소프트웨어 에뮬레이션은 하지 않았다.
- **실기기(폰)·R10·SP60·NGII VRS 시험 전무.** Robolectric 은 실제 폰이 아니다: 터치 지연, 폰별 해상도·시스템 막대(insets), 글꼴, 성능, 블루투스, 직사광선·장갑은 확인 못 함.

## 구현됨 (이번 라운드)
- WP-0: 이전 UI 9개 파일 `legacy/`로 `git mv`(컴파일 제외), osmdroid 의존성 제거, `ARCHITECTURE.md`, design.md §0A "작업" 용어 정정
- WP-1: `device/GnssSource`(link·gnss·clockMs), `FakeGnssSource`(진짜 NMEA 문장 생성→실제 NmeaDriver 통과, 시드 재현, 시나리오 fieldWalk/allFixed), `SurveyEngine : GnssSource`
- WP-2: `core/StatusJudge` 고정/주의/불가 + 이유 + 저장 정책(허용/경고 후 허용/차단). 임계값 σh≤2 cm·5 s 는 제안값 [현장검증]
- WP-4: `antenna_db.json` R10 기본 = 퀵릴리즈 하단 +0.1991(마운트 하단 +0.1491 보조), SP60 ARP +0.0687, `AntennaRecord`, 최소 안테나 시트(모델·기준점·±1cm)
- WP-3: 자체 Canvas 지도(가짜 필지 배경·조사 구역 점선·이동 꼬리·현재 위치 마커+정확도 원+방향·점 라벨), 상태 띠/지도/정보/컨트롤 4영역 분리, 측점 72dp·보조줄 48dp, 폴 쪽 56dp 표시 전용 띠(좌우 반전), 측점→취득 중→결과 띠→펼침→(음성 시안), 다음 점, 되돌리기
- WP-5 일부: 상태 띠 3상태 + 기어 → 왼쪽 서랍(폭 80%, 오른쪽 지도 노출), 메뉴 구성(DrawerMenu), AI 항목은 비활성 자리

## 가짜/시안 (실제 아님)
- GNSS 전부 가짜 시나리오. 측점은 메모리에만 저장(DB 저장은 WP-6). 음성 메모는 **화면 시안만**(마이크 사용 안 함). 사진 슬롯은 눌러도 안내만.
- 서랍 메뉴 대부분은 "다음 라운드에 연결" 안내만. 빠른 시트(s14)·핸들 끌기 미구현.
- 글꼴: 시스템 기본(Light/Normal). Noto Sans KR·JetBrains Mono 번들은 미적용. 글로시 마감 미적용. 아이콘은 Canvas 자체 제작.

## 알려진 문제 / 위험
- 지도 비율: 800dp 가정에서 대기 68.5%, 취득/결과/녹음 66.0%, 펼침 47.3%(의도된 예외). 최소 680dp 높이 필요. 실제 폰은 상태·내비 막대가 빠져 더 작아진다 [현장검증].
- 기존: security-crypto alpha 의 API 21~22 동작 미확인, RFCOMM read()==-1 처리 점검, 안테나 PCO 상수는 문서 기준.
- `AntennaMath` 일부 문구(legacy 용)가 남아 있음; R10↔SP60 "약 13cm" 문구는 기본 기준점 기준.

## 라운드 2 (진행 기록)
- 에뮬레이터 확인: 이 박스는 /dev/kvm 이 있으나 vCPU 생성 시 커널 BUG(kvm_spurious_fault) → KVM 불가. 소프트웨어 에뮬(-accel off)로 부팅해 확인(느림). 상세: build-reports/emulator/README.md
- 에뮬레이터에서 발견·수정: 지도 Canvas 가 영역 밖(상태 띠)까지 칠함 → clipToBounds (커밋 d823bf8)
- WP-6: Room 점 저장(positionType, 소프트삭제), 사진 4장 제한(트랜잭션), 되돌리기/다시실행, 번호 재사용 금지. 화면(SurveyController)과의 연결은 3-E 에서.
- WP-7: DxfWriter(R2000/CP949/INSUNITS=6), ezdxf strict·audit 0·GDAL 판독 통과(tools/validate_dxf.py). 실제 CAD 열람 미실시.
- 3-E 일부: SurveyController ↔ PointStore(Room) 연결. 측점은 디스크에 저장, 되돌리기는 숨김(소프트 삭제), 번호는 되돌려도 재사용 안 함(P-0001 되돌린 뒤 다음은 P-0002). 앱 재시작 시 저장된 점 로드. 현재 신호는 여전히 **가짜**라서 저장되는 점도 가짜 위치(장치명에 '가짜 신호' 표시). 점 목록/편집 화면, 사진·음성 실제 연결은 **미구현**.
- feat/wp9-11 병합(커밋 9ba2554): 앱 아이콘(WP-11) 등. 브랜치에 이후 커밋이 더 생기면 다시 병합 필요.
- 서명 릴리스: 키 /workspace/secrets (권한 600, git 제외). dist/ 에 서명 APK·INSTALL.md·FIELD_CHECKLIST.md.
- 3-A(블루투스 SPP GnssSource)·3-B(NTRIP): 엔진·NTRIP 클라이언트·모의 캐스터 단위 시험(401, 마운트 없음, v2 chunked, 무데이터 타임아웃)은 이전 단계 코드로 이미 있음. **앱 화면(수신기 선택·연결·VRS 입력·오류 카드)에 연결하는 일은 아직 안 함.** 실기기 시험 미실시.
- 3-D, 3-F, 3-G(화면), 3-I, 3-J, 3-K, 폰트 번들, Phase 2: 이번 라운드에서 **착수 못 함**.

## 라운드 2b — 브랜치 feat/wp9-11 → feat/bg-ui (2026-10-04, 박스 실행; 실기기·R10·에뮬레이터 시험 없음)
검증 명령: `gradle testDebugUnitTest lintDebug assembleDebug` 통과 — 단위 테스트 **230개 통과 / 0 실패** (master 140 + 신규). APK(clean 빌드, debug) 11,643,215 B.

### 구현됨 (코드와 박스 시험이 있는 것)
- WP-11 앱 아이콘(적응형 전경/배경/모노크롬, 레거시 PNG, 소형 마크, 서랍 로고, Android 12 스플래시): 벡터 렌더 안전영역·디자이너 PNG 일치(IoU 0.9966) 시험. `tools/icon/`.
- WP-10 업로드 배경 정합 로직 `georef/`: GeoTIFF 태그 리더, 월드파일, .prj, DXF 범위, 좌표계 후보/판정, 닮음/아핀/최소제곱 + 잔차, `BgLayer` 모델(JSON 파일 저장소). pyproj(PROJ) 오라클 대조 통과. `GEOREF.md`.
- WP-9 MapLibre 스파이크 `spikes/maplibre/`(독립 프로젝트) + 결과 `spikes/maplibre/RESULT.md`. 옵트인 `-PwithMapLibre` 빌드에서 `MapLibreMapSurface` 컴파일·패키징 성공(기본 빌드는 Canvas).
- WP-8 `ai/StatusSnapshot.kt`(열거형·숫자·시각·불리언만, 좌표·계정·문자열 필드 없음, 전송 코드 없음), `AiLinkSetting`(꺼짐 고정), 서랍 "AI 연동" 자리(비활성). 시험 8개(필드 타입/이름 검사, 출력에 좌표 값 없음, ai 패키지에 네트워크·저장 코드 없음).
- UI(feat/bg-ui): 지도 오른쪽 위 레이어 버튼 → 투명도 시트(s36, 슬라이더+스위치) ↔ 관리 시트(s35, 삭제는 두 번 눌러야 지움, 파일 올리기) → 예시 파일 고르기 → 좌표 읽기 결과(s34c) / 경고(s34d: 좌표계 직접 지정·수동 정렬·취소) / 좌표계 고르기 / 수동 정렬(s34e: 쌍 1~4, 점별 오차·평균 오차, 미리보기 윤곽, 3점 검산 권고). 미정합 배경은 지도에 올라가지 않음. 시트 높이 ≤ 화면 50%, 버튼 ≥ 48dp(Robolectric 시험). 스크린샷 14장 `build-reports/bg/`.

### 가짜/시안 (실제 아님)
- 파일 선택은 **예시 파일 4종**(`FakeSample`, 실행 중 작은 GeoTIFF/DXF 생성). 정합 판정(`UploadAligner`)과 변환 계산(`AffineFitter`)은 진짜 모듈.
- 수동 정렬의 "점 찍기"는 미리 정한 위치를 약간의 오차와 함께 넣는 **버튼**(지도에서 손으로 찍기 미구현). DXF 수동 정렬 미연결. EPSG 번호 직접 입력 미구현(후보 목록만).
- 위성·지적도 행은 **연결 전 가짜**(지도에 아무것도 올리지 않음). 지도의 업로드 도면은 윤곽+빗금 표시(실제 화소/선 그리기 아님). 업로드 레이어는 메모리에만(앱 재시작·화면 회전 시 사라짐; Room 연결은 보류).
- 지도 기본 배율(1 m = 12 dp)에서는 100 m 도면이 화면보다 커서 윤곽 일부만 보인다.

### 미검증 (실기기 없이는 알 수 없음) — 전체 목록은 `FIELD_CHECKLIST.md`
- 모든 화면의 실제 폰 표시·터치, 아이콘의 실제 런처 표시, MapLibre 실제 렌더·제스처·캐시 비활성, 실제 현장 도면/수치지형도, 수동 정렬 실제 정확도, 대용량 성능.
