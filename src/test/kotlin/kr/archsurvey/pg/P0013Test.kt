package kr.archsurvey.pg

import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.SessionFiles
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.export.BlocksExchange
import kr.archsurvey.pg.export.CloudFile
import kr.archsurvey.pg.export.ExifLens
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.export.PoseLog
import kr.archsurvey.pg.export.Poses
import kr.archsurvey.pg.geo.Helmert
import kr.archsurvey.pg.geo.M3
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.live.LiveEngine
import kr.archsurvey.pg.live.ShotPolicy
import kr.archsurvey.pg.tag.TagDetection
import kr.archsurvey.pg.tag.TagDetector
import kr.archsurvey.pg.tag.TagFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.0.13 독립 교차 검증: 앱 코드와 다른 계산 경로(교과서 식)로 참값을 만들고 앱 결과와 비교. 수치 오차를 출력한다.
 */
class P0013Test {
    private fun report(k: String, v: Double, tol: Double) { println("XCHECK %-28s err=%.3e tol=%.1e".format(k, v, tol)); assertTrue("$k err=$v", v <= tol) }

    // ── 1 장수 제한 없음: 2만 장 전체 경로 ──
    private fun settle(e: LiveEngine) { val l = CountDownLatch(1); e.post { l.countDown() }; assertTrue(l.await(600, TimeUnit.SECONDS)) }

    @Test fun stress_20000_shots_capture_save_poselog_export() {
        val tmp = Files.createTempDirectory("pg13").toFile()
        try {
            val files = SessionFiles(tmp)
            val name = "스트레스"
            files.save(Session(name, 0))
            val e = LiveEngine(files.load(name).session)
            var saves = 0
            val save: (Session) -> Unit = { saves++; files.save(it) }
            val N = 20_000
            val jpg = byteArrayOf(-1, -40, -1, -39)
            var last = 0L; var fired = 0
            val t0 = System.nanoTime()
            for (i in 1..N) {
                // 셔터 판정: 매번 이동 0.2 m (겹침 간격보다 큼) → 장수 상관없이 계속 찍혀야
                val now = i * 500L
                val r = ShotPolicy.decide(false, false, true, true, now, last, ShotPolicy.Motion(0.2, 0.0), 0.096)
                assertTrue("shot $i 막힘", r != null); last = now; fired++
                val file = String.format(java.util.Locale.US, "IMG_%04d.jpg", i)
                assertTrue(files.writePhoto(name, file, jpg))
                val pose = doubleArrayOf(i * 0.2, 1.5, 0.0, -0.3, 0.0, 0.0, 0.954)
                val tags = if (i % 50 == 0) listOf(TagDetection(i / 50 % 4, 0, 900.0, 700.0, DoubleArray(8) { 700.0 })) else emptyList()
                e.addStill(Still(i, file, 1920, 1440, 1500.0, 1500.0, 960.0, 720.0, pose, now, tags, "overlap_distance"), save)
            }
            settle(e)
            files.save(e.session)
            val capMs = (System.nanoTime() - t0) / 1e6
            val back = files.load(name).session
            assertEquals(N, back.stills.size); assertEquals(N, fired)
            assertEquals(N, back.stills.map { it.file }.toSet().size) // 이름 겹침 없음 (IMG_10000 이상도)
            assertTrue(back.stills.last().file == "IMG_20000.jpg")
            val csv = PoseLog.csv(back)
            assertEquals(N + 1, csv.split("\r\n").count { it.isNotEmpty() })
            val t1 = System.nanoTime()
            val out = File(tmp, "out")
            val r = ExportFolder.write(back, files.photos(name), out)
            val expMs = (System.nanoTime() - t1) / 1e6
            assertEquals(N, r.photos)
            assertEquals(N, out.resolve("photos").list()!!.size)
            val xml = java.util.zip.ZipInputStream(File(out, "block.xmlz").inputStream()).use { it.nextEntry; it.readBytes() }
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream())
            assertEquals(N, doc.getElementsByTagName("Photo").length)
            assertEquals(N, doc.getElementsByTagName("Pose").length)
            println("STRESS $N shots: capture+save ${"%.0f".format(capMs)} ms (session saves=$saves, 2s 묶음), export ${"%.0f".format(expMs)} ms")
            e.close()
        } finally { tmp.deleteRecursively() }
    }

    @Test fun manual_fires_when_paused_and_backlog_defers_only_auto() {
        assertEquals(ShotPolicy.Reason.MANUAL, ShotPolicy.decide(true, true, true, true, 10, 5, null, 0.1, backlog = 99))
        assertNull(ShotPolicy.decide(false, false, true, true, 10_000, 5, ShotPolicy.Motion(9.0, 0.0), 0.1, backlog = ShotPolicy.AUTO_BACKLOG))
        assertEquals(ShotPolicy.Reason.OVERLAP_DISTANCE, ShotPolicy.decide(false, false, true, true, 10_000, 5, ShotPolicy.Motion(9.0, 0.0), 0.1, backlog = ShotPolicy.AUTO_BACKLOG - 1))
    }

    // ── 2-a EXIF: 핀홀 교과서 식 fx = f_mm × W / 센서폭_mm, 35mm 환산 = f_mm × 43.267 / 대각_mm ──
    @Test fun xcheck_exif_focal_sensor() {
        val fMm = 5.4; val sw = 6.40; val W = 1920; val H = 1440; val sh = sw * H / W
        val fx = fMm * W / sw
        val v = ExifLens.compute(fx, fx, W, H, sw, sh, 0, "x")
        report("EXIF FocalLength mm", abs(v.focalMm!! - fMm), 1e-9)
        // EXIF 에 실제로 쓰는 유리수 문자열을 다시 읽어 fx 복원
        fun rat(s: String) = s.split('/').let { it[0].toDouble() / it[1].toDouble() }
        val fxBack = rat(ExifLens.rational(v.focalMm!!)) * rat(ExifLens.rational(v.planeXRes!!)) / 10.0
        report("EXIF fx from rationals (px)", abs(fxBack - fx), 1.0)
        val f35 = fMm * sqrt(36.0 * 36 + 24.0 * 24) / sqrt(sw * sw + sh * sh)
        report("EXIF 35mm equiv (mm)", abs(v.focal35 - f35), 0.5)
    }

    // ── 2-b 자세: look-at 으로 iTwin 행렬을 따로 만들고(사진측량 교과서: 행 = 카메라 축, X 오른쪽 Y 아래 Z 앞) 앱 결과와 비교 ──
    private fun lookAtItwin(c: V3, target: V3, up: V3): M3 {
        val z = (target - c).unit(); val x = z.cross(up).unit(); val y = z.cross(x)
        return M3(doubleArrayOf(x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z))
    }
    /** 같은 카메라를 ARCore(OpenGL) 사원수로: 열 = (x, up', −z) */
    private fun arcoreQuat(c: V3, target: V3, up: V3): DoubleArray {
        val f = (target - c).unit(); val x = f.cross(up).unit(); val u = x.cross(f)
        val m = doubleArrayOf(x.x, u.x, -f.x, x.y, u.y, -f.y, x.z, u.z, -f.z) // 카메라→세계
        val tr = m[0] + m[4] + m[8]
        val w: Double; val qx: Double; val qy: Double; val qz: Double
        if (tr > 0) { val s = sqrt(tr + 1) * 2; w = s / 4; qx = (m[7] - m[5]) / s; qy = (m[2] - m[6]) / s; qz = (m[3] - m[1]) / s }
        else if (m[0] > m[4] && m[0] > m[8]) { val s = sqrt(1 + m[0] - m[4] - m[8]) * 2; w = (m[7] - m[5]) / s; qx = s / 4; qy = (m[1] + m[3]) / s; qz = (m[2] + m[6]) / s }
        else if (m[4] > m[8]) { val s = sqrt(1 + m[4] - m[0] - m[8]) * 2; w = (m[2] - m[6]) / s; qx = (m[1] + m[3]) / s; qy = s / 4; qz = (m[5] + m[7]) / s }
        else { val s = sqrt(1 + m[8] - m[0] - m[4]) * 2; w = (m[3] - m[1]) / s; qx = (m[2] + m[6]) / s; qy = (m[5] + m[7]) / s; qz = s / 4 }
        return doubleArrayOf(c.x, c.y, c.z, qx, qy, qz, w)
    }

    @Test fun xcheck_pose_rotation_vs_lookat() {
        val rnd = Random(7); var worst = 0.0; var worstC = 0.0
        repeat(200) {
            val c = V3(rnd.nextGaussian() * 3, 1 + rnd.nextDouble() * 2, rnd.nextGaussian() * 3)
            val t = V3(rnd.nextGaussian(), 0.0, rnd.nextGaussian())
            val upY = V3(0.0, 1.0, 0.0)
            val p = arcoreQuat(c, t, upY)
            val mine = Poses.itwin(p, null)
            val ref = lookAtItwin(c, t, upY)
            worst = maxOf(worst, (0 until 9).maxOf { abs(mine.rotation.a[it] - ref.a[it]) })
            worstC = maxOf(worstC, (mine.center - c).norm())
        }
        report("Pose rotation (local) max|dM|", worst, 1e-9)
        report("Pose center (local) m", worstC, 1e-12)
        // EPSG: 로컬 Y 위 → E,N,H (Z 위). 같은 카메라를 EPSG 에서 직접 look-at, 위 = +H
        val rh = M3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0))
        val tf = Helmert(1.02, rh, V3(245000.0, 551000.0, 50.0))
        var w2 = 0.0; var c2 = 0.0
        repeat(200) {
            val c = V3(rnd.nextGaussian() * 3, 1 + rnd.nextDouble() * 2, rnd.nextGaussian() * 3)
            val t = V3(rnd.nextGaussian(), 0.0, rnd.nextGaussian())
            val mine = Poses.itwin(arcoreQuat(c, t, V3(0.0, 1.0, 0.0)), tf)
            val ref = lookAtItwin(tf.apply(c), tf.apply(t), V3(0.0, 0.0, 1.0))
            w2 = maxOf(w2, (0 until 9).maxOf { abs(mine.rotation.a[it] - ref.a[it]) })
            c2 = maxOf(c2, (mine.center - tf.apply(c)).norm())
        }
        report("Pose rotation (EPSG) max|dM|", w2, 1e-9)
        report("Pose center (EPSG) m", c2, 1e-6)
    }

    // ── 2-c Helmert: 참 변환 + 잡음, RMSE 를 손으로 다시 계산 ──
    @Test fun xcheck_gcp_fit_rmse() {
        val rnd = Random(3)
        val ang = Math.toRadians(23.0)
        val rz = M3(doubleArrayOf(cos(ang), -sin(ang), 0.0, sin(ang), cos(ang), 0.0, 0.0, 0.0, 1.0))
        val yUpToZ = M3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0))
        val R = Poses.mul(rz, yUpToZ); val s = 0.97; val t = V3(245123.4, 551987.6, 61.2)
        val src = (1..8).associateWith { V3(rnd.nextDouble() * 10, rnd.nextDouble() * 0.5, rnd.nextDouble() * 10) }
        val exact = src.mapValues { R * it.value * s + t }
        val f0 = Helmert.fit(src, exact)!!
        report("Helmert scale (no noise)", abs(f0.tf.s - s), 1e-9)
        report("Helmert t (no noise) m", (f0.tf.t - t).norm(), 1e-6)
        report("Helmert R (no noise)", (0 until 9).maxOf { abs(f0.tf.r.a[it] - R.a[it]) }, 1e-9)
        report("Helmert RMSE (no noise) m", f0.rmse, 1e-6)
        val noisy = exact.mapValues { it.value + V3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * 0.01 }
        val f = Helmert.fit(src, noisy)!!
        val hand = sqrt(src.keys.sumOf { k -> val d = noisy.getValue(k) - f.tf.apply(src.getValue(k)); d.dot(d) } / src.size)
        report("RMSE recomputed by hand m", abs(hand - f.rmse), 1e-9)
        val maxHand = src.keys.maxOf { k -> (noisy.getValue(k) - f.tf.apply(src.getValue(k))).norm() }
        val maxApp = kr.archsurvey.pg.export.SiteFit.summary(f).maxCm / 100
        report("max residual by hand m", abs(maxHand - maxApp), 1e-9)
        assertTrue("noise 1 cm/axis → RMSE about 1.4 cm", f.rmse in 0.005..0.025)
    }

    // ── 2-d AprilTag 화소: 2배 축소 그림에서 참값(화소 중심 관례) ──
    @Test fun xcheck_apriltag_pixel_convention_downscaled() {
        val (g, w, h) = TestData.gray(TestData.target(1))
        val w2 = w / 2; val h2 = h / 2
        val g2 = ByteArray(w2 * h2) { k -> val x = k % w2; val y = k / w2
            (((g[2 * y * w + 2 * x].toInt() and 255) + (g[2 * y * w + 2 * x + 1].toInt() and 255) + (g[(2 * y + 1) * w + 2 * x].toInt() and 255) + (g[(2 * y + 1) * w + 2 * x + 1].toInt() and 255)) / 4).toByte() }
        val d = TagDetector.detect(g2, w2, h2, TagFamily.TAG36H11, decimate = 1f, maxHamming = 0).single()
        // 원본 화소 102..901 → 중심(화소 중심 관례) 501.5 → 2배 축소: (501.5 − 0.5)/2 = 250.5
        report("AprilTag x (px, iTwin)", abs(d.cx + BlocksExchange.PIXEL_SHIFT - 250.5), 0.1)
        report("AprilTag y (px, iTwin)", abs(d.cy + BlocksExchange.PIXEL_SHIFT - 250.5), 0.1)
    }

    // ── 2-e EPSG 축 순서: x = 동(E), y = 북(N). 한국 TM 범위로 확인 ──
    @Test fun xcheck_epsg_axis_order() {
        val g = listOf(Gcp(1, 245100.0, 551200.0, 57.0), Gcp(2, 245110.0, 551205.0, 58.0), Gcp(3, 245104.0, 551215.0, 57.5))
        val st = Still(1, "IMG_0001.jpg", 1920, 1080, 1500.0, 1500.0, 960.0, 540.0, null, 0, listOf(TagDetection(0, 0, 100.0, 100.0, DoubleArray(8))))
        val xml = BlocksExchange.write(listOf(st), g, BlocksExchange.Options("a", 5186))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val pos = (doc.getElementsByTagName("Position").item(0) as org.w3c.dom.Element)
        val x = pos.getElementsByTagName("x").item(0).textContent.toDouble(); val y = pos.getElementsByTagName("y").item(0).textContent.toDouble()
        report("EPSG x=E", abs(x - 245100.0), 0.0); report("EPSG y=N", abs(y - 551200.0), 0.0)
        // EPSG:5186 동부원점 가산값 동 200000, 북 600000 → 남한 E 1.0e5~3.5e5, N 1.0e5~7.5e5 범위
        assertTrue(x in 1.0e5..3.5e5 && y in 1.0e5..7.5e5)
    }

    // ── 2-f PLY 좌표계: 표적 1 위치(로컬)에 둔 점이 PLY 에서 표적 1 의 GCP 와 같아야 ──
    @Test fun xcheck_ply_frame() {
        val tags = mapOf(1 to V3(0.0, 0.0, 0.0), 2 to V3(1.0, 0.0, 0.2), 3 to V3(0.2, 0.0, 1.1), 4 to V3(0.8, 0.05, 0.7))
        val rh = M3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0)); val tf = Helmert(1.0, rh, V3(245000.0, 551000.0, 50.0))
        val cams = (0 until 6).map { i -> arcoreQuat(V3(-0.5 + i * 0.4, 1.6, 2.2), V3(0.5, 0.0, 0.5), V3(0.0, 1.0, 0.0)) }
        val stills = cams.mapIndexed { i, p ->
            val dets = tags.map { (no, X) ->
                val r = kr.archsurvey.pg.geo.quatToM3(p[6], p[3], p[4], p[5]); val pc = r.transpose() * (X - V3(p[0], p[1], p[2]))
                val u = 960 + 1500 * pc.x / -pc.z; val v = 720 - 1500 * pc.y / -pc.z
                TagDetection(no - 1, 0, u, v, DoubleArray(8) { k -> if (k % 2 == 0) u else v }) }
            Still(i + 1, "IMG_%04d.jpg".format(i + 1), 1920, 1440, 1500.0, 1500.0, 960.0, 720.0, p, 0, dets)
        }
        val s = Session("ply", 0, stills, tags.map { (no, X) -> val e = tf.apply(X); Gcp(no, e.x, e.y, e.z) }, 5186)
        val tmp = Files.createTempDirectory("ply").toFile()
        try {
            val job = File(tmp, "ply"); val photos = File(job, "photos").apply { mkdirs() }
            stills.forEach { File(photos, it.file).writeBytes(byteArrayOf(1)) }
            val t1 = tags.getValue(1)
            // 칸 경계에 걸리지 않게 칸 중심 근처 점
            CloudFile.savePart(job, floatArrayOf(t1.x.toFloat() + 0.01f, t1.y.toFloat() + 0.01f, t1.z.toFloat() + 0.01f, 1f, 1f, 1f), 1)
            val out = File(tmp, "o"); ExportFolder.write(s, photos, out)
            val l = File(out, CloudFile.EXPORT).readLines(); val p = l[l.indexOf("end_header") + 1].split(' ').map { it.toDouble() }
            val g1 = s.gcps.first { it.target == 1 }
            val expect = tf.apply(V3(0.01, 0.01, 0.01))
            report("PLY point vs EPSG truth m", sqrt((p[0] - expect.x).let { it * it } + (p[1] - expect.y).let { it * it } + (p[2] - expect.z).let { it * it }), 0.002)
            assertTrue(abs(p[0] - g1.e) < 0.05 && abs(p[1] - g1.n) < 0.05 && abs(p[2] - g1.h) < 0.05)
        } finally { tmp.deleteRecursively() }
    }
}
