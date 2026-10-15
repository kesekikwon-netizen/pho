package kr.archsurvey.pg

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.SessionFiles
import kr.archsurvey.pg.export.BlocksExchange
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.live.CloudPick
import kr.archsurvey.pg.live.GlMath
import kr.archsurvey.pg.live.OrbitView
import kr.archsurvey.pg.live.TouchOrbit
import kr.archsurvey.pg.r10.ArSample
import kr.archsurvey.pg.r10.ClockCheck
import kr.archsurvey.pg.r10.GeoCam
import kr.archsurvey.pg.r10.GeoExif
import kr.archsurvey.pg.r10.Lever
import kr.archsurvey.pg.r10.RawLogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 0.0.15: 손가락 점군 보기(카메라 수학·손가락 상태) + 설계 20 고침(세션별 맞춤, 맞춤 실패 GPS, 저널, 시계 점검, YPR) */
class P0015Test {
    private val h = P0014Test()

    private fun vp(o: OrbitView, aspect: Float = 0.5f, radius: Double = 3.0): FloatArray {
        val v = FloatArray(16); val p = FloatArray(16); val m = FloatArray(16)
        o.viewMatrix(v); o.projMatrix(p, aspect, radius); GlMath.mul(m, p, v); return m
    }
    private fun dist(a: FloatArray, b: FloatArray) = Math.sqrt(((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])).toDouble())

    @Test fun beginKeepsEyeAndPivotCentred() {
        val o = OrbitView(); val eye = floatArrayOf(0.3f, 1.4f, 2.0f); val piv = floatArrayOf(0f, 0.2f, -0.5f)
        o.begin(eye, piv, Math.toRadians(60.0))
        assertTrue(o.free); assertTrue(dist(o.eye(), eye) < 1e-4)
        val s = GlMath.project(vp(o), piv[0], piv[1], piv[2])
        assertEquals(0f, s[0], 1e-5f); assertEquals(0f, s[1], 1e-5f); assertTrue(s[2] > 0)
    }

    @Test fun orbitKeepsDistanceAndClampsPitch() {
        val o = OrbitView(); o.begin(floatArrayOf(0f, 0f, 2f), floatArrayOf(0f, 0f, 0f), 1.0)
        o.orbit(300f, 0f); assertEquals(2.0, dist(o.eye(), o.pivot), 1e-5)
        o.orbit(0f, 100000f); assertTrue(o.pitch <= OrbitView.MAX_PITCH + 1e-9)
        o.orbit(0f, -200000f); assertTrue(o.pitch >= -OrbitView.MAX_PITCH - 1e-9)
        val s = GlMath.project(vp(o), 0f, 0f, 0f); assertEquals(0f, s[0], 1e-4f); assertEquals(0f, s[1], 1e-4f) // 위·아래 끝에서도 뒤집히지 않음
    }

    @Test fun zoomClampedAndNearFarNeverFlip() {
        val o = OrbitView(); o.begin(floatArrayOf(0f, 0f, 2f), floatArrayOf(0f, 0f, 0f), 1.0)
        repeat(100) { o.zoom(3f) }; assertEquals(OrbitView.MIN_DIST, o.dist, 1e-9)
        repeat(100) { o.zoom(0.2f) }; assertEquals(OrbitView.MAX_DIST, o.dist, 1e-9)
        o.zoom(0f); o.zoom(Float.NaN); assertEquals(OrbitView.MAX_DIST, o.dist, 1e-9)
        for (d in listOf(0.15, 0.5, 2.0, 10.0, 60.0)) for (r in listOf(0.1, 3.0, 1000.0)) {
            val o2 = OrbitView(); o2.begin(floatArrayOf(0f, 0f, d.toFloat()), floatArrayOf(0f, 0f, 0f), 1.0)
            val (n, f) = o2.nearFar(r)
            assertTrue("$d $r", n >= OrbitView.NEAR_MIN && f > n * 50 && n < d && f > d)
            val s = GlMath.project(vp(o2, radius = r), 0f, 0f, 0f); assertTrue(s[2] > 0) // 기준점은 늘 앞, 깊이 안
        }
    }

    @Test fun panFollowsFinger() {
        val o = OrbitView(); o.begin(floatArrayOf(0f, 0f, 2f), floatArrayOf(0f, 0f, 0f), Math.toRadians(60.0))
        val p0 = floatArrayOf(0f, 0f, 0f)
        o.pan(100f, 0f, 1000)
        val s = GlMath.project(vp(o, aspect = 1f), p0[0], p0[1], p0[2])
        assertTrue("오른쪽으로 끌면 장면도 오른쪽", s[0] > 0.15f && abs(s[1]) < 1e-4f)
        assertEquals(0.2f, s[0], 0.01f) // 100/1000 px × 2 = 0.2 NDC
        o.pan(0f, 100f, 1000); assertTrue(GlMath.project(vp(o, aspect = 1f), 0f, 0f, 0f)[1] < -0.15f) // 아래로 끌면 아래로
    }

    @Test fun touchStatesAndReset() {
        val o = OrbitView(); val t = TouchOrbit(o)
        var begun = 0
        t.onBegin = { _, _ -> begun++; o.begin(floatArrayOf(0f, 0f, 2f), floatArrayOf(0f, 0f, 0f), 1.0) }
        t.down(100f, 100f); t.move(listOf(103f to 102f), 1000)
        assertEquals(TouchOrbit.Mode.PENDING, t.mode); assertFalse(o.free); assertEquals(0, begun) // 살짝 닿음 = 조작 아님
        t.move(listOf(140f to 100f), 1000); assertEquals(TouchOrbit.Mode.ORBIT, t.mode); assertTrue(o.free); assertEquals(1, begun)
        val yaw = o.yaw; t.move(listOf(160f to 100f), 1000); assertTrue(o.yaw != yaw)
        t.up(); assertEquals(TouchOrbit.Mode.IDLE, t.mode); assertTrue(o.free) // 손 떼도 자유 보기 유지
        t.down(0f, 0f); t.move(listOf(0f to 0f, 100f to 0f), 1000); assertEquals(TouchOrbit.Mode.PINCH, t.mode)
        val d0 = o.dist; t.move(listOf(0f to 0f, 200f to 0f), 1000); assertEquals(d0 / 2, o.dist, 1e-6)
        t.move(listOf(10f to 0f), 1000); assertEquals(TouchOrbit.Mode.ORBIT, t.mode) // 두 손가락 → 한 손가락
        t.up(); assertEquals(1, begun)
        t.doubleTap(); assertFalse(o.free)
    }

    @Test fun pickTouchedOrCentre() {
        val o = OrbitView(); o.begin(floatArrayOf(0f, 0f, 3f), floatArrayOf(0f, 0f, 0f), Math.toRadians(60.0))
        val m = vp(o, aspect = 1f)
        val pts = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 0f, 0f, 1f, 1f, 1f, 1f)
        val near = CloudPick.pivot(pts, 2, FloatArray(0), 0, m, 1000, 1000, 500f, 500f)
        assertTrue(near.touched); assertEquals(0f, near.point[0], 0f)
        val far = CloudPick.pivot(pts, 2, FloatArray(0), 0, m, 1000, 1000, 10f, 10f)
        assertFalse(far.touched); assertEquals(0.5f, far.point[0], 1e-6f)
        assertFalse(CloudPick.pivot(FloatArray(0), 0, FloatArray(0), 0, m, 10, 10, 0f, 0f).touched)
    }

    // ---- 설계 20 ----

    /** 같은 걸음을 두 AR 세션(좌표계 30° 돌고 옮겨짐)으로 나눔 */
    private fun twoSessions(): Pair<Session, Map<Int, kr.archsurvey.pg.geo.V3>> {
        val (a, ta, _) = h.scene(Lever(up = 1.6))
        val (b, tb, _) = h.scene(Lever(up = 1.6))
        val ang = Math.toRadians(30.0); val c = cos(ang / 2); val sn = sin(ang / 2)
        val bs = b.stills.map { st ->
            val p = st.pose!!; val x = p[0]; val z = p[2]
            val ca = cos(ang); val sa = sin(ang)
            // y 축 회전 qy = (0, sn, 0, c) · q
            val qx = p[3]; val qy = p[4]; val qz = p[5]; val qw = p[6]
            val nq = doubleArrayOf(c * qx + sn * qz, c * qy + sn * qw, c * qz - sn * qx, c * qw - sn * qy)
            st.copy(id = st.id + 100, file = "IMG_%04d.jpg".format(st.id + 100), time = st.time + 100_000, arSid = "b",
                pose = doubleArrayOf(ca * x + sa * z + 5, p[1] - 0.3, -sa * x + ca * z + 2, nq[0], nq[1], nq[2], nq[3]))
        }
        val truth = ta + tb.mapKeys { it.key + 100 }
        return a.copy(stills = a.stills.map { it.copy(arSid = "a") } + bs) to truth
    }

    @Test fun perSessionFit() {
        val (s, truth) = twoSessions()
        val g = GeoCam.compute(s)
        assertEquals(2, g.fits.size); assertTrue(g.fits.values.all { it.tier == GeoCam.Tier.XYZ })
        assertEquals(60, g.cams.size)
        for ((id, c) in g.cams) { val t = truth[id]!!; assertTrue("cam $id", abs(c.e - t.x) < 3e-3 && abs(c.n - t.y) < 3e-3) }
        // 세션을 섞으면(예전 방식) 맞춤 실패 → 안테나값
        val mixed = GeoCam.compute(s.copy(stills = s.stills.map { it.copy(arSid = null) }))
        assertTrue(mixed.cams.values.all { it.tier == GeoCam.Tier.ANT })
        assertTrue(GeoCam.report(g).contains("AR 세션 a") && GeoCam.report(g).contains("AR 세션 b"))
    }

    @Test fun failedFitStillGivesBlockGpsConsistentWithExif() {
        val (s0, _, _) = h.scene(Lever(up = 1.6), n = 10) // 20개 미만 → 맞춤 없음
        val s = s0.copy(apcM = 0.05)
        val g = GeoCam.compute(s)
        assertEquals(GeoCam.Tier.NONE, g.fit!!.tier); assertEquals(10, g.cams.size)
        for (st in s.stills) {
            val c = g.cams[st.id]!!
            assertEquals(GeoCam.Tier.ANT, c.tier); assertEquals(st.gnss!!.lat, c.lat, 0.0); assertEquals(st.gnss!!.sigmaH!!, c.rmse!!, 0.0)
            val dU = kr.archsurvey.pg.r10.LeverArm.worldOffset(st.pose!!, 90, Lever(up = 1.65)).y
            assertEquals(st.gnss!!.altMsl!! - dU, c.h!!, 1e-9)
            assertNull(c.camYaw); assertTrue(c.camPitch != null)
        }
        val tmp = Files.createTempDirectory("p15").toFile(); val src = File(tmp, "x/photos").apply { mkdirs() }
        s.stills.forEach { File(src, it.file).writeBytes(byteArrayOf(1)) }
        val seen = HashMap<String, Triple<Double, Double, Double?>>()
        ExportFolder.exifGps = { f, c, _ -> seen[f.name] = Triple(c.lat, c.lon, c.h) }
        try { ExportFolder.write(s, src, File(tmp, "o")) } finally { ExportFolder.exifGps = null }
        val xml = java.util.zip.ZipInputStream(File(tmp, "o/block.xmlz").inputStream()).use { it.nextEntry; String(it.readBytes()) }
        assertEquals(10, Regex("<GPS>").findAll(xml).count())
        for (st in s.stills) { val c = g.cams[st.id]!!; assertTrue(xml.contains("<Latitude>" + BlocksExchange.num(c.lat, 9) + "</Latitude>")); assertEquals(Triple(c.lat, c.lon, c.h), seen[st.file]) }
        assertTrue(File(tmp, "o/작업정보.txt").readText().contains("ANT 10장"))
    }

    @Test fun journalRecoversPhotoAfterProcessDeath() {
        val tmp = Files.createTempDirectory("p15j").toFile(); val files = SessionFiles(tmp)
        val (s, _, _) = h.scene(Lever(), n = 3)
        val base = s.copy(name = "J", stills = s.stills.take(1).map { it.copy(arSid = "a") })
        s.stills.forEach { files.writePhoto("J", it.file, byteArrayOf(1, 2)) }
        files.save(base)
        files.journal("J", s.stills[1].copy(arSid = "a")); files.journal("J", s.stills[2].copy(arSid = "a"))
        // 여기서 앱이 죽음(2초 저장 전) → 다시 열면 3장
        val l = files.load("J").session
        assertEquals(listOf(1, 2, 3), l.stills.map { it.id }); assertEquals(s.stills[2].gnss, l.stills[2].gnss); assertEquals("a", l.stills[2].arSid)
        // 저장 뒤 저널에서 들어간 줄만 빠짐
        files.save(l.copy(stills = l.stills.take(2)))
        assertEquals(listOf(3), SessionFiles.readJournal(File(tmp, "J/" + SessionFiles.JOURNAL)).map { it.id })
        files.save(l); assertFalse(File(tmp, "J/" + SessionFiles.JOURNAL).exists())
        // 사진 파일 없는 저널 줄은 버림
        files.journal("J", s.stills[0].copy(id = 9, file = "IMG_0009.jpg")); assertEquals(3, files.load("J").session.stills.size)
    }

    @Test fun clockBaseAndArSidInTrack() {
        assertEquals("elapsedRealtime", ClockCheck.base(5_000_000_000L, 5_020_000_000L, 4_000_000_000L))
        assertTrue(ClockCheck.base(4_000_000_000L, 90_000_000_000L, 4_010_000_000L).startsWith("nanoTime"))
        assertTrue(ClockCheck.base(1L, 90_000_000_000L, 80_000_000_000L).startsWith("unknown"))
        val line = RawLogs.arLine(ArSample(123, doubleArrayOf(1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 1.0), "abc"))
        val f = File.createTempFile("artrack", ".csv"); f.writeText(RawLogs.AR_HEADER + "\n" + line + "\n1,1,2,3,0,0,0,1\n")
        val r = RawLogs.readAr(f); assertEquals("abc", r[0].sid); assertEquals("", r[1].sid) // 0.0.14 형식도 읽음
    }

    @Test fun realYawPitchRoll() {
        // 정북 보고 똑바로: Yaw 0, Pitch 0, Roll 0 (방위 0)
        val (p0, _, _) = h.tilted(0.0, 0.0)
        val a = GeoCam.ypr(p0, 90, 0.0); assertEquals(0.0, a.first!!, 1e-6); assertEquals(0.0, a.second, 1e-6); assertEquals(0.0, a.third, 1e-6)
        // 방위 40°(AR→ENU), 25° 숙임(위가 사용자 쪽 → 카메라는 위를 봄 +25°), roll 10°
        val (p1, _, _) = h.tilted(25.0, 10.0)
        val b = GeoCam.ypr(p1, 90, Math.toRadians(40.0))
        assertEquals(25.0, b.second, 1e-6); assertEquals(320.0, b.first!!, 1e-6) // AR 북이 동→북 쪽으로 40° 돌면 실제 방위 320°; assertEquals(10.0, abs(b.third), 1e-6)
        assertNull(GeoCam.ypr(p1, 90, null).first)
        val x = GeoExif.xmp(null, true, GeoCam.compute(h.scene(Lever()).first).cams[1])
        assertTrue(x.contains("Camera:Yaw=") && x.contains("Camera:Pitch=") && x.contains("Camera:Roll=") && x.contains("pg:FitYaw="))
        assertFalse(GeoExif.xmp(null, false, null, pitch = 1.0, roll = 2.0).contains("Camera:Yaw="))
    }
}
