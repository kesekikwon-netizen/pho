package kr.archsurvey.export

import kr.archsurvey.data.PointEntity
import kr.archsurvey.georef.PrjParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone

/**
 * hgis(Strata) 끌어 놓기용 Shapefile·기준점 CSV 바이트 시험.
 * 기준: ESRI Shapefile Technical Description(1998), dBASE III, hgis src/core/LayerOps.cpp(prepareShapefileEncoding·detectNameField),
 * src/core/ControlPointCsv.cpp, samples/demo_survey/gcp.csv. 마지막 시험은 build/hgis-shp-sample/ 에 표본을 남긴다(GDAL 확인용).
 */
class HgisShpExportTest {
    private val kst = TimeZone.getTimeZone("Asia/Seoul")

    private fun pt(
        id: String, e: Double, n: Double, z: Double? = 30.123, epsg: Int = 5187, memo: String = "", type: String = "유구 경계점",
        rowId: Long = 0, ell: Double? = z?.plus(23.0),
    ) = PointEntity(
        rowId = rowId, project = "발굴-3지구", pointId = id, positionType = type, memo = memo, lat = 36.0, lon = 129.0,
        ellipsoidalHeight = 55.0, altitudeMsl = 32.0, geoidSeparation = 23.0, northing = n, easting = e, crsEpsg = epsg,
        fixQuality = 4, allEpochsFixed = true, satellites = 21, hdop = 0.7, sigmaE = 0.008, sigmaN = 0.009, sigmaH = 0.012, sigmaU = 0.02,
        correctionAgeSec = 1.0, baseStationId = null, epochs = 3, antennaHeight = 1.8 + 0.0687, antennaInputValue = 1.8,
        groundMsl = z, groundEllipsoidalHeight = ell, heightModel = if (z != null) "EGM96" else "",
        device = "Spectra SP60", measuredAtUtcMs = 1_791_100_000_000L, cellName = "A-3",
    )

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun be(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)

    @Test fun pointZ_headerRecordsAndShx() {
        val pts = listOf(pt("P-1", 210000.5, 350000.25, 30.0), pt("P-2", 210010.0, 350020.0, 31.5), pt("P-3", 209990.0, 349990.0, 29.25))
        val s = MoreFormats.shape(pts)
        assertTrue(s.hasZ); assertEquals(5187, s.epsg); assertEquals(3, s.count)
        // .shp 헤더
        assertEquals(100 + 3 * (8 + 36), s.shp.size)
        assertEquals(9994, be(s.shp).getInt(0)); for (o in 4..20 step 4) assertEquals(0, be(s.shp).getInt(o))
        assertEquals(s.shp.size / 2, be(s.shp).getInt(24))
        assertEquals(1000, le(s.shp).getInt(28)); assertEquals(11, le(s.shp).getInt(32))
        assertEquals(209990.0, le(s.shp).getDouble(36), 0.0); assertEquals(349990.0, le(s.shp).getDouble(44), 0.0)
        assertEquals(210010.0, le(s.shp).getDouble(52), 0.0); assertEquals(350020.0, le(s.shp).getDouble(60), 0.0)
        assertEquals(29.25, le(s.shp).getDouble(68), 0.0); assertEquals(31.5, le(s.shp).getDouble(76), 0.0)
        // 레코드: 번호 1부터, 내용 길이 18워드, 유형 11, X=동 Y=북 Z=지면 표고, M=없음
        for (i in 0 until 3) {
            val o = 100 + i * 44
            assertEquals(i + 1, be(s.shp).getInt(o)); assertEquals(18, be(s.shp).getInt(o + 4))
            assertEquals(11, le(s.shp).getInt(o + 8))
            assertEquals(pts[i].easting, le(s.shp).getDouble(o + 12), 0.0); assertEquals(pts[i].northing, le(s.shp).getDouble(o + 20), 0.0)
            assertEquals(pts[i].groundMsl!!, le(s.shp).getDouble(o + 28), 0.0)
            assertTrue(le(s.shp).getDouble(o + 36) < -1e38)
        }
        // .shx: 같은 헤더(길이만 다름) + (오프셋, 길이) 워드
        assertEquals(100 + 3 * 8, s.shx.size); assertEquals(s.shx.size / 2, be(s.shx).getInt(24))
        assertArrayEquals(s.shp.copyOfRange(28, 100), s.shx.copyOfRange(28, 100))
        assertEquals(listOf(50, 72, 94), (0 until 3).map { be(s.shx).getInt(100 + it * 8) })
        assertEquals(listOf(18, 18, 18), (0 until 3).map { be(s.shx).getInt(104 + it * 8) })
    }

    @Test fun missingHeightFallsBackTo2dPointNotFakeZero() {
        val s = MoreFormats.shape(listOf(pt("P-1", 200000.0, 500000.0, 30.0), pt("P-2", 200005.0, 500010.0, z = null, ell = null)))
        assertFalse(s.hasZ)
        assertEquals(1, le(s.shp).getInt(32)); assertEquals(100 + 2 * 28, s.shp.size)
        assertEquals(10, be(s.shp).getInt(104)); assertEquals(1, le(s.shp).getInt(108))
        assertEquals(200005.0, le(s.shp).getDouble(100 + 28 + 12), 0.0)
        assertEquals(listOf(50, 64), (0 until 2).map { be(s.shx).getInt(100 + it * 8) })
        assertEquals(0.0, le(s.shp).getDouble(68), 0.0) // Z 범위 없음
        val rec = Dbf(s.dbf)
        assertEquals("30.000", rec.value(0, "z")); assertEquals("", rec.value(1, "z")) // 없는 표고는 빈칸(0 아님)
        assertEquals("EGM96", rec.value(0, "z_model")); assertEquals("", rec.value(1, "z_model"))
    }

    @Test fun ellipsoidalOnlyHeightIsMarked() {
        val s = MoreFormats.shape(listOf(pt("P-1", 200000.0, 500000.0, z = null, ell = 52.5)))
        assertTrue(s.hasZ); assertEquals(52.5, le(s.shp).getDouble(100 + 28), 0.0)
        assertEquals("ELLIPSOID", Dbf(s.dbf).value(0, "z_model"))
    }

    /** dBASE III 헤더를 읽는 작은 판독기(시험용) */
    private class Dbf(val b: ByteArray) {
        val n = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        val headLen = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(8).toInt()
        val recLen = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt()
        data class F(val name: String, val type: Char, val len: Int, val dec: Int, val off: Int)
        val fields: List<F> = run {
            val out = ArrayList<F>(); var off = 1; var p = 32
            while (b[p].toInt() != 0x0D) {
                val name = String(b, p, 11, Charsets.US_ASCII).trimEnd('\u0000')
                val len = b[p + 16].toInt() and 0xFF
                out.add(F(name, b[p + 11].toInt().toChar(), len, b[p + 17].toInt(), off)); off += len; p += 32
            }
            out
        }
        fun raw(i: Int, name: String): ByteArray { val f = fields.first { it.name == name }; val s = headLen + i * recLen + f.off; return b.copyOfRange(s, s + f.len) }
        fun value(i: Int, name: String) = String(raw(i, name), Charsets.UTF_8).trim()
    }

    @Test fun dbfHeaderFieldsAndUtf8() {
        val longMemo = "토층 경계 확인함 ".repeat(30) // 254 바이트를 넘김
        val pts = listOf(pt("P-0001", 200000.0, 500000.0, memo = "1호 주거지 북벽, \"노지\" 옆", rowId = 7), pt("기준점-가", 200001.0, 500001.0, memo = longMemo, type = "기준점"))
        val s = MoreFormats.shape(pts, mapOf(7L to listOf("photos/a.jpg", "photos/b.jpg")), kst)
        val d = Dbf(s.dbf)
        assertEquals(0x03, s.dbf[0].toInt())
        assertEquals(2, d.n)
        assertEquals(32 + 32 * MoreFormats.DBF_FIELDS.size + 1, d.headLen)
        assertEquals(1 + MoreFormats.DBF_FIELDS.sumOf { it.len }, d.recLen)
        assertEquals(d.headLen + 2 * d.recLen + 1, s.dbf.size); assertEquals(0x1A, s.dbf.last().toInt())
        assertEquals(0, s.dbf[29].toInt()) // 언어 드라이버 0: 인코딩은 .cpg
        val names = d.fields.map { it.name }
        assertEquals(listOf("point_id", "pos_type", "x", "y", "z", "z_model", "ell_h", "ant_h", "pole_h", "fix_type", "sats", "hdop",
            "accuracy_m", "v_acc_m", "epochs", "corr_age", "epsg", "time_local", "time_utc", "grid_cell", "photos", "note", "device"), names)
        assertTrue(names.all { it.length <= 10 && it.all { c -> c.code in 0x21..0x7E } })
        // hgis detectNameField: 이름 후보(name/명칭/번호/kind/label…)에 걸리는 칸이 없어 첫 문자 칸 point_id 가 라벨이 된다
        val hgisCandidates = setOf("사업명", "유적명", "번호", "name", "title", "명칭", "jibun", "지번", "a1", "a2", "pnu", "kind", "kind_ko", "label", "feature_no")
        assertTrue(names.none { it.lowercase() in hgisCandidates || "name" in it.lowercase() || "label" in it.lowercase() || "title" in it.lowercase() })
        assertEquals('C', d.fields.first().type)
        assertEquals(listOf('N', 'N', 'N'), listOf("x", "y", "z").map { n -> d.fields.first { it.name == n }.type })
        assertEquals(3, d.fields.first { it.name == "x" }.dec)
        assertEquals(' '.code.toByte(), s.dbf[d.headLen]) // 삭제 표시 없음
        assertEquals("P-0001", d.value(0, "point_id")); assertEquals("기준점-가", d.value(1, "point_id"))
        assertEquals("1호 주거지 북벽, \"노지\" 옆", d.value(0, "note"))
        assertEquals("200000.000", d.value(0, "x")); assertEquals("500000.000", d.value(0, "y"))
        assertTrue(String(d.raw(0, "x"), Charsets.US_ASCII).startsWith(" ")) // 숫자 오른쪽 맞춤
        assertEquals("RTK_FIXED", d.value(0, "fix_type")); assertEquals("0.0120", d.value(0, "accuracy_m")); assertEquals("0.0200", d.value(0, "v_acc_m"))
        assertEquals("1.800", d.value(0, "pole_h")); assertEquals("1.869", d.value(0, "ant_h"))
        assertEquals("5187", d.value(0, "epsg")); assertEquals("2", d.value(0, "photos")); assertEquals("0", d.value(1, "photos"))
        assertEquals("A-3", d.value(0, "grid_cell"))
        assertEquals("2026-10-04T07:46:40Z", d.value(0, "time_utc")); assertEquals("2026-10-04 16:46:40", d.value(0, "time_local"))
        // 긴 메모: 254바이트 안에서 글자 단위로 잘림(깨진 UTF-8 없음)
        val raw = d.raw(1, "note")
        assertEquals(254, raw.size)
        val decoder = Charsets.UTF_8.newDecoder()
        val text = decoder.decode(ByteBuffer.wrap(raw)).toString() // 잘못된 바이트면 예외
        assertTrue(longMemo.startsWith(text.trimEnd()) && text.trimEnd().length > 50)
    }

    @Test fun prjIsGdalEsriWktPerEpsgAndParsesBack() {
        val expected = mapOf(
            5185 to ("KGD2002_West_Belt_2010" to "125.0"), 5186 to ("KGD2002_Central_Belt_2010" to "127.0"),
            5187 to ("KGD2002_East_Belt_2010" to "129.0"), 5188 to ("KGD2002_East_Sea_Belt_2010" to "131.0"),
        )
        for ((epsg, nl) in expected) {
            val prj = MoreFormats.prj(epsg)!!
            // GDAL 3.10 gdalsrsinfo -o wkt_esri EPSG:5187 과 같은 글(공백·줄바꿈 없는 한 줄)
            assertEquals(
                "PROJCS[\"${nl.first}\",GEOGCS[\"GCS_KGD2002\",DATUM[\"D_Korea_Geodetic_Datum_2002\",SPHEROID[\"GRS_1980\",6378137.0,298.257222101]]," +
                    "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",200000.0]," +
                    "PARAMETER[\"False_Northing\",600000.0],PARAMETER[\"Central_Meridian\",${nl.second}],PARAMETER[\"Scale_Factor\",1.0]," +
                    "PARAMETER[\"Latitude_Of_Origin\",38.0],UNIT[\"Meter\",1.0]]", prj)
            assertTrue(prj.all { it.code in 0x20..0x7E })
            assertEquals(epsg, PrjParser.parse(prj).epsgCode)
        }
        assertEquals(5179, PrjParser.parse(MoreFormats.prj(5179)!!).epsgCode)
        assertNull(MoreFormats.prj(5174)); assertNull(MoreFormats.prj(4326))
        assertEquals("UTF-8", MoreFormats.shape(listOf(pt("P", 1.0, 2.0))).cpg)
        // 작업 좌표계를 따른다: 5186 점은 5186 .prj (5179 아님)
        assertTrue(MoreFormats.shape(listOf(pt("P", 1.0, 2.0, epsg = 5186))).prj!!.contains("Central_Belt_2010"))
    }

    @Test fun mixedCrsSplitsIntoOneSetEach() {
        val sets = MoreFormats.shapesByCrs(listOf(pt("A", 1.0, 2.0, epsg = 5186), pt("B", 3.0, 4.0, epsg = 5187), pt("C", 5.0, 6.0, epsg = 5186)))
        assertEquals(listOf(5186 to 2, 5187 to 1), sets.map { it.epsg to it.count })
        assertEquals(0, MoreFormats.shapesByCrs(emptyList()).size)
    }

    @Test fun hgisControlCsvMatchesParserColumns() {
        val csv = MoreFormats.hgisControlCsv(listOf(
            pt("GCP1", 200000.0, 450000.0, 62.4, epsg = 5186),
            pt("P,2", 200050.0, 450020.0, z = null, ell = 80.0, epsg = 5186).copy(fixQuality = 5, sigmaH = null),
        ))
        assertTrue(csv.startsWith("\uFEFF"))
        val lines = csv.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
        assertEquals("point_id,x,y,z,datum,ellipsoid,projection,origin,accuracy_m,pdop,fix_type", lines[0])
        // x = 동거, y = 북거 (hgis gcp.csv: 200000,450000)
        assertEquals("GCP1,200000.000,450000.000,62.400,세계측지계,GRS80,TM(EPSG:5186 중부원점),중부원점(38N 127E),0.012,,RTK Fix", lines[1])
        // 쉼표 든 번호는 따옴표, 표고 없으면 빈칸(타원체고로 채우지 않음), pdop 은 기록 없음
        assertEquals("\"P,2\",200050.000,450020.000,,세계측지계,GRS80,TM(EPSG:5186 중부원점),중부원점(38N 127E),,,RTK Float", lines[2])
        assertEquals(3, lines.size)
        assertTrue(MoreFormats.hgisControlCsv(listOf(pt("E", 1.0, 2.0, epsg = 5187))).contains("TM(EPSG:5187 동부원점),동부원점(38N 129E)"))
    }

    /** GDAL/pyshp 로 박스에서 열어 볼 표본(build/hgis-shp-sample/) */
    @Test fun writeSampleForGdalCheck() {
        val out = File("build/hgis-shp-sample").apply { deleteRecursively(); mkdirs() }
        val pts = listOf(
            pt("P-0001", 214532.118, 351210.442, 41.237, memo = "1호 주거지 북벽"),
            pt("P-0002", 214540.902, 351215.007, 41.105, type = "모서리"),
            pt("기준점-1", 214500.000, 351200.000, 42.010, type = "기준점"),
            pt("C-01", 199870.25, 451020.75, 62.4, epsg = 5186, type = "기준점"),
            pt("C-02", 199880.5, 451030.0, z = null, ell = null, epsg = 5186, memo = "표고 없음"),
        )
        for (s in MoreFormats.shapesByCrs(pts, emptyMap(), kst)) {
            val b = "points_${s.epsg}"
            File(out, "$b.shp").writeBytes(s.shp); File(out, "$b.shx").writeBytes(s.shx); File(out, "$b.dbf").writeBytes(s.dbf)
            File(out, "$b.prj").writeText(s.prj!!, Charsets.US_ASCII); File(out, "$b.cpg").writeText(s.cpg, Charsets.US_ASCII)
        }
        for ((e, g) in pts.groupBy { it.crsEpsg }) File(out, "hgis_control_points_$e.csv").writeText(MoreFormats.hgisControlCsv(g), Charsets.UTF_8)
        assertEquals(12, out.listFiles()!!.size)
    }
}
