# 0.1.28 릴리스 빌드 (versionCode 29) — T14 음성 「저장」 측점 · 출시 게이트 판정 전

작성 2026-10-06 08:5x KST · 측량앱 안드로이드(구현 담당). **기기 설치·Drive 업로드 안 함** (게이트 판정 전).
근거 문서: `plan/research-voice-command-save.md` §7(통신)·§9(게이트)·§14(UX 확정). 사용자 지시 2026-10-06 08:32(작업 관리 경유) — 보류 해제·구현 허용.

## 1. 엔진 결정 + 오프라인 확인
**결정: 플랫폼 `android.speech.SpeechRecognizer` (추가 라이브러리·모델 없음, APK 크기 영향 거의 0). Vosk(82MB) 쓰지 않음.**
- API 31+ 이고 `isOnDeviceRecognitionAvailable` 이면 `createOnDeviceSpeechRecognizer` = **「기기 내 인식」(오프라인)**.
  - API 33+ 는 `checkRecognitionSupport` 로 ko 설치 확인: 설치됨 → 기기 내 / 지원되지만 미설치 → `triggerModelDownload` 요청 + 이번엔 온라인 / 응답 없음(4초)·오류 → 기기 내로 시도.
  - API 31~32 는 언어 확인 API 없음 → 기기 내로 시도, 오류 12·13(언어 없음)이면 기능을 끄고 안내(자동으로 온라인으로 바꾸지 않음 — 동의 없이 전송 금지).
- 아니면 표준 인식기 + `EXTRA_PREFER_OFFLINE`(API 23+, 효과 보장 없음) = **「온라인 인식」** — 켤 때 동의 창 필수.
- `ko-KR`, `FREE_FORM`, 후보 1개, 부분 결과 끔, API 33+ `EXTRA_BIASING_STRINGS` = 저장·예·취소.
- minSdk 21: 인식 서비스가 없으면 설정에 「이 폰에서는 쓸 수 없어요」만 보이고 토글 숨김. 21~30 은 표준(온라인) 경로만.

**사용자 폰 확인 (SM-F966N, adb 읽기 전용 · 설치·변경 없음, 08:34 KST)**
| 항목 | 결과 |
|---|---|
| OS | Android 17 (SDK 37) — 온디바이스 API(31+)·언어 확인 API(33+) 모두 해당 |
| 기본 음성 인식 서비스(`settings secure voice_recognition_service`) | `com.google.android.tts/…GoogleTTSRecognitionService` (Speech Services by Google, googletts 20260817) |
| `android.speech.RecognitionService` 제공 앱 | ① `com.google.android.as/…AiAiSpeechRecognitionService` (Android System Intelligence — 기기 내 인식 제공자) ② `com.google.android.tts/…GoogleTTSRecognitionService` ③ `com.anthropic.claude/.bell.assist.ClaudeRecognitionService`(기본 아님) |
| Samsung | `com.samsung.android.bixby.ondevice.kokr` 설치(빅스비 전용, 표준 RecognitionService 로 노출 안 됨 → 이 앱은 못 씀) |
| 시스템 언어 | ko-KR |
| 0.1.27 앱 RECORD_AUDIO | granted(음성 메모 때 허용) |

- **결론:** 이 폰은 기기 내 인식 경로를 탈 조건(API 37 + ASI 인식 서비스)을 갖춤 → 「기기 내 인식(오프라인)」이 될 **가능성이 높음**.
- **[미확인]** ko-KR 기기 내 언어팩이 실제로 설치돼 있는지 / `isOnDeviceRecognitionAvailable` 반환값 — adb 읽기 전용으로는 확인 불가(앱 실행 시 `checkRecognitionSupport` 가 판단). 미설치면 앱이 내려받기를 요청하고 이번엔 온라인 동의 창을 띄움.
- **[현장확인]** 비행기 모드에서 실제 인식되는지.

## 2. 동작 (요구사항 대응)
| # | 요구 | 구현 |
|---|---|---|
| 1 | 같은 품질 게이트 | 음성 「저장」·이어폰 버튼 → `SurveyController.voiceHost.requestSave()` = 측량하기 버튼과 같은 `onMeasure()`(결과 화면에선 버튼과 같은 `nextPoint()`→`onMeasure()`). 저장 판단 새로 없음. FIX/σ/보정 나이/엄격 모드 그대로 |
| 2 | 설정 토글 | 설정 > 「음성 「저장」으로 측점」 기본 **끔** (`voice_save_on`) |
| 3 | 듣는 중 표시 | 지도 왼쪽 아래 알약: 점(초록=듣는 중) + 「듣는 중 · 「저장」」/「안내 중」/「수신기 연결 대기」/「녹음 중 · 듣기 멈춤」/「잠시 쉼」 + 둘째 줄 **「기기 내 인식」 또는 「온라인 인식」(빨강 테두리·글자)**. 확인 대기 중엔 화면 위 띠 「「예」라고 말하거나 화면에서 고르세요 · n초 뒤 취소 · 이어폰 버튼 = 취소」 |
| 4 | 햅틱·소리 | 알아들은 순간 새 이벤트 `FeedbackEvent.VOICE_HEARD`(35ms 진동·90ms 짧은 비프)를 기존 `FeedbackPlayer` 로 — 소리/진동 설정·1.5초 연타 방지 그대로. 저장 결과음(SURVEY_OK/FAIL)은 기존 경로 그대로 1회 |
| 5 | 오인식 방지 | 첫 후보 1개만, 공백·문장부호 제거 후 **정확히 「저장」**. 「저장해」「저장소」「저장 저장」「P-0012 저장됨」 등 버림. 승인은 「예」만(「네」 아님), 취소는 「취소」「아니요」「아니오」 |
| 6 | 마이크 권한 | 토글을 켤 때만 요청(음성 메모와 같은 요청 통로). 거부면 꺼진 채 |
| 7 | 오프라인 | §1. 온라인이면 켤 때 동의 창(「말소리가 음성 인식 서비스(인터넷)로 보내질 수 있어요」), 설정·알약에 「온라인 인식」 표시 |
| §14 | 화면 확인을 음성으로 하나씩 | 주의(CAUTION) 확인·기울기 확인·중복 점 시트를 TTS 로 하나씩 묻고 「예」 또는 화면 탭만 승인. 「저장」은 승인 아님. 10초 지나면 취소(+「저장 안 함」). 중복의 「예」 = **새 점으로 따로 저장**(덮어쓰기 아님), 취소/시간 초과 = 저장 안 함 |
| §14 | 미디어키 = 요청만 | 이어폰 재생/일시정지·HEADSETHOOK 1회 = 저장 요청. 확인 대기 중 누르면 **취소**. 길게 누름 반복 무시. 음성 저장이 켜져 있고 측량 화면이 앞일 때만 키를 먹음 |
| §7 | 폰 내장 마이크·SCO 끔 | SCO 를 열지 않음(옵션도 없음). 인식기가 BT 이어폰으로 스스로 SCO 를 여는지는 [미확인] |
| §8 | 마이크 중재자 | `MicArbiter`(앱 1개, `SurveyController.mic`): 음성 메모 녹음 시작 → 명령 듣기 먼저 멈춤, 녹음 끝 → 다시 듣기. 녹음 중엔 명령 불가 |
| — | 측량 화면 앞일 때만 | `onPause` 즉시 멈춤. 다른 화면·창(점 목록·설정·되돌리기 확인 등)·음성 메모 재생 중·**수신기 미연결/끊김/폰 GPS** 이면 멈춤. 멈춘 동안·멈춘 뒤 늦게 온 결과는 세대 번호로 **버림 — 나중에 실행 안 함** |
| — | 연속 오인식 쿨다운 | 알아듣지 못한 말 3번 연속 → 1.5초 쉼. 저장 수락 사이 1.5초(기존 규칙), 측정 중·흐름 진행 중·확인 창 떠 있음 → 버림. 음성 저장 10초 안 되돌리기가 10분에 2번 → 5분 쉼(§5.2) |
| — | TTS 자기 트리거 | 말하는 동안 인식기 정지 + 끝난 뒤 0.5초 더 쉼. 결과 안내 문구 「P-0012 저장됨」은 정확 일치가 아니라 다시 들어도 저장 안 됨 |

**버그 수정(버튼 경로에도 해당, 게이트 확인 요청):** 기본 모드 주의(CAUTION) + 기울기 정책 「확인」 + 기울기 넘음일 때, 주의 승인 → 기울기 승인 → **주의 확인이 다시 뜨고 → 기울기 확인이 다시 뜨는 무한 반복**(0.1.26부터). 음성 「하나씩 묻기」 시험에서 발견. `answerTilt` 가 주의 승인 뒤에 뜬 기울기 창이면 주의 확인을 다시 묻지 않게 고침(상태는 매번 새로 판정 — 불가로 바뀌면 그대로 막힘). 시험 `buttonPathCautionThenTiltNoEndlessLoop`.

## 3. 바뀐 파일 (app/)
- 새: `src/main/kotlin/kr/archsurvey/voicecmd/VoiceCommand.kt` (매처·마이크 중재자·상태 기계 — Android 의존 없음)
- 새: `src/main/kotlin/kr/archsurvey/engine/AndroidVoicePlatform.kt` (SpeechRecognizer 연속 듣기·방식 확인·한국어 TTS)
- 새: `src/main/kotlin/kr/archsurvey/ui/VoiceViews.kt` (설정 카드·듣는 중 알약·확인 대기 띠·문구)
- `ui/SurveyController.kt`: `mic`·`voice`·`voiceHost`(같은 onMeasure 경로)·`saveSerial`, 주의 확인 요청 기억, 기울기 창 열쇠, 음성 메모 녹음에 중재자, 되돌리기 알림, 주의→기울기 반복 버그 수정
- `ui/SurveyViewModel.kt`: 배선 + 200ms tick, `restoreVoice()`, onCleared 해제
- `MainActivity.kt`: onResume/onPause 전경 알림, `onKeyDown` 미디어키
- `ui/SurveyMapScreen.kt`: 알약·확인 띠 배치(2줄) · `ui/ToolScreens.kt`: 설정 카드 1줄
- `feedback/Feedback.kt`: `VOICE_HEARD` 1개 추가(기존 이벤트 변경 없음)
- `data/Settings.kt`: `voiceSaveOn`(기본 false)·`voiceOnlineOk`
- `AndroidManifest.xml`: `<queries>` RecognitionService·TTS_SERVICE (API 30+ 패키지 가시성). 권한 추가 없음(RECORD_AUDIO 기존), FGS 타입 변경 없음
- `build.gradle.kts`: 28/0.1.27 → **29 / 0.1.28**
- 시험: 새 `voicecmd/VoiceCommand0128Tests.kt`(29), `ui/Ui0128VoiceTest.kt`(4, Robolectric 렌더) · 버전 고정 2개 갱신(`HeaderModelTest`, `PhoneGps0120Tests`)
- **안 건드림:** BT-130 / RtcmMountCompat / LinkSupervisor / NMEA·Ntrip / StatusJudge / 내보내기(T16) / 유구면·그리드 / Drive

## 4. 시험
- 명령(최종, 그 뒤 단일 시험 안 돌림): `gradle --offline cleanTestDebugUnitTest testDebugUnitTest` BUILD SUCCESSFUL, 끝 **08:46:56 KST**
- 디스크 `app/build/test-results/testDebugUnitTest/*.xml` **94개** 합산: **tests 659 · 통과 659 · 실패 0 · 오류 0 · 건너뜀 0** (0.1.27 626 + 새 33)
- 새 시험 주요: 매처(단독 일치·첫 후보만·예/네), 중재자(메모 선점·복귀, 실제 `toggleVoice`), **게이트 동등성**(FIX 음성=버튼 같은 점·같은 저장음 / **엄격+주의 = 버튼과 같은 메시지·확인창 없음·실패음 1회** / **기본+주의 = 같은 확인창, 「저장」·「네」로 승인 안 됨, 「예」로만**), 10초 시간 초과 취소, 「취소」·미디어키 취소, 미디어키 요청 경로, 주의→기울기 하나씩, 화면 탭 답변, 중복 시간 초과=저장 안 함·「예」=새 점, **쿨다운(연속 저장 없음)**, **화면 떠남·링크 끊김 시 멈춤 + 늦은 「저장」/「예」 버림·지연 실행 없음**, TTS 자기 트리거, 연속 오인식 쿨다운, 다른 창·재생 중 멈춤, 되돌리기 반복 5분 쉼, TTS 없음, 기본 끔·켤 때만 권한, 온라인 동의·표시, 미지원 폰
- lint: `assembleRelease`(lintVitalRelease 통과) + `lintDebug` 오류 0 (NewApi/InlinedApi 오류 없음; 경고 SwitchIntDef 2건 = API 31 오류 코드 10~13 을 숫자로 씀, 의도)

## 5. APK
- `dist/ExcavationSurvey-0.1.28-release-signed.apk` 10,059,886 B, 08:45 KST (`gradle --offline assembleRelease`)
- **SHA-256 `dbd671929c9341b8720542ea282ac373fa4b89e3a06b4d43f5020183324a3424`**
- aapt: `kr.archsurvey.field` versionCode='29' versionName='0.1.28', sdk 21 / target 35 (`dist/aapt-0.1.28.txt`)
- apksigner: v1/v2/v3 true, cert SHA-1 **`9dac6ce35f6bde0b75832fa8ac12aed750d173a8`** = 0.1.27과 같은 키 (`dist/apksigner-0.1.28.txt`)
- APK 크기 +29 KB (0.1.27 10,030,831 B)

## 6. 릴리스 노트 (사용자용 초안)
**0.1.28 — 음성으로 측점**
- 설정 > 「음성 「저장」으로 측점」을 켜면, 측량 화면에서 「저장」이라고 말해 측점할 수 있어요. 측량하기 버튼과 똑같은 품질 판정을 거쳐요.
- 주의·기울기·같은 자리 확인은 음성으로도 하나씩 물어요. 「예」라고 말하거나 화면을 눌러야 저장해요. 10초 안에 답이 없으면 저장하지 않아요.
- 이어폰 재생 버튼 1번 = 저장 요청(확인에는 못 써요, 확인 중 누르면 취소).
- 측량 화면이 보이고 수신기가 연결돼 있을 때만 들어요. 지도 왼쪽 아래 마이크 표시로 듣는 중인지 알 수 있어요.
- 폰이 한국어 기기 내 인식을 못 하면 **「온라인 인식」**으로만 켤 수 있고, 켤 때 말소리가 인터넷 음성 인식 서비스로 보내질 수 있다고 묻고 동의를 받아요. 화면에도 「온라인 인식」이라고 표시돼요.
- 고친 것: 주의 상태에서 기울기 확인까지 뜨면 확인 창이 계속 반복되던 문제.
- 아직 현장에서 확인하지 않은 것: 인식률(바람·발굴 소음·동료 대화), 배터리, 시끄러운 곳 오인식 — 아래 목록.

## 7. [미확인] / [현장확인]
**[미확인]**
1. 사용자 폰 ko-KR 기기 내 언어팩 설치 여부(§1) · `checkRecognitionSupport` 실제 응답
2. 기기 내 인식기가 API 33+ `EXTRA_BIASING_STRINGS` 를 반영하는지
3. 표준(온라인) 인식기가 `EXTRA_PREFER_OFFLINE` 으로 오프라인 동작하는지(보장 없음)
4. 인식기가 BT 이어폰 연결 시 스스로 SCO 를 여는지(앱은 열지 않음)
5. 인식기 서비스(다른 프로세스) 마이크 해제와 음성 메모 `MediaRecorder` 시작 사이 겹침(중재자는 순서만 보장)
6. 한국어 TTS 음성 데이터 오프라인 설치 여부(없으면 화면·효과음만, 동작은 그대로)
7. Android 17 에서 연속 재시작 시 서비스 측 제한(ERROR_TOO_MANY_REQUESTS 등) 빈도

**[현장확인]**
1. **인식률**: 폴 마운트 거리·바람·삽/체질·발전기·동료 대화 속 「저장」 정상 수락률과 **거짓 수락**(목표 제안: ≥95% / ≤1회/시간)
2. **배터리**: 측량 화면 상시 듣기 1시간 OFF vs ON (`dumpsys batterystats`), 기존 BT SPP+NTRIP+화면 켜짐 위에서
3. **소음·오인식**: 「저장했어?」 등 대화 문장이 단독 「저장」으로 잘리는지, 「예」 오인식
4. **세션 재시작**: 플랫폼 인식기는 발화마다 세션을 끝내고 다시 열어요 → 일부 기기·서비스는 **시작음(삑)** 이 날 수 있고, 다시 듣기까지 지연(약 0.2~1초)이 있어요. 앱에서 삑 소리만 끌 방법이 없음(스트림 음소거는 앱 피드백음까지 꺼져서 쓰지 않음). 실제 삑·지연·말 앞부분 잘림 확인 필요
5. 한국어 TTS 숫자 읽기(「P-0012」·「1.2도」)·소음 속 청취성, 잔향 쉼 0.5초 적정성
6. 이어폰 재생 키가 포그라운드 Activity 에 오는지(이어폰·폰 조합, 다른 음악 앱 재생 중), 장갑·바람 오입력
7. 10초 확인 시간·1.5초 쿨다운·5분 쉼 기준의 현장 적정성
8. 온라인 인식 시 LTE 끊김에서의 안내(「온라인 인식 연결 안 됨」) 표시

## 8. 알려진 한계
- 화면이 꺼지거나 다른 앱이 앞이면 듣지 않음(설계). 화면 꺼짐 미디어키(MediaSession) 없음.
- 폰 GPS(수신기 미연결) 모드에서는 음성 저장 안 됨(링크 끊김 규칙).
- 화면을 떠나거나 링크가 끊기면 진행 중 음성 확인은 버리고, 화면의 확인 창은 손으로 답할 수 있게 그대로 둠(자동 승인·자동 취소 없음).
- 중복 점의 「재측량으로 덮기」는 음성으로 못 고름(화면만). 음성 「예」 = 새 점.
- 온라인 인식은 토글을 켤 때마다 동의 창을 띄움. 앱 재시작 때 켜짐 복원은 저장된 동의(`voice_online_ok`)가 있을 때만(없으면 꺼짐).
- **git:** app git 에는 0.1.15~0.1.26 작업이 여전히 커밋 안 된 채(0.1.27 보고서 우려 1). 이번 커밋은 T14 파일만이지만 `SurveyController.kt`·`Settings.kt`·`MainActivity.kt`·`SurveyViewModel.kt`·`SurveyMapScreen.kt`·`ToolScreens.kt`·`Feedback.kt`·`AndroidManifest.xml` 은 이미 커밋 안 된 이전 변경이 있던 파일이라 그 변경도 함께 들어감. `VWorldDefaults.kt`(내장 키) 등 나머지는 커밋하지 않음.

## 게이트 체크리스트 (이번 버전)
- [x] versionCode/versionName 29 / 0.1.28 · 같은 키
- [x] 시험 659/0/0/0 디스크(XML 94개, 덮어쓰기 없음)
- [x] 햅틱·소리 = 설정 따름(`FeedbackPlayer`), 실패음 중복 없음(시험)
- [x] build-reports
- [ ] QA·UX리뷰·출시게이트 회의 · 통신GNSS(마이크/SCO 무변경 확인) — **대기**
- [ ] 실기기 — 게이트 판정 후
