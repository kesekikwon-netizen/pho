package kr.archsurvey.export

import kr.archsurvey.data.PointEntity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.TimeZone

/**
 * 0.1.14 작업 전송 양식(목업 11). 발굴 기록에 쓰는 양식만 넣었다.
 * 목업의 공공측량·지적 재조사·사용자 정의·물량산출·캘리브레이션(.cal)·배경도면 양식은 발굴 기록 범위 밖이라 없음
 * (build-reports/mockup-complete-0.1.14.md 참고).
 */
enum class ExportFormat(val id: String, val labelKo: String, val ext: String) {
    CSV("csv", "기본 양식", ".csv"),
    DXF("dxf", "캐드", ".dxf"),
    REPORT("report", "점 보고서", ".csv"),
    KML("kml", "구글어스", ".kml"),
    SHP("shp", "Shape 파일 (hgis·QGIS)", ".shp"),
    GEOJSON("geojson", "GeoJSON", ".geojson"),
    /** hgis(Strata) 「CSV 기준점」 불러오기 양식 */
    HGIS_CSV("hgis_csv", "hgis 기준점 CSV", ".csv"),
    /** 0.1.35 도면용 DXF: 레이어별(조사구역·그리드·트렌치·유구·유물·레벨·토층·기준점) + 축척별 글자 */
    DRAWING_DXF("drawing_dxf", "도면 DXF (레이어별)", ".dxf"),
    /** 0.1.35 야장 CSV: 점번호·종류·유구·유물번호·레벨·칸·해발 */
    FIELD_CSV("field_csv", "야장 CSV", ".csv");

    companion object {
        /** 0.1.13 까지 늘 만들던 세 가지 = 기본값 */
        val DEFAULT: Set<ExportFormat> = setOf(CSV, DXF, GEOJSON)
        /** 「hgis용 SHP」 한 번 누르기: Shape 한 벌(.shp/.shx/.dbf/.prj/.cpg) + hgis 기준점 CSV */
        val HGIS: Set<ExportFormat> = setOf(SHP, HGIS_CSV)
        /** 0.1.35 「저녁 도면용 한 번에」: 도면 DXF + SHP + 야장 CSV + 기본 CSV (사진·음성은 켜 둔 대로) */
        val EVENING: Set<ExportFormat> = setOf(DRAWING_DXF, SHP, FIELD_CSV, CSV)
    }
}

object MoreFormats {
    private fun f(v: Double?, d: Int): String = if (v == null) "" else String.format(Locale.US, "%.${d}f", v)
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** 구글어스 KML: 경위도(WGS84) + 지면 타원체고(절대 높이) */
    fun kml(project: String, points: List<PointEntity>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        sb.append("<name>").append(xml(project)).append("</name>\n")
        for (p in points) {
            sb.append("<Placemark><name>").append(xml(p.pointId)).append("</name>")
            val desc = listOf(p.positionType, p.memo, "EPSG:${p.crsEpsg} N ${f(p.northing, 3)} E ${f(p.easting, 3)}", p.groundMsl?.let { "표고 ${f(it, 3)} m" } ?: "").filter { it.isNotBlank() }.joinToString(" · ")
            sb.append("<description>").append(xml(desc)).append("</description>")
            val h = p.groundEllipsoidalHeight
            sb.append("<Point>")
            if (h != null) sb.append("<altitudeMode>absolute</altitudeMode>")
            sb.append("<coordinates>").append(f(p.lon, 9)).append(',').append(f(p.lat, 9)).append(',').append(f(h ?: 0.0, 3)).append("</coordinates></Point></Placemark>\n")
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    /** 점 보고서(엑셀용 CSV, UTF-8 BOM): 현장 보고에 쓰는 핵심 열만 */
    fun reportCsv(points: List<PointEntity>, photosByPoint: Map<Long, List<String>>, tz: TimeZone = TimeZone.getDefault()): String {
        val sb = StringBuilder("\uFEFF")
        sb.append("번호,위치 종류,X(N),Y(E),표고(m),해 품질,위성,수평 정밀(m),수직 정밀(m),측정 횟수,측정 시각,사진,메모\r\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = tz }
        for (p in points) {
            val row = listOf(
                Formats.csvEscape(p.pointId), Formats.csvEscape(p.positionType), f(p.northing, 3), f(p.easting, 3), f(p.groundMsl ?: p.groundEllipsoidalHeight, 3),
                Formats.fixLabel(p.fixQuality), p.satellites.toString(), f(p.sigmaH, 3), f(p.sigmaU, 3), p.epochs.toString(),
                fmt.format(java.util.Date(p.measuredAtUtcMs)), photosByPoint[p.rowId].orEmpty().size.toString(), Formats.csvEscape(p.memo),
            )
            sb.append(row.joinToString(",")).append("\r\n")
        }
        return sb.toString()
    }

    /**
     * ESRI .prj — GDAL 3.10 `gdalsrsinfo -o wkt_esri EPSG:n` 출력과 글자까지 같게 쓴다(2026-10-05 박스 확인).
     * 이 이름(KGD2002_*_Belt_2010)이어야 GDAL/QGIS(=hgis·Strata)가 EPSG:5185~5188 로 100 % 알아본다.
     * 0.1.14 까지 쓰던 "Korea_2000_Korea_Central_Belt_2010" 은 GDAL 이 ESRI:102082(신뢰 90 %)로 읽어서 hgis 범례에 EPSG 가 안 떴다.
     * 앱 목록에 없는 코드는 null(잘못된 .prj 를 지어 쓰지 않는다).
     */
    fun prj(epsg: Int): String? {
        val geog = "GEOGCS[\"GCS_KGD2002\",DATUM[\"D_Korea_Geodetic_Datum_2002\",SPHEROID[\"GRS_1980\",6378137.0,298.257222101]]," +
            "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]"
        fun tm(name: String, fe: String, fn: String, lon0: String, k: String) =
            "PROJCS[\"$name\",$geog,PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",$fe]," +
                "PARAMETER[\"False_Northing\",$fn],PARAMETER[\"Central_Meridian\",$lon0],PARAMETER[\"Scale_Factor\",$k]," +
                "PARAMETER[\"Latitude_Of_Origin\",38.0],UNIT[\"Meter\",1.0]]"
        return when (epsg) {
            5185 -> tm("KGD2002_West_Belt_2010", "200000.0", "600000.0", "125.0", "1.0")
            5186 -> tm("KGD2002_Central_Belt_2010", "200000.0", "600000.0", "127.0", "1.0")
            5187 -> tm("KGD2002_East_Belt_2010", "200000.0", "600000.0", "129.0", "1.0")
            5188 -> tm("KGD2002_East_Sea_Belt_2010", "200000.0", "600000.0", "131.0", "1.0")
            // 제출용 통합좌표계. 앱 작업 좌표계로는 쓰지 않지만 점에 이 코드가 있으면 맞는 .prj 를 쓴다
            5179 -> tm("KGD2002_Unified_Coordinate_System", "1000000.0", "2000000.0", "127.5", "0.9996")
            else -> null
        }
    }

    /** hgis control_points CSV 의 datum/projection/origin 글(hgis samples/demo_survey/gcp.csv 와 같은 꼴) */
    fun hgisProjectionText(epsg: Int): Pair<String, String> = when (epsg) {
        5185 -> "TM(EPSG:5185 서부원점)" to "서부원점(38N 125E)"
        5186 -> "TM(EPSG:5186 중부원점)" to "중부원점(38N 127E)"
        5187 -> "TM(EPSG:5187 동부원점)" to "동부원점(38N 129E)"
        5188 -> "TM(EPSG:5188 동해원점)" to "동해원점(38N 131E)"
        5179 -> "UTM-K(EPSG:5179)" to "통합원점(38N 127.5E)"
        else -> "EPSG:$epsg" to ""
    }

    class Shape(
        val shp: ByteArray, val shx: ByteArray, val dbf: ByteArray, val prj: String?, val cpg: String = "UTF-8",
        val epsg: Int = 5186, val hasZ: Boolean = false, val count: Int = 0,
    )

    /** 지면 표고: 표고(MSL/지오이드) 우선, 없으면 지면 타원체고(DXF 와 같은 규칙). 어느 쪽인지는 z_model 칸에 적는다 */
    fun groundZ(p: PointEntity): Double? = p.groundMsl ?: p.groundEllipsoidalHeight
    private fun zModel(p: PointEntity): String = when {
        p.groundMsl != null -> p.heightModel.ifEmpty { "RECEIVER" }
        p.groundEllipsoidalHeight != null -> "ELLIPSOID"
        else -> ""
    }

    /** 좌표계(crsEpsg)별로 나눈 Shapefile. 한 작업은 보통 하나(작업 좌표계). 섞여 있으면 좌표계마다 한 벌 */
    fun shapesByCrs(points: List<PointEntity>, photosByPoint: Map<Long, List<String>> = emptyMap(), tz: TimeZone = TimeZone.getDefault()): List<Shape> =
        points.groupBy { it.crsEpsg }.map { (_, group) -> shape(group, photosByPoint, tz) }

    /**
     * 점 Shapefile (ESRI Shapefile Technical Description 1998).
     * - 모든 점에 지면 높이가 있으면 PointZ(유형 11, X=동 Y=북 Z=지면 표고, M=없음 −1e39), 하나라도 없으면 Point(유형 1) + DBF z 칸.
     *   (없는 높이를 0 으로 채우면 그럴듯한 가짜 표고가 되기 때문)
     * - DBF: dBASE III, 칸 이름 ASCII 10자 이하, 문자 UTF-8(+ .cpg "UTF-8"). hgis 는 .cpg 를 읽어 SHAPE_ENCODING 을 정한다.
     * - 첫 문자 칸 = point_id → hgis 가 이름 칸으로 골라 점 번호를 자동 라벨로 띄운다(LayerOps::detectNameField 6단계).
     * - 좌표계: 점 묶음의 crsEpsg(=작업 좌표계). 5179 를 기본으로 쓰지 않는다.
     */
    fun shape(points: List<PointEntity>, photosByPoint: Map<Long, List<String>> = emptyMap(), tz: TimeZone = TimeZone.getDefault()): Shape {
        val n = points.size
        val hasZ = n > 0 && points.all { groundZ(it) != null }
        val type = if (hasZ) 11 else 1
        val contentBytes = if (hasZ) 36 else 20
        val recBytes = 8 + contentBytes
        val xs = points.map { it.easting }; val ys = points.map { it.northing }
        val bbox = if (n == 0) doubleArrayOf(0.0, 0.0, 0.0, 0.0) else doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        val zs = if (hasZ) points.map { groundZ(it)!! } else emptyList()
        val zRange = if (hasZ) doubleArrayOf(zs.min(), zs.max()) else doubleArrayOf(0.0, 0.0)
        fun header(totalBytes: Int): ByteArray {
            val b = ByteBuffer.allocate(100)
            b.order(ByteOrder.BIG_ENDIAN).putInt(9994); repeat(5) { b.putInt(0) }; b.putInt(totalBytes / 2)
            b.order(ByteOrder.LITTLE_ENDIAN).putInt(1000).putInt(type)
            for (v in bbox) b.putDouble(v)
            b.putDouble(zRange[0]).putDouble(zRange[1]).putDouble(0.0).putDouble(0.0) // Z 범위, M 범위(없음)
            return b.array()
        }
        val shp = ByteBuffer.allocate(100 + n * recBytes)
        shp.put(header(100 + n * recBytes))
        val shx = ByteBuffer.allocate(100 + n * 8)
        shx.put(header(100 + n * 8))
        points.forEachIndexed { i, p ->
            val offsetWords = (100 + i * recBytes) / 2
            shx.order(ByteOrder.BIG_ENDIAN).putInt(offsetWords).putInt(contentBytes / 2)
            shp.order(ByteOrder.BIG_ENDIAN).putInt(i + 1).putInt(contentBytes / 2)
            shp.order(ByteOrder.LITTLE_ENDIAN).putInt(type).putDouble(p.easting).putDouble(p.northing)
            if (hasZ) shp.putDouble(groundZ(p)!!).putDouble(NO_M)
        }
        val epsg = points.firstOrNull()?.crsEpsg ?: 5186
        return Shape(shp.array(), shx.array(), dbf(points, photosByPoint, tz), prj(epsg), "UTF-8", epsg, hasZ, n)
    }

    /** Shapefile M 값 없음(스펙: −10^38 보다 작으면 없음) */
    const val NO_M = -1.0e39

    internal data class Field(val name: String, val type: Char, val len: Int, val dec: Int = 0) {
        init { require(name.length in 1..10 && name.all { it.code in 0x21..0x7E }) { "DBF 칸 이름은 ASCII 10자 이하: $name" } }
    }

    /** DBF 칸(순서 = 파일 순서). 이름은 hgis control_points 와 겹치는 것(point_id, x, y, z, fix_type, accuracy_m)은 같은 이름 */
    internal val DBF_FIELDS = listOf(
        Field("point_id", 'C', 32),     // 점 번호 (첫 문자 칸 → hgis 자동 라벨)
        Field("pos_type", 'C', 80),     // 위치 종류(경계점·모서리·기준점…)
        Field("x", 'N', 13, 3),         // 동거(E) m
        Field("y", 'N', 13, 3),         // 북거(N) m
        Field("z", 'N', 10, 3),         // 지면 표고 m (z_model 참고)
        Field("z_model", 'C', 10),      // RECEIVER/EGM96/NONE = 표고, ELLIPSOID = 표고 없어 타원체고
        Field("ell_h", 'N', 10, 3),     // 지면 타원체고 m
        Field("ant_h", 'N', 7, 3),      // 지면→안테나 위상중심 총 높이 m
        Field("pole_h", 'N', 7, 3),     // 사용자가 넣은 폴(안테나) 높이 입력값 m
        Field("fix_type", 'C', 12),     // RTK_FIXED/RTK_FLOAT/DGPS/SINGLE
        Field("sats", 'N', 3),
        Field("hdop", 'N', 5, 1),
        Field("accuracy_m", 'N', 8, 4), // 수평 정밀도 σH m
        Field("v_acc_m", 'N', 8, 4),    // 수직 정밀도 σU m
        Field("epochs", 'N', 4),        // 평균 측정 횟수
        Field("corr_age", 'N', 6, 1),   // 보정 연령 s
        Field("epsg", 'N', 5),
        Field("time_local", 'C', 19),   // 폰 시간대 yyyy-MM-dd HH:mm:ss
        Field("time_utc", 'C', 20),
        Field("grid_cell", 'C', 24),    // 그리드 칸 이름(없으면 빈칸)
        Field("photos", 'N', 2),        // 사진 장수
        Field("note", 'C', 254),        // 메모(UTF-8, 254바이트에서 글자 단위로 자름)
        Field("device", 'C', 40),
    )

    private fun dbf(points: List<PointEntity>, photosByPoint: Map<Long, List<String>>, tz: TimeZone): ByteArray {
        val fields = DBF_FIELDS
        val recLen = 1 + fields.sumOf { it.len }
        val headLen = 32 + 32 * fields.size + 1
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        val cal = java.util.Calendar.getInstance()
        h.put(0x03).put((cal.get(java.util.Calendar.YEAR) - 1900).toByte()).put((cal.get(java.util.Calendar.MONTH) + 1).toByte()).put(cal.get(java.util.Calendar.DAY_OF_MONTH).toByte())
        h.putInt(points.size).putShort(headLen.toShort()).putShort(recLen.toShort())
        // 12..31 예약 0, 29(언어 드라이버) = 0: 인코딩은 .cpg(UTF-8)로 알린다(GDAL 이 UTF-8 로 쓸 때와 같음)
        out.write(h.array())
        for (fd in fields) {
            val b = ByteArray(32)
            fd.name.toByteArray(Charsets.US_ASCII).copyInto(b, 0)
            b[11] = fd.type.code.toByte(); b[16] = fd.len.toByte(); b[17] = fd.dec.toByte()
            out.write(b)
        }
        out.write(0x0D)
        val localFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = tz }
        fun num(v: Double?, d: Int) = if (v == null || v.isNaN() || v.isInfinite()) "" else String.format(Locale.US, "%.${d}f", v)
        for (p in points) {
            out.write(' '.code)
            val vals = listOf(
                p.pointId, p.positionType, num(p.easting, 3), num(p.northing, 3), num(groundZ(p), 3), zModel(p),
                num(p.groundEllipsoidalHeight, 3), num(p.antennaHeight, 3), num(p.antennaInputValue, 3),
                Formats.fixLabel(p.fixQuality), p.satellites.toString(), num(p.hdop, 1), num(p.sigmaH, 4), num(p.sigmaU, 4),
                p.epochs.toString(), num(p.correctionAgeSec, 1), p.crsEpsg.toString(),
                localFmt.format(java.util.Date(p.measuredAtUtcMs)), Formats.utcOf(p.measuredAtUtcMs), p.cellName,
                photosByPoint[p.rowId].orEmpty().size.toString(), p.memo, p.device,
            )
            check(vals.size == fields.size)
            fields.zip(vals).forEach { (fd, v) -> out.write(cell(v, fd)) }
        }
        out.write(0x1A)
        return out.toByteArray()
    }

    /** 고정 폭 칸: 문자는 왼쪽 맞춤(UTF-8 바이트가 넘치면 글자 단위로 자름), 숫자는 오른쪽 맞춤(넘치면 빈칸 = 값 없음, 잘린 숫자를 쓰지 않음) */
    private fun cell(v: String, fd: Field): ByteArray {
        if (fd.type == 'N') {
            val bytes = v.toByteArray(Charsets.US_ASCII)
            if (bytes.size > fd.len) return ByteArray(fd.len) { ' '.code.toByte() }
            return ByteArray(fd.len - bytes.size) { ' '.code.toByte() } + bytes
        }
        var s = v.replace('\r', ' ').replace('\n', ' ')
        var bytes = s.toByteArray(Charsets.UTF_8)
        while (bytes.size > fd.len && s.isNotEmpty()) {
            // 서로게이트 쌍(이모지 등)을 반으로 자르지 않는다
            s = if (s.length >= 2 && Character.isLowSurrogate(s.last())) s.dropLast(2) else s.dropLast(1)
            bytes = s.toByteArray(Charsets.UTF_8)
        }
        return bytes + ByteArray(fd.len - bytes.size) { ' '.code.toByte() }
    }

    /** hgis control_points CSV 머리줄(hgis src/core/ControlPointCsv.cpp · samples/demo_survey/gcp.csv 와 같은 순서) */
    val HGIS_CSV_HEADER = listOf("point_id", "x", "y", "z", "datum", "ellipsoid", "projection", "origin", "accuracy_m", "pdop", "fix_type")

    private fun hgisFix(q: Int) = when (q) { 4 -> "RTK Fix"; 5 -> "RTK Float"; 2, 9 -> "DGPS"; 1 -> "단독"; else -> "" }

    /** hgis 칸은 따옴표만 벗기므로 엑셀 수식 막이(') 없이 RFC 4180 따옴표만 쓴다 */
    private fun plainCsv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /**
     * hgis 「기타 › 더보기 › CSV 기준점」용 CSV(UTF-8 BOM, CRLF). 한 파일 = 한 좌표계(점 묶음의 crsEpsg).
     * x = 동거(E), y = 북거(N) — hgis 별칭표에서 x = easting, y = northing. z = 지면 표고(표고가 없으면 빈칸: 타원체고는 표고가 아님).
     * accuracy_m = 수평 σH. pdop 는 앱이 기록하지 않아 빈칸(HDOP 를 PDOP 로 바꿔 쓰지 않음).
     * hgis 는 이 파일의 좌표계 글을 읽지 않고 조사 좌표계로 찍는다 → 같은 좌표계 조사에 불러와야 한다.
     */
    fun hgisControlCsv(points: List<PointEntity>): String {
        val sb = StringBuilder("\uFEFF")
        sb.append(HGIS_CSV_HEADER.joinToString(",")).append("\r\n")
        fun num(v: Double?, d: Int) = if (v == null || v.isNaN()) "" else String.format(Locale.US, "%.${d}f", v)
        for (p in points) {
            val (proj, origin) = hgisProjectionText(p.crsEpsg)
            val row = listOf(
                plainCsv(p.pointId), num(p.easting, 3), num(p.northing, 3), num(p.groundMsl, 3),
                "세계측지계", "GRS80", proj, origin, num(p.sigmaH, 3), "", hgisFix(p.fixQuality),
            )
            sb.append(row.joinToString(",")).append("\r\n")
        }
        return sb.toString()
    }
}
