# Release gate — 사진 기준점 0.1.2 (versionCode 3)

- 커밋 00fd114 (from 46eaa11). 수신기 코어(bt/PairFlow.kt · BtPairing.kt · BtCompat.kt · ui/PairModel.kt · PairSheet.kt · ConnectionModel.kt · engine/AndroidConnectionPlatform.kt)는 측량 앱 0.1.40 과 같은 파일(cmp 동일)
- APK: /workspace/gcp-app/dist/PhotoGCP-0.1.2-release-signed.apk (사본 /workspace/survey-app/dist/) · 9,510,637 B
- SHA-256: be2fcefbaa9c4ff4b59e6a48e0b9b4e2d4ed21b04dc9f15725cb38007e9678d9
- apksigner v1/v2/v3 OK · 인증서 SHA-1 9dac6ce35f6bde0b75832fa8ac12aed750d173a8 · aapt kr.archsurvey.gcp 3 / 0.1.2 「사진 기준점」
- 권한 추가: ACCESS_FINE_LOCATION maxSdk 30(안드로이드 11 이하 수신기 찾기용)
- 시험 239개, 실패 1 = NtripSocketTests.unknownHostIsDns(알려진 것). 새 core0140/Pair0140Test 19
- 확인 안 된 것: 실기기 미설치, 등록 안 된 R10 insecure 연결은 현장 확인 필요
