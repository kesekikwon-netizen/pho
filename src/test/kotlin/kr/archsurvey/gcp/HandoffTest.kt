package kr.archsurvey.gcp

import kr.archsurvey.gcp.engine.AndroidGcpFiles
import kr.archsurvey.gcp.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class HandoffTest {
    private val z = ZoneId.of("Asia/Seoul")
    private val day = 1_791_730_800_000L          // 2026-10-12 00:00 KST
    private val now = day + 10 * 3600_000L
    private val site = Site(1, "강릉 유적", 5187, 0)
    private val a = Obj(1, 1, ObjKind.DWELLING, 1)
    private val b = Obj(2, 1, ObjKind.SECTION, 1)
    private val c = Obj(3, 1, ObjKind.PIT, 3)
    private val sa = Session(11, 1, 1, "검출면", listOf(1, 2, 3), createdAt = day, photoDoneAt = now - 1000, changedAt = now - 2000)   // 전달 필요
    private val sb = Session(21, 2, 1, "검출면", listOf(1, 2, 3, 4), createdAt = day - 86_400_000L * 3, changedAt = day - 86_400_000L * 3)  // 사진 필요(옛날)
    private val sc = Session(31, 3, 1, "검출면", listOf(5, 6, 7), createdAt = day - 86_400_000L * 2, photoDoneAt = day - 86_400_000L * 2 + 5, deliveredAt = day - 86_400_000L, changedAt = day - 86_400_000L * 2)  // 끝
    private val shots = listOf(1, 2, 3).map { shot(it.toLong(), 11, it, 100.0 + it, 200.0, 1.0) } +
        listOf(1, 2, 3).map { shot(10L + it, 21, it, 300.0 + it, 200.0, 1.0) } + listOf(5, 6, 7).map { shot(20L + it, 31, it, 500.0, 200.0 + it, 1.0) }
    private val book = SiteBook(site, listOf(a, b, c), listOf(sa, sb, sc), shots)

    @Test fun counts_perStage() {
        assertEquals(mapOf(Stage.MEASURE to 0, Stage.PHOTO to 1, Stage.HANDOFF to 1, Stage.DONE to 1), book.counts())
    }

    @Test fun scopes_pendingTodayAll() {
        assertEquals(listOf("001호_주거지"), Handoff.select(book, HandoffScope.PENDING, now, z).map { it.first.code })
        assertEquals(listOf("001호_주거지"), Handoff.select(book, HandoffScope.TODAY, now, z).map { it.first.code })
        assertEquals(listOf("001호_주거지", "003호_수혈", "A_토층"), Handoff.select(book, HandoffScope.ALL, now, z).map { it.first.code })
    }

    @Test fun plan_entriesNamesAndDeliveredMarks() {
        val p = Handoff.plan(book, HandoffScope.ALL, now, z)
        assertEquals("강릉유적_전체_20261012.zip", p.zipName)
        assertEquals("강릉유적_전달_20261012.zip", Handoff.zipName(site, HandoffScope.PENDING, now, z))
        assertEquals(listOf("강릉유적_목록.csv",
            "001호_주거지/001호_주거지_1차_EPSG5187.xml", "001호_주거지/001호_주거지_1차_EPSG5187.txt",
            "003호_수혈/003호_수혈_1차_EPSG5187.xml", "003호_수혈/003호_수혈_1차_EPSG5187.txt",
            "A_토층/A_토층_1차_EPSG5187.xml", "A_토층/A_토층_1차_EPSG5187.txt"), p.entries.map { it.first })
        assertEquals(listOf(11L, 31L), p.markDelivered)               // 사진 안 끝난 21 은 전달로 안 바꿈
        assertEquals(listOf("A_토층_1차"), p.noPhoto)
        assertTrue(p.entries[1].second.contains("<Name>Target #3</Name>"))
    }

    @Test fun listCsv_bomCrlfHeaderAndRows() {
        val csv = ListCsv.write(book, z)
        assertTrue(csv.startsWith("\uFEFF폴더,대상,종류,차수,차수 이름,날짜,표적 번호,XML 파일,사진 폴더,상태,마지막 저장\r\n"))
        val rows = csv.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
        assertEquals(4, rows.size)
        assertEquals("001호_주거지,1호 주거지,주거지,1차,검출면,2026-10-12,1 2 3,001호_주거지/001호_주거지_1차_EPSG5187.xml,001호_주거지_1차,전달 필요,", rows[1])
        assertTrue(rows[2].startsWith("003호_수혈,") && rows[2].contains(",끝,"))
        assertTrue(rows[3].startsWith("A_토층,A—A′ 토층,토층,") && rows[3].contains(",사진 필요,"))
    }

    @Test fun zip_containsExactEntriesUtf8() {
        val p = Handoff.plan(book, HandoffScope.PENDING, now, z)
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { AndroidGcpFiles.writeZip(it, p) }
        val got = LinkedHashMap<String, String>()
        ZipInputStream(bo.toByteArray().inputStream()).use { zi -> while (true) { val e = zi.nextEntry ?: break; got[e.name] = String(zi.readBytes(), Charsets.UTF_8) } }
        assertEquals(p.entries.map { it.first }, got.keys.toList())
        assertEquals(p.entries.toMap(), got)
        assertTrue(got.getValue("강릉유적_목록.csv").startsWith("\uFEFF"))
        assertFalse(got.values.filter { true }.drop(1).any { it.startsWith("\uFEFF") })
    }
}
