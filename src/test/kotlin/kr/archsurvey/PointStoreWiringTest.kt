package kr.archsurvey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 화면 컨트롤러 ↔ 저장소 연결 (가짜 PointStore). Room 자체는 RepositoryTests. */
class PointStoreWiringTest {
    private class MemStore(var preload: List<SavedPoint> = emptyList(), var maxSeqValue: Int = 0) : PointStore {
        val saved = ArrayList<SavedPoint>(); var hidden = 0; var failSave = false
        override suspend fun loadAll() = preload
        override suspend fun maxSeq() = maxSeqValue
        override suspend fun save(p: SavedPoint): Long { if (failSave) error("disk full"); saved.add(p); return saved.size.toLong() }
        override suspend fun hideLast() { hidden++ }
    }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun measure(c: SurveyController, f: FakeGnssSource) { repeat(2) { f.emitNext() }; c.onMeasure(); repeat(6) { f.emitNext() } }

    @Test fun measuredPointIsWrittenToStoreWithFields() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val st = MemStore()
        val c = SurveyController(f, db, scope(), store = st, clock = { 12345L })
        measure(c, f)
        val p = st.saved.single()
        assertEquals("P-0001", p.name); assertEquals(12345L, p.measuredAtMs); assertEquals(4, p.fixQuality)
        assertEquals(1L, c.points.single().rowId)
    }
    @Test fun undoHidesInStoreAndNumberKeepsCounting() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val st = MemStore()
        val c = SurveyController(f, db, scope(), store = st)
        measure(c, f); c.undo()
        assertEquals(1, st.hidden); assertTrue(c.points.isEmpty()); assertEquals("P-0002", c.nextName)
    }
    @Test fun restartLoadsSavedPointsAndContinuesNumbering() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val first = MemStore(); val c1 = SurveyController(f, db, scope(), store = first)
        measure(c1, f)
        val f2 = FakeGnssSource(FakeScenarios.allFixed())
        val c2 = SurveyController(f2, db, scope(), store = MemStore(preload = first.saved.toList(), maxSeqValue = 7))
        assertEquals(1, c2.points.size); assertEquals("P-0008", c2.nextName) // 숨긴 점 번호(7)까지 이어서
    }
    @Test fun saveFailureIsShownNotSilent() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val st = MemStore().also { it.failSave = true }
        val c = SurveyController(f, db, scope(), store = st)
        measure(c, f)
        assertTrue(c.message!!.contains("저장에 실패"))
    }

    @Test fun heightsAreConsistentAndStoredSeparately() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val st = MemStore()
        val c = SurveyController(f, db, scope(), store = st)
        measure(c, f)
        val p = st.saved.single(); val apc = p.antenna.apcHeightM
        assertNotNull(p.apcEllipsoidalM); assertNotNull(p.apcMslM); assertNotNull(p.geoidSepM)
        assertEquals(p.apcEllipsoidalM!! - apc, p.groundHeightM!!, 1e-9)       // 지면 타원체고 = APC 타원체고 − 안테나 높이
        assertEquals(p.apcMslM!! - apc, p.groundMslM!!, 1e-9)                   // 지면 표고
        assertEquals(p.apcEllipsoidalM!!, p.apcMslM!! + p.geoidSepM!!, 0.01)    // h = H + N (가짜 신호 기준, GGA 반올림 허용)
    }
    @Test fun zoneChoiceChangesCoordinatesAndEpsg() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val st = MemStore()
        val c = SurveyController(f, db, scope(), store = st)
        measure(c, f); c.zone = KoreanTmZone.EAST; repeat(3) { f.emitNext() }; c.onMeasure(); repeat(8) { f.emitNext() }
        assertEquals(listOf(5186, 5187), st.saved.map { it.crsEpsg })
        assertTrue(Math.abs(st.saved[0].easting - st.saved[1].easting) > 100_000) // 원점이 2도(≈176km) 다름
    }
}
