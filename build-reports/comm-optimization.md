# 통신 계층 감사·최적화 보고 (브랜치 `comm-opt`)

작성: 2026-10-05 (KST). 기준 커밋 `cac3a30`(0.1.6) 에서 분기. UI 파일은 건드리지 않았다(`ui/` 변경 0건).

> **먼저 읽을 것 — 확인 수준**
> - 아래 "확인됨"은 **JVM 단위 시험(목 캐스터·목 소켓·가상 시간)** 으로 확인한 것이다.
> - **실기기(폰), Trimble R10, SP60, NGII VRS 서버로는 시험하지 못했다.** 실제 블루투스 스택·실제 캐스터·절전(Doze)·배터리 소모는 전부 미확인이다. 섹션 5 에 모아 두었다.
> - 이 앱은 **블루투스 SPP(RFCOMM)만** 지원한다. BLE 수신기 코드는 없다(감사 대상 없음, 추가도 하지 않음).

## 0. 요약

| 영역 | 발견 | 수정 | 시험 |
|---|---|---|---|
| 블루투스 재연결 | 백오프가 데이터 1회 수신으로 초기화 → 깜빡이는 수신기에 대기 없이 재연결 폭주 / 블로킹 connect 가 취소 안 됨 / 쓰기 막힘 감지 불가 | 안정 수신(20 s) 후에만 초기화, 취소 시 소켓 close, 쓰기 워치독 | ✔ 목 소켓 |
| 끊김 원인 | 모든 무수신이 "연결 끊김"으로만 표시 | 소켓 닫힘 vs 무수신 구분, 연속 실패 시 `BT-123 전원/범위` 추정 | ✔ (추정 규칙만; 정확도는 실기기) |
| NTRIP 분류 | v2 마운트 없음(200+sourcetable) 을 데이터로 오인, 5xx/DNS/타임아웃 미구분, 소스테이블 타임아웃을 "빈 목록 성공"으로 | 종류별 예외·`NtripFailureKind`, 핸드셰이크 10 s | ✔ 목 캐스터 |
| NTRIP 재접속 | 네트워크 전환·등록 직후 낡은 신호가 **인증 실패 보류를 풀어 틀린 비밀번호로 재시도** | 인증/마운트 보류는 사용자 재시도만 해제 | ✔ |
| RTCM 중계 | 아무 바이트나 수신기로 전달(오류 페이지 포함), 연령은 "바이트 수신 시각" | RTCM3 CRC24Q 프레임 검증, 온전한 프레임만 중계, 연령=마지막 유효 프레임 | ✔ |
| GGA 업로드 | 위치 없는 GGA·낡은 GGA 도 업로드, 수신기 위치 전에 접속 | 위치 있는 GGA 만, 2분 초과 금지, 위치가 들어온 뒤 접속, 1~10 s 클램프 | ✔ |
| 데이터 절약 | 수신기 끊겨도 캐스터 접속 유지, 비RTCM 서버에 1 s 간격 재시도 | 수신기 끊김 60 s 초과 시 접속 보류, 종류별 백오프(30~60 s) | ✔ (수치 절감량은 **미측정**) |
| NMEA | 문장마다 스냅샷·Set 복사, 줄 버퍼 4096 | 호출당 1회 복사, 512, 체크섬 오류율 | ✔ |
| 측점 기록 | 시작 시각·최대 보정 연령 없음, 시각 빈 GGA 는 첫 에폭만 사용 | `AveragedPoint` 확장(아래), 중복 규칙 수정 | ✔ (**저장·화면 연동은 UI/DB 담당 필요**) |
| 배터리 | 알림이 위치 갱신마다 notify, 웨이크락 무기한 | 값이 바뀔 때만, 10분 만료+5분 갱신 | 코드 검토만 |

테스트: 기존 336개 + 신규 61개 = **397개**, 전체 통과(섹션 4).

## 1. 블루투스 연결

### 발견과 수정
1. **백오프 초기화 버그 (수정)** — `Engine.gnssLoop` 는 GGA 를 한 번 받으면 `attempt=0, failures=0` 으로 되돌렸다. 연결되자마자 2~3초 뒤 끊기는 수신기(전원 불안정, 다른 컨트롤러 간섭)면 매번 대기 없이 재연결해 BT 스택을 두드린다. → `LinkSupervisor`: **20초 이상 계속 수신한 뒤 끊긴 경우에만** 초기화. (`flappingReceiverDoesNotResetBackoff`, `failureCounterResetsOnlyAfterStableSession`)
2. **블로킹 `connect()` 취소 불가 (수정)** — 연결 시도(전략 최대 4개 × 약 12 s)가 코루틴 취소로 깨지지 않아 `stop()` 후에도 소켓이 살아 있을 수 있었다. → 취소 시 `close()` 로 깨움. (`cancelDuringBlockedConnectReturnsPromptlyByClosingSocket`)
3. **쓰기 막힘 감지 (수정)** — `write()` 가 반환한 뒤에야 3초 초과를 검사해서, 수신기가 보정 입력을 안 읽어 `write()` 가 영영 블로킹되면 감지하지 못했다(NMEA 는 계속 와서 읽기 워치독도 정상). → 워치독이 진행 중인 쓰기가 3 s 를 넘으면 `BT-142` 로 연결을 새로 맺는다. (`stalledRtcmWriteIsDetected`)
4. **끊김 원인 구분 (부분 수정, 추정)** — 클래식 블루투스 API 는 "수신기 전원 꺼짐"과 "범위 이탈"을 구분해 주지 않는다. 구분 가능한 것만 나눴다:
   - 소켓 EOF/IO 예외 → 상대가 끊음(`BT-122/140`)
   - 소켓은 살아 있는데 데이터만 끊김(워치독 DEAD) → `BT-123 전원/범위(추정)`
   - 한 번 연결됐던 수신기에 연속 2회 이상 시간 초과/소켓 닫힘/전부 실패 → `BT-123`
   - `Host is down` → `BT-123`
   - 처음부터 문장이 0개 → `BT-130 NMEA 출력 꺼짐`(사용자 조치 대기, 재시도 폭주 없음)
   - 블루투스 꺼짐/권한/미등록 → 사용자 조치 대기(기존 유지, 재시도 폭주 없음)
5. **스레드·코루틴 누수 점검 (확인)** — 읽기/워치독/쓰기는 `coroutineScope` 자식이고 취소 시 `close()` 로 블로킹 read 를 깨운다. 취소 후 새 소켓이 만들어지지 않고 읽기 스레드가 종료되는 것을 시험으로 확인(`cancelDuringStreamingClosesSocketAndEndsAllChildren`). 이전 코드의 구조도 같았으나 시험이 없었다.
6. **스레드 안전 (수정)** — `NmeaDriver.snapshot` 을 읽기 스레드가 쓰고 워치독이 읽는데 `@Volatile` 이 아니었다. `lastQuality`, 재연결 알림 플래그도 동일. `ntrip.value = ntrip.value.copy()` 읽기-수정-쓰기 경쟁은 `StateFlow.update` 로.
7. **`read()==0` 스핀 방지** — 100회 연속 0 이면 끊김으로 처리.
8. **재연결 시 낡은 보정 폐기** — 끊긴 동안 쌓인 RTCM 큐는 새 연결 때 비운다(기존 동작 유지, 시험 추가: `staleRtcmQueuedWhileDisconnectedIsNotReplayedAfterReconnect`).

### 포그라운드 서비스·화면 꺼짐
- 구조는 적절함: `connectedDevice` 포그라운드 서비스 + 부분 웨이크락이 소켓·NTRIP 수명을 화면과 분리. **화면이 꺼져도 유지되는지는 실기기에서만 확인 가능**하다.
- 수정: 알림은 제목/문구가 **바뀔 때만** 갱신(이전: 위치가 올 때마다 `notify`), 웨이크락은 10분 만료 + 5분마다 갱신(서비스가 비정상 종료돼도 CPU 를 영구히 붙잡지 않음).
- 알림 문구에 "보정: 수신기 위치 대기" 상태 추가.

## 2. NTRIP / VRS

### 발견과 수정
| # | 발견 | 조치 |
|---|---|---|
| N1 | NTRIP v2 에서 마운트포인트가 없으면 `HTTP/1.1 200 OK` + `Content-Type: gnss/sourcetable` 로 응답한다. 이전 코드는 200 이라서 소스테이블 텍스트를 RTCM 으로 수신기에 보냈다. | `Content-Type` 검사 → `NtripMountException` (`v2MountMissingReturnsSourcetableWith200`) |
| N2 | 응답 헤더 대기가 3 s 고정이라 느린 서버가 타임아웃으로 오분류될 수 있다. | 10 s(생성자 인자). 접속 시간 초과/응답 시간 초과를 구분(`NtripTimeoutException.phase`) |
| N3 | DNS 실패·연결 거부·경로 없음·5xx·서버 닫음·무데이터·비RTCM 이 하나의 일반 예외 처리. | 예외 종류 추가 + `NtripFailureKind`(AUTH/MOUNT/DNS/NETWORK/TIMEOUT/SERVER_ERROR/SERVER_CLOSED/NO_DATA/NOT_RTCM). 종류별 재시도 여부·백오프. 화면의 기존 문구 선택 단서(`UnknownHost`, `ConnectException`, `Timeout`, `동안 오지 않아요`…)는 호환 유지 |
| N4 | **인증 실패 보류가 풀림**: `ConnectivityManager.registerNetworkCallback` 은 등록 직후 `onAvailable` 을 즉시 호출하고 Wi-Fi↔LTE 전환 때마다 호출된다. 이 신호가 인증 실패(401/403) 대기를 풀어 틀린 비밀번호로 반복 접속할 수 있었다(계정 잠금 위험). | 신호를 둘로 분리: 네트워크 복구는 **백오프 대기만** 앞당기고, 인증/마운트 보류는 사용자 `retryNow()` 만 해제. 접속 시도 직전 낡은 신호는 버림 (`authFailureStopsRetrying_…`, `staleNetworkSignalAtStart…`) |
| N5 | 소스테이블: 응답이 전혀 없어도 타임아웃을 삼켜 **빈 목록을 성공으로** 반환, 읽을 때마다 전체를 문자열로 바꾸는 O(n²). | 무응답→예외, 꼬리 비교로 `ENDSOURCETABLE` 검출 (`fetchSourcetableSilentServer…`, `fetchSourcetableParsesAndStopsAtEnd`) |
| N6 | 마운트포인트 선택 기능 없음. | `MountInfo` 확장(위·경도, 인증, 비트레이트…) + `Sourcetable.recommend()` (RTCM3 만, VRS 우선/가까운 순/낮은 비트레이트 순) + `Engine.fetchMountPoints()`. **자동 선택은 하지 않음.** 화면 연결은 UI 담당 |
| N7 | GGA 주기가 프로필 고정(5 s), 클램프 없음. | 1~10 s 로 제한(`NtripTiming`), `Engine.setGgaInterval(ms)` (화면 설정 연결은 UI 담당). 첫 GGA 는 응답 직후 즉시 송신 (`ggaSentPeriodicallyAndFirstOneImmediately`) |
| N8 | 위치 없는 GGA(품질 0, 위·경도 빈칸)와 오래된 GGA 도 업로드. | `GgaRelay.forCaster` 가 위치 없는 GGA 거부, 2분 넘은 GGA 는 보내지 않음 |
| N9 | 수신기 위치(GGA)가 없는 채로 캐스터에 접속(VRS 는 위치가 필요). | `linkStreaming` 이 될 때까지 접속 보류 (`waitsForReceiverPositionBeforeConnecting_savesData`) |
| N10 | RTCM 이 아닌 바이트(오류 HTML, 포털 페이지)가 그대로 수신기로 전달됨. 중간에 잘린 조각도 전달. | `Rtcm3Framer`: 0xD3 + 길이 + **CRC24Q 검증**, 온전한 프레임만 수신기로. CRC 검사값 `"123456789"→0xCDE703` 시험. 3000바이트 받는 동안 유효 프레임이 0이면 `NOT_RTCM` (`nonRtcmStreamIsRejectedAndNotForwarded`) |
| N11 | 보정 연령 = "마지막 바이트 수신". 소켓은 살아 있는데 유효 데이터가 없는 경우를 못 잡음. | 연령 = **마지막 유효 RTCM 프레임** 시각. 10 s 유효 프레임 없음 → `NO_DATA` 재접속 (`silentSocketDetectedAsNoData…`). `NtripStatus.rtcmAgeMs()`, `freshness()`(신선 <3 s / 늙는 중 <10 s / 끊김) |
| N12 | 자동 재접속 | 종류별 지수 백오프(네트워크 2→30 s, 서버오류 5→60 s, 비RTCM 30 s, 서버닫힘 1→15 s), 15 s 이상 안정 수신 뒤 끊기면 초기화, 불안정이면 누적 (`backoffResetsOnlyAfterStableReception`) |
| N13 | 데이터 사용 | 수신기 위치 끊김 60 s 초과 시 캐스터 접속을 닫고 돌아오면 재개, 인증 실패 시 중단, 비RTCM 서버 30 s 간격. **절감량은 측정하지 못했다** |

### 변경하지 않은 것(의도)
- **403 도 자동 재시도 안 함**: 화면 문구("자동 재시도는 하지 않아요")와 일관되게 유지. 동시 접속 중복(같은 ID) 403 은 사용자가 "다시 시도"를 눌러야 풀린다.
- NTRIP v1/v2 선택 로직, Basic 인증 형식, 기본 NGII 호스트/마운트 값.

## 3. NMEA

- 체크섬 불량은 폐기+집계(기존 유지). **체크섬 오류율**(표본 20개 이상, 20% 초과)이면 `BT-131` 경고 (`ParserStats.checksumErrorRate`).
- 줄 버퍼 4096→512 (NMEA 최대 82자, 독자 문장 여유). 잘못된 줄이 오래 쌓이지 않는다.
- 한 문장이 여러 청크에 걸치거나 RTCM 바이너리가 섞여도 다음 문장은 정상 파싱(시험).
- 스냅샷: 문장마다 `copy`+`Set +` 하던 것을 호출당 1회 복사, 새 문장 종류/위성 수 변화가 없으면 집합을 재사용(`snapshotKeepsSameSetWhenNothingNew`). 파싱 지연 자체는 측정하지 않았다(처리량 시험 없음).
- 고정 상태: GGA 품질 지시자 4=Fixed, 5=Float, 2=DGPS, 1=Single 로 구분(기존). `FixQuality.isRtk / isCorrected` 추가.
- **보정 연령**: `GnssSnapshot.correctionAgeNowSec(now)` = GGA 의 연령 필드 + GGA 수신 후 경과(보수적으로 크게). `StatusJudge`: RTK 고정이어도 연령 > 10 s 이면 **주의 `OLD_CORRECTION`** (문턱 10 s 는 **제안값 [현장검증]**; 연령 필드가 비어 있으면 판정에 쓰지 않음).
- 남은 문구 문제: 품질 6/7/8(추측항법/수동/시뮬레이션)도 사유가 "보정 없이 단독 측위"로 나온다(기존 시험이 고정하고 있어 바꾸지 않음). 시뮬레이션(8) 품질 GGA 는 `UNAVAILABLE` 로 막히는 것만 확인.

## 4. 측점 기록 (평균화) 와 시험

`PointAverager.add(gga, gst, nowMs = null)` — 기존 호출은 그대로 컴파일된다. `AveragedPoint` 에 추가된 필드(전부 기본값 있음):
`startedAtMs`, `endedAtMs`, `durationMs`, `epochs`(표본 수, 기존), `maxCorrectionAgeSec`, `minCorrectionAgeSec`, `epochsWithoutAge`, `fixedEpochs`, `floatEpochs`, `rejectedDuplicate`, `rejectedNoPosition`.
- 수정: 시각 필드가 빈 GGA 를 내는 수신기는 모든 에폭이 같은 `""` 라서 **첫 에폭 하나만** 쓰였다 → 빈 시각은 중복 판정하지 않음.
- `startedAtMs` 는 단조 시계(`elapsedRealtime`) 값이다. 벽시계로 저장하려면 호출자가 변환해야 한다.

### ⚠ 연동 필요(내 범위 밖: UI·DB 파일)
`SurveyController.kt:295` 가 평균화를 직접 한다(엔진의 `measure()` 가 아님). 다음이 이루어져야 "시작 시각·표본 수·보정 연령 저장"이 끝난다:
1. `SurveyController` 에서 `avg.add(gga, gst, snap.lastGgaAtMs)` 로 호출하고 `AveragedPoint` 의 새 필드를 `PointEntity` 로 전달.
2. `data/Db.kt` `PointEntity` 에 열 추가(`avgStartedAtUtcMs`, `avgDurationMs`, `corrAgeMaxSec`, `fixedEpochs`, `floatEpochs`) + Room 마이그레이션(스키마 JSON 갱신) + CSV/GeoJSON 헤더.
3. 현재 저장되는 값은 `epochs`(표본 수)와 `correctionAgeSec`(**마지막 에폭 값만**)뿐이다.

### 시험 (397개 = 기존 336 + 신규 61)
- 신규 파일: `src/test/kotlin/kr/archsurvey/comm/` — `RtcmAndNmeaTests`(RTCM3/CRC, NMEA, GGA 중계, 판정, 평균화, 분류, 소스테이블), `NtripSocketTests`(실제 소켓 목 캐스터: v2 소스테이블, 503, 403, Bad Password, 무응답 타임아웃, 연결 거부, DNS 실패, GGA 주기, 감독자+실클라이언트 종단), `NtripSupervisorTests`(가상 시간: 인증 보류, 백오프, 안정 후 초기화, 무데이터, 비RTCM, 수신기 끊김 보류/재개), `LinkSupervisorTests`(목 소켓: 스트리밍, RTCM 청크 쓰기, EOF 재연결, 깜빡임 백오프, 무수신, 쓰기 막힘, 취소, 사용자 조치 대기).
- 기존 UI/로직 시험은 수정하지 않았고 모두 통과.
- 실시간 소켓·스레드를 쓰는 시험이라 느린 CI 에서 타이밍 여유가 부족할 수 있다박스에서 전체 시험을 `--rerun-tasks` 로 3회 연속 실행했고 3회 모두 397개 통과(0 실패)였다. 느린 기기·CI 에서의 안정성은 확인하지 못했다.

## 5. 남은 위험과 실수신기에서만 확인 가능한 항목

### 남은 위험 (코드로 더 줄일 수 있으나 이번에 하지 않음)
- 서비스는 `START_NOT_STICKY`: 시스템이 프로세스를 죽이면 자동으로 되살아나지 않는다(Android 12+ 백그라운드 FGS 시작 제한 때문에 의도). 사용자가 앱을 다시 열어야 한다.
- `BtError.PERMISSION_NEARBY` 등 사용자 조치 대기 상태에서, 권한을 준 뒤 `kickReconnect()` 를 부르는 곳은 UI(연결 화면)다. 이번 범위에서 확인하지 못했다.
- `RECEIVING` 시점이 바뀌었다: 이전엔 "첫 바이트", 지금은 "첫 유효 RTCM 프레임". 화면에 표시되는 시점이 약간 늦어질 수 있다.
- RTCM 이 아닌 형식(CMR, RTCM 2.x)을 주는 마운트는 `NOT_RTCM` 으로 거부된다. NGII 는 RTCM 3.x 라고 알려져 있으나 **실서버 확인 못 함**. 필요하면 `NtripSupervisor(notRtcmAfterBytes=…)` 로 조정/통과 모드 추가.
- 가짜 프리앰블(`D3 00 xx`)은 최대 약 1 KB 분량 뒤 프레임을 잠시 붙잡은 뒤 복구한다(1바이트씩 재탐색). 지연은 데이터 속도에 비례.
- 문턱값(10 s 무RTCM, 3 s 쓰기, 20 s/15 s 안정 기준, 60 s 보류, 10 s 보정 연령)은 전부 **제안값**.

### 실수신기/실서버에서만 확인 가능 (미확인)
1. R10/SP60 에서 SPP 연결 전략(보안/비보안/SDP/리플렉션) 중 무엇이 먼저 되는지, `connect()` 소요 시간.
2. 전원 끔·범위 이탈·다른 컨트롤러 선점 시 Android 가 내는 **실제 예외 문구** (`classify()` 의 문자열 매칭은 제조사별로 다르다). `BT-123` 추정의 정확도.
3. 수신기가 GGA 의 보정 연령 필드를 실제로 채우는지, GST 출력 여부, 출력 주파수(1/5/10 Hz)별 처리 부담.
4. NGII VRS 의 실제 응답(ICY vs HTTP, 소스테이블 형식, 401/403 문구, 동시접속 정책), GGA 최소/최대 허용 간격, 마운트별 RTCM 메시지 종류와 데이터량.
5. 블루투스로 RTCM 을 쓸 때 청크 크기·간격(R10 512 B/0 ms, SP60 256 B/20 ms)과 쓰기 막힘의 실제 빈도.
6. 화면 꺼짐·Doze·제조사 절전(삼성/샤오미 등)에서 소켓·NTRIP 유지. 웨이크락 갱신 주기 영향.
7. 배터리 소모(mAh/h)와 데이터 사용량(MB/h): **어떤 수치도 측정하지 않았다.**
8. 장시간(수 시간) 연속 실행 중 메모리/스레드 증가.
9. Android 14/15 의 `connectedDevice` 포그라운드 서비스 시작 제한 동작, 알림 권한 거부 시.
10. 실제 측점 취득 중 보정 끊김이 평균값에 미치는 영향.

## 6. 변경 파일
- 수정: `bt/BtCompat.kt`, `bt/BtError.kt`, `core/{Averager,GgaRelay,Nmea,NmeaDriver,StatusJudge}.kt`, `engine/{Engine,SurveyConnectionService}.kt`, `ntrip/Ntrip.kt`
- 신규: `engine/{LinkSupervisor,NtripSupervisor,NtripStatus}.kt`, `ntrip/Rtcm3.kt`, 시험 `src/test/kotlin/kr/archsurvey/comm/*`, 이 보고서
- `NtripStatus` 는 `Engine.kt` 에서 `engine/NtripStatus.kt` 로 옮겼고(같은 패키지·이름), 기존 필드는 그대로이며 새 필드는 기본값이 있다. `NtripState` 열거값은 늘리지 않았다(화면의 `when` 이 전수 처리).
- 새 공개 API(UI 연결용): `SurveyEngine.fetchMountPoints(cfg)`, `setGgaInterval(ms)`, `Sourcetable.recommend(...)`, `NtripStatus.rtcmAgeMs()/freshness()/paused/failure/nextRetryAtMs`, `GnssSnapshot.correctionAgeNowSec(now)`, `AveragedPoint` 새 필드.
