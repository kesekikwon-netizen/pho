package kr.archsurvey.core

/** GGA 품질 지시자 (NMEA 표준). 4=RTK Fixed, 5=RTK Float. */
enum class FixQuality(val code: Int, val labelKo: String, val short: String) {
    NONE(0, "위치 없음", "NO FIX"),
    SINGLE(1, "단독 측위", "SINGLE"),
    DGPS(2, "DGPS", "DGPS"),
    PPS(3, "PPS", "PPS"),
    RTK_FIXED(4, "RTK 고정", "RTK FIXED"),
    RTK_FLOAT(5, "RTK 플로트", "RTK FLOAT"),
    DEAD_RECKONING(6, "추측항법", "DR"),
    MANUAL(7, "수동 입력", "MANUAL"),
    SIMULATION(8, "시뮬레이션", "SIM");

    companion object {
        fun fromCode(c: Int?): FixQuality = entries.firstOrNull { it.code == c } ?: NONE
    }
}

sealed interface NmeaMessage {
    val raw: String
}

data class GgaMessage(
    override val raw: String,
    val utcTime: String,          // hhmmss.ss 원문
    val lat: Double?,             // 십진도, 남(-)
    val lon: Double?,
    val quality: FixQuality,
    val satellites: Int,
    val hdop: Double?,
    val altitudeMsl: Double?,     // 안테나 고도 (MSL, m)
    val geoidSeparation: Double?, // 지오이드 분리 (m)
    val correctionAgeSec: Double?,
    val baseStationId: String?,
) : NmeaMessage {
    /** 타원체고 = MSL고도 + 지오이드분리 (둘 다 있을 때) */
    val ellipsoidalHeight: Double? get() =
        if (altitudeMsl != null && geoidSeparation != null) altitudeMsl + geoidSeparation else altitudeMsl
    val hasPosition: Boolean get() = lat != null && lon != null && quality != FixQuality.NONE
}

/** GST: 위치 오차 표준편차. */
data class GstMessage(
    override val raw: String,
    val rmsRange: Double?,
    val sigmaLat: Double?,
    val sigmaLon: Double?,
    val sigmaAlt: Double?,
) : NmeaMessage {
    val sigmaHoriz: Double? get() =
        if (sigmaLat != null && sigmaLon != null) Math.sqrt(sigmaLat * sigmaLat + sigmaLon * sigmaLon) else null
}

data class GsaMessage(
    override val raw: String,
    val fixType: Int?, // 1 없음, 2 2D, 3 3D
    val pdop: Double?,
    val hdop: Double?,
    val vdop: Double?,
) : NmeaMessage

/** 보이는 위성 수(GSV). [talker] 는 위성군(GP=GPS, GL=GLONASS, GA=Galileo, GB/BD=BeiDou, GQ=QZSS) */
data class GsvMessage(override val raw: String, val talker: String, val satsInView: Int?) : NmeaMessage

data class ZdaMessage(override val raw: String, val utcTime: String, val day: Int?, val month: Int?, val year: Int?) : NmeaMessage

data class ParserStats(
    var valid: Long = 0,
    var checksumErrors: Long = 0,
    var malformed: Long = 0,
    var overflows: Long = 0,
    var ignored: Long = 0,
)

/**
 * NMEA 0183 스트림 파서. 라인 단위 버퍼링, 체크섬 불일치 문장은 폐기(카운터 증가),
 * 버퍼 상한 초과 시 리셋. 어떤 입력에도 예외를 던지지 않는다.
 */
class NmeaParser(private val maxLine: Int = 4096) {
    val stats = ParserStats()
    private val sb = StringBuilder()
    private var overflowed = false

    /** 바이트 청크를 공급하고, 완성된 문장들을 반환. */
    fun feed(buf: ByteArray, len: Int = buf.size): List<NmeaMessage> {
        val out = ArrayList<NmeaMessage>(2)
        for (i in 0 until len) {
            val b = buf[i].toInt() and 0xFF
            when (b) {
                '\n'.code, '\r'.code -> {
                    if (sb.isNotEmpty()) {
                        if (!overflowed) parseLine(sb.toString())?.let { out.add(it) }
                        sb.setLength(0)
                    }
                    overflowed = false
                }
                else -> {
                    if (b == '$'.code) { // 새 문장 시작: 이전 찌꺼기 버림
                        sb.setLength(0); overflowed = false
                    }
                    if (sb.length >= maxLine) {
                        if (!overflowed) stats.overflows++
                        overflowed = true
                        sb.setLength(0)
                    } else if (!overflowed) {
                        // 비 ASCII(예: 같은 포트로 섞여 들어온 RTCM/바이너리)는 문장을 깨뜨리므로 무시
                        if (b in 0x20..0x7E) sb.append(b.toChar())
                        else if (sb.isNotEmpty()) { sb.setLength(0); overflowed = true }
                    }
                }
            }
        }
        return out
    }

    fun parseLine(line: String): NmeaMessage? {
        try {
            val s = line.trim()
            if (s.isEmpty() || s[0] != '$') { stats.malformed++; return null }
            val star = s.lastIndexOf('*')
            val body: String
            if (star >= 0) {
                body = s.substring(1, star)
                val given = s.substring(star + 1).trim()
                if (given.length < 2) { stats.checksumErrors++; return null }
                val expected = checksum(body)
                val g = given.substring(0, 2).toIntOrNull(16)
                if (g == null || g != expected) { stats.checksumErrors++; return null }
            } else {
                // 체크섬 없는 문장은 신뢰하지 않음
                stats.malformed++; return null
            }
            val f = body.split(',')
            val id = f[0]
            if (id.length < 3) { stats.malformed++; return null }
            val type = id.takeLast(3)
            val msg: NmeaMessage? = when {
                id.startsWith("P") -> null // PTNL 등 독자 문장은 현재 무시
                type == "GGA" -> parseGga(s, f)
                type == "GST" -> parseGst(s, f)
                type == "GSA" -> parseGsa(s, f)
                type == "GSV" -> parseGsv(s, f, id.dropLast(3))
                type == "ZDA" -> parseZda(s, f)
                else -> null
            }
            if (msg == null) stats.ignored++ else stats.valid++
            return msg
        } catch (e: Exception) {
            stats.malformed++
            return null
        }
    }

    companion object {
        fun checksum(body: String): Int {
            var c = 0
            for (ch in body) c = c xor ch.code
            return c and 0xFF
        }

        /** 송신용 문장 생성 */
        fun build(body: String): String = "$" + body + "*" + "%02X".format(checksum(body)) + "\r\n"

        private fun d(f: List<String>, i: Int): Double? = f.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        private fun n(f: List<String>, i: Int): Int? = f.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toIntOrNull()

        /** ddmm.mmmm / dddmm.mmmm -> 십진도 */
        fun parseCoord(v: String?, hemi: String?, degDigits: Int): Double? {
            if (v.isNullOrEmpty() || v.length <= degDigits) return null
            val deg = v.substring(0, degDigits).toIntOrNull() ?: return null
            val min = v.substring(degDigits).toDoubleOrNull() ?: return null
            if (min >= 60.0) return null
            var r = deg + min / 60.0
            when (hemi) { "S", "W" -> r = -r; "N", "E" -> {} ; else -> return null }
            return r
        }

        private fun parseGga(raw: String, f: List<String>): GgaMessage? {
            if (f.size < 10) return null
            val lat = parseCoord(f.getOrNull(2), f.getOrNull(3), 2)
            val lon = parseCoord(f.getOrNull(4), f.getOrNull(5), 3)
            if (lat != null && (lat < -90 || lat > 90)) return null
            if (lon != null && (lon < -180 || lon > 180)) return null
            return GgaMessage(
                raw = raw.trim(),
                utcTime = f.getOrNull(1) ?: "",
                lat = lat, lon = lon,
                quality = FixQuality.fromCode(n(f, 6)),
                satellites = n(f, 7) ?: 0,
                hdop = d(f, 8),
                altitudeMsl = d(f, 9),
                geoidSeparation = d(f, 11),
                correctionAgeSec = d(f, 13),
                baseStationId = f.getOrNull(14)?.takeIf { it.isNotEmpty() },
            )
        }

        private fun parseGst(raw: String, f: List<String>): GstMessage? {
            if (f.size < 9) return null
            return GstMessage(raw.trim(), d(f, 2), d(f, 6), d(f, 7), d(f, 8))
        }

        private fun parseGsa(raw: String, f: List<String>): GsaMessage? {
            if (f.size < 18) return null
            return GsaMessage(raw.trim(), n(f, 2), d(f, 15), d(f, 16), d(f, 17))
        }

        private fun parseGsv(raw: String, f: List<String>, talker: String): GsvMessage? {
            if (f.size < 4) return null
            return GsvMessage(raw.trim(), talker, n(f, 3))
        }

        private fun parseZda(raw: String, f: List<String>): ZdaMessage? {
            if (f.size < 5) return null
            return ZdaMessage(raw.trim(), f[1], n(f, 2), n(f, 3), n(f, 4))
        }
    }
}
