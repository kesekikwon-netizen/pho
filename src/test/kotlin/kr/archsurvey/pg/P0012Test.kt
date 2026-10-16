package kr.archsurvey.pg

import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.export.BlocksExchange
import kr.archsurvey.pg.export.CloudFile
import kr.archsurvey.pg.export.ExifLens
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.export.PoseLog
import kr.archsurvey.pg.export.Poses
import kr.archsurvey.pg.export.SiteFit
import kr.archsurvey.pg.geo.Helmert
import kr.archsurvey.pg.geo.M3
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.geo.quatToM3
import kr.archsurvey.pg.live.ShotPolicy
import kr.archsurvey.pg.live.ShotPolicy.Motion
import kr.archsurvey.pg.live.ShotPolicy.Reason
import kr.archsurvey.pg.tag.TagDetection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos
import kotlin.math.sin

class P0012Test {
    // ── 1 겹침 자동 ──
    private val sp = ShotPolicy.spacingM(1.0, 1500.0, 1920, 1440) // 바닥 0.96 m × 0.1 = 0.096 m

    @Test fun spacing_is_footprint_times_one_minus_overlap_and_min_depth() {
        assertEquals(1.0 * 1440 / 1500 * 0.1, sp, 1e-9)
        assertEquals(ShotPolicy.spacingM(0.25, 1500.0, 1920, 1440), ShotPolicy.spacingM(0.05, 1500.0, 1920, 1440), 1e-12)
    }
    @Test fun manual_always_fires_even_paused_or_stationary() {
        assertEquals(Reason.MANUAL, ShotPolicy.decide(true, true, false, true, 10, 9, Motion(0.0, 0.0), sp))
    }
    @Test fun only_storage_blocks() {
        assertNull(ShotPolicy.decide(true, false, true, false, 10_000, 0, Motion(9.0, 90.0), sp))
    }
    @Test fun overlap_distance_and_angle_fire_immediately() {
        assertEquals(Reason.FIRST, ShotPolicy.decide(false, false, true, true, 5, 0, null, sp))
        assertEquals(Reason.OVERLAP_DISTANCE, ShotPolicy.decide(false, false, true, true, 1300, 1000, Motion(0.1, 0.0), sp))
        assertEquals(Reason.OVERLAP_ANGLE, ShotPolicy.decide(false, false, true, true, 1300, 1000, Motion(0.0, 21.0), sp))
        assertNull(ShotPolicy.decide(false, false, true, true, 1300, 1000, Motion(0.05, 5.0), sp))
    }
    @Test fun timer_is_fallback_and_skips_only_stationary() {
        assertEquals(Reason.TIMER, ShotPolicy.decide(false, false, true, true, 4000, 1000, Motion(0.03, 0.0), sp))
        assertNull(ShotPolicy.decide(false, false, true, true, 60_000, 1000, Motion(0.001, 0.5), sp))
        assertEquals(Reason.NO_POSE_TIMER, ShotPolicy.decide(false, false, true, true, 4000, 1000, null, sp))
        assertNull(ShotPolicy.decide(false, true, true, true, 60_000, 1000, Motion(5.0, 50.0), sp))
    }
    @Test fun no_photo_count_or_distance_limit() {
        var last = 0L; var n = 0
        for (i in 1..5000) { val now = i * 2000L; if (ShotPolicy.decide(false, false, true, true, now, last, Motion(1000.0 + i, 0.0), sp) != null) { last = now; n++ } }
        assertEquals(5000, n)
    }

    // ── 3 EXIF ──
    @Test fun exif_focal_mm_and_plane_resolution_reproduce_fx() {
        val v = ExifLens.compute(1500.0, 1500.0, 1920, 1440, 6.4, 4.8, 1_700_000_000_123, "ab")
        assertEquals(1500.0, v.focalMm!! * v.planeXRes!! / 10.0, 1e-6)
        assertEquals("123", v.subSec)
        assertEquals(32, ExifLens.uniqueId("a", "IMG_0001.jpg", 1).length)
        assertTrue(v.focal35 in 20..40)
        assertNull(ExifLens.compute(1500.0, 1500.0, 1920, 1440, null, null, 0, "x").focalMm)
    }

    // ── 5 자세(iTwin XRightYDown, 세계→카메라) ──
    private fun quat(axis: V3, deg: Double): DoubleArray { val a = Math.toRadians(deg) / 2; val u = axis.unit(); return doubleArrayOf(u.x * sin(a), u.y * sin(a), u.z * sin(a), cos(a)) }
    private fun pose(c: V3, q: DoubleArray) = doubleArrayOf(c.x, c.y, c.z, q[0], q[1], q[2], q[3])
    /** ARCore(OpenGL) 투영: 우리 앱 ray() 의 역 */
    private fun projGl(p: DoubleArray, X: V3, f: Double, cx: Double, cy: Double): DoubleArray {
        val r = quatToM3(p[6], p[3], p[4], p[5]); val pc = r.transpose() * (X - V3(p[0], p[1], p[2]))
        return doubleArrayOf(cx + f * pc.x / -pc.z, cy - f * pc.y / -pc.z)
    }
    private fun projItwin(po: Poses.Pose, X: V3, f: Double, cx: Double, cy: Double): DoubleArray {
        val xc = po.rotation * (X - po.center); assertTrue("앞쪽", xc.z > 0)
        return doubleArrayOf(cx + f * xc.x / xc.z, cy + f * xc.y / xc.z)
    }

    @Test fun itwin_rotation_convention_matches_arcore_projection_local_and_epsg() {
        val p = pose(V3(0.3, 1.5, 2.0), quat(V3(1.0, 0.2, 0.1), -35.0))
        val Xs = listOf(V3(0.2, 0.0, 0.0), V3(0.5, 0.1, -0.4), V3(-0.3, 0.05, 0.3))
        val po = Poses.itwin(p, null)
        // 정규 직교 + det 1
        val m = Poses.mul(po.rotation, po.rotation.transpose()).a
        for (i in 0 until 3) for (j in 0 until 3) assertEquals(if (i == j) 1.0 else 0.0, m[3 * i + j], 1e-9)
        for (X in Xs) { val a = projGl(p, X, 1500.0, 960.0, 720.0); val b = projItwin(po, X, 1500.0, 960.0, 720.0); assertEquals(a[0], b[0], 1e-6); assertEquals(a[1], b[1], 1e-6) }
        // EPSG 변환(축척 1.03, Y 위 → Z 위 회전, 큰 이동)
        val rh = M3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0))
        val tf = Helmert(1.03, rh, V3(245000.0, 551000.0, 50.0))
        val pe = Poses.itwin(p, tf)
        for (X in Xs) { val a = projGl(p, X, 1500.0, 960.0, 720.0); val b = projItwin(pe, tf.apply(X), 1500.0, 960.0, 720.0); assertEquals(a[0], b[0], 1e-5); assertEquals(a[1], b[1], 1e-5) }
    }

    /** 장면: 표적 4개(로컬) + 사진 6장 */
    private fun scene(): Session {
        val tags = mapOf(1 to V3(0.0, 0.0, 0.0), 2 to V3(1.0, 0.0, 0.2), 3 to V3(0.2, 0.0, 1.1), 4 to V3(0.8, 0.05, 0.7))
        val stills = (0 until 6).map { i ->
            val c = V3(-0.5 + i * 0.4, 1.6, 2.2)
            // 아래·앞을 보게: x 축으로 −35°
            val p = pose(c, quat(V3(1.0, 0.0, 0.0), -35.0))
            val dets = tags.map { (no, X) -> val uv = projGl(p, X, 1500.0, 960.0, 720.0); TagDetection(no - 1, 0, uv[0], uv[1], DoubleArray(8) { k -> if (k % 2 == 0) uv[0] else uv[1] }) }
                .filter { it.cx in 0.0..1920.0 && it.cy in 0.0..1440.0 }
            Still(i + 1, "IMG_%04d.jpg".format(i + 1), 1920, 1440, 1500.0, 1500.0, 960.0, 720.0, p, 1000L * i, dets, "overlap_distance")
        }
        val rh = M3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0)); val tf = Helmert(1.0, rh, V3(245000.0, 551000.0, 50.0))
        val g = tags.map { (no, X) -> val e = tf.apply(X); Gcp(no, e.x, e.y, e.z + if (no == 4) 0.03 else 0.0) }
        return Session("현장A", 0, stills, g, 5187)
    }

    @Test fun fit_residuals_rmse_and_max_shown_as_info() {
        val s = scene()
        val f = Poses.fit(s); assertNotNull(f)
        val sum = SiteFit.summary(f!!)
        println("fit ${SiteFit.line(sum)} rows=${sum.rows}"); assertTrue(sum.rmseCm < 3.0); assertTrue(sum.maxCm > 0.5)
        assertEquals(4, sum.rows.size)
        assertTrue(SiteFit.line(sum).contains("최대 잔차"))
        assertTrue(SiteFit.text(f, 6, 6).contains("Target #"))
    }

    @Test fun block_has_pose_per_photo_in_epsg_and_projects_targets() {
        val s = scene()
        val poses = Poses.forExport(s); assertEquals(6, poses.size)
        val xml = BlocksExchange.write(s.stills, s.gcps, BlocksExchange.Options("현장A", 5187, poses = poses))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(6, doc.getElementsByTagName("Pose").length)
        assertEquals(6, doc.getElementsByTagName("M_22").length)
        val c = (doc.getElementsByTagName("Center").item(0) as org.w3c.dom.Element).getElementsByTagName("x").item(0).textContent.toDouble()
        assertTrue(c > 244000 && c < 246000)
        // 표적 1 을 EPSG 자세로 투영 → 검출 화소와 같음(1 cm 맞춤 오차 이내 → 몇 px)
        val g1 = s.gcps.first { it.target == 1 }
        val uv = projItwin(poses.getValue(1), V3(g1.e, g1.n, g1.h), 1500.0, 960.0, 720.0)
        val t = s.stills[0].tags.first { it.id == 0 }
        assertEquals(t.cx, uv[0], 15.0); assertEquals(t.cy, uv[1], 15.0)
    }

    @Test fun coords_but_no_fit_means_no_pose_never_wrong_frame() {
        val s = scene().let { it.copy(gcps = listOf(Gcp(77, 1.0, 2.0, 3.0), Gcp(78, 2.0, 2.0, 3.0), Gcp(79, 1.0, 3.0, 3.0))) }
        assertTrue(Poses.forExport(s).isEmpty())
        val local = scene().copy(gcps = emptyList())
        assertEquals(6, Poses.forExport(local).size)
    }

    // ── 4 자세 기록 ──
    @Test fun pose_log_csv_and_reason_roundtrip() {
        val s = scene()
        val csv = PoseLog.csv(s).split("\r\n").filter { it.isNotEmpty() }
        assertEquals(PoseLog.HEADER, csv[0]); assertEquals(7, csv.size)
        assertEquals(PoseLog.HEADER.split(',').size, csv[1].split(',').size)
        assertTrue(csv[1].contains("overlap_distance"))
        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals("overlap_distance", back.stills[0].reason)
        val old = JSONObject(s.toJson().toString()); old.getJSONArray("stills").getJSONObject(0).remove("reason")
        assertNull(Session.fromJson(old).stills[0].reason)
    }

    // ── 5 점군 ──
    @Test fun cloud_parts_merge_dedupe_and_export_ply_in_zip_folder() {
        val tmp = Files.createTempDirectory("pg12").toFile()
        try {
            val job = File(tmp, "현장A"); val photos = File(job, "photos").apply { mkdirs() }
            val s = scene()
            s.stills.forEach { File(photos, it.file).writeBytes(byteArrayOf(1, 2, 3)) }
            CloudFile.savePart(job, floatArrayOf(0.001f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0f, 0f, 0f, 1f, 0f), 1)
            CloudFile.savePart(job, floatArrayOf(0.005f, 0f, 0f, 0f, 0f, 1f, 1.0f, 0f, 0f, 0f, 0f, 1f), 2) // 첫 점은 같은 2cm 칸
            assertEquals(3, CloudFile.loadAll(job).size / 6)
            val out = File(tmp, "out")
            ExportFolder.write(s, photos, out)
            val ply = File(out, CloudFile.EXPORT).readLines()
            assertTrue(ply.contains("element vertex 3"))
            val first = ply[ply.indexOf("end_header") + 1].split(' ')
            assertTrue(first[0].toDouble() > 244000) // EPSG 로 옮김
            assertTrue(File(out, PoseLog.FILE).exists())
            assertTrue(File(out, "작업정보.txt").readText().contains("최대 잔차"))
            val xml = java.util.zip.ZipInputStream(File(out, "block.xmlz").inputStream()).use { it.nextEntry; it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(xml.contains("<Pose>"))
        } finally { tmp.deleteRecursively() }
    }
}
