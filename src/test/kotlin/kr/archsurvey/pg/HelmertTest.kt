package kr.archsurvey.pg

import kr.archsurvey.pg.geo.Helmert
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.geo.quatToM3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HelmertTest {
    private val q = doubleArrayOf(0.8, 0.1, -0.5, 0.3).let { a -> val n = Math.sqrt(a.sumOf { it * it }); a.map { it / n } }
    private val R = quatToM3(q[0], q[1], q[2], q[3])
    private val s = 1.0213; private val t = V3(245100.123, 512300.456, 57.89)

    @Test fun exact() {
        val src = mapOf(1 to V3(0.0, 0.0, 0.0), 2 to V3(4.0, 0.1, 0.2), 3 to V3(3.5, -0.2, 3.1), 4 to V3(0.2, 0.3, 2.9), 5 to V3(2.0, 0.5, 1.5))
        val dst = src.mapValues { R * it.value * s + t }
        val f = Helmert.fit(src, dst)!!
        assertEquals(s, f.tf.s, 1e-9)
        assertTrue(f.rmse < 1e-6)
        assertTrue(f.looRmse!! < 1e-6)
        val p = V3(1.0, 2.0, 3.0); val d = f.tf.apply(p) - (R * p * s + t)
        assertTrue(d.norm() < 1e-6)
    }

    @Test fun noisy_residuals_and_loo() {
        val rnd = Random(3)
        val src = (1..6).associateWith { V3(rnd.nextDouble() * 10, rnd.nextDouble() * 0.5, rnd.nextDouble() * 8) }
        val dst = src.mapValues { R * it.value * s + t + V3(rnd.nextGaussian() * 0.01, rnd.nextGaussian() * 0.01, rnd.nextGaussian() * 0.01) }
        val f = Helmert.fit(src, dst)!!
        assertTrue("rmse ${f.rmse}", f.rmse in 0.003..0.03)
        assertTrue("loo ${f.looRmse} ≥ rmse", f.looRmse!! >= f.rmse)
        assertEquals(6, f.residuals.size); assertEquals(6, f.looResiduals.size)
    }

    @Test fun needs3_and_not_collinear() {
        assertNull(Helmert.fit(mapOf(1 to V3.ZERO, 2 to V3(1.0, 0.0, 0.0)), mapOf(1 to V3.ZERO, 2 to V3(1.0, 0.0, 0.0))))
        val line = (1..4).associateWith { V3(it.toDouble(), 0.0, 0.0) }
        assertNull(Helmert.fit(line, line))
    }
}
