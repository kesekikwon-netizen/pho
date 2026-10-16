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

    /** 0.1.61: 항목 하나 최대 크기(사진이 섞인 큰 zip 에서 DBF/CSV 만 읽고, 비정상 항목으로 메모리가 넘치지 않게) */
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** 0.1.61: 한국 주변 위경도만 믿는다(0·빈칸·뒤바뀐 값은 버리고 CSV N/E 로) */
    fun plausibleLatLon(lat: Double?, lon: Double?) = lat != null && lon != null && lat in 32.0..40.0 && lon in 123.0..133.0

    /** 0.1.61: 글자 인코딩 — BOM 이나 올바른 UTF-8 이면 UTF-8, 아니면 cp949 */
    fun decodeText(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrElse { String(b, CP949) }
    }

    /** 0.1.61: 따옴표로 묶인 칸(쉼표 포함) 처리 */
    fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                q && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out += sb.toString().trim(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString().trim(); return out
    }

    /** 0.1.61: TM → 위경도(앞 계산을 뉴턴 반복으로 되돌림, 1 mm 이내) */
    fun inverse(n: Double, e: Double, zone: KoreanTmZone): Pair<Double, Double>? {
        var lat = 38.0
        var lon = when (zone) { KoreanTmZone.WEST -> 125.0; KoreanTmZone.CENTRAL -> 127.0; KoreanTmZone.EAST -> 129.0; else -> 131.0 }
        repeat(30) {
            val p = TmProjection.forward(lat, lon, zone)
            val dn = n - p.northing; val de = e - p.easting
            if (Math.abs(dn) < 1e-4 && Math.abs(de) < 1e-4) return lat to lon
            val h = 1e-6
            val pl = TmProjection.forward(lat + h, lon, zone); val po = TmProjection.forward(lat, lon + h, zone)
            val a = (pl.northing - p.northing) / h; val b = (po.northing - p.northing) / h
            val c = (pl.easting - p.easting) / h; val d = (po.easting - p.easting) / h
            val det = a * d - b * c; if (det == 0.0) return null
            lat += (d * dn - b * de) / det; lon += (-c * dn + a * de) / det
            if (lat.isNaN() || lon.isNaN()) return null
        }
        return null
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
        val c = splitCsv(line)
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
    fun readZip(bytes: ByteArray): Pair<List<StPoint>, List<StPoint>> = readZip(ByteArrayInputStream(bytes))

    /** 0.1.61: 스트림으로 읽음(zip 전체를 메모리에 올리지 않음). 필요한 항목만, 항목당 [MAX_ENTRY_BYTES] 까지 */
    fun readZip(input: java.io.InputStream): Pair<List<StPoint>, List<StPoint>> {
        val dbf = ArrayList<StPoint>(); val csv = ArrayList<StPoint>()
        fun ZipInputStream.readCapped(): ByteArray {
            val o = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536); var tot = 0L
            while (true) { val k = read(buf); if (k < 0) break; tot += k; if (tot > MAX_ENTRY_BYTES) throw java.io.IOException("항목이 너무 커요"); o.write(buf, 0, k) }
            return o.toByteArray()
        }
        ZipInputStream(input, CP949).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val nm = e.name.substringAfterLast('/')
                if (e.isDirectory || nm.contains("StakeOut", ignoreCase = true)) continue
                when {
                    nm.endsWith("_point.dbf", true) -> dbf += fromDbf(readDbf(z.readCapped()))
                    nm.endsWith("_Data.csv", true) -> csv += fromCsv(decodeText(z.readCapped()))
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
            val llOk = plausibleLatLon(p.lat, p.lon)
            val tm = if (llOk) TmProjection.forward(p.lat!!, p.lon!!, zone) else null
            val n = tm?.northing ?: p.n; val e = tm?.easting ?: p.e
            if (n == null || e == null) { noPos++; continue }
            if (tm != null && p.n != null && p.e != null) {
                val dd = Math.hypot(tm.northing - p.n, tm.easting - p.e); maxDiff = maxOf(maxDiff ?: 0.0, dd)
            }
            // 0.1.61: CSV 만 있는 점은 위경도 0,0 이 아니라 N/E 를 지금 작업 좌표계로 보고 되돌려 계산
            val ll = if (llOk) p.lat!! to p.lon!! else inverse(n, e, zone)?.takeIf { plausibleLatLon(it.first, it.second) }
            if (ll == null) { noPos++; continue }
            val sep = Egm96Korea.undulation(ll.first, ll.second)
            val msl = p.z ?: if (p.ellH != null && llOk && sep != null) p.ellH - sep else null
            val memo = listOf(p.code.takeIf { it.isNotBlank() && it != "없음" }, p.memo.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
            out += PointEntity(
                project = project, pointId = p.name, positionType = "", memo = memo,
                lat = ll.first, lon = ll.second, ellipsoidalHeight = null, altitudeMsl = null, geoidSeparation = sep,
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
