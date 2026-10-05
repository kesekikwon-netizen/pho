package kr.archsurvey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ToolsTests {
    private class MemStore : PointStore {
        val saved = ArrayList<SavedPoint>(); val meta = ArrayList<Triple<Long, String, String>>()
        override suspend fun loadAll() = saved.toList()
        override suspend fun maxSeq() = saved.size
        override suspend fun save(p: SavedPoint): Long { saved.add(p); return saved.size.toLong() }
        override suspend fun hideLast() {}
        override suspend fun updateMeta(rowId: Long, type: String, memo: String) { meta.add(Triple(rowId, type, memo)) }
    }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private fun setup(n: Int = 3): Triple<SurveyController, FakeGnssSource, MemStore> {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val st = MemStore()
        val c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), store = st)
        repeat(2) { f.emitNext() }
        repeat(n) { c.onMeasure(); repeat(7) { f.emitNext() } }
        return Triple(c, f, st)
    }

    @Test fun listFilterNewestFirstAndSearch() {
        val (c, _, _) = setup(3)
        assertEquals(listOf("P-0003", "P-0002", "P-0001"), c.hub.filtered().map { it.name })
        c.hub.query = "0002"; assertEquals(listOf("P-0002"), c.hub.filtered().map { it.name })
        c.hub.query = "유구"; assertEquals(3, c.hub.filtered().size)
        c.hub.query = "없는말"; assertTrue(c.hub.filtered().isEmpty())
    }

    @Test fun editTypeAndMemoUpdatesListAndStore() {
        val (c, _, st) = setup(2)
        val p = c.points.first()
        c.hub.openDetail(p)
        assertEquals(ToolScreen.POINT_DETAIL, c.hub.screen); assertFalse(c.hub.dirty)
        c.hub.editType = "기준점"; c.hub.editMemo = "  북동쪽 모서리  "; assertTrue(c.hub.dirty)
        c.hub.saveEdit()
        val u = c.points.first { it.name == p.name }
        assertEquals("기준점", u.type); assertEquals("북동쪽 모서리", u.memo)
        assertEquals(Triple(p.rowId, "기준점", "북동쪽 모서리"), st.meta.single())
        assertFalse(c.hub.dirty)
        // 좌표는 그대로
        assertEquals(p.northing, u.northing, 0.0); assertEquals(p.easting, u.easting, 0.0)
        c.hub.query = "북동쪽"; assertEquals(listOf(p.name), c.hub.filtered().map { it.name })
        c.hub.back(); assertEquals(ToolScreen.POINTS, c.hub.screen)
        c.hub.back(); assertNull(c.hub.screen)
    }

    @Test fun blankTypeKeepsOldType() {
        val (c, _, _) = setup(1)
        val p = c.points.single(); c.hub.openDetail(p); c.hub.editType = "  "; c.hub.editMemo = "x"; c.hub.saveEdit()
        assertEquals(p.type, c.points.single().type)
    }

    @Test fun gotoGuidanceFromCurrentPositionToSavedPoint() {
        val (c, f, _) = setup(1)
        repeat(3) { f.emitNext() }
        val p = c.points.single()
        c.hub.startGoto(p)
        assertEquals(ToolScreen.GOTO, c.hub.screen)
        val g = c.hub.guidance()!!
        val cur = c.currentNE()!!; val tgt = c.pointNE(p)
        assertEquals(Calc.distance(cur, tgt), g.distance, 1e-9)
        assertEquals(tgt.n - cur.n, g.dN, 1e-9)
        c.hub.back(); assertNull(c.hub.screen)
    }

    @Test fun gotoFromDetailReturnsToDetail() {
        val (c, _, _) = setup(1)
        c.hub.open(ToolScreen.POINTS); c.hub.openDetail(c.points.single()); c.hub.startGoto(c.points.single())
        c.hub.back(); assertEquals(ToolScreen.POINT_DETAIL, c.hub.screen)
    }

    @Test fun manualGotoParsesAndRejectsGarbage() {
        val (c, _, _) = setup(1)
        c.hub.gotoN = "abc"; c.hub.gotoE = "1"; assertFalse(c.hub.setManualGoto())
        c.hub.gotoN = "500000.5"; c.hub.gotoE = "200000"; assertTrue(c.hub.setManualGoto())
        assertEquals(NE(500000.5, 200000.0), c.hub.gotoTargetNE())
        assertEquals("입력한 좌표", c.hub.gotoTarget!!.label)
    }

    @Test fun calcDistanceAreaOffset() {
        val (c, _, _) = setup(3)
        val a = c.points[0]; val b = c.points[1]
        c.hub.calcA = a; c.hub.calcB = b
        val r = c.hub.distResult()!!
        assertEquals(Calc.distance(c.pointNE(a), c.pointNE(b)), r.dist, 1e-9)
        c.hub.calcUseCurrentA = true; c.hub.calcA = null
        assertNotNull(c.hub.distResult()) // 현재 위치 → 도착
        c.hub.calcUseCurrentA = false
        assertNull(c.hub.distResult())
        // 면적: 3점 미만이면 없음
        assertNull(c.hub.areaResult())
        c.hub.calcPicking = "AREA"; c.points.forEach { c.hub.pick(it) }
        assertEquals(3, c.hub.areaPicks.size); assertEquals("AREA", c.hub.calcPicking) // 여러 점 연속 선택
        assertNotNull(c.hub.areaResult())
        c.hub.finishAreaPick(); assertNull(c.hub.calcPicking)
        // 오프셋
        c.hub.calcPicking = "BASE"; c.hub.pick(a)
        c.hub.offBearing = "90"; c.hub.offDist = "10"
        val o = c.hub.offsetResult()!!; val base = c.pointNE(a)
        assertEquals(base.n, o.n, 1e-9); assertEquals(base.e + 10, o.e, 1e-9)
        c.hub.offDist = "-1"; assertNull(c.hub.offsetResult())
        c.hub.offDist = "x"; assertNull(c.hub.offsetResult())
    }

    @Test fun pointNeUsesSavedCoordsWhenSameZoneElseReprojects() {
        val (c, _, _) = setup(1)
        val p = c.points.single()
        val same = c.pointNE(p)
        assertEquals(p.northing, same.n, 0.0)
        c.zone = KoreanTmZone.WEST // 원점을 바꾸면 경위도에서 다시 계산
        val other = c.pointNE(p)
        assertNotEquals(p.easting, other.e, 1.0)
        val exp = TmProjection.forward(p.lat, p.lon, KoreanTmZone.WEST)
        assertEquals(exp.northing, other.n, 1e-6)
    }

    @Test fun settingsStateAppliesAndPersists() {
        val (c, _, _) = setup(0)
        val p = object : SettingsPlatform {
            override var zone = KoreanTmZone.EAST; override var poleRight = false; override var sound = true; override var vibration = false; override var keepScreenOn = true
        }
        val st = SettingsState(c, p)
        // 0.1.13: 좌표계 SSOT=JobConfig — SettingsState.init 은 폴 위치만 Pref에서 싣는다
        assertEquals(KoreanTmZone.CENTRAL, c.zone); assertFalse(c.poleOnRight)
        st.setZone(KoreanTmZone.EAST_SEA); assertEquals(KoreanTmZone.EAST_SEA, p.zone); assertEquals(KoreanTmZone.EAST_SEA, c.zone)
        st.setPoleRight(true); assertTrue(p.poleRight); assertTrue(c.poleOnRight)
        st.changeSound(false); assertFalse(p.sound); st.changeVibration(true); assertTrue(p.vibration); st.changeKeepScreenOn(false); assertFalse(p.keepScreenOn)
    }

    @Test fun newPointsKeepZoneAtSaveTime() {
        val (c, f, _) = setup(1)
        assertEquals(5186, c.points.single().crsEpsg)
        c.zone = KoreanTmZone.WEST
        c.onMeasure(); repeat(7) { f.emitNext() }
        assertEquals(listOf(5186, 5185), c.points.map { it.crsEpsg })
    }

    @Test fun feedbackCalledOnSave() {
        val f = FakeGnssSource(FakeScenarios.allFixed())
        val c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val res = ArrayList<Boolean>(); c.feedback = { res.add(it) }
        repeat(2) { f.emitNext() }; c.onMeasure(); repeat(7) { f.emitNext() }
        assertEquals(listOf(true), res)
    }
}

class JobTests {
    private class FakeJobs : JobPlatform {
        override var current = "작업1"
        val jobs = linkedMapOf("작업1" to 3)
        var backups = 0
        override suspend fun list() = jobs.map { JobInfo(it.key, it.value, 0, 0, null, null) }
        override suspend fun info(name: String) = JobInfo(name, jobs[name] ?: 0, 1, 2, 10L, 20L)
        override suspend fun create(name: String) { JobNames.problem(name, jobs.keys)?.let { throw JobException(it) }; jobs[name.trim()] = 0; current = name.trim() }
        override suspend fun open(name: String) { if (name !in jobs) throw JobException("그런 작업이 없어요"); current = name }
        override suspend fun delete(name: String): String { backups++; jobs.remove(name); if (current == name) current = jobs.keys.firstOrNull() ?: "작업1"; return "before_delete_$backups.zip" }
    }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private fun setup(): Triple<SurveyController, JobController, FakeJobs> {
        val c = SurveyController(FakeGnssSource(FakeScenarios.allFixed()), db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val p = FakeJobs(); val jc = JobController(c, p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        c.jobs = jc
        return Triple(c, jc, p)
    }

    @Test fun nameRules() {
        assertNotNull(JobNames.problem("", emptyList())); assertNotNull(JobNames.problem("   ", emptyList()))
        assertNotNull(JobNames.problem("a".repeat(31), emptyList())); assertNull(JobNames.problem("a".repeat(30), emptyList()))
        assertNotNull(JobNames.problem("a/b", emptyList())); assertNotNull(JobNames.problem("a:b", emptyList()))
        assertNotNull(JobNames.problem("작업1", listOf("작업1"))); assertNotNull(JobNames.problem(" 작업1 ", listOf("작업1")))
        assertNull(JobNames.problem("3지구-A트렌치", listOf("작업1")))
    }

    @Test fun createSwitchesAndShowsInfo() {
        val (c, jc, p) = setup()
        jc.openNew(); assertEquals(ToolScreen.JOB_NEW, c.hub.screen)
        jc.newName = "  새작업  "; jc.create()
        assertEquals("새작업", jc.current); assertEquals("새작업", c.projectName); assertEquals("새작업", p.current)
        assertEquals(ToolScreen.JOB_INFO, c.hub.screen); assertTrue(jc.notice!!.contains("새작업"))
    }

    @Test fun duplicateAndEmptyNamesShowError() {
        val (_, jc, p) = setup()
        jc.openNew(); jc.refresh()
        jc.newName = "작업1"; jc.create(); assertTrue(jc.error!!.contains("이미")); assertEquals(1, p.jobs.size)
        jc.newName = ""; jc.create(); assertTrue(jc.error!!.contains("입력"))
    }

    @Test fun deleteNeedsConfirmAndMakesBackup() {
        val (c, jc, p) = setup()
        p.jobs["B"] = 1
        jc.openList()
        val b = jc.list.first { it.name == "B" }
        jc.askDelete(b); assertEquals(0, p.backups); assertEquals("B", jc.confirmDelete?.name)
        jc.confirmDelete = null; jc.delete(); assertEquals(0, p.backups) // 취소
        jc.askDelete(b); jc.delete()
        assertEquals(1, p.backups); assertFalse("B" in p.jobs); assertTrue(jc.notice!!.contains("before_delete_1.zip"))
        assertEquals("작업1", c.projectName.ifEmpty { "작업1" })
    }

    @Test fun deletingCurrentJobSwitchesToAnother() {
        val (c, jc, p) = setup()
        p.jobs["B"] = 1; p.current = "B"
        jc.openList(); jc.askDelete(jc.list.first { it.name == "B" }); jc.delete()
        assertEquals("작업1", jc.current); assertEquals("작업1", c.projectName)
    }

    @Test fun openMissingJobShowsError() {
        val (_, jc, p) = setup()
        p.jobs["X"] = 0; jc.openList(); p.jobs.remove("X")
        jc.open("X"); assertTrue(jc.error!!.contains("없어요"))
    }
}
