package kr.archsurvey.imports

import kr.archsurvey.core.Egm96Korea
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.core.TmProjection
import kr.archsurvey.data.PointEntity
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * 0.1.59 B4 SmartTopo 작업 가져오기(순수 Kotlin).
 * 입력: 작업 zip, `*_point.dbf`(cp949, 34칸 C형), `*_Data.csv`(cp949 「포인트명,X(N),Y(E),Z(h),코드명」).
 * 이관: 이름·메모(코드명 포함)·N/E/Z·σH/σV·안테나 높이/기준·시각·위성·PDOP·에폭.
 * N/E 는 DBF 위경도로 지금 작업 좌표계에 다시 계산(CSV 의 N/E 는 대조용). 해발 = CSV Z(h) → 없으면 타원체고−EGM96.
 */
object SmartTopoImport {
    val CP949: Charset = Charset.forName("x-windows-949")

    data class StPoint(
        val name: String, val code: String = "", val memo: String = "", val deleted: Boolean = false,
        val lat: Double? = null, val lon: Double? = null, val ellH: Double? = null,
        val n: Double? = null, val e: Double? = null, val z: Double? = null,
        val sigmaH: Double? = null, val sigmaV: Double? = null, val antennaH: Double? = null, val measureTo: String = "",
        val time: Long? = null, val pdop: Double? = null, val epochs: Int? = null, val satellites: Int? = null, val device: String = "",
    )

    data class Result(val points: List<PointEntity>, val skippedDeleted: Int, val skippedDuplicate: Int, val skippedNoPosition: Int, val maxNeDiffM: Double?)

    /** dBASE III 읽기(C/N 칸만). 반환 = 칸 이름 → 값(앞뒤 공백 제거) 행 목록. 삭제 표시(*) 행은 뺀다 */
    fun readDbf(bytes: ByteArray, cs: Charset = CP949): List<Map<String, String>> {
        if (bytes.size < 32) return emptyList()
        fun u16(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        val n = u32(4); val headerLen = u16(8); val recLen = u16(10)
        data class F(val name: String, val len: Int)
        val fields = ArrayList<F>()
        var o = 32
        while (o + 32 <= headerLen && bytes[o].toInt() != 0x0D) {
            val nm = String(bytes, o, 11, Charsets.US_ASCII).substringBefore('\u0000').trim()
            fields.add(F(nm, bytes[o + 16].toInt() and 0xff)); o += 32
        }
        val out = ArrayList<Map<String, String>>()
        for (i in 0 until n) {
            val start = headerLen + i * recLen
            if (start + recLen > bytes.size) break
            if (bytes[start].toInt().toChar() == '*') continue
            var p = start + 1
            val row = LinkedHashMap<String, String>()
            for (f in fields) { row[f.name] = String(bytes, p, f.len, cs).trim().trimEnd('\u0000'); p += f.len }
            out.add(row)
        }
        return out
    }

    private fun d(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    fun parseTime(s: String?): Long? {
        val t = s?.trim() ?: return null
        if (!Regex("^\\d{14}$").matches(t)) return null
        return runCatching {
            java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul"); isLenient = false }.parse(t)!!.time
        }.getOrNull()
    }

    fun fromDbf(rows: List<Map<String, String>>): List<StPoint> = rows.mapNotNull { r ->
        val name = r["Pointname"]?.trim().orEmpty(); if (name.isEmpty()) return@mapNotNull null
        StPoint(
            name = name, code = r["Pointcode"].orEmpty(), memo = r["Pointmemo"].orEmpty(),
            deleted = r["Pointinfo"]?.contains("삭제") == true,
            lat = d(r["Latitude"]), lon = d(r["Longitude"]), ellH = d(r["Elevation"]),
            sigmaH = d(r["Haccuracy"]), sigmaV = d(r["Vaccuracy"]), antennaH = d(r["Antenah"]), measureTo = r["MeasureTo"].orEmpty(),
            time = parseTime(r["Endtime"]) ?: parseTime(r["Starttime"]), pdop = d(r["PDOP"]), epochs = d(r["Epoch"])?.toInt(),
            satellites = d(r["Satellites"])?.toInt(), device = r["Device"].orEmpty(),
        )
    }

    /** `_Data.csv`: 첫 줄 머리, 「이름,N,E,Z,코드」 */
    fun fromCsv(text: String): List<StPoint> = text.removePrefix("\uFEFF").lines().drop(1).mapNotNull { line ->
        val c = line.split(',').map { it.trim() }
        if (c.size < 4 || c[0].isEmpty()) return@mapNotNull null
        val n = d(c[1]); val e = d(c[2]); if (n == null || e == null) return@mapNotNull null
        StPoint(name = c[0], code = c.getOrElse(4) { "" }, n = n, e = e, z = d(c[3]))
    }

    /** DBF(주) + CSV(N/E/Z 보충). DBF 가 없으면 CSV 만 */
    fun merge(dbf: List<StPoint>, csv: List<StPoint>): List<StPoint> {
        val byName = csv.associateBy { it.name }
        val merged = dbf.map { p -> byName[p.name]?.let { c -> p.copy(n = c.n, e = c.e, z = c.z, code = p.code.ifEmpty { c.code }) } ?: p }
        val names = dbf.map { it.name }.toSet()
        return merged + csv.filter { it.name !in names }
    }

    /** 압축(zip) 안의 DBF·CSV 찾기. 측설목록(StakeOutList)은 뺀다 */
    fun readZip(bytes: ByteArray): Pair<List<StPoint>, List<StPoint>> {
        val dbf = ArrayList<StPoint>(); val csv = ArrayList<StPoint>()
        ZipInputStream(ByteArrayInputStream(bytes), CP949).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val nm = e.name.substringAfterLast('/')
                if (e.isDirectory || nm.contains("StakeOut", ignoreCase = true)) continue
                when {
                    nm.endsWith("_point.dbf", true) -> dbf += fromDbf(readDbf(z.readBytes()))
                    nm.endsWith("_Data.csv", true) -> csv += fromCsv(String(z.readBytes(), CP949))
                }
            }
        }
        return dbf to csv
    }

    /** 이름이 [existing] 에 있으면 건너뜀(다시 가져와도 안전) */
    fun toEntities(points: List<StPoint>, project: String, zone: KoreanTmZone, existing: Set<String>, nowMs: Long): Result {
        var del = 0; var dup = 0; var noPos = 0; var maxDiff: Double? = null
        val seen = HashSet(existing.map { it.lowercase(Locale.ROOT) })
        val out = ArrayList<PointEntity>()
        for (p in points) {
            if (p.deleted) { del++; continue }
            if (!seen.add(p.name.lowercase(Locale.ROOT))) { dup++; continue }
            val tm = if (p.lat != null && p.lon != null) TmProjection.forward(p.lat, p.lon, zone) else null
            val n = tm?.northing ?: p.n; val e = tm?.easting ?: p.e
            if (n == null || e == null) { noPos++; continue }
            if (tm != null && p.n != null && p.e != null) {
                val dd = Math.hypot(tm.northing - p.n, tm.easting - p.e); maxDiff = maxOf(maxDiff ?: 0.0, dd)
            }
            val sep = if (p.lat != null && p.lon != null) Egm96Korea.undulation(p.lat, p.lon) else null
            val msl = p.z ?: if (p.ellH != null && sep != null) p.ellH - sep else null
            val memo = listOf(p.code.takeIf { it.isNotBlank() && it != "없음" }, p.memo.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
            out += PointEntity(
                project = project, pointId = p.name, positionType = "", memo = memo,
                lat = p.lat ?: 0.0, lon = p.lon ?: 0.0, ellipsoidalHeight = null, altitudeMsl = null, geoidSeparation = sep,
                northing = n, easting = e, crsEpsg = zone.epsg, fixQuality = 0, allEpochsFixed = false,
                satellites = p.satellites ?: 0, hdop = null, sigmaE = null, sigmaN = null, sigmaH = p.sigmaH, sigmaU = p.sigmaV,
                correctionAgeSec = null, baseStationId = null, epochs = p.epochs ?: 0,
                antennaHeight = p.antennaH ?: 0.0, antennaModel = "SMARTTOPO", antennaRefPoint = p.measureTo, antennaInputValue = p.antennaH ?: 0.0,
                antennaReceiverApplied = true, groundEllipsoidalHeight = p.ellH, groundMsl = msl,
                heightModel = if (p.z != null) "SMARTTOPO" else "EGM96",
                device = "SmartTopo 가져오기" + (p.device.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                measuredAtUtcMs = p.time ?: nowMs,
            )
        }
        return Result(out, del, dup, noPos, maxDiff)
    }
}
