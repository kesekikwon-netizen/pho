package kr.archsurvey.pg

import kr.archsurvey.pg.export.BlocksExchange
import kr.archsurvey.pg.tag.AprilTagNative
import kr.archsurvey.pg.tag.TagDetector
import kr.archsurvey.pg.tag.TagFamily
import kr.archsurvey.pg.tag.TargetNaming
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** 사용자의 iTwin Capture 표적 그림 4장(Target #1..4, Library: AprilTags)을 진짜 AprilTag 3 C 코드(JNI)로 디코딩 */
class TagDecodeTest {
    @Test fun nativeLoads() = assertTrue("libpgapriltag(host) 로드", AprilTagNative.available())

    @Test fun itwinTargets_are_tag36h11_id_N_minus_1() {
        for (n in 1..4) {
            val (g, w, h) = TestData.gray(TestData.target(n))
            val d = TagDetector.detect(g, w, h, TagFamily.TAG36H11, decimate = 1f, maxHamming = 0)
            assertEquals("Target #$n 검출 1개", 1, d.size)
            assertEquals("Target #$n → tag36h11 id", n - 1, d[0].id)
            assertEquals(0, d[0].hamming)
            assertEquals("Target #$n", d[0].name)
            // 검은 네모 화소 102..901 → iTwin 화소 관례(왼쪽 위 화소 중심 = 0) 중심 501.5
            assertEquals(501.5, d[0].cx + BlocksExchange.PIXEL_SHIFT, 0.05)
            assertEquals(501.5, d[0].cy + BlocksExchange.PIXEL_SHIFT, 0.05)
            println("Target #$n → tag36h11 id=${d[0].id} ham=${d[0].hamming} centre=(${d[0].cx},${d[0].cy})")
        }
    }

    @Test fun naming() {
        assertEquals("Target #7", TargetNaming.name(6))
        assertEquals(7, TargetNaming.parseTargetNo("Target #7"))
        assertEquals(7, TargetNaming.parseTargetNo("AprilTag #7"))
        assertEquals(8, TargetNaming.parseTargetNo("TAG_36h11_07"))
    }

    /** 합성 사진(원근·흐림·잡음)에서 참값 화소와 1 px 안 */
    @Test fun synthPhotos_detect_at_truth() {
        val dir = File(TestData.root, "testdata/synth")
        val truth = JSONObject(File(dir, "truth.json").readText())
        val st = truth.getJSONArray("stills")
        var n = 0; var maxErr = 0.0; val errs = ArrayList<Double>()
        for (i in 0 until st.length()) {
            val s = st.getJSONObject(i)
            val (g, w, h) = TestData.gray(File(dir, s.getString("file")))
            val det = TagDetector.detect(g, w, h, decimate = 1.5f).associateBy { it.target }
            val marks = s.getJSONObject("marks")
            for (k in marks.keys()) {
                val m = marks.getJSONArray(k); val d = det[k.toInt()]
                val sz = m.getDouble(2); val edge = m.getDouble(0) < sz || m.getDouble(1) < sz || m.getDouble(0) > w - sz || m.getDouble(1) > h - sz
                if (d == null && (sz < 30 || edge)) { println("작거나 가장자리에 걸린 표적 놓침(허용): ${s.getString("file")} #$k ${"%.0f".format(m.getDouble(2))} px"); continue }
                assertTrue("사진 ${s.getString("file")} 에서 Target #$k", d != null)
                val e = maxOf(abs(d!!.cx - m.getDouble(0)), abs(d.cy - m.getDouble(1)))
                maxErr = maxOf(maxErr, e); errs += e; n++
            }
            assertTrue("거짓 번호 금지(가장자리에 걸친 표적만 추가 허용)", det.keys.all { it in 1..4 })
        }
        val med = errs.sorted()[errs.size / 2]
        println("합성 사진 표적 $n 개, 화소 오차 중앙값 ${"%.3f".format(med)} px · 최대 ${"%.3f".format(maxErr)} px")
        assertTrue(n >= 20); assertTrue(med < 0.3); assertTrue(maxErr < 0.5)
    }
}
