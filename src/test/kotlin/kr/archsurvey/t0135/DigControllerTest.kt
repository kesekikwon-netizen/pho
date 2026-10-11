package kr.archsurvey.t0135

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kr.archsurvey.arch.ArchController
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.RoomArchStore
import kr.archsurvey.data.RoomPointStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.device.FakeGnssSource
import kr.archsurvey.device.FakeScenarios
import kr.archsurvey.dig.*
import kr.archsurvey.ui.SavedPoint
import kr.archsurvey.ui.SurveyController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** 0.1.35 DigController: 실제 Room 저장소 + 가짜 수신기로 측점 흐름을 끝까지 돌려 본다(실기기 아님). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DigControllerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: SurveyDb
    private lateinit var f: FakeGnssSource
    private lateinit var c: SurveyController
    private lateinit var store: RoomPointStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, SurveyDb::class.java).allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        f = FakeGnssSource(FakeScenarios.allFixed())
        store = RoomPointStore(db, { "광령리" }, { "R10" })
        c = SurveyController(f, AntennaDb.parse(File("src/main/assets/antenna_db.json").readText()), scope, store = store)
        c.projectName = "광령리"
        c.arch = ArchController(c, RoomArchStore(db, { "광령리" }), scope)
        repeat(2) { f.emitNext() }
    }
    @After fun tearDown() { db.close() }

    private fun measure() { c.onMeasure(); repeat(12) { f.emitNext() }; c.dismissResult() }

    @Test fun featurePointsMakeProvisionalFeatureAndKeepPNames() {
        c.dig.select(DigKind.FEATURE, null)
        repeat(3) { measure() }
        assertEquals(listOf("P-0001", "P-0002", "P-0003"), c.points.map { it.name })
        val o = c.arch.outlines.single()
        assertEquals("가-1", o.label); assertEquals("feature", o.kind)
        assertEquals(c.points.map { it.rowId }, o.pointRowIds)
        assertTrue(c.points.all { it.digKind == "feature" && it.featureId == o.id })
        // 디스크에도 유구 소속이 남는다
        val disk = runBlocking { store.loadAll() }
        assertTrue(disk.all { it.featureId == o.id })
        assertEquals("가-1 · 4번째 점", c.dig.measureSub())
    }

    @Test fun closeThenUndoReopensWithoutDeletingPoints() {
        c.dig.select(DigKind.FEATURE, null)
        repeat(3) { measure() }
        c.dig.armClose(); assertTrue(c.dig.closeArmed)
        c.onMeasure()   // 측점 단추 = 면 닫기 확인
        val id = c.arch.outlines.single().id
        assertTrue(c.arch.outlines.single().finished); assertTrue(c.arch.outlines.single().closed)
        assertEquals(3, c.points.size)
        c.undo()
        assertFalse(c.arch.outlines.single { it.id == id }.finished)
        assertEquals(3, c.points.size)   // 점은 지우지 않음
        c.undo()   // 그다음 되돌리기는 평소처럼 마지막 점
        assertEquals(2, c.points.size)
        assertEquals(2, c.arch.outlines.single().pointRowIds.size)
    }

    @Test fun closeNeedsThreePoints() {
        c.dig.select(DigKind.FEATURE, null)
        repeat(2) { measure() }
        c.dig.armClose()
        assertFalse(c.dig.closeArmed)
        assertTrue(c.message!!.contains("3개 이상"))
    }

    @Test fun artifactsNumberedPerJobAndAssignedToFeature() {
        c.dig.select(DigKind.FEATURE, null)
        repeat(3) { measure() }
        val fid = c.arch.outlines.single().id
        c.dig.select(DigKind.ARTIFACT, fid)
        measure(); measure()
        val arts = c.points.filter { it.digKind == "artifact" }
        assertEquals(listOf(1, 2), arts.map { it.artifactNo })
        assertTrue(arts.all { it.featureId == fid })   // 고른 유구(면이 아직 안 닫힘)
        assertEquals("유물 3", c.dig.measureSub())
        val l = c.dig.ledger(arts.last())
        assertEquals("유물 2", l.title)
        assertTrue(l.line.startsWith(arts.last().name))
    }

    private fun savedAt(name: String, n: Double, e: Double, h: Double, type: String = "유구 경계점", kind: String = "feature"): SavedPoint {
        val p = DigFixtures.point(name, n, e, h, type).copy(crsEpsg = c.zone.epsg, digKind = kind)
        val id = runBlocking { store.save(p) }
        val sp = p.copy(rowId = id); c.points.add(sp); return sp
    }

    @Test fun artifactInsideClosedPolygonIsAutoAssignedAndDepthFromDetection() = runBlocking {
        val fid = c.dig.newFeature("수혈", prov = false)!!
        assertEquals("1호 수혈", c.dig.featureName(fid))
        for ((i, q) in listOf(0.0 to 0.0, 0.0 to 4.0, 4.0 to 4.0, 4.0 to 0.0).withIndex()) {
            val p = savedAt("P-%04d".format(i + 1), 400000.0 + q.first, 200000.0 + q.second, 50.0)
            c.arch.attachVertexTo(fid, p.rowId)
        }
        assertTrue(c.arch.finishOutlineId(fid, true))
        c.dig.chooseFeature(null)
        val inside = c.dig.decorate(DigFixtures.point("P-0005", 400002.0, 200002.0, 49.62, "유물 출토 위치").copy(crsEpsg = c.zone.epsg))
        assertEquals(fid, inside.featureId); assertEquals(1, inside.artifactNo)
        val outside = c.dig.decorate(DigFixtures.point("P-0006", 400010.0, 200010.0, 49.0, "유물 출토 위치").copy(crsEpsg = c.zone.epsg))
        assertNull(outside.featureId)
        // 유물 깊이: 검출면 레벨이 없으면 경계점 평균 높이(50.0) 기준
        c.points.add(inside.copy(rowId = 99))
        val l = c.dig.ledger(c.points.last())
        assertEquals("검출면에서", l.cells[1].label); assertEquals("−0.38", l.cells[1].value)
        assertTrue(c.dig.whatLine(c.points.last()).contains("1호 수혈 안(자동)"))
        // 레벨 바닥 → 유구 카드에 깊이·바닥 평균
        c.dig.select(DigKind.LEVEL, fid, LevelKind.FLOOR)
        val lv = c.dig.decorate(DigFixtures.point("P-0007", 400001.0, 200001.0, 49.40, "레벨점").copy(crsEpsg = c.zone.epsg))
        assertEquals("floor", lv.levelKind); assertEquals(fid, lv.featureId)
        c.points.add(lv.copy(rowId = 100))
        val card = c.dig.featureCards().single()
        assertEquals(16.0, card.areaSqm!!, 1e-6); assertEquals(0.60, card.depthM!!, 1e-6); assertEquals(49.40, card.floorMeanM!!, 1e-6)
        assertEquals(listOf(1), card.artifacts)
    }

    @Test fun renameKeepsPointsAndHistory() {
        c.dig.select(DigKind.FEATURE, null)
        repeat(3) { measure() }
        val o = c.arch.outlines.single()
        assertNotNull(c.dig.renameFeature(o.id, "  ", ""))   // 빈 이름은 거절
        assertNull(c.dig.renameFeature(o.id, "2호 수혈", "수혈"))
        val r = c.arch.outlines.single()
        assertEquals("2호 수혈", r.label); assertEquals(listOf("가-1", "2호 수혈"), FeatureNaming.history(r.history))
        assertEquals(listOf("P-0001", "P-0002", "P-0003"), c.points.map { it.name })
        assertTrue(c.points.all { it.featureId == o.id })
        val reloaded = runBlocking { RoomArchStore(db, { "광령리" }).load() }.outlines.single()
        assertEquals("2호 수혈", reloaded.label); assertEquals("수혈", reloaded.featureType)
        assertEquals("2호 수혈", c.dig.featureCards().single().name)
    }

    @Test fun bmFirstIsReferenceThenCheckedWithTolerance() = runBlocking {
        val prefs = MemoryDigPrefs(); c.dig.prefs = prefs
        c.dig.today = { "2026-10-12" }
        val ref = savedAt("P-0001", 400000.0, 200000.0, 50.000, "기준점", "bm").copy(digTag = "BM-1")
        c.points[c.points.lastIndex] = ref
        val ok = c.dig.decorate(DigFixtures.point("P-0002", 400000.012, 200000.010, 50.008, "기준점").copy(crsEpsg = c.zone.epsg))
        assertEquals("BM-1", ok.digTag)
        c.points.add(ok.copy(rowId = 50)); c.dig.afterSaved(c.points.last())
        assertEquals(true, c.dig.bmToday)
        assertTrue(c.dig.bmResult!!.second.pass)
        val bad = DigFixtures.point("P-0003", 400000.05, 200000.0, 50.0, "기준점").copy(crsEpsg = c.zone.epsg, digKind = "bm", digTag = "BM-1", rowId = 51)
        c.points.add(bad); c.dig.afterSaved(bad)
        assertEquals(false, c.dig.bmToday); assertFalse(c.dig.bmResult!!.second.pass)
        c.dig.changeBmTol(0.06)
        assertEquals("0.060", prefs.get("dig_bm_tol"))
    }

    @Test fun sectionAndStakeTags() = runBlocking {
        c.dig.select(DigKind.SECTION)
        val a = c.dig.decorate(DigFixtures.point("P-0001", type = "토층 단면"))
        assertEquals("A", a.digTag); c.points.add(a)
        assertEquals("A′", c.dig.decorate(DigFixtures.point("P-0002", type = "토층 단면")).digTag)
        assertTrue(c.dig.measureSub().startsWith("A′"))
    }

    @Test fun poleConfirmTodayIsPerDayAndPerHeight() {
        val prefs = MemoryDigPrefs(); c.dig.prefs = prefs
        var day = "2026-10-12"; c.dig.today = { day }
        assertFalse(c.dig.poleConfirmedToday)
        c.dig.confirmPole(); assertTrue(c.dig.poleConfirmedToday)
        day = "2026-10-13"; assertFalse(c.dig.poleConfirmedToday)
        val steps = c.dig.prepSteps()
        assertEquals(5, steps.size)
        assertEquals(listOf("receiver", "vrs", "pole", "crs", "bm"), steps.map { it.id })
    }

    @Test fun settingsPersistAndSelectionRestoresPerJob() {
        val prefs = MemoryDigPrefs(); c.dig.prefs = prefs
        c.dig.changeArtifactMode(ArtifactMode.PER_FEATURE); c.dig.changeProvisional(false); c.dig.setFeatureTypes(listOf("주거지", "수혈", "주거지", " "))
        c.dig.select(DigKind.LEVEL, null, LevelKind.TOP)
        val c2 = c.dig
        c2.prefs = prefs   // 다시 읽기
        assertEquals(ArtifactMode.PER_FEATURE, c2.artifactMode); assertFalse(c2.provisional)
        assertEquals(listOf("주거지", "수혈"), c2.featureTypes.toList())
        assertEquals(DigKind.LEVEL, c2.kind); assertEquals(LevelKind.TOP, c2.level)
        assertEquals("1호 주거지", c2.nextFeatureName())
    }
}
