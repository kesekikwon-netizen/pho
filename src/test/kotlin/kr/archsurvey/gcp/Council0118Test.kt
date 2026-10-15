package kr.archsurvey.gcp

import kr.archsurvey.gcp.ControllerTest.Companion.measure
import kr.archsurvey.gcp.ControllerTest.Companion.rig
import kr.archsurvey.gcp.model.*
import kr.archsurvey.gcp.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 0.1.18 위원회 15번 명세 Must·Should 받아들이는 기준(JVM, 실기 아님) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Council0118Test {
    private fun named(title: String = "주거지"): ControllerTest.Rig {
        val r = rig(); val c = r.c
        c.openNewSite(); c.newSite = c.newSite!!.copy(typed = "강릉 유적", epsg = 5187); c.submitNewSite()
        c.openNewObj(); c.newObj = c.newObj!!.copy(title = title); c.submitNewObj()
        r.measure(1, 194884.215, 573986.096); r.measure(2, 194890.0, 573990.0); r.measure(3, 194895.0, 573980.0)
        return r
    }
    private fun plan(r: ControllerTest.Rig) = r.c.handoffPlan()!!.let { Handoff.plan(r.c.book!!, HandoffScope.ALL, 1_791_766_800_000L, r.c.zone) }

    @Test fun m1_sheetVerbatim_oneMethod() {
        val p = plan(named())
        val sheet = p.entries.first { it.first == ItwinSheet.FILE }.second
        assertEquals(File("/workspace/design-program/shared/iTwin_불러오기.txt").readText(Charsets.UTF_8), sheet)
        assertFalse(sheet.contains("마법사")); assertFalse(sheet.contains("Wizard"))
    }

    @Test fun m2_noTxt_refCsvWithBom() {
        val p = plan(named())
        val names = p.entries.map { it.first }
        assertTrue(names.none { it.endsWith(".txt") && it != ItwinSheet.FILE && it != "보낸기록.txt" })
        assertEquals(1, names.count { it.endsWith(".xml") })
        val csv = p.entries.first { it.first == "주거지/참고용/좌표.csv" }.second
        assertTrue(csv.startsWith("\uFEFF")); assertTrue(csv.contains("Target #1,194884.215,573986.096"))
    }

    @Test fun m3_fileTarget_noAprilTag_screenNoTarget() {
        val p = plan(named())
        val xml = p.entries.first { it.first.endsWith(".xml") }.second
        assertTrue(xml.contains("<Name>Target #1</Name>")); assertFalse(xml.contains("AprilTag"))
        // 화면 글(ui 소스)에 따옴표 안 "Target" 0
        val ui = File("src/main/kotlin/kr/archsurvey/gcp/ui").listFiles()!!.joinToString("\n") { it.readText() }
        assertFalse(Regex("\"[^\"\\n]*Target [#0-9$][^\"\\n]*\"").containsMatchIn(ui))
    }

    @Test fun m4_folderExactlyTyped() {
        val r = named("주거지")
        val o = r.c.obj!!; val s = r.c.session!!
        assertEquals("주거지", Names.objFolder(o)); assertEquals("주거지", Names.photoFolder(o, s)); assertEquals("주거지.xml", Names.xmlName(o, s, 5187))
    }

    @Test fun m5_root() { assertEquals("발굴조사/사진 기준점", Names.ROOT); assertEquals("발굴조사/사진 기준점/강릉 유적", Names.siteRel(named().c.book!!.site)) }

    @Test fun m6_sameZipNameTwice_oneTap() {
        val r = named()
        r.c.oneTapHandoff(); r.c.oneTapHandoff()
        assertEquals(2, r.files.zips.size); assertEquals(r.files.zips[0].zipName, r.files.zips[1].zipName)
        assertFalse(r.files.zips[0].zipName.contains("(1)"))
    }

    @Test fun m7_emptyNameRefused_duplicateOpensExisting() {
        val r = named(); val c = r.c
        val first = c.obj!!.id
        c.back(); c.openNewObj(); assertNotNull(ObjNames.problem("", emptyList()))
        assertNotNull(c.sameNameObj("주거지")); c.openSameNameObj("주거지"); assertEquals(first, c.obj!!.id); assertNull(c.newObj)
        c.openSitePicker(); c.openNewSite(); c.newSite = c.newSite!!.copy(typed = "강릉 유적"); c.submitNewSite()
        assertEquals(1, c.sites.size)
    }

    @Test fun s1_tapTwiceArms_thenUndoRestores() {
        val r = named(); val c = r.c
        c.onTargetTap(1); assertEquals(1, c.armedRetake); assertNull(c.measuringTag); assertNull(c.ui.confirm)
        c.onTargetTap(1); assertNull(c.armedRetake)
        c.cancelMeasure()
        val before = c.shotOf(1)!!
        r.measure(1, 194884.220, 573986.100)  // 5 mm 다름 → 팝업 없이 저장
        assertTrue(c.undoVisible()); c.undoRetake()
        assertEquals(before.e, c.shotOf(1)!!.e, 1e-9); assertEquals(before.n, c.shotOf(1)!!.n, 1e-9)
    }
}

/** CEO 결정(10-15 16:53): 사진 기준점 단추 말 = 「iTwin으로 보내기」, 안내문은 공유 파일과 바이트까지 같음 */
class Council0118WordTest {
    @Test fun buttonWord_iTwin_noPcWord() {
        val ui = File("src/main/kotlin/kr/archsurvey/gcp").walk().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue(ui.contains("\"iTwin으로 보내기\""))
        assertFalse(ui.contains("PC로 보내기")); assertFalse(ui.contains("zip 만들어 보내기"))
    }
    @Test fun sheetBytesEqual() {
        assertArrayEquals(File("/workspace/design-program/shared/iTwin_불러오기.txt").readBytes(), ItwinSheet.TEXT.toByteArray(Charsets.UTF_8))
    }
}
