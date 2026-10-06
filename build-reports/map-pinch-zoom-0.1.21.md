# map pinch zoom 0.1.21 (2026-10-05 KST)

## Summary
- **Root cause:** `MapArea` only used `detectDragGestures` (one-finger pan). No pinch / transform gesture handler — zoom buttons (`zoomIn`/`zoomOut`) worked; finger pinch did not.
- **Fix:** Replace with `detectTransformGestures` (pan + pinch). Add `SurveyController.scaleBy(factor, focusXM, focusYM)` so zoom keeps the pinch centroid under the fingers (same local XY mapping as Canvas `sx`/`sy`). Clamp `dpPerM` to 2.0…80.0 (same as zoom buttons).
- Version: **0.1.21** / versionCode **22**.

## Files
- `ui/SurveyMapScreen.kt` — `detectTransformGestures` on `map_area`
- `ui/SurveyController.kt` — `scaleBy`
- `build.gradle.kts` — version bump
- `UiLogicTests.kt` — `scaleByZoomsAndKeepsFocus`
- `HeaderModelTest.kt`, `PhoneGps0120Tests.kt` — version asserts

## Tests / APK
- `gradle testDebugUnitTest assembleRelease --offline` → **590 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL
- APK: `/workspace/survey-app/dist/ExcavationSurvey-0.1.21-release-signed.apk`
- SHA-256: `a08aa22aa57deea678bdbf3b8841fa36794116a56766b018e93632a19229c94d`
- Signed: same release key (`CN=Survey Field App, O=photomaster kwon, C=KR`); jarsigner verified

## Untested on device
- Real SM-F966N pinch feel / two-finger start over chrome buttons
- Phone GPS / VWorld tile draw regression (logic unchanged; no intentional change there)
