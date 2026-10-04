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
) {
    /** 보이는 위성 수 합계. GSV 를 받지 못했으면 null */
    val satsInView: Int? get() = if (inViewByTalker.isEmpty()) null else inViewByTalker.values.sum()
    val quality: FixQuality get() = gga?.quality ?: FixQuality.NONE
}

enum class DataHealth { NO_DATA_YET, OK, STALE, DEAD }

/**
 * 모델 공통 NMEA 드라이버. 모델별 차이는 DeviceProfile(필수 문장, 워치독 시간)로만 주입.
 * 스레드 안전하지 않음: 한 reader 가 호출하고 snapshot 은 값 복사로 공유.
 */
class NmeaDriver(val profile: DeviceProfile) {
    private val parser = NmeaParser()
    var snapshot = GnssSnapshot()
        private set
    private var startedAtMs = 0L

    fun reset(nowMs: Long) {
        snapshot = GnssSnapshot()
        startedAtMs = nowMs
    }

    /** 수신 바이트를 처리하고 새로 파싱된 메시지를 반환 */
    fun onBytes(buf: ByteArray, len: Int, nowMs: Long): List<NmeaMessage> {
        val msgs = parser.feed(buf, len)
        var s = snapshot
        for (m in msgs) {
            s = when (m) {
                is GgaMessage -> s.copy(gga = m, lastGgaAtMs = nowMs)
                is GstMessage -> s.copy(gst = m)
                is GsaMessage -> s.copy(gsa = m)
                is GsvMessage -> s.copy(inViewByTalker = s.inViewByTalker + (m.talker to (m.satsInView ?: 0)))
                is ZdaMessage -> s
            }
            s = s.copy(seenTypes = s.seenTypes + typeOf(m))
        }
        if (msgs.isNotEmpty()) s = s.copy(lastAnyAtMs = nowMs, sentencesTotal = s.sentencesTotal + msgs.size)
        snapshot = s.copy(checksumErrors = parser.stats.checksumErrors)
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
