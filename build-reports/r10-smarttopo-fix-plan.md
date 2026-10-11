# R10 연결 수정 계획서 (SmartTopo C 디컴파일 근거, 측량 전용)

## 0. 전제
- 대상: 본인 앱(pho, `kr.archsurvey.field` 0.1.33) — Trimble R10 연결 에러, SmartTopo C에서는 정상.
- 범위: **측량만** (GGA 수신→VRS 보정→측점 저장). Tilt/전자기포/고급 SDK 기능 제외.
- 금지: SmartTopo 바이너리·SDK·appGuid·라이선스 복사, Empower/License 우회. 합법 범위(raw SPP+NMEA)만.

## 1. 디컴파일 증거 (out/smarttopo205C)
- 패키지: `kr.co.geosys.SmartTopo205C` 2.05.17(19), 클래스 10901, 리소스 4543. `base.apk` SHA `cea036e1…9751a`.
- 연결 방식: `Modules/BlueToothModule.java:1642` `connectTPSDK` → `BluetoothCommunicator(device)` + `ConnectionSettings(ConnectionType.Bluetooth)` → `Sensor.beginConnect()`. raw SPP 직접 연결 아님.
- 소켓 순서: `trimble/jssi/android/communicators/BluetoothCommunicator.java:71` `connect()` = **insecure SPP → BASE_UUID(`00000000-…`) secure → reflect ch1**.
- 드라이버: `MainActivity.java:305` `loadDriver(RSeries/SP80)`, `DriverManagerInstance(V2, appGuid)`. 네이티브 31개(`libSwiftJni.so` 28M 등), Empower 권한. 측위는 `SsiPositioning(Roving, 1Hz)` API, raw DCOL 64h 미사용.
- 매니페스트: `targetSdk 29`, Empower 권한 포함.

## 2. 본인 앱 현행 (out/pho-app)
- `bt/BtCompat.kt`: SECURE-first → INSECURE → SDP → REFLECT. R10에서 재인증/SDP 지연 → BT-120/124.
- `bt/R10AutoConfig.kt` + `TrimbleDcol.kt`: 자연 NMEA 2s → ENQ → BT포트 17/18/19에 GGA 1Hz(DCOL 64h) 탐색. SmartTopo 대응물 없음(SDK 내부 처리). NAK/무응답 시 BT-130.
- `device/DeviceProfile.kt`: R10 현장메모 "GGA 받다가 1~2분마다 세션 재시작 — R10에 쓰는 것은 RTCM뿐".
- `engine/Engine.kt` + `LinkSupervisor.kt`: RTCM 256B/20ms 동일 소켓 interleave(frameLock), reconnect 20s.

## 3. 판단
- 연결 단계 에러 주원인: **소켓 순서 불일치** (secure-first). → 수정 완료.
- 세션 재시작(1~2분): raw SPP에 RTCM+DCOL interleave + R10이 비인증 APPFILE을 리셋하는 것으로 추정. DCOL 최소화로 완화 가능, 완전 해소는 SDK 없이 불가.

## 4. 작업 (완료/예정)
- [x] T1: R10 insecure-first + BASE_UUID fallback (`BtCompat.kt`, `Engine.kt`, 브랜치 `fix/r10-insecure-first`).
- [ ] T2: 실기기 확인 — R10 단독(타 컨트롤러 분리, 2m) 연결, 오류번호+`R10 자동설정` 진단(ENQ ACK/NAK, 포트, NATURAL 여부) 수집.
- [ ] T3: T2 결과별 분기
  - 소켓 실패 지속(BT-120/124): 저장 선호전략 확인, bonded 재등록 안내, SCAN/CONNECT 권한·검색중지 로그 확인.
  - 무음(BT-130/ALL_NAK): 웹UI에서 BT포트 NMEA 수동 ON 후 NATURAL 되는지 확인, DCOL 추가 발송 금지(현행 유지).
  - 1~2분 재시작: RTCM 청크/간격 유지, `addPrecision` 재탐색 금지(현행 유지), `reconnectAfterMs` 조정은 T2 로그 보고만 결정.
- [ ] T4: T2 로그 반영 추가 수정 + 회귀(LinkSupervisor/Bt130 유닛) 확인 후 main 머지.

## 5. 비목표
- Tilt/기포/정밀 측위 API, Trimble SDK 연동, 라이선스 우회, 바이너리 복사.

## 6. 검증
- 성공: R10 연결→STREAMING(GGA)→VRS RECEIVING→FIX, 측점 저장. 5분 이상 세션 유지.
- 실패 시 수집: `LinkStatus.error.code`, strategy, `R10AutoResult(outcome/port/enq/tried/seenAfter/elapsedMs)`, TX/RX 바이트.
