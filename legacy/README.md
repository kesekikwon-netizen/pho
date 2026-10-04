# legacy/ — 컴파일되지 않는 이전 UI (삭제 아님, 격리)

2026-10-04 WP-0에서 `src/main/kotlin/kr/archsurvey/ui/*` 9개 파일을 `git mv`로 옮겼다 (이력 보존).
빌드 소스셋(`src/main/kotlin`) 밖이라 **컴파일되지 않는다.** 새 UI(상태 띠 + 지도 상시 + 서랍)와 구조가 달라 격리했으며, 화면별로 필요할 때 로직·문구를 참고해 새 `ui/`로 옮긴다.

| 파일 | 참고할 곳 |
|---|---|
| `AntennaUi.kt` | s4 안테나 높이 화면 구현 시 문구·흐름 |
| `ConnectScreens.kt` | s8a 장비 연결 / s9 VRS (BT 권한·기기 선택·NTRIP 입력) |
| `SurveyScreens.kt`, `MainVm.kt` | 측점 취득·저장·사진·내보내기 호출 흐름(Phase 1) |
| `SettingsScreen.kt`, `Components.kt` | 설정 화면 |
| `MapScreen.kt` | osmdroid 기반 — **폐기 예정**(osmdroid 의존성도 제거함). `map/MapCore.kt`의 TileMath·V-World URL 로직만 보존 |
| `AppRoot.kt`, `Theme.kt` | 하단 탭 루트 / 이전 초록 계열 테마 — 새 테마는 `ui/Theme.kt` |
