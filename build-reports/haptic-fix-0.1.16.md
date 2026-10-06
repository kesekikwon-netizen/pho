# 0.1.16 햅틱 수정 (버튼 탭 무반응)

- versionName `0.1.16` / versionCode `17`
- APK: `/workspace/survey-app/dist/ExcavationSurvey-0.1.16-release-signed.apk` (동일 릴리스 키, SHA-1 `9dac6ce35f6bde0b75832fa8ac12aed750d173a8`)
- Drive 업로드: **안 함** (위임 지시)

## 원인
- 0.1.14~0.1.15 `TapHaptics` 가 `View.performHapticFeedback` 만 사용.
- 폰 시스템「터치 진동/햅틱」이 꺼져 있으면 조용히 실패 → 앱 설정「진동」ON 이어도 현장 무반응.
- 버튼 배선(`hClickable` / `MBtn`→`hCombinedClickable`)·권한 `VIBRATE`·설정 기본값(true)은 이미 있었음.

## 수정 (최소)
- `TapHaptic.kt`: 앱「진동」ON 이면 `Vibrator`/`VibrationEffect.createOneShot` 으로 직접 짧게 울림 (시스템 터치진동과 무관).
- `Settings.vibrationOn` 주석: SharedPreferences 기본 **ON**.
- 단위시험 `Ui0116HapticTest` + 기존 `Ui0114UxTest` 회귀.

## 시험 증거
- `gradle testDebugUnitTest --tests Ui0116HapticTest --tests Ui0114UxTest` → **11 tests, 0 failures** (Ui0116:4 + Ui0114Ux:7).
- 실기기 햅틱·시스템 터치진동 OFF 조합: **미시험**.

## 현장 확인
1. 덮어설치 후 설정 › 알림 소리·진동에서「진동」ON 확인.
2. 홈「환경설정」·측량 하단 버튼 탭 시 짧은 진동.
3. 진동 OFF 하면 탭 무진동.
