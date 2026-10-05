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
    SHP("shp", "Shape 파일", ".shp"),
    GEOJSON("geojson", "GeoJSON", ".geojson");

    companion object {
        /** 0.1.13 까지 늘 만들던 세 가지 = 기본값 */
        val DEFAULT: Set<ExportFormat> = setOf(CSV, DXF, GEOJSON)
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

    /** ESRI .prj (Korea 2000 / Belt 2010, GRS80, 원점위도 38°, FE 200000, FN 600000, k=1) */
    fun prj(epsg: Int): String {
        val (name, lon0) = when (epsg) {
            5185 -> "West" to 125.0
            5187 -> "East" to 129.0
            5188 -> "East_Sea" to 131.0
            else -> "Central" to 127.0
        }
        return "PROJCS[\"Korea_2000_Korea_${name}_Belt_2010\",GEOGCS[\"GCS_Korea_2000\",DATUM[\"D_Korea_2000\",SPHEROID[\"GRS_1980\",6378137.0,298.257222101]]," +
            "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",200000.0]," +
            "PARAMETER[\"False_Northing\",600000.0],PARAMETER[\"Central_Meridian\",${lon0}],PARAMETER[\"Scale_Factor\",1.0],PARAMETER[\"Latitude_Of_Origin\",38.0],UNIT[\"Meter\",1.0]]"
    }

    class Shape(val shp: ByteArray, val shx: ByteArray, val dbf: ByteArray, val prj: String, val cpg: String = "UTF-8")

    /** 점 Shapefile(유형 1 Point, X=동 Y=북). 속성: ID·TYPE·MEMO·Z(표고)·EPSG. 문자 UTF-8(.cpg) */
    fun shape(points: List<PointEntity>): Shape {
        val n = points.size
        val xs = points.map { it.easting }; val ys = points.map { it.northing }
        val bbox = if (n == 0) doubleArrayOf(0.0, 0.0, 0.0, 0.0) else doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        fun header(totalBytes: Int): ByteBuffer {
            val b = ByteBuffer.allocate(100)
            b.order(ByteOrder.BIG_ENDIAN).putInt(9994); repeat(5) { b.putInt(0) }; b.putInt(totalBytes / 2)
            b.order(ByteOrder.LITTLE_ENDIAN).putInt(1000).putInt(1)
            for (v in bbox) b.putDouble(v)
            repeat(4) { b.putDouble(0.0) }
            return b
        }
        val recBytes = 8 + 20
        val shp = ByteBuffer.allocate(100 + n * recBytes)
        shp.put(header(100 + n * recBytes).array())
        val shx = ByteBuffer.allocate(100 + n * 8)
        shx.put(header(100 + n * 8).array())
        points.forEachIndexed { i, p ->
            val offsetWords = (100 + i * recBytes) / 2
            shx.order(ByteOrder.BIG_ENDIAN).putInt(offsetWords).putInt(10)
            shp.order(ByteOrder.BIG_ENDIAN).putInt(i + 1).putInt(10)
            shp.order(ByteOrder.LITTLE_ENDIAN).putInt(1).putDouble(p.easting).putDouble(p.northing)
        }
        val epsg = points.firstOrNull()?.crsEpsg ?: 5186
        return Shape(shp.array(), shx.array(), dbf(points), prj(epsg))
    }

    private data class Field(val name: String, val type: Char, val len: Int, val dec: Int = 0)

    private fun dbf(points: List<PointEntity>): ByteArray {
        val fields = listOf(Field("ID", 'C', 24), Field("TYPE", 'C', 60), Field("MEMO", 'C', 120), Field("Z", 'N', 12, 3), Field("EPSG", 'N', 6))
        val recLen = 1 + fields.sumOf { it.len }
        val headLen = 32 + 32 * fields.size + 1
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        val cal = java.util.Calendar.getInstance()
        h.put(0x03).put((cal.get(java.util.Calendar.YEAR) - 1900).toByte()).put((cal.get(java.util.Calendar.MONTH) + 1).toByte()).put(cal.get(java.util.Calendar.DAY_OF_MONTH).toByte())
        h.putInt(points.size).putShort(headLen.toShort()).putShort(recLen.toShort())
        out.write(h.array())
        for (fd in fields) {
            val b = ByteArray(32)
            fd.name.toByteArray(Charsets.US_ASCII).copyInto(b, 0)
            b[11] = fd.type.code.toByte(); b[16] = fd.len.toByte(); b[17] = fd.dec.toByte()
            out.write(b)
        }
        out.write(0x0D)
        for (p in points) {
            out.write(' '.code)
            val z = p.groundMsl ?: p.groundEllipsoidalHeight
            val vals = listOf(p.pointId, p.positionType, p.memo, z?.let { String.format(Locale.US, "%.3f", it) } ?: "", p.crsEpsg.toString())
            fields.zip(vals).forEach { (fd, v) -> out.write(cell(v, fd)) }
        }
        out.write(0x1A)
        return out.toByteArray()
    }

    /** 고정 폭 칸: 문자는 왼쪽 맞춤(UTF-8 바이트가 넘치면 글자 단위로 자름), 숫자는 오른쪽 맞춤 */
    private fun cell(v: String, fd: Field): ByteArray {
        var s = v
        var bytes = s.toByteArray(Charsets.UTF_8)
        while (bytes.size > fd.len && s.isNotEmpty()) { s = s.dropLast(1); bytes = s.toByteArray(Charsets.UTF_8) }
        val pad = ByteArray(fd.len - bytes.size) { ' '.code.toByte() }
        return if (fd.type == 'N') pad + bytes else bytes + pad
    }
}
