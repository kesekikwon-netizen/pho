package kr.archsurvey.pg

import kr.archsurvey.pg.data.Jpeg
import kr.archsurvey.pg.data.PhotoIds
import kr.archsurvey.pg.data.PhotoSaver
import kr.archsurvey.pg.data.PhotoWrite
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.SessionFiles
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.live.LiveEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** 0.0.16: 원자적 사진 번호 · 버리지 않는 병렬 저장 · 나갈 때 비우기 · 고아/조각 되살리기 */
class P0016Test {
    /** 최소 JPEG: SOI · SOF0(48×64) · EOI */
    private val base: ByteArray = intArrayOf(0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x30, 0x00, 0x40, 0x01, 0x01, 0x11, 0x00, 0xFF, 0xD9).map { it.toByte() }.toByteArray()
    /** 번호가 든 COM 조각을 넣은 JPEG(사진마다 내용이 다름 → 덮어쓰기 검출) */
    private fun jpeg(id: Int): ByteArray {
        val c = "id=$id".toByteArray(); val seg = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, (c.size + 2).toByte()) + c
        return base.copyOfRange(0, 2) + seg + base.copyOfRange(2, base.size)
    }
    private fun still(id: Int) = Still(id, PhotoIds.fileName(id), 64, 48, 50.0, 50.0, 32.0, 24.0, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), id.toLong(), emptyList(), "overlap_distance")

    @Test fun idsAtomicUniqueMonotonic() {
        val ids = PhotoIds(1); val got = Collections.synchronizedList(ArrayList<Int>())
        val pool = Executors.newFixedThreadPool(8); val done = CountDownLatch(8)
        repeat(8) { pool.execute { repeat(2500) { got += ids.take() }; done.countDown() } }
        done.await(); pool.shutdown()
        assertEquals(20_000, got.toSet().size); assertEquals((1..20_000).toSet(), got.toSet()); assertEquals(20_001, ids.peek())
        // 시작값: 저장된 다음 번호·기록·폴더 파일(조각 포함) 중 가장 큰 것 + 1
        val d = Files.createTempDirectory("ids").toFile()
        File(d, "IMG_0040.jpg").writeBytes(byteArrayOf(1)); File(d, "IMG_0057.jpg.part").writeBytes(byteArrayOf(1)); File(d, "x.jpg").writeBytes(byteArrayOf(1))
        assertEquals(58, PhotoIds.startFor(Session("s", 0L, listOf(still(12))), d))
        assertEquals(90, PhotoIds.startFor(Session("s", 0L, listOf(still(12))).copy(nextPhotoId = 90), d))
        assertEquals(1, PhotoIds.startFor(Session("s", 0L), Files.createTempDirectory("e").toFile()))
        assertEquals(10000, PhotoIds.fileNo(PhotoIds.fileName(10000)))
    }

    @Test fun fastShootingSlowSavingExitMidQueue() {
        val root = Files.createTempDirectory("p16").toFile(); val files = SessionFiles(root); val name = "fast"
        files.save(Session(name, 0L)); files.photos(name)
        val loaded = files.load(name).session
        val engine = LiveEngine(loaded); engine.saveEveryMs = 50
        val ids = PhotoIds(PhotoIds.startFor(loaded, files.photos(name)))
        val saver = PhotoSaver(2); val rnd = Random(3); val exifCalls = java.util.concurrent.atomic.AtomicInteger()
        val n = 400
        val save: (Session) -> Unit = { s -> files.save(s) }
        // 촬영: 기다림 없이 연달아 받음(번호는 받는 순간). 저장은 느림(0–6 ms + EXIF 0–3 ms)
        repeat(n) {
            engine.pendingShots.incrementAndGet()
            val id = ids.take(); val delay = rnd.nextInt(7).toLong(); val exDelay = rnd.nextInt(4).toLong()
            saver.submit {
                try {
                    Thread.sleep(delay)
                    val st = still(id)
                    assertTrue(PhotoWrite.save(files, name, st, jpeg(id)) { part -> exifCalls.incrementAndGet(); Thread.sleep(exDelay); assertTrue(part.name.endsWith(".part")) })
                    engine.addStill(st, save)
                } finally { engine.pendingShots.decrementAndGet() }
            }
        }
        // 줄이 남은 채 「끝」: 비우기 → 다음 번호 기록 → 엔진 비우기 → 마지막 저장
        assertTrue(saver.pending.get() > 0)
        val left = ArrayList<Int>()
        assertTrue(saver.drain { left += it })
        assertTrue(left.first() > 0 && left.last() == 0)
        engine.noteNextId(ids.peek()); assertTrue(engine.idle()); files.save(engine.session); saver.shutdown(); engine.closeAfterPending()

        val back = files.load(name).session
        assertEquals((1..n).toList(), back.stills.map { it.id })                     // 모두 기록, 번호 겹침 없음
        assertEquals(n, back.stills.map { it.file }.toSet().size)
        assertEquals(n + 1, back.nextPhotoId)
        val ph = files.photos(name).listFiles()!!
        assertEquals(n, ph.count { it.name.endsWith(".jpg") }); assertEquals(0, ph.count { it.name.endsWith(".part") })
        for (st in back.stills) assertArrayEquals("덮어쓰기 없음 ${st.file}", jpeg(st.id), File(files.photos(name), st.file).readBytes())
        assertEquals(n, exifCalls.get())                                            // EXIF 는 장마다 한 번
        assertFalse(File(root, "$name/" + SessionFiles.JOURNAL).exists())            // 다 들어가 저널 비움
        // 다시 열어 더 찍어도 번호는 이어서(다시 쓰지 않음)
        assertEquals(n + 1, PhotoIds.startFor(back, files.photos(name)))
    }

    @Test fun processDeathRecovery() {
        val root = Files.createTempDirectory("p16d").toFile(); val files = SessionFiles(root); val name = "dead"
        files.save(Session(name, 0L, listOf(still(1))).copy(nextPhotoId = 2)); File(files.photos(name), still(1).file).writeBytes(jpeg(1))
        // 2: 저널 + 파일(저장 전 죽음) / 3: 저널 + 조각만(이름 바꾸기 전 죽음) / 4: 기록 없는 파일(옛 버전 고아) / 5: 저널 + 깨진 조각
        assertTrue(PhotoWrite.save(files, name, still(2), jpeg(2)) {})
        File(files.photos(name), still(3).file + ".part").writeBytes(jpeg(3)); files.journal(name, still(3))
        File(files.photos(name), still(4).file).writeBytes(jpeg(4))
        File(files.photos(name), still(5).file + ".part").writeBytes(jpeg(5).copyOf(30)); files.journal(name, still(5))
        val l = files.load(name).session
        assertEquals(listOf(1, 2, 3, 4), l.stills.map { it.id })
        assertEquals("recovered", l.stills[3].reason); assertEquals(64, l.stills[3].w); assertEquals(48, l.stills[3].h)
        assertArrayEquals(jpeg(3), File(files.photos(name), still(3).file).readBytes())
        assertTrue(PhotoIds.startFor(l, files.photos(name)) >= 6)                  // 깨진 5번 조각 번호도 다시 안 씀
        assertEquals(64 to 48, Jpeg.size(File(files.photos(name), still(4).file))); assertFalse(Jpeg.complete(File(files.photos(name), still(5).file + ".part")))
    }

    @Test fun neverOverwritesExistingPhoto() {
        val root = Files.createTempDirectory("p16o").toFile(); val files = SessionFiles(root); val name = "o"
        files.save(Session(name, 0L)); File(files.photos(name), still(7).file).writeBytes(jpeg(7))
        assertFalse(PhotoWrite.save(files, name, still(7), jpeg(99)) {})
        assertArrayEquals(jpeg(7), File(files.photos(name), still(7).file).readBytes())
        assertFalse(File(files.photos(name), still(7).file + ".part").exists())
    }

    @Test fun nextPhotoIdRoundTrip() {
        val s = Session("r", 0L).copy(nextPhotoId = 123)
        assertEquals(123, Session.fromJson(org.json.JSONObject(s.toJson().toString())).nextPhotoId)
        assertEquals(1, Session.fromJson(org.json.JSONObject(Session("r", 0L).toJson().toString().replace("nextPhotoId", "x"))).nextPhotoId)
    }
}
