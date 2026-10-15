package kr.archsurvey.pg

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.geo.quatToM3
import kr.archsurvey.pg.live.Coverage
import kr.archsurvey.pg.live.LiveEngine
import kr.archsurvey.pg.tag.TagDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 뜨거운 경로 측정(box CPU, x86 — 폰과 절대값은 다르고 상대 비교용) */
class PerfTest {
    private fun settle(e: LiveEngine) { val l = CountDownLatch(1); e.post { l.countDown() }; l.await(60, TimeUnit.SECONDS) }

    @Test fun meshUpdate_and_coverage() {
        val e = LiveEngine(Session("p", 0L))
        // 10 × 10 m 발굴면 5 cm = 4만 칸
        val n = 200 * 200; val pts = FloatArray(n * 3)
        for (i in 0 until n) { pts[3 * i] = (i % 200) * 0.05f + 0.01f; pts[3 * i + 1] = 0.02f * (i % 7); pts[3 * i + 2] = (i / 200) * 0.05f + 0.01f }
        repeat(2) { e.addPoints(pts, n) }; settle(e)
        repeat(3) { e.benchMesh() }
        val ms = (1..5).map { e.benchMesh() }.average()
        val m = e.mesh!!
        val bytes = (m.triCount * 3 + m.ptCount) * 28L
        println("PERF mesh 40k 칸: %.1f ms · 삼각형 ${m.triCount} · GPU 버퍼 ${bytes / 1024} KB".format(ms))
        val cam = Coverage.Cam(V3(5.0, 1.6, 5.0), quatToM3(Math.cos(Math.PI / 4), -Math.sin(Math.PI / 4), 0.0, 0.0), 1500.0, 1500.0, 960.0, 720.0, 1920, 1440)
        val t = System.nanoTime(); repeat(10) { e.cov.addStill(100 + it, cam) }
        val cms = (System.nanoTime() - t) / 1e6 / 10
        println("PERF coverage 사진 1장(1만 칸 면): %.1f ms".format(cms))
        assertTrue(ms < 400); assertTrue(cms < 400)
        e.close()
    }

    @Test fun tagDetect_decimate_tradeoff() {
        val dir = File(TestData.root, "testdata/synth")
        val files = (1..16).map { File(dir, "IMG_%04d.jpg".format(it)) }.map { TestData.gray(it) }
        for (dec in listOf(1f, 1.5f, 2f, 3f, 4f)) {
            TagDetector.detect(files[0].first, files[0].second, files[0].third, decimate = dec)
            val t = System.nanoTime(); var found = 0
            for ((g, w, h) in files) found += TagDetector.detect(g, w, h, decimate = dec).size
            println("PERF tag detect 2000×1500 decimate=$dec: %.1f ms/장 · 찾은 표적 $found".format((System.nanoTime() - t) / 1e6 / files.size))
        }
    }
}
