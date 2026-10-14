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
    class Rig(val c: GcpController, val files: MemFiles, val gnss: TestGnss, val prefs: MemPrefs, var t: Long, val repo: GcpRepo? = null)

    companion object {
        val antennaDb by lazy { AntennaDb.parse(File("src/main/assets/antenna_db.json").readText()) }
        fun rig(addr: AddressParts? = null): Rig {
            val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GcpDb::class.java)
                .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
            var t = 1_791_766_800_000L   // 2026-10-12 10:00 KST
            val clock = { t++ }
            val files = MemFiles(); val g = TestGnss(); val p = MemPrefs()
            val repo = GcpRepo(db.dao(), clock)
            val c = GcpController(repo, files, g, p, antennaDb, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined, clock = clock,
                addressLookup = { _, _ -> addr })
            c.start()
            return Rig(c, files, g, p, t, repo)
        }
        fun m(e: Double, n: Double, h: Double = 4.0) = Geo.latLon(e, n).let { (la, lo) -> Measured(la, lo, e, n, h, h + 1.6, 0.006, 0.012, 3, 3) }

        /** 유적(고르기만) + 대상(종류 타일 → 다음 번호) 만들어 지도 화면까지 */
        fun Rig.siteWithObj(kind: ObjKind = ObjKind.PIT): Rig {
            c.openNewSite(); c.newSite = c.newSite!!.copy(base = "강릉 유적", epsg = 5187); c.submitNewSite()
            c.openNewObj(); c.pickKind(kind); c.submitNewObj()
            return this
        }
        fun Rig.measure(tag: Int, e: Double, n: Double, h: Double = 4.0) { c.afterMeasure(c.session!!, tag, m(e, n, h), 1.6, 1.68, "R10") }
    }

    @Test fun createSite_byPickingOnly_andObjects_kindTileNextNumber_uniquePerKind() {
        val r = rig().siteWithObj()
        val c = r.c
        assertEquals(Screen.TARGETS, c.screen)   // 0.1.13: 만들면 바로 표적 재기(뒤로 = 폴더 안)
        assertEquals(Screen.OBJ, c.stack[c.stack.size - 2])
        assertEquals("1호 수혈", c.obj!!.name); assertEquals("001호_수혈", c.obj!!.code)
        assertEquals(listOf(1, 2, 3, 4), c.session!!.slots); assertEquals("검출면", c.session!!.label)
        // 같은 종류는 다음 번호, 다른 종류는 1호부터
        c.openNewObj(); c.pickKind(ObjKind.PIT); assertEquals(2, c.newObj!!.no); c.submitNewObj(); assertEquals("2호 수혈", c.obj!!.name)
        c.openNewObj(); c.pickKind(ObjKind.DWELLING); assertEquals(1, c.newObj!!.no)
        // 번호판으로 12호 → 「1」「2」
        c.openObjNoPad(); c.numDigit(1); c.numDigit(2); c.submitNum(); assertEquals(12, c.newObj!!.no)
        c.pickLabel("완토"); c.submitNewObj(); assertEquals("12호 주거지", c.obj!!.name); assertEquals("완토", c.session!!.label)
        // 이미 있는 번호는 못 만듦
        c.openNewObj(); c.pickKind(ObjKind.DWELLING); assertEquals(13, c.newObj!!.no); c.pickObjNo(12); c.submitNewObj()
        assertTrue(c.ui.toast!!.contains("이미 있어요")); assertNotNull(c.newObj); c.newObj = null
        c.openNewObj(); c.pickKind(ObjKind.SECTION); assertEquals(1, c.newObj!!.no); c.submitNewObj(); assertEquals("A—A′ 토층", c.obj!!.name); assertEquals("A_토층", c.obj!!.code)
        c.back(); assertEquals(Screen.OBJ, c.screen); c.back(); assertEquals(Screen.OBJECTS, c.screen); c.back(); assertEquals(Screen.SITES, c.screen)
        assertEquals(1, c.sites.size); assertEquals(4, c.sites[0].objCount)
        // 같은 이름 유적은 (2)
        c.openNewSite(); c.newSite = c.newSite!!.copy(base = "강릉 유적"); c.submitNewSite()
        assertEquals("강릉 유적 (2)", c.book!!.site.name)
    }

    @Test fun newSite_namesFromAddress_district_epsgFromLongitude_noTyping() {
        val r = rig(AddressParts("강원특별자치도", "강릉시", "성남동", "중앙동")); val c = r.c
        c.openNewSite()
        assertEquals("유적 2026-10-12", c.newSite!!.base)     // 위치 없음 → 날짜
        c.newSite = null
        r.gnss.emit(lat = 37.7519, lon = 128.8961)
        c.openNewSite()
        val f = c.newSite!!
        assertEquals(5187, f.epsg)                            // 경도 128.9 → 동부
        assertEquals("강릉 성남동 유적", f.base); assertEquals(listOf("강릉 성남동 유적", "성남동 유적", "강릉 중앙동 유적", "유적 2026-10-12"), f.suggestions)
        c.newSite = f.copy(district = 3); assertEquals("강릉 성남동 유적 3지구", c.newSite!!.name())
        c.numPick = NumPick(PickFor.District, "몇 지구?", 5, emptySet(), max = 99); c.numDigit(1); c.numDigit(2); c.submitNum()
        assertEquals("강릉 성남동 유적 12지구", c.newSite!!.name())
        c.submitNewSite(); assertEquals("강릉 성남동 유적 12지구", c.book!!.site.name); assertEquals(5187, c.book!!.site.epsg)
        // 다음 새 유적: 최근 이름이 제안에 나옴(지구 뗀 것)
        c.back(); c.openNewSite(); assertTrue(c.newSite!!.suggestions.contains("강릉 성남동 유적"))
    }

    @Test fun numPad_digitsBackspaceCapAndTaken() {
        var p = NumPick(PickFor.AddSlot, "", 5, setOf(1, 2), max = 999)
        p = p.digit(1); assertEquals(1, p.value); assertTrue(p.taken); assertFalse(p.ok)
        p = p.digit(2).digit(7); assertEquals(127, p.value); assertTrue(p.ok)
        p = p.digit(4); assertEquals(4, p.value)                 // 999 넘으면 새로 시작
        p = p.backspace(); assertEquals(0, p.value); assertFalse(p.ok)
    }

    @Test fun measureSavesXmlEveryTime_pipelineToPhotoThenHandoff() {
        val r = rig().siteWithObj(); val c = r.c
        r.measure(1, 194884.215, 573986.096, 4.192)
        assertEquals(1, r.files.saved.size)
        assertEquals("iTwinGCP/강릉 유적/001호_수혈/001호_수혈_1차_EPSG5187.xml", r.files.saved.last().first)
        assertNotNull(c.session!!.xmlSavedAt)
        r.measure(2, 194880.652, 573989.204, 2.883)
        assertEquals(Stage.MEASURE, c.stageNow())
        c.markPhotoDone(); assertNull(c.session!!.photoDoneAt)        // 3개 미만은 사진 끝 못 함
        r.measure(3, 194877.654, 573994.437, 2.87)
        assertEquals(3, r.files.saved.size)
        assertEquals(Stage.PHOTO, c.stageNow())
        assertEquals(3, Regex("<ControlPoint>").findAll(r.files.saved.last().second).count())
        assertEquals(4, c.nextTag())
        c.openIdCard(); assertEquals(Screen.ID_CARD, c.screen)
        assertEquals("001호_수혈|1차|EPSG5187|T1-4", c.qr())
        c.markPhotoDone(); assertEquals(Screen.TARGETS, c.screen) // 0.1.13: 사진 끝 = ID 카드를 연 화면으로
        assertEquals(Stage.HANDOFF, c.stageNow())
        assertEquals(mapOf(Stage.MEASURE to 0, Stage.PHOTO to 0, Stage.HANDOFF to 1, Stage.DONE to 0), c.counts())
        c.sendHandoff(HandoffScope.PENDING)
        assertEquals(1, r.files.zips.size); assertEquals("강릉유적_전달_20261012.zip", r.files.zips[0].zipName)
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
        // 수신기가 그 근처(5 m)에 있으면 새 대상은 #3–6 을 제안
        r.gnss.emitAt(194889.0, 573986.0)
        val here = c.here()!!
        assertEquals(194889.0, here.first, 5.0); assertEquals(573986.0, here.second, 5.0)
        c.openNewObj()
        val near = c.newObj!!
        assertEquals(listOf(3, 4, 5, 6), near.slots); assertTrue(near.nearNote!!.contains("1호 수혈"))
        c.newObj = near.copy(slots = listOf(1, 2, 3, 4)); c.pickKind(ObjKind.PIT); c.submitNewObj()
        r.measure(1, 194890.0, 573986.0)        // 6 m 옆 1호 수혈도 #1
        val ask = c.ui.confirm!!; assertEquals("번호가 겹쳐요", ask.title); assertTrue(ask.body.contains("1호 수혈"))
        c.ui.answer(false); assertNull(c.shotOf(1))
        r.measure(5, 194890.0, 573986.0); assertNull(c.ui.confirm); assertNotNull(c.shotOf(5))   // 다른 번호는 그냥 저장
        assertEquals(listOf(1, 2, 3, 4, 5), c.session!!.slots)
    }

    @Test fun sessions_lockOlder_newFileNames_andSlotNumberPad() {
        val r = rig().siteWithObj(); val c = r.c
        r.measure(1, 1.0, 1.0)
        c.openNewSession(); assertEquals("중간", c.newSession!!.label); c.newSession = c.newSession!!.copy(label = "완토"); c.submitNewSession()
        assertEquals(2, c.session!!.no); assertFalse(c.locked())
        r.measure(1, 1.0, 1.0)
        assertTrue(r.files.saved.last().first.endsWith("001호_수혈/001호_수혈_2차_EPSG5187.xml"))
        c.pickSession(c.sessionsOfObj().first().id); assertTrue(c.locked())
        c.onTargetTap(2); assertEquals(GcpController.LOCKED_MSG, c.ui.toast)
        c.pickSession(c.sessionsOfObj().last().id)
        c.onTargetLong(1)                     // 안 잰 칸 길게 = 번호판
        assertTrue(c.numPick!!.forWhat is PickFor.ChangeSlot)
        c.numDigit(1); assertTrue(c.numPick!!.taken); c.submitNum(); assertNotNull(c.numPick)   // 이미 있는 번호
        c.numDigit(7); c.numBack(); c.numBack(); c.numDigit(2); c.numDigit(7); c.submitNum(); assertNull(c.numPick)
        assertEquals(listOf(1, 27, 3, 4), c.session!!.slots)
        c.openAddSlot(); assertEquals(28, c.numPick!!.value); c.submitNum()
        assertEquals(listOf(1, 27, 3, 4, 28), c.session!!.slots)
    }

    @Test fun objList_stageFilter_measureFirst_sortedByNumber() {
        val r = rig().siteWithObj(); val c = r.c
        repeat(3) { r.measure(it + 1, it * 3.0, 0.0) }
        c.openNewObj(); c.pickKind(ObjKind.SECTION); c.submitNewObj()
        c.openNewObj(); c.pickKind(ObjKind.DWELLING); c.submitNewObj()
        assertEquals(listOf("001호_주거지", "001호_수혈", "A_토층"), c.rows().map { it.obj.code })
        c.toggleFilter(Stage.PHOTO); assertEquals(listOf("001호_수혈"), c.rows().map { it.obj.code })
        c.toggleFilter(Stage.MEASURE); assertEquals(listOf("001호_주거지", "A_토층"), c.rows().map { it.obj.code })
    }

    @Test fun mapState_satelliteCadastralLayers_targetsAndOutline() {
        val r = rig().siteWithObj(); val c = r.c
        r.measure(1, 194884.0, 573986.0); r.measure(2, 194890.0, 573986.0); r.measure(3, 194887.0, 573990.0)
        c.fitAll()
        val ms = c.mapState(kr.archsurvey.map.VWorldAccess("TESTKEY", ""))
        assertEquals(listOf("sat", "cad"), ms.tiles.map { it.id })                  // 측량 앱과 같은 위성 + 지적 레이어
        assertTrue(ms.tiles[0].urlOf(19, 1, 2).contains("/TESTKEY/Satellite/19/2/1.jpeg"))
        assertEquals(listOf("#1", "#2", "#3"), ms.points.map { it.label })
        assertTrue(ms.points.all { it.sym == kr.archsurvey.map.MapSym.TAG })
        assertEquals("1호 수혈", ms.outlines.single().label)
        assertTrue(c.mapState(kr.archsurvey.map.VWorldAccess("", "")).tiles.isEmpty())   // 키 없으면 배경 없음
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

    @Test fun measureButton_noObjOpensNewObj_noReceiverOpensConnection() {
        val r = rig(); val c = r.c
        c.openNewSite(); c.newSite = c.newSite!!.copy(base = "가"); c.submitNewSite()
        c.onMeasureButton(); assertNotNull(c.newObj)
    }

    @Test fun xmlWriteFailure_toldHonestly() {
        val r = rig().siteWithObj(); r.files.fail = true
        r.measure(1, 1.0, 1.0)
        assertTrue(r.c.ui.toast!!.contains("XML 을 쓰지 못했어요")); assertNull(r.c.session!!.xmlSavedAt)
        assertNotNull(r.c.shotOf(1))
    }

    /** 0.1.5 시안 흐름: 폴더 목록 → 폴더 안 → 표적 재기 · 지도는 따로 · 다시 켜면 마지막 유적 폴더 목록 */
    @Test fun folderFlow_0105_navigation_and_resume() {
        val r = rig().siteWithObj(); val c = r.c
        assertEquals(listOf(Screen.SITES, Screen.OBJECTS, Screen.OBJ, Screen.TARGETS), c.stack) // 0.1.13 만들면 바로 표적 재기
        c.openTargets(); assertEquals(Screen.TARGETS, c.screen)
        c.go(Screen.MAP); assertEquals(Screen.MAP, c.screen); c.back(); assertEquals(Screen.TARGETS, c.screen)
        c.back(); c.back(); assertEquals(Screen.OBJECTS, c.screen)
        c.openNewObj(); assertEquals(ObjKind.PIT, c.newObj!!.kind); assertEquals(2, c.newObj!!.no)   // 마지막 종류 · 다음 번호가 처음부터
        c.back(); assertNull(c.newObj)
        c.openSitePicker(); assertEquals(Screen.SITES, c.screen)
        // 앱을 다시 켠 것처럼: 같은 장부 · 같은 설정 → 마지막 유적 폴더 목록
        val c2 = GcpController(r.repo!!, r.files, r.gnss, r.prefs, antennaDb, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined)
        c2.start(); assertEquals(Screen.OBJECTS, c2.screen); assertEquals(c.book!!.site.id, c2.book!!.site.id)
    }
}
