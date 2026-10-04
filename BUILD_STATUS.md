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
