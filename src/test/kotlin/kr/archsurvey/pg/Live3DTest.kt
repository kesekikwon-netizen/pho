package kr.archsurvey.pg

import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.live.Coverage
import kr.archsurvey.pg.live.DepthSampler
import kr.archsurvey.pg.live.DepthVoxels
import kr.archsurvey.pg.live.FeatureCloud
import kr.archsurvey.pg.live.GpuSlots
import kr.archsurvey.pg.live.SpeedGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.0.5 설계도 11 S1~S4 의 순수 계산 부분 */
class Live3DTest {
    private val I = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    @Test fun s2_depthSampler_plane_bestConfidence_and_filters() {
        val w = 159; val h = 120; val fx = 120f
        // 1.5 m 앞 평면, 신뢰도: 칸 안 한 화소만 255, 나머지 120
        val o = DepthSampler.sample(w, h, { _, _ -> 1500 }, { x, y -> if (x % 3 == 1 && y % 3 == 1) 255 else 120 }, fx, fx, 80f, 60f, I, grid = 3)
        assertEquals(53 * 40, o.n)
        for (i in 0 until o.n) { assertEquals(-1.5f, o.xyz[3 * i + 2], 1e-4f); assertEquals(1f, o.conf[i], 1e-6f); assertEquals(1, o.u[i] % 3) }
        // 가운데 화소 → 광축 위
        val c = (0 until o.n).first { o.u[it] == 79 && o.v[it] == 58 }
        assertEquals((79 - 80f) / fx * 1.5f, o.xyz[3 * c], 1e-5f); assertEquals(-(58 - 60f) / fx * 1.5f, o.xyz[3 * c + 1], 1e-5f)
        // 신뢰도 낮음(<0.4) · 거리 밖(0.2 m, 5 m) · 0(없음)
        assertEquals(0, DepthSampler.sample(w, h, { _, _ -> 1500 }, { _, _ -> 90 }, fx, fx, 80f, 60f, I).n)
        assertEquals(0, DepthSampler.sample(w, h, { x, _ -> if (x < 80) 200 else 5000 }, null, fx, fx, 80f, 60f, I).n)
        assertEquals(0, DepthSampler.sample(w, h, { _, _ -> 0 }, null, fx, fx, 80f, 60f, I).n)
        // 자세(세계 이동 +1 m x)
        val T = I.copyOf().also { it[12] = 1f }
        val o2 = DepthSampler.sample(w, h, { _, _ -> 1000 }, null, fx, fx, 80f, 60f, T)
        assertTrue(o2.xyz[0] > 0.3f)
    }

    @Test fun s2_voxels_2cm_dedupe_exportConfidence_eviction() {
        val v = DepthVoxels(0.02, cap = 100)
        repeat(10) { v.add(0.001f * it, 0f, 0f, 1f, 0f, 0f, 0.5f) } // 한 칸
        assertEquals(1, v.size); assertEquals(10, v.count(0))
        v.add(0.5f, 0f, 0f, 0f, 1f, 0f, 0.9f)
        assertEquals(6, v.export(0.4f).size / 1 / 1 - 6) // 두 점 × 6
        assertEquals(1, v.export(0.66f).size / 6)        // 0.66 이상은 하나
        for (i in 0 until 500) v.add(1f + i * 0.03f, 0f, 0f, 0f, 0f, 1f, 0.5f)
        assertTrue(v.size <= 100); assertTrue(v.coarsenings >= 1)
        val s0 = v.size; v.add(Float.NaN, 0f, 0f, 1f, 1f, 1f, 1f); assertEquals(s0, v.size)
    }

    @Test fun s2_featureCloud_dedupe_by_id_and_lru_eviction() {
        val f = FeatureCloud(cap = 3)
        f.put(7, 0f, 0f, 0f, 1f, 0f, 0f); f.put(7, 0.1f, 0f, 0f, 1f, 0f, 0f)
        assertEquals(1, f.size)
        f.put(8, 0f, 0f, 0f, 0f, 0f, 0f); f.put(9, 0f, 0f, 0f, 0f, 0f, 0f); f.put(7, 0.2f, 0f, 0f, 1f, 0f, 0f) // 7 최근
        f.put(10, 5f, 0f, 0f, 0f, 0f, 0f) // 8 이 가장 오래됨 → 그 칸 재사용
        assertEquals(3, f.size); assertEquals(3, f.gpu.size)
        assertTrue((0 until 3).any { f.gpu.data[it * 7] == 5f }); assertTrue((0 until 3).any { f.gpu.data[it * 7] == 0.2f })
    }

    @Test fun s2_gpuSlots_dirty_subrange_only() {
        val g = GpuSlots(1000)
        for (i in 0 until 500) g.put(i, 1f, 2f, 3f, 0f, 0f, 0f)
        val d = g.takeDirty()!!; assertEquals(0, d.first); assertEquals(500, d.count)
        g.put(120, 9f, 9f, 9f, 1f, 1f, 1f); g.put(130, 9f, 9f, 9f, 1f, 1f, 1f)
        val d2 = g.takeDirty()!!; assertEquals(120, d2.first); assertEquals(11, d2.count); assertEquals(500, d2.total); assertEquals(9f, d2.buf.get(0))
        g.markAll(); assertEquals(500, g.takeDirty()!!.count)
    }

    @Test fun s1_motion_poseJump_and_gap_do_not_trigger_slow() {
        val g = SpeedGate(); val q = floatArrayOf(0f, 0f, 0f, 1f)
        var t = 0L; var x = 0f
        // 0.3 m/s 걷다가 한 프레임에 1 m 튐(재위치) → 4 m/s 같은 값이 나오면 안 됨
        repeat(60) { t += 33_333_333; x += 0.01f; g.feed(t, floatArrayOf(x, 0f, 0f), q); assertFalse(g.slow(t)) }
        t += 33_333_333; x += 1.0f; g.feed(t, floatArrayOf(x, 0f, 0f), q)
        repeat(30) { t += 33_333_333; x += 0.01f; g.feed(t, floatArrayOf(x, 0f, 0f), q); assertFalse("jump 후 move=${g.move}", g.slow(t)) }
        assertEquals(1, g.jumps); assertTrue(g.move < 0.6)
        // 0.5초 끊김 뒤 다른 곳 → 새로 시작
        t += 500_000_000; x += 0.5f; g.feed(t, floatArrayOf(x, 0f, 0f), q); assertEquals(1, g.gaps); assertFalse(g.slow(t))
        // 정지 → move < 0.02
        repeat(30) { t += 33_333_333; g.feed(t, floatArrayOf(x, 0f, 0f), q) }
        assertTrue(g.move < 0.02)
    }

    @Test fun s4_coverage_three_views_within_15deg() {
        fun d(deg: Double) = V3(Math.sin(Math.toRadians(deg)), Math.cos(Math.toRadians(deg)), 0.0)
        assertEquals(3, Coverage.linked15(listOf(d(0.0), d(10.0), d(20.0))))
        assertEquals(0, Coverage.linked15(listOf(d(0.0), d(30.0), d(60.0))))
        assertEquals(2, Coverage.linked15(listOf(d(0.0), d(5.0), d(60.0))))
    }
}
