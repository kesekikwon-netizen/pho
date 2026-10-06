package kr.archsurvey.ui

import kr.archsurvey.bt.PrecisionResult
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.LinkState

/** 0.1.30 T23: 정밀도(GST) 안내 문구. SP60 은 화면이 없어 「SP60 설정 앱」 같은 말은 쓰지 않는다 */
object PrecisionGuide {
    /** SP60 자동 켜기 실패(NAK·5초 안에 GST 없음·횟수 상한) — 통신GNSS 확정 문구 */
    const val SP60_FAIL = "SP60이 정밀도(GST)를 보내지 않아요. 터미널/SPace에서 켜 주세요"
    /** R10: 자동 명령 없음 → 10초 동안 GST 가 없으면 */
    const val R10_NO_GST = "수신기가 정밀도(GST)를 보내지 않아요. R10: 웹 설정 → Bluetooth NMEA에서 GST·GSA 켜기"
    const val R10_NO_GST_AFTER_MS = 10_000L

    /** 「정밀도 값을 받지 못했어요」(NO_SIGMA) 주의에 붙이는 기기별 안내 */
    fun noSigmaHint(deviceId: String?): String? = when (deviceId) {
        DeviceProfiles.SP60_ID -> "SP60: 앱이 GST 켜기를 요청해요 · 안 되면 터미널/SPace에서 GST·GSA 켜기"
        DeviceProfiles.R10_ID -> "R10: 웹 UI I/O 설정 → BT 포트 → NMEA에서 GST·GSA 켜기"
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
