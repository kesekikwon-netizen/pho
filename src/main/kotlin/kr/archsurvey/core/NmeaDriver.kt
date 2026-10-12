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
    /** 0.1.33: 마지막 GST/GSA 를 받은 시각(수신 시계 ms). null = 시각 모름(손으로 만든 스냅샷) → 신선으로 본다 */
    val lastGstAtMs: Long? = null,
    val lastGsaAtMs: Long? = null,
) {
    /**
     * 0.1.33: [maxAgeMs] 안에 받은 GST 만. GGA 는 계속 오는데 GST 출력이 멈추면 옛 σ 를 보여 주거나
     * 그 σ 로 「고정·측량 가능」을 판정하지 않는다(그때는 σ 없음).
     */
    fun freshGst(nowMs: Long, maxAgeMs: Long = PRECISION_MAX_AGE_MS): GstMessage? {
        val at = lastGstAtMs ?: return gst
        return if (nowMs - at <= maxAgeMs) gst else null
    }
    /** 0.1.33: [maxAgeMs] 안에 받은 GSA 만(PDOP·VDOP) */
    fun freshGsa(nowMs: Long, maxAgeMs: Long = PRECISION_MAX_AGE_MS): GsaMessage? {
        val at = lastGsaAtMs ?: return gsa
        return if (nowMs - at <= maxAgeMs) gsa else null
    }

    /** 보이는 위성 수 합계. GSV 를 받지 못했으면 null */
    val satsInView: Int? get() = if (inViewByTalker.isEmpty()) null else inViewByTalker.values.sum()
    val quality: FixQuality get() = gga?.quality ?: FixQuality.NONE

    companion object {
        /** GST/GSA 신선 기준(ms). GGA 끊김 기준(StatusThresholds.staleAfterMs 5 s)과 같다 */
        const val PRECISION_MAX_AGE_MS = 5_000L
    }

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
    /**
     * 0.1.33 Trimble GSV: 위성군을 하나의 묶음으로 번호를 이어 매긴다(예: $GPGSV 8,1..8,4 → $GLGSV 8,5..8,7) — 필드 3(보이는 위성)은
     * **전체 합계**라 위성군별로 더하면 두 배가 된다. 다른 위성군인데 묶음 총수가 같고 번호가 이어지면 합계 방식으로 본다.
     */
    private var gsvCombined = false
    private var lastGsv: GsvMessage? = null
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
        var lastGstAt = s0.lastGstAtMs; var lastGsaAt = s0.lastGsaAtMs
        var types: MutableSet<String>? = null
        var inView: MutableMap<String, Int>? = null
        var satsChanged = false
        for (m in msgs) {
            when (m) {
                is GgaMessage -> { gga = m; lastGgaAt = nowMs }
                is GstMessage -> { gst = m; lastGstAt = nowMs }
                is GsaMessage -> { gsa = m; lastGsaAt = nowMs }
                is GsvMessage -> {
                    if (m.sats.isNotEmpty()) { for (q in m.sats) satSeen[m.talker + ":" + q.prn] = q to nowMs; satsChanged = true }
                    val prev = lastGsv
                    if (!gsvCombined && prev != null && prev.talker != m.talker && m.msgNum != null && m.msgNum > 1 &&
                        prev.totalMsgs == m.totalMsgs && prev.msgNum == m.msgNum - 1) {
                        gsvCombined = true
                        inView = HashMap()
                    }
                    lastGsv = m
                    val v = m.satsInView ?: 0
                    val key = if (gsvCombined) "ALL" else m.talker
                    if (s0.inViewByTalker[key] != v || inView != null) {
                        if (inView == null) inView = HashMap(s0.inViewByTalker)
                        if (gsvCombined) inView.keys.retainAll(setOf("ALL"))
                        inView[key] = v
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
            gga = gga, gst = gst, gsa = gsa, lastGgaAtMs = lastGgaAt, lastGstAtMs = lastGstAt, lastGsaAtMs = lastGsaAt,
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
