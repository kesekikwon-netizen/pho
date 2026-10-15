package kr.archsurvey.pg.r10

import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.core.TmProjection
import kr.archsurvey.device.LinkState

/**
 * 0.0.14 사진마다 R10 원시 위치(안테나, GGA) 기록. R10 은 선택 — 없거나 끊겼으면 null(사진은 그대로 찍힘).
 * fixed = RTK 고정 + 위치가 2초 안에 받은 것. FIX 아니어도 기록은 하고 표시만 한다(촬영을 막지 않음).
 */
data class GnssTag(
    val lat: Double, val lon: Double, val altMsl: Double?,
    val quality: String, val fixed: Boolean,
    val sigmaH: Double?, val sigmaU: Double?, val ageMs: Long, val sats: Int,
) {
    fun toArray(): List<Any?> = listOf(lat, lon, altMsl, quality, fixed, sigmaH, sigmaU, ageMs, sats)
}

object GeoTag {
    const val FRESH_MS = 2_000L

    fun from(snap: GnssSnapshot, link: LinkState, nowMs: Long): GnssTag? {
        if (link == LinkState.IDLE) return null
        val g = snap.gga ?: return null
        val lat = g.lat ?: return null; val lon = g.lon ?: return null
        val age = (nowMs - snap.lastGgaAtMs).coerceAtLeast(0)
        val gst = snap.freshGst(nowMs)
        val fresh = age <= FRESH_MS && (link == LinkState.STREAMING || link == LinkState.CONNECTED_NO_DATA)
        return GnssTag(lat, lon, g.altitudeMsl, g.quality.name, fresh && g.quality == FixQuality.RTK_FIXED,
            gst?.sigmaHoriz, gst?.sigmaAlt, age, g.satellites)
    }

    /** 좌표가 없는 작업에서 R10 만 쓸 때: 경도로 한국 TM 원점(EPSG 5185~5188) */
    fun zoneForLon(lon: Double): KoreanTmZone = when {
        lon < 126.0 -> KoreanTmZone.WEST
        lon < 128.0 -> KoreanTmZone.CENTRAL
        lon < 130.0 -> KoreanTmZone.EAST
        else -> KoreanTmZone.EAST_SEA
    }

    /** (E, N) */
    fun en(t: GnssTag, epsg: Int): Pair<Double, Double> {
        val p = TmProjection.forward(t.lat, t.lon, KoreanTmZone.fromEpsg(epsg))
        return p.easting to p.northing
    }

    /** 작은 ENU 이동(m) → 위경도(구면 근사, 수 m 안에서 mm 오차) */
    fun shiftLatLon(lat: Double, lon: Double, dE: Double, dN: Double): Pair<Double, Double> {
        val r = 6_378_137.0
        val dLat = Math.toDegrees(dN / r)
        val dLon = Math.toDegrees(dE / (r * Math.cos(Math.toRadians(lat))))
        return (lat + dLat) to (lon + dLon)
    }
}
