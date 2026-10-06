package kr.archsurvey.core

import kr.archsurvey.device.DeviceProfile

/** 최신 측위 상태 스냅샷 */
data class GnssSnapshot(
    val gga: GgaMessage? = null,
    val gst: GstMessage? = null,
    val gsa: GsaMessage? = null,
    val lastGgaAtMs: Long = 0,
    val lastAnyAtMs: Long = 0,
    val sentencesTotal: Long = 0,
    val checksumErrors: Long = 0,
    val seenTypes: Set<String> = emptySet(),
    /** 위성군별 보이는 위성 수(GSV) */
    val inViewByTalker: Map<String, Int> = emptyMap(),
    /** 0.1.11 GSV 위성 상세(최근 10초 안에 본 것). GSV 를 받지 못하면 빈 목록 */
    val satsDetail: List<SatInfo> = emptyList(),
    /** 폰 내장 GPS(수신기 NMEA 아님). StatusJudge 가 RTK 가 아닌 「폰GPS」주의로 판정 */
    val fromPhone: Boolean = false,
) {
    /** 보이는 위성 수 합계. GSV 를 받지 못했으면 null */
    val satsInView: Int? get() = if (inViewByTalker.isEmpty()) null else inViewByTalker.values.sum()
    val quality: FixQuality get() = gga?.quality ?: FixQuality.NONE

    /**
     * 지금 시점의 보정 연령(초). GGA 의 "보정 연령" 필드는 그 문장이 만들어진 순간의 값이므로,
     * GGA 를 받은 뒤 흐른 시간을 더해 보수적으로(크게) 표시한다. GGA 에 값이 없으면 null.
     */
    fun correctionAgeNowSec(nowMs: Long): Double? {
        val a = gga?.correctionAgeSec ?: return null
        return a + (nowMs - lastGgaAtMs).coerceAtLeast(0) / 1000.0
    }
}

enum class DataHealth { NO_DATA_YET, OK, STALE, DEAD }

/**
 * 모델 공통 NMEA 드라이버. 모델별 차이는 DeviceProfile(필수 문장, 워치독 시간)로만 주입.
 * 스레드 안전하지 않음: 한 reader 가 호출하고 snapshot 은 값 복사로 공유.
 */
class NmeaDriver(val profile: DeviceProfile) {
    private val parser = NmeaParser()
    /** 읽기 스레드가 쓰고 워치독/UI 스레드가 읽는다 */
    @Volatile var snapshot = GnssSnapshot()
        private set
    private var startedAtMs = 0L

    val parserStats: ParserStats get() = parser.stats

    fun reset(nowMs: Long) {
        snapshot = GnssSnapshot()
        startedAtMs = nowMs
    }

    /** GSV 위성 상세: 키 "talker:PRN" → (위성, 마지막 본 시각) */
    private val satSeen = LinkedHashMap<String, Pair<SatInfo, Long>>()
    private companion object { const val SAT_KEEP_MS = 10_000L }

    /**
     * 수신 바이트를 처리하고 새로 파싱된 메시지를 반환.
     * 스냅샷은 호출당 한 번만 복사한다(문장마다 copy/Set 복사를 하던 이전 방식은 GSV 가 많은 출력에서 초당 수십 번 객체를 만들었다).
     */
    fun onBytes(buf: ByteArray, len: Int, nowMs: Long): List<NmeaMessage> {
        val msgs = parser.feed(buf, len)
        if (msgs.isEmpty()) {
            if (parser.stats.checksumErrors != snapshot.checksumErrors) snapshot = snapshot.copy(checksumErrors = parser.stats.checksumErrors)
            return msgs
        }
        val s0 = snapshot
        var gga = s0.gga; var gst = s0.gst; var gsa = s0.gsa
        var lastGgaAt = s0.lastGgaAtMs
        var types: MutableSet<String>? = null
        var inView: MutableMap<String, Int>? = null
        var satsChanged = false
        for (m in msgs) {
            when (m) {
                is GgaMessage -> { gga = m; lastGgaAt = nowMs }
                is GstMessage -> gst = m
                is GsaMessage -> gsa = m
                is GsvMessage -> {
                    if (m.sats.isNotEmpty()) { for (q in m.sats) satSeen[m.talker + ":" + q.prn] = q to nowMs; satsChanged = true }
                    val v = m.satsInView ?: 0
                    if (s0.inViewByTalker[m.talker] != v || inView != null) {
                        if (inView == null) inView = HashMap(s0.inViewByTalker)
                        inView[m.talker] = v
                    }
                }
                is ZdaMessage -> {}
            }
            val t = typeOf(m)
            if (t !in s0.seenTypes && (types == null || t !in types)) {
                if (types == null) types = HashSet(s0.seenTypes)
                types.add(t)
            }
        }
        snapshot = s0.copy(
            gga = gga, gst = gst, gsa = gsa, lastGgaAtMs = lastGgaAt,
            lastAnyAtMs = nowMs, sentencesTotal = s0.sentencesTotal + msgs.size,
            seenTypes = types ?: s0.seenTypes,
            inViewByTalker = inView ?: s0.inViewByTalker,
            satsDetail = if (satsChanged) { satSeen.entries.removeAll { nowMs - it.value.second > SAT_KEEP_MS }; satSeen.values.map { it.first } } else s0.satsDetail,
            checksumErrors = parser.stats.checksumErrors,
        )
        return msgs
    }

    fun health(nowMs: Long): DataHealth {
        val s = snapshot
        val last = maxOf(s.lastAnyAtMs, 0L)
        if (last == 0L) {
            val waited = nowMs - startedAtMs
            return when {
                waited >= profile.reconnectAfterMs -> DataHealth.DEAD
                waited >= profile.staleAfterMs -> DataHealth.STALE
                else -> DataHealth.NO_DATA_YET
            }
        }
        val gap = nowMs - last
        return when {
            gap >= profile.reconnectAfterMs -> DataHealth.DEAD
            gap >= profile.staleAfterMs -> DataHealth.STALE
            else -> DataHealth.OK
        }
    }

    /** 필수 문장이 아직 없는데 다른 문장은 온다면 설정 안내 */
    fun missingRequired(): Set<String> = profile.requiredSentences - snapshot.seenTypes

    private fun typeOf(m: NmeaMessage) = when (m) {
        is GgaMessage -> "GGA"; is GstMessage -> "GST"; is GsaMessage -> "GSA"; is GsvMessage -> "GSV"; is ZdaMessage -> "ZDA"
    }
}
