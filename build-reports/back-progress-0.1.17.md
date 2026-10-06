# 0.1.17 시스템 뒤로 — 「연결 중」 진행 창 (ConnectionProgress)

- versionName `0.1.17` / versionCode `18`
- APK: `/workspace/survey-app/dist/ExcavationSurvey-0.1.17-release-signed.apk` (0.1.16과 같은 릴리스 키, cert SHA-1 `9dac6ce35f6bde0b75832fa8ac12aed750d173a8`, v1/v2/v3 검증됨)
- SHA-256 `1e6c0d1cc47b01ecaa5c61f100e3cb40694a8e0555556cf4aaafbd5c8d8e2b4c`
- Drive 업로드: **안 함** (위임 지시)

## 증상
「연결 중」 진행 창이 떠 있을 때 폰의 **시스템 뒤로**를 누르면 진행 창은 그대로 남고, 뒤 화면만 뒤로 갔음(측량 → 홈, 또는 장비 연결 화면 닫힘).

## 원인
- `SurveyMapScreen.backTarget()` 에 `cc.showProgress` 처리가 없어서 뒤로가 `cc.close()` / `homeOpen = true` 로 갔음.
- `ConnectionProgress` 자체에 `BackHandler` 가 없었음.

## 수정 (최소)
1. `SurveyMapScreen.kt` `backTarget()`: drawer 다음, 마운트 선택·연결 화면 확인보다 **먼저**
   `cc.showProgress && cc.connectRequested` → `cc.showProgress = false` (「닫기(계속 연결)」과 같음, 연결은 계속).
   조건은 진행 창이 실제로 그려지는 조건(`ConnectionHost`)과 같게 맞춤.
2. `ConnectionScreen.kt` `ConnectionProgress`: `BackHandler { cc.showProgress = false; if (done) cc.close() }` (= 닫기/「확인」 단추와 같은 동작).
   나중에 구성되므로 Compose 뒤로 처리에서 이게 우선임.
3. MountPicker: 기존 `backTarget` 의 `closeMountPicker` 경로가 이미 있으니 범위를 넓히지 않음.
4. `build.gradle.kts` 17→18 / 0.1.16→0.1.17. `HeaderModelTest` 의 버전 고정 검사(0.1.16 때 이미 0.1.15/16에 묶여 실패 중이던 것)를 0.1.17/18로 맞춤.

## 수정 후 뒤로 동작
- 진행 중(미완료): 뒤로 1번 → 진행 창만 닫힘, 연결 계속(`connectRequested` 유지, `stopReal` 안 부름), 뒤 화면은 그대로. 다음 뒤로부터 원래 순서(연결 화면 닫기 → … → 측량 → 홈).
- 완료(「연결 완료 · 고정됨」): 뒤로 1번 = 「확인」 → 진행 창과 연결 화면이 함께 닫힘.

## 시험 증거 (Robolectric JVM, 실기기 아님)
- 새 시험 `Ui0117BackProgressTest` 3개: backTarget(측량 위) / backTarget(연결 화면 열린 상태) / 실제 `OnBackPressedDispatcher.onBackPressed()` 경로.
- 음성 대조: 수정 두 곳을 잠시 되돌리면 **3/3 실패**, 다시 넣으면 3/3 통과.
- `gradle testDebugUnitTest assembleRelease` → **527 tests, 0 failures, 0 errors, 0 skipped**, 빌드 성공.

## 폰에서 확인
1. 0.1.17 덮어설치 → 측량 화면 → 환경설정 › 장비 연결 → 「연결」.
2. 「연결 중」 창이 뜨면 **폰 뒤로** → 창만 사라지고, 장비 연결 화면이 그대로 있어야 함. 하단 대기열·상태 띠에 연결 진행이 계속 보여야 함.
3. 한 번 더 뒤로 → 장비 연결 화면 닫힘. 그다음 뒤로 → 측량 → 홈.
4. 연결이 끝나 「연결 완료 · 고정됨」일 때 뒤로 → 진행 창과 연결 화면이 함께 닫힘.

## 미시험
- 실기기 시스템 뒤로/제스처 뒤로(Android 13+ 예측 뒤로 애니메이션 포함): **미시험**.
- 실제 BT 수신기를 연결하는 도중의 동작: 미시험 (가짜 플랫폼으로만 확인).
