package kr.archsurvey.ui

import kr.archsurvey.bt.PrecisionResult
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.LinkState

/** 0.1.30 T23: 정밀도(GST) 안내 문구. SP60 은 화면이 없어 「SP60 설정 앱」 같은 말은 쓰지 않는다 */
object PrecisionGuide {
    /** SP60 자동 켜기 실패(NAK·5초 안에 GST 없음·횟수 상한) — 통신GNSS 확정 문구 */
    const val SP60_FAIL = "SP60이 정밀도(GST)를 보내지 않아요. 터미널/SPace에서 켜 주세요"
    /**
     * R10: 0.1.33 부터 앱이 GST/GSA 를 자동 보충(5 s 확인 + 포트 탐색 최대 약 20 s)한다.
     * 그 뒤에도 GST 가 없을 때만 → 25초(자동 보충이 끝날 시간)
     */
    const val R10_NO_GST = "수신기가 정밀도(GST)를 보내지 않아요(앱 자동 켜기 실패). R10: 웹 설정 → Bluetooth NMEA에서 GST·GSA 켜기"
    const val R10_NO_GST_AFTER_MS = 25_000L

    // 0.1.30 T23-b: 엄격 모드 + GST 없음 → 측점 막힘 창
    const val PRECISION_OFF = "수신기 정밀도 출력 꺼짐"
    const val RETRY_LABEL = "자동 켜기 다시 (SP60)"
    const val SETTINGS_LABEL = "설정 열기 (엄격 모드 바꾸기)"
    const val RETRY_SENT = "SP60에 정밀도(GST·GSA) 켜기를 다시 보냈어요 — 몇 초 기다려요"
    const val RETRY_UNAVAILABLE = "지금은 다시 보낼 수 없어요 — SP60 연결을 확인하세요"
    const val RETRY_OK = "정밀도(GST) 받는 중 — 다시 측점하세요"
    /** 막힘 창 본문: 원인 + 기기별 해결 경로 + 모드는 사용자가 바꾼다 */
    fun strictBlockedNote(deviceId: String?): String =
        "고정(FIX)이지만 수신기가 정밀도(σ, GST)를 보내지 않아 엄격 모드(FIX만 저장)에서 측점할 수 없어요.\n" +
            when (deviceId) {
                DeviceProfiles.SP60_ID -> "SP60: [자동 켜기 다시] · 안 되면 터미널/SPace에서 GST·GSA 켜기"
                DeviceProfiles.R10_ID -> "R10: 앱 자동 켜기가 안 되면 웹UI I/O 설정 → BT 포트 → NMEA에서 GST·GSA 켜기"
                else -> "수신기 설정에서 NMEA GST·GSA 출력을 켜 주세요"
            } + "\n엄격 모드를 끄면 주의 표시와 함께 저장돼요(설정에서 직접 바꿔야 해요)."

    /** 「정밀도 값을 받지 못했어요」(NO_SIGMA) 주의에 붙이는 기기별 안내 */
    fun noSigmaHint(deviceId: String?): String? = when (deviceId) {
        DeviceProfiles.SP60_ID -> "SP60: 앱이 GST 켜기를 요청해요 · 안 되면 터미널/SPace에서 GST·GSA 켜기"
        DeviceProfiles.R10_ID -> "R10: 앱이 GST 켜기를 시도해요 · 안 되면 웹 UI I/O 설정 → BT 포트 → NMEA에서 GST·GSA 켜기"
        else -> null
    }
}

/**
 * 정밀도 안내를 **연결당 한 번만** 내는 문지기(점마다 아님). 순수 로직(JVM 시험).
 * 연결 = 링크가 STREAMING/STALE 로 들어온 때부터 그 밖으로 나갈 때까지.
 */
class PrecisionNoticeGate(private val r10NoGstMs: Long = PrecisionGuide.R10_NO_GST_AFTER_MS) {
    private var connected = false
    private var since = 0L
    var shownThisConnection = false; private set

    /** 수신 갱신마다. 낼 문구가 있으면 돌려준다(R10 10초 GST 없음) */
    fun onUpdate(deviceId: String?, link: LinkState, seenTypes: Set<String>, nowMs: Long): String? {
        val up = link == LinkState.STREAMING || link == LinkState.STALE
        if (!up) { connected = false; return null }
        if (!connected) { connected = true; since = nowMs; shownThisConnection = false }
        if (deviceId == DeviceProfiles.R10_ID && !shownThisConnection && "GST" !in seenTypes && nowMs - since >= r10NoGstMs) {
            shownThisConnection = true
            return PrecisionGuide.R10_NO_GST
        }
        return null
    }

    /** SP60 자동 요청 결과. GST 가 여전히 없으면(실패·부분·상한) 이 연결에서 한 번 */
    fun onActivation(deviceId: String?, r: PrecisionResult): String? {
        if (deviceId != DeviceProfiles.SP60_ID || !connected || shownThisConnection) return null
        if (!r.gstMissing) return null
        shownThisConnection = true
        return PrecisionGuide.SP60_FAIL
    }
}
