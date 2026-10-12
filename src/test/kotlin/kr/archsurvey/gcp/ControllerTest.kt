package kr.archsurvey.gcp

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.gcp.data.GcpDb
import kr.archsurvey.gcp.data.GcpRepo
import kr.archsurvey.gcp.measure.Measured
import kr.archsurvey.gcp.model.*
import kr.archsurvey.gcp.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 장부(Room, 메모리) + 화면 상태를 실제로 돌린다. 측점 평균은 MeasureTest, 여기서는 측점 뒤 흐름(afterMeasure) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControllerTest {
    class Rig(val c: GcpController, val files: MemFiles, val gnss: TestGnss, val prefs: MemPrefs, var t: Long)

    companion object {
        val antennaDb by lazy { AntennaDb.parse(File("src/main/assets/antenna_db.json").readText()) }
        fun rig(): Rig {
            val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GcpDb::class.java)
                .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
            var t = 1_791_766_800_000L   // 2026-10-12 10:00 KST
            val clock = { t++ }
            val files = MemFiles(); val g = TestGnss(); val p = MemPrefs()
            val c = GcpController(GcpRepo(db.dao(), clock), files, g, p, antennaDb, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined, clock = clock)
            c.start()
            return Rig(c, files, g, p, t)
        }
        fun m(e: Double, n: Double, h: Double = 4.0) = Measured(37.7, 128.9, e, n, h, h + 1.6, 0.006, 0.012, 3, 3)

        /** 유적 + 대상 만들어 측점 화면까지 */
        fun Rig.siteWithObj(kind: ObjKind = ObjKind.FEATURE): Rig {
            c.openNewSite(); c.newSite = c.newSite!!.copy(name = "강릉 유적", code = "gn", epsg = 5187); c.submitNewSite()
            c.openNewObj(kind); c.submitNewObj()
            return this
        }
        fun Rig.measure(tag: Int, e: Double, n: Double, h: Double = 4.0) { c.afterMeasure(c.session!!, tag, m(e, n, h), 1.6, 1.68, "R10") }
    }

    @Test fun createSite_andObjects_autoNumberedStableIds() {
        val r = rig().siteWithObj()
        val c = r.c
        assertEquals(Screen.MEASURE, c.screen)
        assertEquals("GN-001", c.obj!!.code); assertEquals("1호 수혈", c.obj!!.name)
        assertEquals(listOf(1, 2, 3, 4), c.session!!.slots)
        c.back()
        assertEquals("다음 2호", "다음 ${c.nextFeatureNo()}호")
        c.openNewObj(ObjKind.SECTION); assertEquals("A—A′ 토층", c.newObj!!.name()); c.submitNewObj()
        assertEquals("GN-002", c.obj!!.code)
        c.back(); c.setOperator("B"); c.openNewObj(ObjKind.FEATURE); c.submitNewObj()
        assertEquals("GN-B001", c.obj!!.code); assertEquals("2호 수혈", c.obj!!.name)
        // 약호 중복·잘못된 약호
        c.back(); c.back(); assertEquals(Screen.SITES, c.screen)
        c.openNewSite(); c.newSite = c.newSite!!.copy(name = "다른", code = "GN"); c.submitNewSite()
        assertNotNull(c.newSite!!.error)
        assertEquals(1, c.sites.size); assertEquals(3, c.sites[0].objCount)
    }

    @Test fun measureSavesXmlEveryTime_pipelineToPhotoThenHandoff() {
        val r = rig().siteWithObj(); val c = r.c
        r.measure(1, 194884.215, 573986.096, 4.192)
        assertEquals(1, r.files.saved.size)
        assertEquals("iTwinGCP/강릉 유적/GN-001_1호수혈/GN-001_1호수혈_1차_EPSG5187.xml", r.files.saved.last().first)
        assertNotNull(c.session!!.xmlSavedAt)
        r.measure(2, 194880.652, 573989.204, 2.883)
        assertEquals(Stage.MEASURE, c.stageNow())
        c.markPhotoDone(); assertNull(c.session!!.photoDoneAt)        // 3개 미만은 사진 끝 못 함
        r.measure(3, 194877.654, 573994.437, 2.87)
        assertEquals(3, r.files.saved.size)
        assertEquals(Stage.PHOTO, c.stageNow())
        assertEquals(3, Regex("<ControlPoint>").findAll(r.files.saved.last().second).count())
        c.openIdCard(); assertEquals(Screen.ID_CARD, c.screen)
        assertEquals("GN-001|1차|EPSG5187|T1-4", c.qr())
        c.markPhotoDone(); assertEquals(Screen.MEASURE, c.screen)
        assertEquals(Stage.HANDOFF, c.stageNow())
        assertEquals(mapOf(Stage.MEASURE to 0, Stage.PHOTO to 0, Stage.HANDOFF to 1, Stage.DONE to 0), c.counts())
        c.back(); c.handoffScope = HandoffScope.PENDING; c.sendHandoff()
        assertEquals(1, r.files.zips.size)
        assertEquals(Stage.DONE, c.book!!.stageOf(c.book!!.objs[0]))
        // 보낸 뒤 다시 재면(움직임 없음) 다시 전달 필요
        c.openObj(c.book!!.objs[0].id); r.measure(3, 194877.655, 573994.437, 2.87)
        assertEquals(Stage.HANDOFF, c.stageNow())
    }

    @Test fun reMeasureOver2cm_asksMoved_andResetsPhoto() {
        val r = rig().siteWithObj(); val c = r.c
        r.measure(1, 100.0, 100.0); r.measure(2, 103.0, 100.0); r.measure(3, 100.0, 103.0); c.markPhotoDone()
        assertEquals(Stage.HANDOFF, c.stageNow())
        r.measure(2, 103.0, 100.05)
        val ask = c.ui.confirm!!; assertEquals("표적이 움직였나요?", ask.title); assertTrue(ask.body.contains("5.0 cm"))
        c.ui.answer(false)       // 실수 → 앞 값 그대로
        assertEquals(100.0, c.shotOf(2)!!.n, 1e-9); assertEquals(Stage.HANDOFF, c.stageNow())
        r.measure(2, 103.0, 100.05); c.ui.answer(true)
        assertEquals(100.05, c.shotOf(2)!!.n, 1e-9)
        assertEquals(Stage.PHOTO, c.stageNow())         // 사진 다시
        assertTrue(c.book!!.shots.any { it.tag == 2 && !it.active })   // 앞 값은 기록으로 남음
    }

    @Test fun sameNumberNearbyOtherObject_warnsClash_andNewObjSuggestsFreeNumbers() {
        val r = rig().siteWithObj(); val c = r.c
        r.measure(1, 194884.0, 573986.0); r.measure(2, 194886.0, 573986.0)
        c.back()
        // 수신기가 그 근처에 있으면 새 대상은 #3–6 을 제안
        r.gnss.emit(lat = c.book!!.shots[0].lat, lon = c.book!!.shots[0].lon)
        val here = c.here()!!
        c.openNewObj(ObjKind.FEATURE)
        val near = c.newObj!!
        // 시험 좌표(37.7,128.9)와 표적 좌표가 달라 근처가 아닐 수 있다 → 근처면 피한 번호
        if (Math.hypot(here.first - 194884.0, here.second - 573986.0) <= 20) assertEquals(listOf(3, 4, 5, 6), near.slots)
        c.newObj = near.copy(slots = listOf(1, 2, 3, 4)); c.submitNewObj()
        r.measure(1, 194890.0, 573986.0)        // 6 m 옆 GN-001 도 #1
        val ask = c.ui.confirm!!; assertEquals("번호가 겹쳐요", ask.title); assertTrue(ask.body.contains("GN-001"))
        c.ui.answer(false); assertNull(c.shotOf(1))
        r.measure(5, 194890.0, 573986.0); assertNull(c.ui.confirm); assertNotNull(c.shotOf(5))   // 다른 번호는 그냥 저장
        assertEquals(listOf(1, 2, 3, 4, 5), c.session!!.slots)
    }

    @Test fun sessions_lockOlder_newFileNames_andSlotEdit() {
        val r = rig().siteWithObj(); val c = r.c
        r.measure(1, 1.0, 1.0)
        c.openNewSession(); assertEquals("중간", c.newSession!!.label); c.newSession = c.newSession!!.copy(label = "완토"); c.submitNewSession()
        assertEquals(2, c.session!!.no); assertFalse(c.locked())
        r.measure(1, 1.0, 1.0)
        assertTrue(r.files.saved.last().first.endsWith("GN-001_1호수혈_2차_EPSG5187.xml"))
        c.pickSession(c.sessionsOfObj().first().id); assertTrue(c.locked())
        c.onTargetTap(2); assertEquals(GcpController.LOCKED_MSG, c.ui.toast)
        c.pickSession(c.sessionsOfObj().last().id)
        c.onTargetLong(1)                     // 안 잰 칸 길게 = 번호 바꾸기
        c.slotEdit = c.slotEdit!!.copy(text = "1"); c.submitSlot(); assertNotNull(c.slotEdit!!.error)   // 이미 있는 번호
        c.slotEdit = c.slotEdit!!.copy(text = "27"); c.submitSlot(); assertNull(c.slotEdit)
        assertEquals(listOf(1, 27, 3, 4), c.session!!.slots)
        c.openAddSlot(); assertEquals("28", c.slotEdit!!.text); c.submitSlot()
        assertEquals(listOf(1, 27, 3, 4, 28), c.session!!.slots)
    }

    @Test fun rename_keepsId_andRecordsOldName() {
        val r = rig().siteWithObj(); val c = r.c
        r.measure(1, 1.0, 1.0)
        c.renaming = "1호 주거지"; c.submitRename()
        assertEquals("GN-001", c.obj!!.code); assertEquals("1호 주거지", c.obj!!.name); assertEquals(listOf("1호 수혈"), c.obj!!.oldNames)
        assertTrue(r.files.saved.last().first.endsWith("GN-001_1호주거지/GN-001_1호주거지_1차_EPSG5187.xml"))
    }

    @Test fun boardFiltersTabsSearch() {
        val r = rig().siteWithObj(); val c = r.c
        repeat(3) { r.measure(it + 1, it * 3.0, 0.0) }
        c.back(); c.openNewObj(ObjKind.SECTION); c.submitNewObj(); c.back()
        assertEquals(listOf("GN-002", "GN-001"), c.rows().map { it.obj.code })   // 측점 필요가 위
        c.toggleFilter(Stage.PHOTO); assertEquals(listOf("GN-001"), c.rows().map { it.obj.code })
        c.toggleFilter(Stage.PHOTO); c.kindTab = ObjKind.SECTION; assertEquals(listOf("GN-002"), c.rows().map { it.obj.code })
        c.kindTab = null; c.query = "001"; assertEquals(listOf("GN-001"), c.rows().map { it.obj.code })
        c.query = "토층"; assertEquals(listOf("GN-002"), c.rows().map { it.obj.code })
    }

    @Test fun pole_defaultsPerDevice_andApcIncludesAntennaOffset() {
        val c = rig().c
        assertEquals(1.6, c.poleM("trimble_r10"), 1e-9); assertEquals(1.8, c.poleM("spectra_sp60"), 1e-9)
        c.stepPole(10); assertEquals(1.7, c.poleM(), 1e-9)
        assertTrue(c.apcFor("trimble_r10", 1.6) > 1.6)        // 폴 + 기준점→위상중심
    }

    @Test fun exit_twoSteps_honestWording() {
        val c = rig().c; var exited = false; c.onExitApp = { exited = true }
        c.back(); assertEquals("프로그램 종료", c.ui.confirm!!.title)    // 수신기 없음 → 2단계만
        c.ui.answer(true); assertTrue(exited)
        assertEquals("수신기 연결 끊기", kr.archsurvey.ui.AppExit.TITLE_DEVICE)
    }

    @Test fun xmlWriteFailure_toldHonestly() {
        val r = rig().siteWithObj(); r.files.fail = true
        r.measure(1, 1.0, 1.0)
        assertTrue(r.c.ui.toast!!.contains("XML 을 쓰지 못했어요")); assertNull(r.c.session!!.xmlSavedAt)
        assertNotNull(r.c.shotOf(1))
    }
}
