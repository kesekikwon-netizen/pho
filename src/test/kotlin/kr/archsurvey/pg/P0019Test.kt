package kr.archsurvey.pg

import kr.archsurvey.bt.R10AutoOptions
import kr.archsurvey.bt.R10PortStore
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.pg.diag.ThermalPolicy
import kr.archsurvey.pg.live.DepthVoxels
import org.junit.Assert.*
import org.junit.Test

/** 0.0.19: 실기(10-16) 상태 0~2·피부 39 °C 인데 「뜨거워요」, 점군 성장 멈춤 · R10 설정 매번 보냄 */
class P0019Test {
    @Test fun headroomAloneNeverMeansHot() {
        assertEquals(2, ThermalPolicy.combine(0, 0.97f)); assertEquals(2, ThermalPolicy.combine(2, 1.2f))
        assertEquals(0, ThermalPolicy.combine(0, 0.5f)); assertEquals(3, ThermalPolicy.combine(3, Float.NaN))
        assertNull(ThermalPolicy.of(ThermalPolicy.combine(0, 0.97f)).message)
    }

    @Test fun realHeatOnlySlowsNeverStops() {
        for (st in 0..6) { val t = ThermalPolicy.of(st); assertTrue(t.autoShot); assertTrue("depth $st", t.depthMs > 0) }
        assertTrue(ThermalPolicy.of(4).message!!.contains("촬영은 계속"))
    }

    @Test fun voxelsCoarsenInsteadOfFreezing() {
        val v = DepthVoxels(0.02, cap = 1000)
        // 40 m 길이를 2 cm 간격으로 걸어감 = 2000 칸 > 상한
        for (i in 0 until 2000) v.add(i * 0.02f, 0f, 0f, 1f, 1f, 1f, 0.9f)
        assertTrue(v.size <= 1000); assertTrue(v.coarsenings >= 1); assertEquals(0L, v.evicted)
        // 시작과 끝 둘 다 남아 있음(옛 부분을 지우지 않음)
        val xs = (0 until v.gpu.size).map { v.gpu.data[it * 7] }
        assertTrue(xs.min() < 0.1f); assertTrue(xs.max() > 39.8f)
        // 계속 자람: 새 구역 추가 시 범위가 늘어남
        for (i in 0 until 500) v.add(40f + i * 0.02f, 0f, 0f, 1f, 1f, 1f, 0.9f)
        assertTrue((0 until v.gpu.size).maxOf { v.gpu.data[it * 7] } > 49.5f)
    }

    @Test fun r10ConfigNotSentOnEveryConnect() {
        val st = object : R10PortStore { override fun port(): Int? = null; override fun save(port: Int) {} }
        assertNull(R10AutoOptions.forProfile(DeviceProfiles.R10, st))
        R10AutoOptions.requestOnce(); assertNotNull(R10AutoOptions.forProfile(DeviceProfiles.R10, st))
        assertNull(R10AutoOptions.forProfile(DeviceProfiles.R10, st))
    }
}
