package kr.archsurvey.export

import kr.archsurvey.data.PointEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** CSV / GeoJSON 직렬화 (순수 로직, 단위 테스트 대상). 숫자는 항상 Locale.US. */
object Formats {
    private fun f(v: Double?, d: Int): String = if (v == null) "" else String.format(Locale.US, "%.${d}f", v)

    private fun utc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun local(ms: Long, tz: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = tz }.format(Date(ms))

    fun csvEscape(s: String): String {
        val needs = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        // 엑셀 수식 주입 방지: = + - @ 로 시작하는 문자열 앞에 ' 를 붙임 (숫자 컬럼에는 적용 안 함)
        val safe = if (s.isNotEmpty() && s[0] in "=+@" ) "'$s" else s
        return if (needs || safe != s) "\"" + safe.replace("\"", "\"\"") + "\"" else s
    }

    val POINT_HEADER = listOf(
        "point_id", "code", "memo", "northing_N", "easting_E", "crs_epsg",
        "height_apc_ellipsoidal", "height_ground_ellipsoidal", "height_apc_msl", "height_ground_msl",
        "antenna_height_m", "antenna_model", "antenna_ref_point", "antenna_input_mode", "antenna_input_m", "pco_v_m", "pco_h_m", "extra_parts_m", "base_ext_m", "antenna_receiver_applied",
        "lat", "lon", "fix", "all_epochs_fixed", "sats", "hdop", "sigma_e", "sigma_n", "sigma_h", "sigma_u",
        "corr_age_s", "base_id", "epochs", "time_utc", "time_local", "photo_count", "photo_files", "device", "project",
    )

    fun fixLabel(q: Int) = when (q) { 4 -> "RTK_FIXED"; 5 -> "RTK_FLOAT"; 2 -> "DGPS"; 1 -> "SINGLE"; 0 -> "NONE"; else -> "OTHER_$q" }

    fun pointsCsv(points: List<PointEntity>, photosByPoint: Map<Long, List<String>>, tz: TimeZone = TimeZone.getDefault()): String {
        val sb = StringBuilder()
        sb.append('\uFEFF') // UTF-8 BOM (엑셀 한글)
        sb.append(POINT_HEADER.joinToString(",")).append("\r\n")
        for (p in points) {
            val photos = photosByPoint[p.rowId].orEmpty()
            val row = listOf(
                csvEscape(p.pointId), csvEscape(p.code), csvEscape(p.memo),
                f(p.northing, 3), f(p.easting, 3), p.crsEpsg.toString(),
                f(p.ellipsoidalHeight, 3), f(p.groundEllipsoidalHeight, 3), f(p.altitudeMsl, 3), f(p.groundMsl, 3),
                f(p.antennaHeight, 3), csvEscape(p.antennaModel), csvEscape(p.antennaRefPoint), p.antennaInputMode, f(p.antennaInputValue, 3), f(p.antennaPcoV, 4), f(p.antennaPcoH, 4), f(p.antennaExtraPartsM, 3), f(p.antennaBaseExtM, 3), p.antennaReceiverApplied.toString(),
                f(p.lat, 9), f(p.lon, 9), fixLabel(p.fixQuality), p.allEpochsFixed.toString(), p.satellites.toString(), f(p.hdop, 1),
                f(p.sigmaE, 4), f(p.sigmaN, 4), f(p.sigmaH, 4), f(p.sigmaU, 4),
                f(p.correctionAgeSec, 1), csvEscape(p.baseStationId ?: ""), p.epochs.toString(),
                utc(p.measuredAtUtcMs), local(p.measuredAtUtcMs, tz), photos.size.toString(),
                csvEscape(photos.joinToString(";")), csvEscape(p.device), csvEscape(p.project),
            )
            sb.append(row.joinToString(",")).append("\r\n")
        }
        return sb.toString()
    }

    fun photosCsv(rows: List<Triple<String, String, Long>>): String {
        val sb = StringBuilder("\uFEFFpoint_id,photo_file,added_utc\r\n")
        for ((pid, file, at) in rows) sb.append(csvEscape(pid)).append(',').append(csvEscape(file)).append(',').append(utc(at)).append("\r\n")
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append(String.format(Locale.US, "\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    private fun jnum(v: Double?, d: Int) = if (v == null) "null" else String.format(Locale.US, "%.${d}f", v)

    /** GeoJSON (RFC 7946: 경도, 위도, 타원체고). 투영좌표·안테나·원고도·보정고도는 properties */
    fun pointsGeoJson(points: List<PointEntity>, photosByPoint: Map<Long, List<String>>): String {
        val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        points.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            val photos = photosByPoint[p.rowId].orEmpty()
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(jnum(p.lon, 9)).append(',').append(jnum(p.lat, 9))
            p.ellipsoidalHeight?.let { sb.append(',').append(jnum(it, 3)) }
            sb.append("]},\"properties\":{")
            sb.append("\"point_id\":").append(jsonStr(p.pointId))
            sb.append(",\"code\":").append(jsonStr(p.code))
            sb.append(",\"memo\":").append(jsonStr(p.memo))
            sb.append(",\"northing_N\":").append(jnum(p.northing, 3)).append(",\"easting_E\":").append(jnum(p.easting, 3))
            sb.append(",\"crs_epsg\":").append(p.crsEpsg)
            sb.append(",\"height_apc_ellipsoidal\":").append(jnum(p.ellipsoidalHeight, 3))
            sb.append(",\"height_ground_ellipsoidal\":").append(jnum(p.groundEllipsoidalHeight, 3))
            sb.append(",\"height_apc_msl\":").append(jnum(p.altitudeMsl, 3))
            sb.append(",\"height_ground_msl\":").append(jnum(p.groundMsl, 3))
            sb.append(",\"antenna_height_m\":").append(jnum(p.antennaHeight, 3))
            sb.append(",\"antenna_model\":").append(jsonStr(p.antennaModel))
            sb.append(",\"antenna_ref_point\":").append(jsonStr(p.antennaRefPoint))
            sb.append(",\"pco_v_m\":").append(jnum(p.antennaPcoV, 4)).append(",\"pco_h_m\":").append(jnum(p.antennaPcoH, 4))
            sb.append(",\"extra_parts_m\":").append(jnum(p.antennaExtraPartsM, 3)).append(",\"base_ext_m\":").append(jnum(p.antennaBaseExtM, 3))
            sb.append(",\"antenna_input_mode\":").append(jsonStr(p.antennaInputMode))
            sb.append(",\"antenna_input_m\":").append(jnum(p.antennaInputValue, 3))
            sb.append(",\"antenna_receiver_applied\":").append(p.antennaReceiverApplied)
            sb.append(",\"fix\":").append(jsonStr(fixLabel(p.fixQuality)))
            sb.append(",\"sats\":").append(p.satellites)
            sb.append(",\"sigma_h\":").append(jnum(p.sigmaH, 4)).append(",\"sigma_u\":").append(jnum(p.sigmaU, 4))
            sb.append(",\"epochs\":").append(p.epochs)
            sb.append(",\"time_utc\":").append(jsonStr(utc(p.measuredAtUtcMs)))
            sb.append(",\"device\":").append(jsonStr(p.device))
            sb.append(",\"photos\":[").append(photos.joinToString(",") { jsonStr(it) }).append("]")
            sb.append("}}")
        }
        return sb.append("]}").toString()
    }
}
