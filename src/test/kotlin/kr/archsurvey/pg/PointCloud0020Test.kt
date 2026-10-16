package kr.archsurvey.pg

import kr.archsurvey.pg.ar.LiveDepthPolicy
import kr.archsurvey.pg.live.DepthTarget
import kr.archsurvey.pg.live.DepthVoxels
import org.junit.Assert.*
import org.junit.Test

/** 0.0.20 점군(24-pointcloud-gap.md P1) */
class PointCloud0020Test {
    private val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    @Test fun displayKeepsRawPointsNoAveraging() {
        val v = DepthVoxels(0.02, cap = 50, exportCap = 50)
        v.add(0f, 0f, 0f, 1f, 1f, 1f, 0.9f); v.add(0.01f, 0f, 0f, 1f, 1f, 1f, 0.9f)
        assertEquals(2, v.gpu.size); assertEquals(0f, v.gpu.data[0]); assertEquals(0.01f, v.gpu.data[7])
    }

    @Test fun exportUsesMedianRejectingOutlier() {
        val v = DepthVoxels(0.02, cap = 50, exportCap = 50)
        v.add(0.005f, 0.005f, 0.005f, 1f, 1f, 1f, 0.9f); v.add(0.006f, 0.005f, 0.005f, 1f, 1f, 1f, 0.9f); v.add(0.019f, 0.005f, 0.005f, 1f, 1f, 1f, 0.9f)
        val e = v.export(0.66f); assertEquals(1, e.size / 6); assertEquals(0.006f, e[0], 1e-6f)
    }

    @Test fun fullBufferThinsRandomlyAndKeepsAcceptingPoints() {
        val v = DepthVoxels(0.02, cap = 10_000, exportCap = 10)
        for (i in 0 until 50_000) v.add(i * 0.001f, 0f, 0f, 1f, 1f, 1f, 0.5f)
        assertEquals(10_000, v.size); assertEquals(50_000L, v.total); assertEquals(40_000L, v.evicted)
        assertTrue(v.exportDropped > 0)
        // 옛 구역(앞 1/5)도 일부 남음(오래될수록 서서히 솎임)
        val old = (0 until v.size).count { v.gpu.data[it * 7] < 10f }
        assertTrue("old=$old", old in 50..5000)
    }

    @Test fun targetPrefersHighConfidenceThenFills() {
        val w = 160; val h = 90
        // 왼쪽 절반 신뢰도 255(1.0), 오른쪽 절반 120(0.47)
        val conf = { x: Int, _: Int -> if (x < 80) 255 else 120 }
        val d = { _: Int, _: Int -> 1500 }
        val o = DepthTarget.sample(w, h, d, conf, 100f, 100f, 80f, 45f, id, target = 3000)
        assertEquals(3000, o.n)
        assertTrue((0 until o.n).all { o.conf[it] >= 0.99f }) // 높은 것 7200개 중 3000개만
        val o2 = DepthTarget.sample(w, h, d, conf, 100f, 100f, 80f, 45f, id, target = 14000)
        assertTrue(o2.n > 7200); assertTrue((0 until o2.n).any { o2.conf[it] < 0.5f }) // 0.40 으로 채움
        val low = { _: Int, _: Int -> 60 } // 0.24 → 버림
        assertEquals(0, DepthTarget.sample(w, h, d, low, 100f, 100f, 80f, 45f, id, target = 7000).n)
    }

    @Test fun targetClampAndTimerRare() {
        assertEquals(3000, DepthTarget.clamp(100)); assertEquals(14000, DepthTarget.clamp(99_999)); assertEquals(7000, DepthTarget.DEFAULT)
        assertEquals(3000L, LiveDepthPolicy.timerMs(500)); assertEquals(7000, LiveDepthPolicy.target(true, 7000)); assertEquals(3000, LiveDepthPolicy.target(false, 7000))
    }
}
