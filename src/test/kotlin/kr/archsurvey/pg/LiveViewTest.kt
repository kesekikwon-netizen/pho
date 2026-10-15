package kr.archsurvey.pg

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.live.ArRendererMath
import kr.archsurvey.pg.live.LiveEngine
import kr.archsurvey.pg.live.PointCloud
import kr.archsurvey.pg.live.SpeedGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LiveViewTest {
    private fun q(yawDeg: Double, pitchDeg: Double = 0.0): FloatArray {
        val y = Math.toRadians(yawDeg) / 2; val p = Math.toRadians(pitchDeg) / 2
        // yaw(Y) * pitch(X), x,y,z,w
        val cy = cos(y); val sy = sin(y); val cp = cos(p); val sp = sin(p)
        return floatArrayOf((cy * sp).toFloat(), (sy * cp).toFloat(), (-sy * sp).toFloat(), (cy * cp).toFloat())
    }

    /** 폰 손떨림 영상: 30 fps 카메라, 120 Hz 화면(LATEST_CAMERA_IMAGE → 같은 프레임 3~4번), 걷는 속도 0.3 m/s, 위치 ±4 mm · 각도 ±1.2° 떨림 */
    private fun walk(speed: Double, rotRate: Double, sec: Double, seed: Int = 1): List<Triple<Long, FloatArray, FloatArray>> {
        val r = java.util.Random(seed.toLong()); val out = ArrayList<Triple<Long, FloatArray, FloatArray>>()
        var camT = 0L; var t = 0L; var cur: Triple<Long, FloatArray, FloatArray>? = null
        while (t < (sec * 1e9).toLong()) {
            if (cur == null || t - camT >= 33_333_333L) {
                camT = t; val s = t / 1e9
                cur = Triple(t, floatArrayOf((speed * s + r.nextGaussian() * 0.004).toFloat(), (1.4 + r.nextGaussian() * 0.004).toFloat(), 0f),
                    q(rotRate * s + r.nextGaussian() * 1.2, -60 + r.nextGaussian() * 1.2))
            }
            out += cur; t += 8_333_333L
        }
        return out
    }

    @Test fun oldGate_falsePositive_reproduced_newGate_quiet() {
        val frames = walk(0.3, 10.0, 6.0)
        // 0.0.2 방식: 화면 프레임마다 uptime 간격으로 미분, 60°/s · 0.8 m/s
        var oldSlow = 0; var prev: Triple<Long, FloatArray, FloatArray>? = null
        for (f in frames) { prev?.let { p -> val dt = (f.first - p.first) / 1e9; if (ArRendererMath.dist(p.second, f.second) / dt > 0.8 || ArRendererMath.angDeg(p.third, f.third) / dt > 60) oldSlow++ }; prev = f }
        val g = SpeedGate(); var newSlow = 0
        for (f in frames) { g.feed(f.first, f.second, f.third); if (g.slow(f.first)) newSlow++ }
        println("old gate slow frames=$oldSlow/${frames.size}  new gate slow=$newSlow  last move=%.2f rot=%.1f".format(g.move, g.rot))
        assertTrue("0.0.2 게이트는 보통 걸음에도 「천천히」(재현)", oldSlow > frames.size / 10)
        assertEquals(0, newSlow)
    }

    @Test fun newGate_fires_on_real_fast_motion() {
        val g = SpeedGate(); var slow = false
        for (f in walk(1.8, 10.0, 2.0)) { g.feed(f.first, f.second, f.third); slow = slow || g.slow(f.first) }
        assertTrue("1.8 m/s", slow)
        val g2 = SpeedGate(); var slow2 = false
        for (f in walk(0.1, 150.0, 2.0)) { g2.feed(f.first, f.second, f.third); slow2 = slow2 || g2.slow(f.first) }
        assertTrue("150°/s 휘두름", slow2)
        // 멈추면 0.6초 뒤 풀림
        val stop = walk(0.0, 0.0, 2.0).map { Triple(it.first + 3_000_000_000L, it.second, it.third) }
        stop.forEach { g.feed(it.first, it.second, it.third) }
        assertFalse(g.slow(stop.last().first))
    }

    @Test fun pointCloud_voxel_color_cap_nan() {
        val pc = PointCloud(voxel = 0.015, cap = 1000)
        repeat(50) { pc.add(0.001f * (it % 10), 0f, 0f, 1f, 0f, 0f) } // 같은 1.5 cm 복셀
        assertEquals(1, pc.size); assertEquals(50, pc.count(0))
        pc.add(Float.NaN, 0f, 0f, 1f, 1f, 1f); pc.add(1e9f, 0f, 0f, 1f, 1f, 1f)
        assertEquals(1, pc.size); assertEquals(2L, pc.rejected)
        for (i in 0 until 3000) pc.add(i * 0.02f, 0f, 0f, 0f, 1f, 0f)
        assertEquals("상한", 1000, pc.size)
        val (buf, n) = pc.snapshot()
        assertEquals(1000, n); assertEquals(7000, buf.capacity())
        // 음수 좌표도 서로 다른 복셀
        val pc2 = PointCloud(); pc2.add(-0.001f, 0f, 0f, 0f, 0f, 0f); pc2.add(0.001f, 0f, 0f, 0f, 0f, 0f); assertEquals(2, pc2.size)
    }

    @Test fun engine_features_dedup_by_id_and_depth_voxels_visible() {
        val e = LiveEngine(Session("t", 0, emptyList(), emptyList()))
        val xyz = FloatArray(300) { if (it % 3 == 1) -1.4f else (it / 3) * 0.05f }
        val ids = IntArray(100) { it }
        repeat(5) { e.addFeatures(ids, xyz, null, 100) } // 같은 ID 5번 → 100점
        e.addDepth(xyz, FloatArray(300) { 0.5f }, FloatArray(100) { 0.9f }, 100, true)
        val l = java.util.concurrent.CountDownLatch(1); e.post { l.countDown() }; l.await()
        assertEquals(100, e.featureCount); assertEquals(100, e.voxelCount)
        val d = e.features.gpu.takeDirty()!!; assertEquals(100, d.total); assertEquals(0.85f, d.buf.get(3), 1e-6f)
        assertNull("두 번째는 바뀐 것 없음", e.features.gpu.takeDirty())
        e.close()
    }
}
