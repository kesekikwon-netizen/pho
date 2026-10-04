# BUILD_STATUS (2026-10-04 16:22 KST) — 개발 중단 시점 스냅샷

## 빌드 상태: 정상
- `gradle testDebugUnitTest lintDebug assembleDebug assembleRelease` 모두 성공. 단위 테스트 64개 통과(0 실패). lint(NewApi/InlinedApi=error) 통과.
- APK: build/outputs/apk/debug/ExcavationSurvey-debug.apk (12MB), build/outputs/apk/release/ExcavationSurvey-release.apk (8.4MB, keystore.properties 없어 debug 키 서명 — 배포 금지)
- minSdk 21 / targetSdk 35 / compileSdk 35 (aapt 확인).

## 환경
- JDK 21, Gradle 8.9 (/workspace/tools/gradle-8.9), Android SDK /workspace/tools/sdk (platform 35, build-tools 35.0.0). local.properties sdk.dir 설정됨.
- 빌드: `JAVA_HOME=<jdk> /workspace/tools/gradle-8.9/bin/gradle assembleDebug` (온라인 필요; --offline 은 osmdroid 미캐시로 실패).

## 구현됨 (코드 존재, 실기기 미검증)
- NMEA 파서(GGA/GST/GSA/ZDA, 체크섬, 라인 상한), NmeaDriver 건강도, 평균 측량, TM 투영(5185~5188)
- DeviceProfile(R10/SP60), 모델 선택/저장, 모델별 안내·재연결 정책
- BT RFCOMM 4단계 폴백, BtError(BT-1xx), 포그라운드 서비스, 재연결
- NTRIP 클라이언트(기본 RTS1.ngii.go.kr:2101 / VRS-RTCM31), GGA 중계, 소스테이블
- 안테나 높이: DB(assets/antenna_db.json, §5+§10 adapters), 기준점별 PCO 계산, 출처 표시, 모델 변경 안내, 불일치 경고(값 변경 안 함), 베이스 연장봉 0.15, 기타 부품 높이(숨김, 기본 0), 결과 한 줄 요약. 어댑터 heightM 은 표시용(이중 가산 없음). 필수 3종 테스트(2.000 QR→2.1991, 2.050 마운트→2.1991, SP60 2.000→2.0687) 통과
- Room(points/photos), 사진 촬영/갤러리/썸네일, CSV/GeoJSON/ZIP 내보내기(안테나·보정고 포함)
- 피드백(진동/소리/시각)
- UI: 모델선택, 연결, NTRIP, 측량(안테나 바·다이얼로그·기준점 그림), 결과/사진, 목록/상세, 지도(osmdroid V-World 위성·지적도, 타일 디스크 저장 안 함), 설정, 하단 탭 루트

## 미구현 / 모의
- Spectra 토탈스테이션 프로토콜 없음(SP60 는 GNSS 로 처리). 수신기 보고 안테나 높이는 NMEA 에 없어 사실상 항상 '기본값/직접 입력'
- 지도: 오프라인 캐시 없음(약관 확인 전 금지), 실제 V-World 키로 타일 로딩 미검증
- UI 스크린샷/에뮬레이터 실행 미수행(Compose 화면 런타임 동작 미확인)
- 릴리스 서명 키 없음, R8/minify 꺼짐, 앱 아이콘 기본

## 알려진 문제 / 위험
- 실기기(R10/SP60/NGII VRS) 테스트 전무. 안테나 PCO 상수는 문서 기준 [현장확인]
- security-crypto 1.1.0-alpha06 가 API 21~22 에서 런타임 동작하는지 미확인(매니페스트 병합은 통과). 
- 사선 높이 공식 현장 검증 필요; 2.6m 텔레스코픽 폴 135mm 방향 등 §10.7 [미확인] 항목
- 불일치 경고는 2.05/2.00 ±1.5cm 휴리스틱
- R10 기본 기준점: **퀵릴리즈 하단으로 확정(사용자 지시 2026-10-04)**. 현재 코드/asset(앱 정책 = 마운트 하단)은 아직 이 확정을 반영하지 않음 → 플랜 승인 후 개발 단계 1에서 변경(`plan/phase1-antenna-reference.md`). 이번 턴은 문서만 수정
- RFCOMM read()==-1 처리 점검 필요(BtTransport/Engine)
