# 사진 기준점 0.1.8 (vc9) — 데이터 안전 + Field Ivory 디자인 1차

## 바뀐 것
1. 데이터 안전
   - `fallbackToDestructiveMigrationFrom(1)` 제거 → `MIGRATION_1_2` (site/obj 재구성, 기록 보존; obj.no = 같은 유적·종류 안 seq 순서, v1 이름 중복은 뒤 것에 약호 붙임). `ALL_MIGRATIONS`.
   - MigrationTest: schemas/1.json 그대로 옛 DB 생성 → Room 2로 열기(스키마 검증) → 모든 행 보존 확인; 모든 옛 스키마→현재 경로 존재 시험.
   - SafeFile: XML/TXT/목록.csv/넘기기 zip 을 .tmp + fsync + rename 으로 씀(반쯤 쓴 파일 없음).
   - 평균 측정 체크포인트(prefs commit, 10%마다). 프로세스가 죽으면 다음 실행 때 그 유적·대상·차수·표적 재기 화면으로 돌아오고 「표적 #N 평균이 X%에서 끊겼어요 · 다시 재세요」 알림(30분 이내). 반쯤 모은 평균은 쓰지 않음(폴 이동 가능). 잰 표적은 DB 에 이미 있음.
2. 공용 토큰 `kr/archsurvey/ui/FieldIvory.kt` (주 행동 #C6613F, FIX #1F7A3A, FLOAT #A86400, 오류 #B42318, 최소 16sp, 56dp, 측량 96dp). 검정 주 단추 → 테라코타. Pretendard(OFL 1.1, assets/licenses/OFL-Pretendard.txt) Regular/SemiBold/Bold. gcp 화면 글자 16sp 미만 전부 16sp, Ty 기본 크기 16sp 이상.
3. 화면: g03 표적 재기(지금 재는 것+폴 카드, 표적 칸 완료/지금/대기, 대상 + 96dp 「표적 #N 측량」), g04 재는 중 큰 「남은 N초」 + 지도 위 작은 띠, g08 오류 칩(예외 원문 숨김, 무엇+영향+[다시]), 버튼·타일 최소 56dp.
4. 접힘/펼침: 폭 600dp 이상이면 왼쪽 지도 / 오른쪽 340dp 판(표적 재기·유적 지도).
5. lint: 매니페스트 FINE_LOCATION(maxSdk 30) CoarseFineLocation 오탐 tools:ignore.

## 그대로 둔 것
VWorld 키·위성·지적, iTwin XML/TXT 형식, R10 잠금·넘김, 측정 판정(엄격 FIX), 기능 추가 없음.

## 시험
- testDebugUnitTest 280개 중 279 통과. 실패 1: NtripSocketTests.unknownHostIsDns — 이 상자 DNS 가 없는 호스트도 198.18.0.1 로 풀어 시간 초과(환경, 코드 무관).
- 새 시험: MigrationTest(2), DataSafety0108Test(5), Layout0108Test(접힘 100%/130%, 펼침 130%: 측량 단추 ≥96dp, 표적·폴 ≥56dp). 화면: build-reports/0.1.8/screens/.
- lintRelease 통과. assembleRelease 서명 = 0.1.7 과 같은 인증서(2f210fd4…acdf3).

## 남은 것(이번에 못 함)
- g01 홈 유적, g02 새 대상, g05 대상 체크리스트, g06 내보내기, g07 설정 나누기, g09 폴 시트는 토큰(색·글자·56dp)만 적용, 시안 배치 그대로는 아님.
- 숫자 글꼴은 IBM Plex Mono 유지(시안은 Pretendard tnum).
- 펼침에서 저장 알림(toast)이 화면 가운데라 오른쪽 판 위에 겹침.
- FieldIvory.kt 는 발굴 측량에 아직 복사 안 됨(그쪽 팀이 같은 파일을 넣어야 함).
- 실기기(Fold7) 확인 안 함. 설치·실행 안 함.

---
## 2차 (CEO 검토 반영, 같은 vc9 · 배포 전)
- g01 홈(폴더 목록): 세그먼트 전체/측량 필요/사진 필요/보내기, 차수·면 묶음 카드, 배지(시작 전·측량 N점 남음·사진 필요·보내기 준비·끝), 아래 PC로 보내기 + 새 대상.
- g02 새 대상: 종류 3열, 번호 − / 큰 값(누르면 번호판) / + 와 빠른 번호, 차수·면, 맨 아래 「N호 ○○ 만들기 / 1차 · 면」.
- g05 폴더 안: 「4단계 중 N단계 완료」 막대 + 단계 카드 4장(완료 초록 ✓ · 지금 테라코타 테두리+배지 · 대기 회색) + 「다음: ○○」.
- g06 PC로 보내기: 무엇을(안 보낸 것·오늘·유적 전체) · 받는 곳(공유하기 / USB·PC 케이블 안내) · 들어가는 파일 카드. zip 내용·형식 그대로.
- g07 설정: 일반/수신기/고급 탭. g09 폴 시트: 큰 1.60 m · ±0.10 · 프리셋 4 · 적용.
- 공용: 상태 알약 채움색, 머리줄(제목+보조줄), 선택 타일 = 연테라코타+테라코타 테두리, 시트 손잡이.
- 좌표·숫자: Pretendard + fontFeatureSettings "tnum" (Ty.coord / Ty.ledger).
- 펼침: 저장 알림은 지도 경계 안 아래쪽(ToastAnchor) — 오른쪽 판과 겹치지 않음(시험 GScreensUnfoldedTest).
- FieldIvory.kt 단일 원본: /workspace/design-program/shared/FieldIvory.kt (sha256 76a7afa6…bc80c, 앱 안 사본과 같음).
- NtripSocketTests.unknownHostIsDns: 리졸버가 .invalid 를 싱크홀 주소로 풀면 Assume 으로 건너뜀(이 상자: 198.18.0.1).
- lint NewApi(Engine.kt ConcurrentHashMap.KeySetView.clear, API 24) → Collections.newSetFromMap 로 교체(안드로이드 5–6 안전).
- 비교 PNG: build-reports/0.1.8/compare/g*.png (목업 | Robolectric 렌더), 렌더 원본 build-reports/0.1.8/render/, 스크립트 tools/compare.py.

### 목업과 다른 점(의도)
- g07: 목업의 진동·저장 소리 스위치, 글자 크기(보통/크게/아주 크게), 배경 지도 선택은 앱에 없는 기능이라 넣지 않음(새 기능 금지). 대신 있는 설정(폴 높이·평균 횟수·볼륨 키·수신기·V월드 키·진단)을 탭에 나눔.
- g06: 목업의 「이 대상/이 차수」 범위와 사진·ID 카드 파일 포함은 기존 zip 형식에 없어 기존 범위(안 보낸 것/오늘/전체)와 실제 내용(목록 CSV·XML·TXT)으로 표시. Google Drive 따로 단추 없음(공유 창에서 고름).
- g02: 2차/3차 타일 없음(새 대상은 항상 1차 — 기존 흐름).
- g05: 사진 장수(「지금 0장」)는 앱이 세지 않아 표시 안 함.
- 렌더는 V월드 타일 끔(네트워크 없음) → 지도는 회색 격자.

### 시험
testDebugUnitTest 290개: 통과 289 · 건너뜀 1(DNS 싱크홀) · 실패 0. lintRelease 통과. 서명 인증서 = 0.1.7 과 같음(2f210fd4…acdf3).
