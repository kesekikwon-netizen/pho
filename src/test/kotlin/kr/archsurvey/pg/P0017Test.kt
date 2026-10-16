package kr.archsurvey.pg

import kr.archsurvey.pg.data.HeightRef
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.export.ProjectJson
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.r10.GeoCam
import kr.archsurvey.pg.r10.GeoTag
import kr.archsurvey.pg.r10.Lever
import kr.archsurvey.pg.r10.LeverArm
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin

/** 0.0.17: 구간(piecewise) 맞춤 · 높이 기준 · project.json */
class P0017Test {
    private val h = P0014Test()

    /** 192 m 지그재그 걸음, AR 높이가 걸은 거리에 비례해 떠오름(0.5 mm/m) → 전체 맞춤은 XYZ 못 함, 70 m 구간은 됨 */
    private fun longWalk(n: Int = 240, driftPerM: Double = 0.0005): Pair<Session, Map<Int, V3>> {
        val lever = Lever(up = 1.6)
        val stills = ArrayList<Still>(); val truth = HashMap<Int, V3>()
        val (e0, n0) = GeoTag.en(h.tag(h.lat0, h.lon0, 0.0, true), 5186)
        for (i in 0 until n) {
            val x = i * 0.8; val z = -2.0 * sin(i / 5.0)
            val cam = V3(x, 1.4, z)
            val (pose, _, _) = h.tilted(25.0, 0.0, V3(x, 1.4 + driftPerM * x, z))
            val ant = cam + LeverArm.worldOffset(pose, 90, lever)
            val antEnu = LeverArm.toEnu(ant, 0.0); val camEnu = LeverArm.toEnu(cam, 0.0)
            val (la, lo) = h.llOf(antEnu.x, antEnu.y)
            val g = h.tag(la, lo, 50.0 + antEnu.z, true).copy(geoidSep = 25.0)
            truth[i + 1] = V3(e0 + camEnu.x, n0 + camEnu.y, 50.0 + camEnu.z)
            stills += h.still(i + 1, pose, g, i * 1000L)
        }
        return Session("long", 0L, stills).copy(lever = lever, sensorDeg = 90) to truth
    }

    @Test fun segmentsCover70mWith20mOverlap() {
        val (s, _) = longWalk()
        val ep = s.stills.map { st -> st.gnss!!.let { g -> kr.archsurvey.pg.r10.Epoch(st.time, g.lat, g.lon, g.altMsl, g.quality, g.fixed, g.sigmaH, g.sigmaU) } }
        val seg = GeoCam.segments(ep, 5186)
        assertTrue("${seg.size}", seg.size >= 3)
        for (k in 1 until seg.size) assertTrue("overlap", seg[k].first < seg[k - 1].second)
        assertEquals(ep.first().tMs, seg.first().first); assertEquals(ep.last().tMs, seg.last().second)
        // 짧으면 구간 없음
        assertTrue(GeoCam.segments(ep.take(40), 5186).isEmpty())
    }

    @Test fun piecewiseFitBeatsSingleFitOnDrift() {
        val (s, truth) = longWalk()
        val g = GeoCam.compute(s)
        val whole = g.fits[""]!!
        assertNotEquals(GeoCam.report(whole), GeoCam.Tier.XYZ, whole.tier)
        val segFits = g.fits.filterKeys { it.contains('#') }
        assertTrue(GeoCam.report(g), segFits.isNotEmpty() && segFits.values.all { it.tier == GeoCam.Tier.XYZ && it.rmse!! <= GeoCam.GOOD })
        val xyz = g.cams.values.count { it.tier == GeoCam.Tier.XYZ }
        assertTrue("xyz $xyz", xyz >= 230)
        for ((id, c) in g.cams) if (c.tier == GeoCam.Tier.XYZ) {
            val t = truth[id]!!
            assertTrue("cam $id h", abs(c.h!! - t.z) < 0.03); assertTrue("cam $id en", abs(c.e - t.x) < 0.02 && abs(c.n - t.y) < 0.02)
        }
    }

    @Test fun gatesStillApplyPerSegment() {
        // 정지(움직임 3 m 미만) 걸음은 구간도 없고 맞춤도 없음 → 안테나값(ANT)만
        val (s, _) = longWalk(n = 30, driftPerM = 0.0)
        val still = s.copy(stills = s.stills.map { it.copy(gnss = it.gnss!!.copy(lat = s.stills[0].gnss!!.lat, lon = s.stills[0].gnss!!.lon)) })
        val g = GeoCam.compute(still)
        assertTrue(g.fits.keys.none { it.contains('#') })
        assertTrue(g.cams.values.all { it.tier == GeoCam.Tier.ANT })
    }

    @Test fun heightReferenceEllipsoidAddsGeoid() {
        val (s, _) = longWalk(n = 30, driftPerM = 0.0)
        val g = GeoCam.compute(s)
        val e = ExportFolder.withHeightRef(s.copy(heightRef = HeightRef.ELLIPSOID), g)
        for ((id, c) in g.cams) { assertEquals(c.h!! + 25.0, e.cams[id]!!.h!!, 1e-9); assertEquals(HeightRef.ELLIPSOID, e.cams[id]!!.hRef) }
        assertSame(g, ExportFolder.withHeightRef(s, g)) // 기본 MSL = 그대로
        // 지오이드고 없으면 MSL 유지
        val noN = s.copy(heightRef = HeightRef.ELLIPSOID, stills = s.stills.map { it.copy(gnss = it.gnss!!.copy(geoidSep = null)) })
        assertTrue(ExportFolder.withHeightRef(noN, GeoCam.compute(noN)).cams.values.all { it.hRef == "MSL" })
        assertTrue(kr.archsurvey.pg.r10.GeoExif.xmp(null, true, e.cams[1]).contains("pg:Height=\"ELLIPSOID\""))
    }

    @Test fun sessionRoundTripKeepsHeightRefAndGeoid() {
        val (s, _) = longWalk(n = 3)
        val r = Session.fromJson(JSONObject(s.copy(heightRef = HeightRef.ELLIPSOID).toJson().toString()))
        assertEquals(HeightRef.ELLIPSOID, r.heightRef); assertEquals(25.0, r.stills[0].gnss!!.geoidSep!!, 0.0)
        assertEquals(HeightRef.MSL, Session.fromJson(JSONObject("{\"name\":\"a\",\"created\":0}")).heightRef)
    }

    @Test fun exportWritesProjectJson() {
        val (s, _) = longWalk(n = 40, driftPerM = 0.0)
        val tmp = Files.createTempDirectory("p17").toFile(); val src = File(tmp, "x/photos").apply { mkdirs() }
        s.stills.forEach { File(src, it.file).writeBytes(byteArrayOf(1)) }
        ExportFolder.write(s.copy(heightRef = HeightRef.ELLIPSOID), src, File(tmp, "o"))
        val j = JSONObject(File(tmp, "o/" + ProjectJson.FILE).readText())
        assertEquals(ProjectJson.FORMAT, j.getString("format")); assertEquals(40, j.getJSONObject("images").getInt("count"))
        assertEquals("ELLIPSOID", j.getJSONObject("crs").getString("height"))
        assertTrue(j.getJSONObject("r10").getJSONArray("fits").length() >= 1)
        assertFalse(j.toString().contains("password", true))
    }
}
