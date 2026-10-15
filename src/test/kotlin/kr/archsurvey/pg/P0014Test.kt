package kr.archsurvey.pg

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.export.PoseLog
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.r10.GeoCam
import kr.archsurvey.pg.r10.GeoTag
import kr.archsurvey.pg.r10.GnssTag
import kr.archsurvey.pg.r10.Lever
import kr.archsurvey.pg.r10.LeverArm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 0.0.14 R10(선택): 레버암(가상 기울기), 방위, 사진 위치, 내보내기, R10 없음 = 예전 그대로 */
class P0014Test {
    private fun near(a: V3, b: V3, tol: Double = 1e-9) = assertTrue("$a != $b", (a - b).norm() < tol)

    /** 폰 몸체 축(오른쪽·위·화면 밖, 세계)으로 ARCore 카메라 자세를 만든다(센서 90°) */
    internal fun poseFor(right: V3, up: V3, out: V3, sensorDeg: Int = 90, t: V3 = V3(0.0, 0.0, 0.0)): DoubleArray {
        val a = Math.toRadians(sensorDeg.toDouble())
        val cx = right * cos(a) - up * sin(a); val cy = right * sin(a) + up * cos(a); val cz = out
        val m = arrayOf(doubleArrayOf(cx.x, cy.x, cz.x), doubleArrayOf(cx.y, cy.y, cz.y), doubleArrayOf(cx.z, cy.z, cz.z))
        val tr = m[0][0] + m[1][1] + m[2][2]
        val q = DoubleArray(4) // w,x,y,z
        if (tr > 0) { val s = sqrt(tr + 1) * 2; q[0] = 0.25 * s; q[1] = (m[2][1] - m[1][2]) / s; q[2] = (m[0][2] - m[2][0]) / s; q[3] = (m[1][0] - m[0][1]) / s }
        else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) { val s = sqrt(1 + m[0][0] - m[1][1] - m[2][2]) * 2; q[0] = (m[2][1] - m[1][2]) / s; q[1] = 0.25 * s; q[2] = (m[0][1] + m[1][0]) / s; q[3] = (m[0][2] + m[2][0]) / s }
        else if (m[1][1] > m[2][2]) { val s = sqrt(1 + m[1][1] - m[0][0] - m[2][2]) * 2; q[0] = (m[0][2] - m[2][0]) / s; q[1] = (m[0][1] + m[1][0]) / s; q[2] = 0.25 * s; q[3] = (m[1][2] + m[2][1]) / s }
        else { val s = sqrt(1 + m[2][2] - m[0][0] - m[1][1]) * 2; q[0] = (m[1][0] - m[0][1]) / s; q[1] = (m[0][2] + m[2][0]) / s; q[2] = (m[1][2] + m[2][1]) / s; q[3] = 0.25 * s }
        return doubleArrayOf(t.x, t.y, t.z, q[1], q[2], q[3], q[0])
    }

    /** 정북을 보고 앞으로 pitch, 옆으로 roll 기울인 폰(세계: x=동, y=위, z=남) */
    internal fun tilted(pitchDeg: Double, rollDeg: Double, t: V3 = V3(0.0, 0.0, 0.0)): Triple<DoubleArray, V3, V3> {
        val p = Math.toRadians(pitchDeg); val r = Math.toRadians(rollDeg)
        var right = V3(1.0, 0.0, 0.0); var up = V3(0.0, 1.0, 0.0); var out = V3(0.0, 0.0, 1.0)
        // pitch: 위쪽이 북(−z)으로 기욺 — x 축 회전
        fun rx(v: V3) = V3(v.x, v.y * cos(p) - v.z * sin(p), v.y * sin(p) + v.z * cos(p))
        right = rx(right); up = rx(up); out = rx(out)
        // roll: 화면 밖 축 회전
        val r2 = right * cos(r) + up * sin(r); val u2 = up * cos(r) - right * sin(r)
        return Triple(poseFor(r2, u2, out, t = t), u2, out)
    }

    @Test fun leverUprightAndSyntheticTilts() {
        val (pose0, _, _) = tilted(0.0, 0.0)
        val (r, u, o) = LeverArm.deviceAxes(pose0, 90)
        near(r, V3(1.0, 0.0, 0.0)); near(u, V3(0.0, 1.0, 0.0)); near(o, V3(0.0, 0.0, 1.0))
        val l = Lever(up = 1.6, fwd = 0.10, right = -0.05)
        near(LeverArm.worldOffset(pose0, 90, l), V3(-0.05, 1.6, -0.10)) // 앞 = 카메라 쪽 = 북(−z)
        for (pitch in listOf(-45.0, -20.0, 0.0, 15.0, 30.0, 60.0)) for (roll in listOf(-30.0, 0.0, 10.0, 25.0)) {
            val (pose, upW, _) = tilted(pitch, roll)
            val w = LeverArm.worldOffset(pose, 90, Lever(up = 1.6))
            assertEquals(1.6, w.norm(), 1e-9)               // 길이 그대로
            near(w, upW * 1.6)
            assertEquals(1.6 * cos(Math.toRadians(pitch)) * cos(Math.toRadians(roll)), w.y, 1e-9) // 높이 성분
        }
        // 30° 앞으로 숙임: 1.6 m 폴 → 위 1.386, 북 0.8
        val (p30, _, _) = tilted(30.0, 0.0)
        val enu = LeverArm.toEnu(LeverArm.worldOffset(p30, 90, Lever(up = 1.6)), 0.0)
        near(enu, V3(0.0, -0.8, 1.6 * cos(PI / 6)), 1e-9) // 위쪽이 남(사용자 쪽)으로 기욺
    }

    @Test fun headingRecovered() {
        for (hDeg in listOf(-170.0, -35.0, 0.0, 12.5, 90.0, 179.0)) {
            val h = Math.toRadians(hDeg)
            val a = listOf(V3(0.0, 0.0, 0.0), V3(3.0, 0.2, -1.0), V3(-2.0, 0.1, -4.0), V3(5.0, -0.3, 2.0))
            val b = a.map { LeverArm.toEnu(it, h) + V3(200000.0, 550000.0, 30.0) }
            assertEquals(h, LeverArm.heading(a, b)!!, 1e-9)
        }
        assertNull(LeverArm.heading(listOf(V3(0.0, 0.0, 0.0), V3(0.1, 0.0, 0.0)), listOf(V3(0.0, 0.0, 0.0), V3(0.1, 0.0, 0.0))))
    }

    internal fun tag(lat: Double, lon: Double, alt: Double, fix: Boolean, sh: Double = 0.012) = GnssTag(lat, lon, alt, if (fix) "RTK_FIXED" else "RTK_FLOAT", fix, if (fix) sh else 0.4, 0.02, 300, 24)
    internal fun still(id: Int, pose: DoubleArray?, g: GnssTag?, t: Long = 0L) = Still(id, "IMG_%04d.jpg".format(id), 4000, 3000, 3000.0, 3000.0, 2000.0, 1500.0, pose, t, emptyList(), "manual", gnss = g, speed = 0.02, tMs = t)

    internal val lat0 = 37.5; internal val lon0 = 127.0
    internal fun llOf(e: Double, n: Double): Pair<Double, Double> {
        val (e0, n0) = GeoTag.en(tag(lat0, lon0, 0.0, true), 5186)
        var la = lat0; var lo = lon0
        repeat(6) { val (e1, n1) = GeoTag.en(tag(la, lo, 0.0, true), 5186); val r = GeoTag.shiftLatLon(la, lo, e0 + e - e1, n0 + n - n1); la = r.first; lo = r.second }
        return la to lo
    }

    /** 가상 현장: 방위 40°, 폰 25° 숙임 + 걷는 방향으로 돌아감, 레버암. 원 모양(지름 4 m)으로 30장 */
    internal fun scene(lever: Lever, n: Int = 30, noise: Double = 0.0, badEvery: Int = 0, lagMs: Long = 0, outlierAt: Int = -1): Triple<Session, Map<Int, V3>, Pair<List<kr.archsurvey.pg.r10.Epoch>, List<kr.archsurvey.pg.r10.ArSample>>> {
        val h = Math.toRadians(40.0)
        val rnd = java.util.Random(7)
        val stills = ArrayList<Still>(); val truth = HashMap<Int, V3>()
        val epochs = ArrayList<kr.archsurvey.pg.r10.Epoch>(); val track = ArrayList<kr.archsurvey.pg.r10.ArSample>()
        // AR 궤적 10 Hz, R10 1 Hz(지연 lagMs: R10 도착이 늦음)
        fun camAt(tMs: Long): Pair<V3, DoubleArray> {
            val a = tMs / 1000.0 / n * 2 * PI
            val cam = V3(2 * cos(a), 1.4 + 0.1 * sin(3 * a), -2 * sin(a))
            val (pose, _, _) = tilted(25.0, 10 * sin(a), cam)
            return cam to pose
        }
        val (e0, n0) = GeoTag.en(tag(lat0, lon0, 0.0, true), 5186)
        for (tMs in 0L..(n * 1000L) step 100) track += kr.archsurvey.pg.r10.ArSample(tMs, camAt(tMs).second)
        for (i in 0 until n) {
            val tMs = i * 1000L + 500
            val (cam, pose) = camAt(tMs)
            val ant = cam + LeverArm.worldOffset(pose, 90, lever)
            val antEnu = LeverArm.toEnu(ant, h); val camEnu = LeverArm.toEnu(cam, h)
            val nz = if (noise > 0) V3(rnd.nextGaussian() * noise, rnd.nextGaussian() * noise, rnd.nextGaussian() * noise) else V3(0.0, 0.0, 0.0)
            val out = if (i == outlierAt) V3(0.5, 0.0, 0.0) else V3(0.0, 0.0, 0.0)
            val (la, lo) = llOf(antEnu.x + nz.x + out.x, antEnu.y + nz.y)
            val fix = badEvery == 0 || i % badEvery != 0
            val g = tag(la, lo, 50.0 + antEnu.z + nz.z, fix)
            epochs += kr.archsurvey.pg.r10.Epoch(tMs + lagMs, la, lo, g.altMsl, g.quality, fix, g.sigmaH, g.sigmaU)
            truth[i + 1] = V3(e0 + camEnu.x, n0 + camEnu.y, 50.0 + camEnu.z)
            stills += still(i + 1, pose, g, tMs)
        }
        return Triple(Session("r10", 0L, stills).copy(lever = lever, sensorDeg = 90), truth, epochs to track)
    }

    @Test fun fitRecoversCameraCentresWithLeverAfterFit() {
        val (s, truth, _) = scene(Lever(up = 1.6, fwd = 0.05, right = 0.03))
        val g = GeoCam.compute(s)
        assertEquals(GeoCam.report(g.fit), GeoCam.Tier.XYZ, g.fit!!.tier); assertEquals(5186, g.epsg)
        assertEquals(40.0, Math.toDegrees(g.headingRad!!), 1e-3)
        assertEquals(30, g.cams.size)
        for ((id, c) in g.cams) { val t = truth[id]!!; assertTrue("cam $id", abs(c.e - t.x) < 2e-3 && abs(c.n - t.y) < 2e-3 && abs(c.h!! - t.z) < 2e-3) }
    }

    @Test fun apcConstantSeparateFromLever() {
        val (s, truth, _) = scene(Lever(up = 1.65))
        val g = GeoCam.compute(s.copy(lever = Lever(up = 1.60), apcM = 0.05))
        for ((id, c) in g.cams) assertEquals(truth[id]!!.z, c.h!!, 2e-3)
    }

    @Test fun lagEstimatedFromRawLogs() {
        val (s, truth, logs) = scene(Lever(up = 1.6), lagMs = 230)
        val g = GeoCam.compute(s, logs.first, logs.second)
        assertEquals(-230.0, g.fit!!.lagMs.toDouble(), 20.0)
        assertEquals(GeoCam.Tier.XYZ, g.fit!!.tier)
        for ((id, c) in g.cams) assertEquals(truth[id]!!.x, c.e, 0.01)
    }

    @Test fun tiersAndGates() {
        assertEquals(GeoCam.Tier.XY, GeoCam.compute(scene(Lever(), noise = 0.02).first).fit!!.tier)   // ~3.5 cm
        assertEquals(GeoCam.Tier.NONE, GeoCam.compute(scene(Lever(), noise = 0.05).first).fit!!.tier) // 그 이상 → 좌표 없음
        assertTrue(GeoCam.compute(scene(Lever(), noise = 0.05).first).cams.values.all { it.tier == GeoCam.Tier.ANT }) // 0.0.15: 맞춤 실패 → 안테나값
        val few = GeoCam.compute(scene(Lever(), n = 15).first); assertEquals(GeoCam.Tier.NONE, few.fit!!.tier); assertTrue(few.fit!!.reason.contains("20"))
        // 3σ 이상치 하나: 버리고 XYZ
        val o = GeoCam.compute(scene(Lever(), n = 40, noise = 0.003, outlierAt = 5).first).fit!!
        assertEquals(GeoCam.Tier.XYZ, o.tier); assertEquals(39, o.used)
        // FIX 아님·σ>2cm 은 표본 아님
        val nf = GeoCam.compute(scene(Lever(), n = 30, badEvery = 2).first).fit!!; assertEquals(15, nf.n)
        assertEquals(GeoCam.Tier.NONE, nf.tier)
    }

    @Test fun straightLineRejected() {
        val st = (0 until 30).map { i -> val cam = V3(i * 0.2, 1.4, 0.0); val (la, lo) = llOf(cam.x, 0.0)
            still(i + 1, tilted(0.0, 0.0, cam).first, tag(la, lo, 51.4, true), i * 1000L) }
        val f = GeoCam.compute(Session("line", 0L, st)).fit!!
        assertEquals(GeoCam.Tier.NONE, f.tier); assertTrue(f.reason.contains("한 줄"))
    }

    @Test fun poseLogColumnsAndSpeed() {
        val (s, _, _) = scene(Lever(), badEvery = 3)
        val g = GeoCam.compute(s); val csv = PoseLog.csv(s, g).lines()
        for (c in listOf("r10_fix", "cam_e", "fit_tier", "lag_ms", "speed_mps")) assertTrue(PoseLog.HEADER.contains(c))
        assertEquals(PoseLog.HEADER.split(',').size, csv[1].split(',').size)
        assertTrue(csv[1].contains("RTK_FLOAT"))
    }

    @Test fun gcpStatsBoth() {
        val st = kr.archsurvey.pg.r10.GcpStats.of(listOf(Triple(0.0, 0.0, 10.0), Triple(0.02, 0.0, 10.02), Triple(0.01, 0.0, 10.01)), listOf(0.01, 0.012), listOf(0.02))
        assertEquals(0.01, st.sdH!!, 1e-12); assertEquals(0.01, st.sdU!!, 1e-12); assertEquals(0.011, st.sigH!!, 1e-12); assertEquals(0.02, st.sigU!!, 1e-12)
        val g = kr.archsurvey.pg.data.Gcp(1, 1.0, 2.0, 3.0, 0.01, 0.02, 0.011, 0.02, 10)
        val b = Session.fromJson(org.json.JSONObject(Session("g", 0L, gcps = listOf(g)).toJson().toString()))
        assertEquals(g, b.gcps[0])
    }

    @Test fun sessionJsonRoundTrip() {
        val (s0, _, _) = scene(Lever(0.3, 0.1, -0.2)); val s = s0.copy(apcM = 0.0472)
        val b = Session.fromJson(org.json.JSONObject(s.toJson().toString()))
        assertEquals(s.lever, b.lever); assertEquals(90, b.sensorDeg); assertEquals(0.0472, b.apcM, 0.0)
        assertEquals(s.stills.map { it.speed to it.tMs }, b.stills.map { it.speed to it.tMs })
        assertEquals(s.stills.map { it.gnss }, b.stills.map { it.gnss })
        val old = Session.fromJson(org.json.JSONObject(Session("old", 0L).toJson().toString().replace("\"lever\"", "\"x_lever\"")))
        assertEquals(Lever(), old.lever)
    }

    @Test fun exportR10IsPriorOnlyGcpAuthority() {
        val (s, _, _) = scene(Lever(up = 1.6))
        val tmp = Files.createTempDirectory("p14").toFile(); val src = File(tmp, "src").apply { mkdirs() }
        s.stills.forEach { File(src, it.file).writeBytes(byteArrayOf(1, 2, 3)) }
        val out = File(tmp, "o"); ExportFolder.write(s, src, out)
        val xml = java.util.zip.ZipInputStream(File(out, "block.xmlz").inputStream()).use { it.nextEntry; String(it.readBytes()) }
        assertTrue(!xml.contains("EPSG:"))                 // 기준점 없으면 SRS 없음(R10 은 보조값)
        assertEquals(30, Regex("<GPS>").findAll(xml).count()) // 사진별 GPS 보조값
        assertTrue(!xml.contains("<Center>") || xml.indexOf("<Center>") > 0)
        val info = File(out, "작업정보.txt").readText(); assertTrue(info.contains("R10") && info.contains("XYZ") && info.contains("기준점이 정함"))
    }

    @Test fun noR10UnchangedPath() {
        val s = Session("plain", 0L, listOf(still(1, null, null), still(2, null, null)))
        val g = GeoCam.compute(s)
        assertTrue(g.cams.isEmpty()); assertNull(g.headingRad)
        val tmp = Files.createTempDirectory("p14b").toFile(); val src = File(tmp, "src").apply { mkdirs() }
        s.stills.forEach { File(src, it.file).writeBytes(byteArrayOf(1)) }
        val out = File(tmp, "o"); ExportFolder.write(s, src, out)
        val xml = java.util.zip.ZipInputStream(File(out, "block.xmlz").inputStream()).use { it.nextEntry; String(it.readBytes()) }
        assertTrue(!xml.contains("EPSG:") && !xml.contains("<GPS>"))
        assertTrue(!File(out, "작업정보.txt").readText().contains("R10"))
        assertTrue(!File(out, "r10_raw.csv").exists())
    }

    @Test fun rawLogsRoundTripAndExported() {
        val (s, _, logs) = scene(Lever(up = 1.6), lagMs = 120)
        val tmp = Files.createTempDirectory("p14c").toFile(); val ses = File(tmp, "r10").apply { mkdirs() }; val src = File(ses, "photos").apply { mkdirs() }
        s.stills.forEach { File(src, it.file).writeBytes(byteArrayOf(1)) }
        File(ses, kr.archsurvey.pg.r10.RawLogs.R10).writeText(kr.archsurvey.pg.r10.RawLogs.R10_HEADER + "\n" + logs.first.joinToString("\n") { kr.archsurvey.pg.r10.RawLogs.r10Line(it.copy(raw = "\$GPGGA,x")) } + "\n")
        File(ses, kr.archsurvey.pg.r10.RawLogs.AR).writeText(kr.archsurvey.pg.r10.RawLogs.AR_HEADER + "\n" + logs.second.joinToString("\n") { kr.archsurvey.pg.r10.RawLogs.arLine(it) } + "\n")
        assertEquals(logs.first.size, kr.archsurvey.pg.r10.RawLogs.readR10(File(ses, "r10_raw.csv")).size)
        val out = File(tmp, "o"); ExportFolder.write(s, src, out)
        assertTrue(File(out, "r10_raw.csv").readText().contains("GPGGA"))
        assertTrue(File(out, "ar_track.csv").isFile)
        assertTrue(File(out, "작업정보.txt").readText().contains("지연 -12"))
    }
}
