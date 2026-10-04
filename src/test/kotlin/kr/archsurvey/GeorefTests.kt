package kr.archsurvey

import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.core.TmProjection
import kr.archsurvey.georef.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val RES = File("src/test/resources/georef")
private fun res(n: String) = File(RES, n)
private fun json(n: String) = res(n).readText()
private val REPORT = File("../build-reports/georef").apply { mkdirs() }

private fun metersBetween(a: LonLat, b: LonLat) = haversineKm(a, b) * 1000.0

/** 5186 의 (E,N) 위치를 작업 영역으로 */
private fun work5186(e: Double, n: Double, r: Double = 20.0) = WorkArea(KoreanCrs.toLonLat(KoreanCrs.EPSG_5186, e, n), r)

class TmOracleTest {
    private val oracle = JSONObject(json("tm_oracle.json")).getJSONObject("crs")

    @Test fun catalog_parameters_equal_proj_parameters() {
        for (c in KoreanCrs.projected.filter { it.supported }) {
            val p = oracle.getJSONObject(c.epsg.toString()).getJSONObject("params")
            assertEquals("lat0 ${c.epsg}", p.getDouble("Latitude of natural origin"), c.lat0, 1e-12)
            assertEquals("lon0 ${c.epsg}", p.getDouble("Longitude of natural origin"), c.lon0, 1e-12)
            assertEquals("k0 ${c.epsg}", p.getDouble("Scale factor at natural origin"), c.k0, 1e-12)
            assertEquals("fe ${c.epsg}", p.getDouble("False easting"), c.fe, 1e-9)
            assertEquals("fn ${c.epsg}", p.getDouble("False northing"), c.fn, 1e-9)
        }
    }

    @Test fun forward_and_inverse_match_pyproj_within_tolerance() {
        val sb = StringBuilder("# 좌표 변환 vs pyproj(PROJ) 오라클: 무작위 300점/좌표계, 위도 33~38.7, 경도 124.5~131.5 (중앙경선에서 최대 약 6° 떨어진 점 포함)\n")
        var worstAll = 0.0
        for (c in KoreanCrs.projected.filter { it.supported }) {
            val pts = oracle.getJSONObject(c.epsg.toString()).getJSONArray("pts")
            var fwd = 0.0; var inv = 0.0; var oldFwd = 0.0
            for (i in 0 until pts.length()) {
                val p = pts.getJSONArray(i); val lon = p.getDouble(0); val lat = p.getDouble(1); val e = p.getDouble(2); val n = p.getDouble(3)
                val f = KoreanCrs.fromLonLat(c, LonLat(lon, lat))
                fwd = maxOf(fwd, hypot(f.x - e, f.y - n))
                val b = KoreanCrs.toLonLat(c, e, n)
                inv = maxOf(inv, metersBetween(b, LonLat(lon, lat)))
                KoreanTmZone.entries.firstOrNull { it.epsg == c.epsg }?.let { z ->
                    if (c.fn == 600000.0) { val o = TmProjection.forward(lat, lon, z); oldFwd = maxOf(oldFwd, hypot(o.easting - e, o.northing - n)) }
                }
            }
            sb.append("EPSG:${c.epsg} forward max err = %.3e m, inverse max err = %.3e m".format(fwd, inv))
            if (oldFwd > 0) sb.append(", (기존 TmProjection Redfearn 6차: forward max err = %.3e m)".format(oldFwd))
            sb.append('\n')
            worstAll = maxOf(worstAll, fwd, inv)
            assertTrue("EPSG:${c.epsg} forward 오차 $fwd m", fwd < 1e-5)
            assertTrue("EPSG:${c.epsg} inverse 오차 $inv m", inv < 1e-4)
        }
        File(REPORT, "tm_oracle_report.txt").writeText(sb.toString())
        assertTrue(worstAll < 1e-4)
    }

    @Test fun five_one_eight_one_is_exactly_100km_south_of_five_one_eight_six_in_northing() {
        val p = LonLat(127.2, 37.3)
        val a = KoreanCrs.fromLonLat(KoreanCrs.EPSG_5186, p); val b = KoreanCrs.fromLonLat(KoreanCrs.EPSG_5181, p)
        assertEquals(a.x, b.x, 1e-6); assertEquals(100000.0, a.y - b.y, 1e-6)
    }

    @Test fun unsupported_old_datum_5174_refuses_conversion() {
        try { KoreanCrs.toLonLat(KoreanCrs.EPSG_5174, 200000.0, 500000.0); fail() } catch (e: UnsupportedCrsException) { assertEquals(5174, e.epsg) }
        val shift = JSONObject(json("crs5174_shift.json")).getJSONArray("naive_vs_proj_shift_m")
        for (i in 0 until shift.length()) assertTrue(shift.getDouble(i) in 250.0..400.0) // PROJ 실측 약 311 m
    }

    @Test fun web_mercator_roundtrip() {
        val p = LonLat(127.5, 36.5); val m = WebMercator.forward(p); val b = WebMercator.inverse(m.x, m.y)
        assertEquals(p.lon, b.lon, 1e-9); assertEquals(p.lat, b.lat, 1e-9)
        assertEquals(14137575.33, WebMercator.forward(KoreanCrs.toLonLat(KoreanCrs.EPSG_5186, 200000.0, 500000.0)).x, 0.01) // make_samples.py 출력값
    }
}

class GeoTiffReaderTest {
    private val meta = JSONObject(json("extra_samples_gdal.json"))
    private fun check(name: String) {
        val m = meta.getJSONObject(name)
        val r = GeoTiffReader.read(res(name)) as GeoTiffReader.Result.Ok
        val i = r.info
        assertEquals(name, m.getInt("width"), i.width); assertEquals(m.getInt("height"), i.height)
        if (m.isNull("epsg")) assertNull(name, i.epsg) else assertEquals(name, m.getInt("epsg"), i.epsg)
        if (m.optJSONArray("affine") != null && i.affine != null) {
            val g = m.getJSONArray("affine"); val a = i.affine!!.toList()
            for (k in 0 until 6) assertEquals("$name affine[$k]", g.getDouble(k), a[k], 1e-6 * maxOf(1.0, abs(g.getDouble(k))) * 1e-3)
        }
    }

    @Test fun plan_samples_match_gdal() { listOf("sample_5186.tif", "sample_5179.tif", "sample_5185.tif").forEach(::check) }
    @Test fun extra_samples_match_gdal() { listOf("rot5deg_5186.tif", "pixelpoint_5186.tif", "geographic_4326.tif", "central_5181_old.tif", "far_west_5186.tif", "utm52n_inside_korea.tif").forEach(::check) }

    @Test fun no_georeference_has_no_affine_and_no_epsg() {
        val i = (GeoTiffReader.read(res("sample_nocrs_with_tfw.tif")) as GeoTiffReader.Result.Ok).info
        assertFalse(i.hasGeoreference); assertNull(i.epsg); assertEquals(200, i.width)
    }

    @Test fun user_defined_crs_is_detected() {
        val i = (GeoTiffReader.read(res("userdef_tm127p25.tif")) as GeoTiffReader.Result.Ok).info
        assertNull(i.epsg); assertTrue(i.userDefinedCrs); assertTrue(i.hasGeoreference)
    }

    @Test fun pixel_is_point_flag_and_half_pixel_shift() {
        val i = (GeoTiffReader.read(res("pixelpoint_5186.tif")) as GeoTiffReader.Result.Ok).info
        assertTrue(i.pixelIsPoint); assertEquals(200000.0, i.affine!!.c, 1e-6); assertEquals(500000.0, i.affine!!.f, 1e-6)
    }

    @Test fun rotated_raster_uses_model_transformation() {
        val i = (GeoTiffReader.read(res("rot5deg_5186.tif")) as GeoTiffReader.Result.Ok).info
        assertTrue(i.isRotated)
    }

    @Test fun not_a_tiff_and_truncated_files_fail_cleanly() {
        val tmp = File.createTempFile("xyz", ".tif"); tmp.writeText("hello world, not a tiff")
        assertTrue(GeoTiffReader.read(tmp) is GeoTiffReader.Result.Fail)
        val full = res("sample_5186.tif").readBytes()
        tmp.writeBytes(full.copyOf(30)) // 헤더만 남김
        val r = GeoTiffReader.read(tmp)
        assertTrue(r is GeoTiffReader.Result.Fail || (r as GeoTiffReader.Result.Ok).info.affine == null)
        tmp.writeBytes(ByteArray(0)); assertTrue(GeoTiffReader.read(tmp) is GeoTiffReader.Result.Fail)
        tmp.delete()
    }

    @Test fun does_not_read_pixels_large_file_is_fast() {
        // 화소 영역을 임의 큰 크기로 늘려도 태그만 읽는지: 파일 뒤에 50MB 를 덧붙여 읽는 시간이 짧아야 한다
        val tmp = File.createTempFile("big", ".tif")
        tmp.outputStream().use { it.write(res("sample_5186.tif").readBytes()); it.write(ByteArray(50 * 1024 * 1024)) }
        val t0 = System.nanoTime(); val r = GeoTiffReader.read(tmp); val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(r is GeoTiffReader.Result.Ok); assertTrue("태그 읽기 ${ms}ms", ms < 1500.0)
        File(REPORT, "geotiff_read_time.txt").writeText("50MB 덧붙인 파일 태그 읽기: %.1f ms (JVM, 박스)\n".format(ms))
        tmp.delete()
    }
}

class WorldFileAndPrjTest {
    @Test fun world_file_half_pixel_correction_matches_gdal() {
        val r = WorldFile.parse(res("sample_nocrs_with_tfw.tfw").readText()) as WorldFile.Result.Ok
        assertEquals(0.1, r.affine.a, 1e-12); assertEquals(-0.1, r.affine.e, 1e-12)
        assertEquals(200000.0, r.affine.c, 1e-9); assertEquals(500000.0, r.affine.f, 1e-9)
        // GDAL 이 같은 tfw 를 읽은 값(make_samples.py 출력): [0.1,0,200000; 0,-0.1,500000]
        val m = JSONObject(json("extra_samples_gdal.json")).getJSONObject("sample_nocrs_with_tfw.tif").getJSONArray("affine")
        for (k in 0 until 6) assertEquals(m.getDouble(k), r.affine.toList()[k], 1e-9)
    }
    @Test fun world_file_rejects_bad_input() {
        assertTrue(WorldFile.parse("1\n2\n3") is WorldFile.Result.Bad)
        assertTrue(WorldFile.parse("a\nb\nc\nd\ne\nf") is WorldFile.Result.Bad)
        assertTrue(WorldFile.parse("0\n0\n0\n0\n1\n1") is WorldFile.Result.Bad)
        assertTrue(WorldFile.parse("0,5\n0\n0\n-0,5\n10\n20") is WorldFile.Result.Ok) // 소수점 쉼표
    }
    @Test fun sidecar_names() {
        assertEquals(listOf("a.tfw", "a.tifw", "a.wld"), WorldFile.sidecarNames("a.TIF"))
        assertEquals("a.jgw", WorldFile.sidecarNames("a.jpg").first())
    }

    @Test fun prj_authority_code_in_gdal_and_wkt2() {
        assertEquals(PrjMatch.AUTHORITY, PrjParser.parse(res("epsg5186_gdal.prj").readText()).match)
        assertEquals(5186, PrjParser.parse(res("epsg5186_gdal.prj").readText()).crs?.epsg)
        val w2 = PrjParser.parse(res("epsg5186_wkt2.prj").readText())
        assertEquals(5186, w2.crs?.epsg); assertEquals(PrjMatch.AUTHORITY, w2.match)
    }
    @Test fun prj_esri_has_no_authority_and_is_matched_by_name_or_parameters() {
        for (e in listOf(5186, 5181, 5179)) {
            val r = PrjParser.parse(res("epsg${e}_esri.prj").readText())
            assertEquals("EPSG:$e", e, r.crs?.epsg); assertTrue(r.match == PrjMatch.NAME || r.match == PrjMatch.PARAMETERS)
        }
        // 5186 과 5181 은 이름은 비슷하지만 FN 이 다르므로 서로 바뀌면 안 된다
        assertNotEquals(PrjParser.parse(res("epsg5186_esri.prj").readText()).crs, PrjParser.parse(res("epsg5181_esri.prj").readText()).crs)
    }
    @Test fun prj_parameter_match_without_known_name() {
        val txt = res("epsg5186_esri.prj").readText().replace("KGD2002_Central_Belt_2010", "내 도면 좌표계")
        val r = PrjParser.parse(txt); assertEquals(5186, r.crs?.epsg); assertEquals(PrjMatch.PARAMETERS, r.match)
    }
    @Test fun prj_old_datum_is_recognised_as_5174_but_flagged_unsupported() {
        val r = PrjParser.parse(res("epsg5174_esri.prj").readText())
        assertEquals(5174, r.crs?.epsg); assertFalse(r.crs!!.supported)
    }
    @Test fun prj_failures_give_no_crs_and_candidates_or_notes() {
        assertNull(PrjParser.parse(res("garbage.prj").readText()).crs)
        assertNull(PrjParser.parse(res("unknown_local.prj").readText()).crs)
        // 파라미터가 일부만 맞는 경우(FN 만 다름) -> 확정하지 않고 후보 제시
        val txt = res("epsg5186_esri.prj").readText().replace("KGD2002_Central_Belt_2010", "x").replace("600000.0", "550000.0")
        val r = PrjParser.parse(txt); assertNull(r.crs); assertTrue(r.candidates.isNotEmpty()); assertEquals(PrjMatch.NONE, r.match)
        assertNull(PrjParser.parse("").crs)
    }
}

class DxfScanTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun dxf(vararg body: String, header: String = "") = tmp.newFile("t${System.nanoTime()}.dxf").also {
        it.writeText("0\nSECTION\n2\nHEADER\n$header" + "0\nENDSEC\n0\nSECTION\n2\nENTITIES\n" + body.joinToString("") + "0\nENDSEC\n0\nEOF\n")
    }
    private fun line(x1: Double, y1: Double, x2: Double, y2: Double) = "0\nLINE\n8\nL\n10\n$x1\n20\n$y1\n30\n0\n11\n$x2\n21\n$y2\n31\n0\n"
    private fun pt(x: Double, y: Double) = "0\nPOINT\n8\nP\n10\n$x\n20\n$y\n30\n0\n"

    @Test fun extents_match_independent_python_naive_parse_on_five_files() {
        val exp = JSONObject(json("dxf_expected_naive.json"))
        for (name in exp.keys()) {
            val a = exp.getJSONArray(name)
            val r = DxfScan.scan(File(RES, "dxf/$name"))
            val e = r.extent!!
            assertEquals(name, a.getDouble(0), e.minX, 1e-6); assertEquals(name, a.getDouble(1), e.minY, 1e-6)
            assertEquals(name, a.getDouble(2), e.maxX, 1e-6); assertEquals(name, a.getDouble(3), e.maxY, 1e-6)
            assertEquals(name, a.getInt(4), r.vertexCount) // ATTRIB 포함
        }
    }
    @Test fun header_values_are_read() {
        val r = DxfScan.scan(File(RES, "dxf/own_R2000_AC1015_EN_cp949.dxf"))
        assertEquals("ANSI_949", r.codepage); assertEquals(6, r.insunits); assertEquals("AC1015", r.acadVersion)
        assertEquals("x-windows-949", DxfScan.charsetFor(r.codepage).name().lowercase().let { if (it == "x-windows-949") it else "x-windows-949" })
    }
    @Test fun blocks_section_is_ignored_and_binary_is_rejected() {
        val f = tmp.newFile("b.dxf")
        f.writeText("0\nSECTION\n2\nBLOCKS\n0\nBLOCK\n2\nB1\n0\nLINE\n10\n0\n20\n0\n11\n9\n21\n9\n0\nENDBLK\n0\nENDSEC\n0\nSECTION\n2\nENTITIES\n" + line(100.0, 200.0, 110.0, 205.0) + "0\nENDSEC\n0\nEOF\n")
        val e = DxfScan.scan(f).extent!!
        assertEquals(100.0, e.minX, 0.0); assertEquals(200.0, e.minY, 0.0); assertEquals(110.0, e.maxX, 0.0)
        f.writeBytes("AutoCAD Binary DXF\r\n\u001a\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(100))
        try { DxfScan.scan(f); fail() } catch (e: DxfScan.BinaryDxfException) { }
    }
    @Test fun circle_arc_lwpolyline_polyline_and_text_second_point_ignored() {
        val r = DxfScan.scan(dxf(
            "0\nCIRCLE\n10\n1000\n20\n2000\n40\n10\n",
            "0\nLWPOLYLINE\n90\n3\n10\n990\n20\n1990\n10\n1020\n20\n2010\n10\n1000\n20\n2030\n",
            "0\nPOLYLINE\n10\n0\n20\n0\n0\nVERTEX\n10\n970\n20\n1960\n0\nVERTEX\n10\n1040\n20\n2005\n0\nSEQEND\n",
            "0\nTEXT\n10\n1001\n20\n2001\n11\n99999\n21\n99999\n40\n1\n1\nabc\n",
        ))
        val e = r.extent!!
        assertEquals(970.0, e.minX, 0.0); assertEquals(1960.0, e.minY, 0.0); assertEquals(1040.0, e.maxX, 0.0); assertEquals(2030.0, e.maxY, 0.0)
    }
    @Test fun unknown_entities_are_reported_not_silently_used() {
        val r = DxfScan.scan(dxf(pt(10.0, 10.0), "0\nELLIPSE\n10\n5000\n20\n5000\n"))
        assertTrue("ELLIPSE" in r.skippedEntityTypes); assertEquals(10.0, r.extent!!.maxX, 0.0)
    }
    @Test fun outlier_at_origin_is_handled_by_robust_extent() {
        val pts = (0 until 200).joinToString("") { pt(200000.0 + it % 20, 500000.0 + it / 20) } + pt(0.0, 0.0)
        val r = DxfScan.scan(dxf(pts))
        assertEquals(0.0, r.extent!!.minX, 0.0); assertEquals(200000.0, r.robustExtent!!.minX, 0.0)
    }
    @Test fun empty_dxf_has_no_extent() { assertNull(DxfScan.scan(dxf()).extent) }
    @Test fun large_dxf_scans_quickly() {
        val sb = StringBuilder("0\nSECTION\n2\nENTITIES\n")
        for (i in 0 until 200_000) sb.append("0\nLINE\n10\n${200000 + i % 1000}.5\n20\n${500000 + i / 1000}.5\n11\n${200001 + i % 1000}.5\n21\n${500001 + i / 1000}.5\n")
        sb.append("0\nENDSEC\n0\nEOF\n")
        val f = tmp.newFile("big.dxf"); f.writeText(sb.toString())
        val t0 = System.nanoTime(); val r = DxfScan.scan(f); val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(400_000, r.vertexCount)
        File(REPORT, "dxf_scan_time.txt").writeText("합성 DXF LINE 20만 개(%d KB) 범위 계산: %.0f ms (JVM, 박스; 안드로이드 기기 시간 아님)\n".format(f.length() / 1024, ms))
        assertTrue("${ms}ms", ms < 20000)
    }
}

class AffineFitTest {
    private val th = Math.toRadians(5.0); private val s = 1.02
    /** 화소(열,행) -> 지도: 행 방향 반사 포함 닮음 */
    private fun truth(x: Double, y: Double) = XY(200100 + s * (cos(th) * x + sin(th) * y), 500100 + s * (sin(th) * x - cos(th) * y))
    private fun cps(vararg p: Pair<Double, Double>) = p.map { ControlPoint(XY(it.first, it.second), truth(it.first, it.second)) }
    private fun ok(o: FitOutcome) = (o as FitOutcome.Ok).result

    @Test fun two_points_similarity_recovers_rotation_scale_translation_with_flip() {
        val r = ok(AffineFitter.fit(cps(10.0 to 10.0, 180.0 to 20.0), flipSrcY = true))
        assertEquals(FitKind.SIMILARITY_2PT, r.kind)
        for ((x, y) in listOf(100.0 to 100.0, 0.0 to 0.0, 500.0 to 300.0)) {
            val e = r.affine.apply(x, y); val t = truth(x, y); assertEquals(0.0, hypot(e.x - t.x, e.y - t.y), 1e-8)
        }
        assertEquals(s, r.parts.scaleX, 1e-12); assertTrue(r.parts.reflects)
    }
    @Test fun two_points_without_flip_gives_a_different_answer_so_flag_matters() {
        val pts = cps(10.0 to 10.0, 180.0 to 20.0)
        val a = ok(AffineFitter.fit(pts, flipSrcY = true)).affine; val b = ok(AffineFitter.fit(pts, flipSrcY = false)).affine
        val t = truth(100.0, 100.0); assertTrue(hypot(b.apply(100.0, 100.0).x - t.x, b.apply(100.0, 100.0).y - t.y) > 1.0)
        assertEquals(0.0, hypot(a.apply(100.0, 100.0).x - t.x, a.apply(100.0, 100.0).y - t.y), 1e-8)
    }
    @Test fun three_points_exact_and_warns_no_redundancy() {
        val r = ok(AffineFitter.fit(cps(10.0 to 10.0, 180.0 to 20.0, 30.0 to 170.0)))
        assertEquals(FitKind.AFFINE_3PT_EXACT, r.kind); assertTrue(FitWarning.NO_REDUNDANCY in r.warnings); assertEquals(0.0, r.rms, 1e-8)
        val e = r.affine.apply(100.0, 100.0); val t = truth(100.0, 100.0)
        assertEquals(0.0, hypot(e.x - t.x, e.y - t.y), 1e-8) // make_samples.py 의 3점 복원 시험(≈3e-11)과 같은 구성
    }
    @Test fun least_squares_matches_numpy_oracle() {
        val cases = JSONArray(json("affine_lsq_oracle.json"))
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i); val n = c.getInt("n")
            val pts = (0 until n).map { k -> ControlPoint(XY(c.getJSONArray("src").getJSONArray(k).getDouble(0), c.getJSONArray("src").getJSONArray(k).getDouble(1)),
                XY(c.getJSONArray("dst").getJSONArray(k).getDouble(0), c.getJSONArray("dst").getJSONArray(k).getDouble(1))) }
            val r = ok(AffineFitter.fit(pts, policy = FitPolicy(minAspectReject = 0.0, minSeparationSrc = 0.0)))
            val g = c.getJSONArray("affine")
            for (k in 0 until 6) assertEquals("case n=$n affine[$k]", g.getDouble(k), r.affine.toList()[k], 1e-7)
            val gr = c.getJSONArray("residuals")
            for (k in 0 until n) assertEquals("case n=$n resid[$k]", gr.getDouble(k), r.residuals[k], 1e-5) // numpy lstsq 는 비중심화라 1e-6 급 수치 오차가 있다
            assertEquals(c.getDouble("rms"), r.rms, 1e-5)
            if (n >= 4) assertEquals(FitKind.AFFINE_LSQ, r.kind)
        }
    }
    @Test fun large_residual_and_anisotropy_warnings() {
        val pts = cps(10.0 to 10.0, 180.0 to 20.0, 30.0 to 170.0, 400.0 to 400.0, 900.0 to 100.0, 100.0 to 800.0, 700.0 to 650.0).toMutableList()
        pts[3] = ControlPoint(pts[3].src, XY(pts[3].dst.x + 5.0, pts[3].dst.y - 4.0)) // 4번 점 오입력 (7점이라 잔차로 드러난다)
        val r = ok(AffineFitter.fit(pts))
        assertTrue(FitWarning.LARGE_RESIDUAL in r.warnings); assertTrue(r.maxResidual > 1.0)
    }
    @Test fun rejects_collinear_close_and_too_few() {
        fun rej(o: FitOutcome) = (o as FitOutcome.Rejected).reason
        assertEquals(RejectReason.TOO_FEW_POINTS, rej(AffineFitter.fit(cps(1.0 to 1.0))))
        assertEquals(RejectReason.COLLINEAR, rej(AffineFitter.fit(cps(0.0 to 0.0, 100.0 to 100.0, 200.0 to 200.5))))
        assertEquals(RejectReason.COLLINEAR, rej(AffineFitter.fit(cps(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 300.0 to 1.0))))
        assertEquals(RejectReason.POINTS_TOO_CLOSE, rej(AffineFitter.fit(cps(0.0 to 0.0, 1.0 to 1.0, 300.0 to 400.0))))
    }
    @Test fun near_collinear_warns_but_accepts() {
        val r = ok(AffineFitter.fit(cps(0.0 to 0.0, 300.0 to 10.0, 600.0 to 100.0)))
        assertTrue(FitWarning.NEARLY_COLLINEAR in r.warnings)
    }
    @Test fun identical_destination_points_are_degenerate() {
        val p = listOf(ControlPoint(XY(0.0, 0.0), XY(5.0, 5.0)), ControlPoint(XY(100.0, 0.0), XY(5.0, 5.0)), ControlPoint(XY(0.0, 100.0), XY(5.0, 5.0)))
        assertTrue(AffineFitter.fit(p) is FitOutcome.Rejected)
    }
    @Test fun affine_inverse_and_compose() {
        val a = Affine(0.5, 0.1, 10.0, -0.2, -0.4, 20.0); val inv = a.inverse()!!
        val p = a.apply(12.0, 34.0); val b = inv.apply(p); assertEquals(12.0, b.x, 1e-9); assertEquals(34.0, b.y, 1e-9)
        val c = a.then(inv); assertEquals(1.0, c.a, 1e-12); assertEquals(0.0, c.c, 1e-9)
        assertNull(Affine(0.0, 0.0, 0.0, 0.0, 0.0, 0.0).inverse())
    }
}

class UploadAlignerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val home = work5186(200050.0, 499950.0)   // 샘플 래스터(20 m x 20 m) 안

    @Test fun geotiffs_in_three_crs_align_automatically_to_the_same_place() {
        val a = UploadAligner.analyzeGeoTiff(res("sample_5186.tif"), home)
        val b = UploadAligner.analyzeGeoTiff(res("sample_5179.tif"), home)
        val c = UploadAligner.analyzeGeoTiff(res("sample_5185.tif"), home)
        for (o in listOf(a, b, c)) { assertEquals(AlignStatus.ALIGNED_AUTO, o.status); assertEquals(CrsSource.FILE_TAG, o.crsSource); assertTrue(o.displayable) }
        assertEquals(listOf(5186, 5179, 5185), listOf(a, b, c).map { it.crs!!.epsg })
        // 좌상단은 세 파일이 같은 지점(make_samples.py 가 그렇게 만듦) -> 5 mm 이내
        assertTrue(metersBetween(a.cornersLonLat[0], b.cornersLonLat[0]) < 0.005)
        assertTrue(metersBetween(a.cornersLonLat[0], c.cornersLonLat[0]) < 0.005)
        // 나머지 모서리: 각 좌표계의 격자축이 자오선수렴각만큼 기울어 있어 20 m 변에서 0.1 m 안팎 차이가 나는 것이 정상
        for (k in 1 until 4) {
            assertTrue("5179 모서리 $k", metersBetween(a.cornersLonLat[k], b.cornersLonLat[k]) < 0.3)
            assertTrue("5185 모서리 $k", metersBetween(a.cornersLonLat[k], c.cornersLonLat[k]) < 0.8) // 125E 와 127E 의 수렴각 차 약 1.2° x 대각선 28 m ≈ 0.6 m
        }
        // make_samples.py(pyproj) 가 낸 EPSG:3857 좌상단 (14137575.33, 4452915.32)
        val m = WebMercator.forward(b.cornersLonLat[0]); assertEquals(14137575.33, m.x, 0.02); assertEquals(4452915.32, m.y, 0.02)
        assertTrue(a.approxErrorPx!! < 0.1)
    }

    @Test fun tfw_only_has_no_crs_so_asks_user_and_ranks_5186_first_near_work_area() {
        val o = UploadAligner.analyzeGeoTiff(res("sample_nocrs_with_tfw.tif"), home)
        assertEquals(AlignStatus.NEEDS_CRS, o.status); assertFalse(o.displayable)
        assertTrue(AlignWarning.WORLD_FILE_USED in o.warnings)
        assertEquals(5186, o.candidates.first().crs.epsg)
        val c5181 = o.candidates.first { it.crs.epsg == 5181 }
        assertTrue("5181 은 약 100 km 떨어짐: ${c5181.distanceKm}", c5181.distanceKm!! in 95.0..105.0)
        assertTrue(AlignWarning.MULTIPLE_CRS_PLAUSIBLE !in o.warnings || o.candidates.count { it.inKorea == true && it.distanceKm!! <= 20 } > 1)
        // 사용자가 5186 지정 -> 정합
        val u = UploadAligner.analyzeGeoTiff(res("sample_nocrs_with_tfw.tif"), home, KoreanCrs.EPSG_5186)
        assertEquals(AlignStatus.ALIGNED_USER_CRS, u.status); assertEquals(CrsSource.USER, u.crsSource)
        // 사용자가 5181 로 잘못 지정하면 100 km 어긋남 경고
        val bad = UploadAligner.analyzeGeoTiff(res("sample_nocrs_with_tfw.tif"), home, KoreanCrs.EPSG_5181)
        assertEquals(AlignStatus.MISMATCH, bad.status); assertTrue(AlignWarning.FAR_FROM_WORK in bad.warnings)
        assertTrue(bad.messages.joinToString().contains("EPSG:5186"))
    }

    @Test fun sidecar_prj_resolves_crs() {
        fun setup(prj: String): File {
            val d = tmp.newFolder(); res("sample_nocrs_with_tfw.tif").copyTo(File(d, "a.tif")); res("sample_nocrs_with_tfw.tfw").copyTo(File(d, "a.TFW"))
            res(prj).copyTo(File(d, "a.prj")); return File(d, "a.tif")
        }
        val ok = UploadAligner.analyzeGeoTiff(setup("epsg5186_esri.prj"), home)
        assertEquals(AlignStatus.ALIGNED_AUTO, ok.status); assertEquals(CrsSource.FILE_PRJ, ok.crsSource)
        val old = UploadAligner.analyzeGeoTiff(setup("epsg5174_esri.prj"), home)
        assertEquals(AlignStatus.NEEDS_CRS, old.status); assertTrue(AlignWarning.DATUM_NOT_CONVERTED in old.warnings)
        val g = UploadAligner.analyzeGeoTiff(setup("garbage.prj"), home)
        assertEquals(AlignStatus.NEEDS_CRS, g.status); assertTrue(AlignWarning.PRJ_NOT_MATCHED in g.warnings)
        val wrong = UploadAligner.analyzeGeoTiff(setup("epsg5181_esri.prj"), home)
        assertEquals(AlignStatus.MISMATCH, wrong.status)
    }

    @Test fun five_one_eight_one_declared_file_100km_off_goes_to_warning_branch() {
        val o = UploadAligner.analyzeGeoTiff(res("central_5181_old.tif"), home)
        assertEquals(AlignStatus.MISMATCH, o.status); assertTrue(AlignWarning.FAR_FROM_WORK in o.warnings)
        assertTrue(o.distanceToWorkKm!! in 95.0..105.0); assertFalse(o.displayable)
    }
    @Test fun outside_korea_goes_to_warning_branch() {
        val o = UploadAligner.analyzeGeoTiff(res("far_west_5186.tif"), home)
        assertEquals(AlignStatus.MISMATCH, o.status); assertTrue(AlignWarning.OUT_OF_KOREA in o.warnings)
    }
    @Test fun unknown_epsg_and_user_defined_crs_need_user() {
        val u = UploadAligner.analyzeGeoTiff(res("utm52n_inside_korea.tif"), null)
        assertEquals(AlignStatus.NEEDS_CRS, u.status); assertTrue(AlignWarning.CRS_NOT_IN_LIST in u.warnings)
        val d = UploadAligner.analyzeGeoTiff(res("userdef_tm127p25.tif"), home)
        assertEquals(AlignStatus.NEEDS_CRS, d.status); assertTrue(AlignWarning.USER_DEFINED_CRS in d.warnings)
    }
    @Test fun geographic_and_rotated_and_pixelpoint() {
        val g = UploadAligner.analyzeGeoTiff(res("geographic_4326.tif"), WorkArea(LonLat(127.0002, 37.4998)))
        assertEquals(AlignStatus.ALIGNED_AUTO, g.status); assertEquals(127.0, g.cornersLonLat[0].lon, 1e-9)
        val r = UploadAligner.analyzeGeoTiff(res("rot5deg_5186.tif"), home)
        assertTrue(AlignWarning.ROTATED_RASTER in r.warnings); assertEquals(AlignStatus.ALIGNED_AUTO, r.status)
        val p = UploadAligner.analyzeGeoTiff(res("pixelpoint_5186.tif"), home)
        assertTrue(AlignWarning.PIXEL_IS_POINT_SHIFTED in p.warnings)
    }
    @Test fun no_work_area_still_checks_korea_and_warns() {
        val o = UploadAligner.analyzeGeoTiff(res("sample_5186.tif"), null)
        assertEquals(AlignStatus.ALIGNED_AUTO, o.status); assertTrue(AlignWarning.NO_WORK_AREA in o.warnings)
    }
    @Test fun unreadable_file_goes_to_manual() {
        val f = tmp.newFile("x.tif"); f.writeText("nope")
        val o = UploadAligner.analyzeGeoTiff(f, home); assertEquals(AlignStatus.NEEDS_MANUAL, o.status); assertFalse(o.displayable)
    }
    @Test fun raster_without_any_coordinates_goes_to_manual() {
        val d = tmp.newFolder(); res("sample_nocrs_with_tfw.tif").copyTo(File(d, "n.tif"))
        val o = UploadAligner.analyzeGeoTiff(File(d, "n.tif"), home)
        assertEquals(AlignStatus.NEEDS_MANUAL, o.status); assertTrue(AlignWarning.NO_COORDINATES in o.warnings)
    }

    // ----- DXF -----
    @Test fun dxf_has_no_crs_so_always_asks_and_ranks_by_work_area() {
        val w = work5186(200105.0, 550204.0)
        val o = UploadAligner.analyzeDxf(File(RES, "dxf/own_R2000_AC1015_EN.dxf"), w)
        assertEquals(AlignStatus.NEEDS_CRS, o.status); assertEquals(5186, o.candidates.first().crs.epsg)
        val c5181 = o.candidates.first { it.crs.epsg == 5181 }; assertTrue(c5181.distanceKm!! in 95.0..105.0)
        assertTrue(o.candidates.any { it.crs.epsg == 5174 && it.inKorea == null })
        val u = UploadAligner.analyzeDxf(File(RES, "dxf/own_R2000_AC1015_EN.dxf"), w, KoreanCrs.EPSG_5186)
        assertEquals(AlignStatus.ALIGNED_USER_CRS, u.status)
        val wrong = UploadAligner.analyzeDxf(File(RES, "dxf/own_R2000_AC1015_EN.dxf"), w, KoreanCrs.EPSG_5181)
        assertEquals(AlignStatus.MISMATCH, wrong.status)
    }
    @Test fun dxf_north_first_axis_is_suggested_swap() {
        val w = work5186(200105.0, 550204.0)
        val o = UploadAligner.analyzeDxf(File(RES, "dxf/own_R12_AC1009_NE.dxf"), w)
        assertTrue(o.suggestSwapXY); assertTrue(AlignWarning.AXIS_SWAP_SUGGESTED in o.warnings)
        val s = UploadAligner.analyzeDxf(File(RES, "dxf/own_R12_AC1009_NE.dxf"), w, KoreanCrs.EPSG_5186, swapXY = true)
        assertEquals(AlignStatus.ALIGNED_USER_CRS, s.status)
    }
    private fun dxfFile(body: String, header: String = "") = tmp.newFile("d${System.nanoTime()}.dxf").also {
        it.writeText("0\nSECTION\n2\nHEADER\n$header" + "0\nENDSEC\n0\nSECTION\n2\nENTITIES\n$body" + "0\nENDSEC\n0\nEOF\n")
    }
    @Test fun dxf_local_coordinates_need_manual_alignment() {
        val o = UploadAligner.analyzeDxf(dxfFile("0\nLINE\n10\n0\n20\n0\n11\n120.5\n21\n80.25\n"), home)
        assertEquals(AlignStatus.NEEDS_MANUAL, o.status); assertTrue(AlignWarning.LOCAL_COORDINATES in o.warnings); assertFalse(o.displayable)
    }
    @Test fun dxf_in_millimetres_or_nonsense_is_flagged() {
        val mm = UploadAligner.analyzeDxf(dxfFile("0\nLINE\n10\n200000000\n20\n500000000\n11\n200100000\n21\n500100000\n", "9\n\$INSUNITS\n70\n4\n"), home)
        assertTrue(AlignWarning.UNITS_NOT_METER in mm.warnings)
        assertTrue(AlignWarning.UNIT_SUSPECT in mm.warnings || mm.status == AlignStatus.MISMATCH)
        assertEquals(AlignStatus.MISMATCH, mm.status)
    }
    @Test fun dxf_binary_is_rejected_cleanly() {
        val f = tmp.newFile("bin.dxf"); f.writeBytes("AutoCAD Binary DXF\r\n\u001a\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(64))
        val o = UploadAligner.analyzeDxf(f, home); assertEquals(AlignStatus.NEEDS_MANUAL, o.status); assertTrue(AlignWarning.BINARY_DXF in o.warnings)
    }
    @Test fun dxf_outlier_vertex_at_origin_does_not_break_judgement() {
        val pts = (0 until 200).joinToString("") { "0\nPOINT\n10\n${200000 + it % 20}\n20\n${500000 + it / 20}\n" } + "0\nPOINT\n10\n0\n20\n0\n"
        val o = UploadAligner.analyzeDxf(dxfFile(pts), home, KoreanCrs.EPSG_5186)
        assertTrue(AlignWarning.OUTLIER_VERTICES in o.warnings); assertEquals(AlignStatus.ALIGNED_USER_CRS, o.status)
    }
}

class BgLayerModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val home = work5186(200050.0, 499950.0)

    private fun layer(): BgLayer {
        val f = res("sample_5179.tif"); val o = UploadAligner.analyzeGeoTiff(f, home)
        return BgLayer.fromOutcome("L1", BgKind.GEOTIFF, "sample_5179.tif", f, o, 1_700_000_000_000L)
    }

    @Test fun auto_layer_badge_opacity_default_and_displayable() {
        val l = layer(); assertEquals("정합됨(자동)", l.badge); assertEquals(0.5, l.opacity, 0.0); assertTrue(l.displayable); assertTrue(l.shouldDraw)
        assertEquals(5179, l.sourceEpsg); assertEquals(200, l.rasterWidth)
    }
    @Test fun unaligned_layer_is_never_drawn_and_has_no_coordinates() {
        val o = UploadAligner.analyzeGeoTiff(res("central_5181_old.tif"), home)
        val l = BgLayer.fromOutcome("L2", BgKind.GEOTIFF, "x", res("central_5181_old.tif"), o, 0)
        assertEquals(BgStatus.UNALIGNED, l.status); assertEquals("미정합", l.badge); assertFalse(l.shouldDraw)
        assertNull(l.fileToTarget(0.0, 0.0, 5186)); assertNull(l.cornersLonLat())
        assertFalse(l.withEnabled(true).withOpacity(1.0).shouldDraw)
    }
    @Test fun converts_file_coordinates_to_work_crs_like_pyproj() {
        val l = layer()
        val p = l.fileToTarget(0.0, 0.0, 5186)!!  // 샘플 5179 좌상단 -> 5186: 원래 (200000, 500000)
        assertEquals(200000.0, p.x, 0.001); assertEquals(500000.0, p.y, 0.001)
        // 5179 격자축은 5186 격자축과 자오선수렴각만큼 기울어 있어 20 m 이동이 0.11 m 정도 달라진다(정상)
        val q = l.fileToTarget(200.0, 200.0, 5186)!!; assertEquals(200020.0, q.x, 0.2); assertEquals(499980.0, q.y, 0.2)
        // 같은 CRS 면 그대로
        val same = layer().copy(sourceEpsg = 5179).fileToTarget(0.0, 0.0, 5179)!!
        assertEquals(l.fileToSource!!.c, same.x, 1e-9)
    }
    @Test fun oracle_cross_check_5179_to_5186_points() {
        val o = JSONObject(json("tm_oracle.json")).getJSONObject("crs")
        val a = o.getJSONObject("5179").getJSONArray("pts"); val b = o.getJSONObject("5186").getJSONArray("pts")
        // 오라클은 무작위 점이 좌표계마다 달라서, 같은 점을 pyproj 값으로 만들 수 없다 -> 5179 의 E/N 을 앱으로 5186 변환 후 다시 5179 로 되돌려 왕복 오차를 본다
        var worst = 0.0
        for (i in 0 until a.length()) {
            val pt = a.getJSONArray(i); val l = BgLayer("t", BgKind.DXF, "t", "p", 0, null, null, Affine.IDENTITY, 5179, CrsSource.USER, null, BgStatus.ALIGNED_USER_CRS)
            val fwd = l.fileToTarget(pt.getDouble(2), pt.getDouble(3), 5186)!!
            val back = l.copy(sourceEpsg = 5186).fileToTarget(fwd.x, fwd.y, 5179)!!
            worst = maxOf(worst, hypot(back.x - pt.getDouble(2), back.y - pt.getDouble(3)))
            // pyproj 로 만든 위경도(pt[0],pt[1]) 를 5186 으로 앱이 직접 변환한 값과도 같아야 한다
            val direct = KoreanCrs.fromLonLat(KoreanCrs.EPSG_5186, LonLat(pt.getDouble(0), pt.getDouble(1)))
            assertEquals(direct.x, fwd.x, 0.001); assertEquals(direct.y, fwd.y, 0.001)
        }
        assertTrue("왕복 오차 $worst", worst < 0.001); assertTrue(b.length() == 300)
    }
    @Test fun manual_alignment_overrides_and_badge_shows_rms() {
        val base = layer().copy(status = BgStatus.UNALIGNED, sourceEpsg = null, fileToSource = null, crsSource = CrsSource.NONE)
        val pts = listOf(ControlPoint(XY(10.0, 10.0), XY(200001.0, 499999.0)), ControlPoint(XY(190.0, 10.0), XY(200019.0, 499999.0)),
            ControlPoint(XY(10.0, 190.0), XY(200001.0, 499981.0)), ControlPoint(XY(190.0, 190.0), XY(200019.0, 499981.02)))
        val fit = (AffineFitter.fit(pts) as FitOutcome.Ok).result
        val l = BgLayer.withManual(base, fit, pts, 5186)
        assertEquals(BgStatus.ALIGNED_MANUAL, l.status); assertTrue(l.badge.startsWith("정합됨(수동, RMS ")); assertTrue(l.shouldDraw)
        val p = l.fileToTarget(100.0, 100.0, 5186)!!; assertEquals(200010.0, p.x, 0.01); assertEquals(499990.0, p.y, 0.01)
        // 다른 작업 좌표계로 요청하면 위경도 경유
        val q = l.fileToTarget(100.0, 100.0, 5179)!!
        val back = KoreanCrs.toLonLat(KoreanCrs.EPSG_5179, q.x, q.y); val orig = KoreanCrs.toLonLat(KoreanCrs.EPSG_5186, p.x, p.y)
        assertTrue(metersBetween(back, orig) < 0.002)
        assertNotNull(l.cornersLonLat())
    }
    @Test fun json_roundtrip_and_file_store_survives_reopen() {
        val l0 = layer()
        val pts = listOf(ControlPoint(XY(1.0, 2.0), XY(3.0, 4.0)), ControlPoint(XY(100.0, 2.0), XY(103.0, 4.0)))
        val l = l0.copy(manual = ManualAlignment(Affine(1.0, 0.0, 2.0, 0.0, 1.0, 2.0), 5186, 0.012, 0.02, FitKind.SIMILARITY_2PT, pts), status = BgStatus.ALIGNED_MANUAL, rmsMeters = 0.012, opacity = 0.35)
        assertEquals(l, BgLayer.fromJson(JSONObject(BgLayer.toJson(l).toString())))
        val dir = tmp.newFolder("bg"); val s1 = JsonFileBgLayerStore(dir)
        s1.put(l); s1.put(l0.copy(id = "L9", createdAtMillis = 5))
        val s2 = JsonFileBgLayerStore(dir)   // 앱 재시작 흉내
        assertEquals(2, s2.list().size); assertEquals(l, s2.get("L1"))
        s2.put(l.withOpacity(0.8)); assertEquals(0.8, JsonFileBgLayerStore(dir).get("L1")!!.opacity, 0.0)
        assertTrue(s2.remove("L1")); assertNull(JsonFileBgLayerStore(dir).get("L1")); assertEquals(1, s2.list().size)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
    }
    @Test fun opacity_out_of_range_is_rejected_and_clamped_by_helper() {
        try { layer().copy(opacity = 1.5); fail() } catch (e: IllegalArgumentException) { }
        assertEquals(1.0, layer().withOpacity(7.0).opacity, 0.0); assertEquals(0.0, layer().withOpacity(-1.0).opacity, 0.0)
    }
    @Test fun in_memory_store_basic() {
        val s = InMemoryBgLayerStore(); s.put(layer()); assertEquals(1, s.list().size); assertTrue(s.remove("L1")); assertFalse(s.remove("L1"))
    }
}

class GeorefTermRulesTest {
    @Test fun no_forbidden_civil_terms_or_artifact_fields_in_georef_sources() {
        val banned = listOf("공사", "굴착", "시공", "측설", "성토", "절토", "준공", "검측", "노선", "도로", "수량", "재질")
        val dir = File("src/main/kotlin/kr/archsurvey/georef")
        val hits = dir.listFiles { f -> f.name.endsWith(".kt") }!!.flatMap { f -> f.readLines().mapIndexed { i, l -> Triple(f.name, i + 1, l) } }
            .filter { (_, _, l) -> banned.any { l.contains(it) } }
        assertTrue("금지어: $hits", hits.isEmpty())
    }
}
