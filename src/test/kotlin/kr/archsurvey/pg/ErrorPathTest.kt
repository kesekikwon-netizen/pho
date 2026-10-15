package kr.archsurvey.pg

import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.data.GcpTxt
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.SessionFiles
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.diag.FitCheck
import kr.archsurvey.pg.diag.Msg
import kr.archsurvey.pg.diag.StoragePolicy
import kr.archsurvey.pg.diag.ThermalPolicy
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.live.HeightField
import kr.archsurvey.pg.live.LiveEngine
import kr.archsurvey.pg.tag.AprilTagNative
import kr.archsurvey.pg.tag.TagDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 오류 경로마다: 죽지 않고, 한 줄 안내가 나오고, 복구되는지 */
class ErrorPathTest {
    private fun tmp() = Files.createTempDirectory("pg").toFile()
    private fun settle(e: LiveEngine) { val l = CountDownLatch(1); e.post { l.countDown() }; assertTrue(l.await(30, TimeUnit.SECONDS)) }

    @Test fun trackingMessages_lowLight_motion_features() {
        assertEquals("너무 어두워요 · 밝은 곳에서 찍어 주세요", Msg.tracking("INSUFFICIENT_LIGHT"))
        assertEquals("천천히 움직여 주세요", Msg.tracking("EXCESSIVE_MOTION"))
        assertTrue(Msg.tracking("INSUFFICIENT_FEATURES").startsWith("무늬가"))
        assertEquals(Msg.CAM_BUSY, Msg.tracking("CAMERA_UNAVAILABLE"))
        assertTrue(Msg.tracking(null).isNotBlank()); assertTrue(Msg.tracking("NEW_REASON_2030").isNotBlank())
    }

    @Test fun thermal_throttles_then_stops() {
        assertEquals(350L, ThermalPolicy.of(0).depthMs); assertTrue(ThermalPolicy.of(1).autoShot)
        assertEquals(700L, ThermalPolicy.of(2).depthMs)
        ThermalPolicy.of(3).let { assertFalse(it.autoShot); assertEquals(1500L, it.depthMs); assertNotNull(it.message) }
        ThermalPolicy.of(6).let { assertEquals(0L, it.depthMs); assertFalse(it.autoShot); assertNotNull(it.message) }
    }

    @Test fun fitCheck_lt3_unseen_collinear_ok() {
        val pos = mapOf(1 to V3.ZERO, 2 to V3(1.0, 0.0, 0.0), 3 to V3(2.0, 0.0, 0.0), 4 to V3(0.0, 0.0, 2.0))
        assertEquals(FitCheck.R.NO_GCP, FitCheck.check(emptyList(), pos).first)
        assertEquals(FitCheck.R.LT3, FitCheck.check(listOf(Gcp(1, 1.0, 1.0, 1.0), Gcp(2, 2.0, 1.0, 1.0)), pos).first)
        assertEquals(FitCheck.R.NOT_SEEN, FitCheck.check(listOf(Gcp(1, 0.0, 0.0, 0.0), Gcp(7, 5.0, 0.0, 0.0), Gcp(8, 0.0, 5.0, 0.0)), pos).first)
        val line = listOf(Gcp(1, 100.0, 200.0, 5.0), Gcp(2, 101.0, 200.0, 5.0), Gcp(3, 102.0, 200.001, 5.0))
        val (r, m) = FitCheck.check(line, pos); assertEquals(FitCheck.R.COLLINEAR, r); assertTrue(m!!.contains("한 줄"))
        assertEquals(FitCheck.R.OK, FitCheck.check(line.take(2) + Gcp(4, 100.0, 202.0, 5.1), pos).first)
    }

    @Test fun gcpTxt_bad_and_odd_files() {
        assertEquals("빈 파일이에요", GcpTxt.report("  \r\n").message)
        assertTrue(GcpTxt.report("PK\u0003\u0004\u0000\u0000xx").message.contains("TXT"))
        assertTrue(GcpTxt.report("이름,동,북,높이\r\nfoo bar\r\n").message.startsWith("좌표를 읽지 못했어요"))
        GcpTxt.report("Target #1,128.9012,37.7512,57.0\nTarget #2,128.9013,37.7513,57.1\nTarget #3,128.9,37.75,57\n").let { assertTrue(it.message.startsWith("경위도")); assertTrue(it.gcps.isEmpty()) }
        // 세미콜론 + 소수점 쉼표(유럽식 엑셀), 머리줄, 중복, NaN, 엄청 큰 값, 음수 번호, 탭, BOM+CRLF
        val r = GcpTxt.report("\uFEFFName;E;N;H\r\nTarget #1;245100,40;512300,40;57,03\r\nTarget #2\t245103.6\t512300.5\t57.13\r\n" +
            "Target #2,245103.7,512300.6,57.2\r\nTarget #3,NaN,512302.6,57.2\r\nTarget #4,1e12,512302.5,57.1\r\nTarget #-1,245100,512300,57\r\n5 245100.5 512302.5 57.14\r\n")
        assertEquals(listOf(1, 2, 5), r.gcps.map { it.target })
        assertEquals(245100.40, r.gcps[0].e, 1e-9); assertEquals(245103.7, r.gcps[1].e, 1e-9)
        assertEquals(1, r.duplicates); assertTrue(r.skipped >= 3); assertTrue(r.message.contains("건너뜀"))
    }

    @Test fun storage_full_policy_and_photo_write_failure() {
        assertFalse(StoragePolicy.canShoot(100L * 1024 * 1024)); assertTrue(StoragePolicy.canShoot(500L * 1024 * 1024))
        assertFalse(StoragePolicy.canExport(50L * 1024 * 1024, 40L * 1024 * 1024)); assertTrue(StoragePolicy.canExport(1L shl 32, 40L * 1024 * 1024))
        val root = tmp(); val sf = SessionFiles(root)
        assertTrue(sf.writePhoto("s", "IMG_0001.jpg", ByteArray(10)))
        // 쓸 수 없는 폴더(저장 공간 꽉 참과 같은 IOException 경로): 실패 + 반쪽 파일 없음
        val ph = sf.photos("ro"); ph.setWritable(false)
        val ok = sf.writePhoto("ro", "IMG_0001.jpg", ByteArray(10))
        if (!ok) assertTrue(ph.listFiles().orEmpty().isEmpty()) else println("(root 권한이라 쓰기 금지 시험 건너뜀)")
        ph.setWritable(true)
    }

    @Test fun processDeath_restore_and_corrupt_session_backup_and_missing_photo() {
        val root = tmp(); val sf = SessionFiles(root)
        val st = (1..3).map { Still(it, "IMG_000$it.jpg", 10, 10, 8.0, 8.0, 5.0, 5.0, null, 0L, emptyList()) }
        var s = Session("A", 1L)
        // 사진 한 장마다 저장 → 프로세스가 죽어도 새로 연 쪽에서 그대로
        for (x in st) { sf.writePhoto("A", x.file, ByteArray(5)); s = s.copy(stills = s.stills + x); sf.save(s) }
        val again = SessionFiles(root).load("A"); assertEquals(3, again.session.stills.size); assertFalse(again.fromBackup)
        // 쓰다 끊김 = session.json 손상 → 바로 앞 판(.bak, 사진 2장)으로
        File(root, "A/session.json").writeText("{\"name\":\"A\",\"stil")
        val b = sf.load("A"); assertTrue(b.fromBackup); assertEquals(2, b.session.stills.size)
        // 사진 파일이 사라짐 → 그 장만 빼고 연다
        sf.save(s); File(root, "A/photos/IMG_0002.jpg").delete()
        val m = sf.load("A"); assertEquals(1, m.missingPhotos); assertEquals(listOf(1, 3), m.session.stills.map { it.id })
        // 둘 다 손상 → 예외(화면은 홈으로), 목록에서는 빠짐
        File(root, "A/session.json").writeText("x"); File(root, "A/session.json.bak").writeText("y")
        assertTrue(runCatching { sf.load("A") }.isFailure); assertTrue(sf.list().isEmpty())
    }

    @Test fun export_write_fail_is_exception_not_crash() {
        val root = tmp(); val out = File(root, "out"); out.writeText("파일이 있어 폴더를 못 만듦")
        val r = runCatching { ExportFolder.write(Session("E", 0L), root, out) }
        assertTrue(r.isFailure)
    }

    @Test fun tags_none_partial_tiny_noise_badArgs() {
        assertTrue(AprilTagNative.available())
        assertTrue(TagDetector.detect(ByteArray(640 * 480) { 120 }, 640, 480).isEmpty())
        val rnd = Random(1); val noise = ByteArray(1280 * 960).also { rnd.nextBytes(it) }
        assertTrue("잡음에서 거짓 표적 없음", TagDetector.detect(noise, 1280, 960).isEmpty())
        val (g, w, h) = TestData.gray(TestData.target(1))
        // 오른쪽 절반을 지운 표적(가려짐)
        val half = g.copyOf(); for (y in 0 until h) for (x in w / 2 until w) half[y * w + x] = 255.toByte()
        assertTrue(TagDetector.detect(half, w, h).isEmpty())
        // 아주 작게(검은 네모 ≈ 8 px): 못 찾을 수 있지만 죽으면 안 됨
        val sw = w / 100; val sh = h / 100; val small = ByteArray(sw * sh) { i -> g[(i / sw) * 100 * w + (i % sw) * 100] }
        TagDetector.detect(small, sw, sh)
        // 잘못된 인자
        assertTrue(TagDetector.detect(ByteArray(10), 640, 480).isEmpty())
        assertTrue(TagDetector.detect(ByteArray(0), 0, 0).isEmpty())
    }

    @Test fun depthUnsupported_featurePoints_and_noPose_and_memoryCap() {
        // 특징점만(드문드문) → 점 표시, 죽지 않음
        val e = LiveEngine(Session("f", 0L))
        val rnd = Random(2); val pts = FloatArray(300 * 3) { (rnd.nextFloat() * 3) }
        e.addDepth(pts, null, FloatArray(300) { 1f }, 300, false); settle(e); settle(e)
        assertTrue(e.voxelCount > 0)
        // ARCore 없는 사진(자세 없음) + NaN 점
        e.addStill(Still(1, "a.jpg", 100, 100, 80.0, 80.0, 50.0, 50.0, null, 0L, emptyList())) { }
        e.addDepth(floatArrayOf(Float.NaN, 0f, 0f, 0f, Float.POSITIVE_INFINITY, 0f), null, floatArrayOf(1f, 1f), 2, false); settle(e)
        assertNull(e.fit); assertEquals(1, e.progress.photos)
        e.close()
        // 칸 상한(메모리)
        val hf = HeightField(0.05, maxCells = 1000)
        for (i in 0 until 5000) hf.add(i * 0.05 + 0.025, 0.0, 0.0)
        assertEquals(1000, hf.cells.size); assertEquals(4000L, hf.dropped)
        // 메모리 부족 → 절반
        val e2 = LiveEngine(Session("m", 0L)); val big = FloatArray(60_000 * 3)
        for (i in 0 until 60_000) { big[3 * i] = (i % 300) * 0.05f; big[3 * i + 2] = (i / 300) * 0.05f }
        val cf = FloatArray(60_000) { 1f }; e2.addDepth(big, null, cf, 60_000, false); e2.addDepth(big, null, cf, 60_000, false); settle(e2)
        val before = e2.hf.cells.size; e2.trim(); settle(e2)
        assertTrue("trim $before → ${e2.hf.cells.size}", e2.hf.cells.size <= before / 2 + 1)
        e2.close()
    }
}
