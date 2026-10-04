package kr.archsurvey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.map.LocalProjection
import kr.archsurvey.map.LocalXY
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

private val DB2 by lazy { AntennaDb.parse(File("src/main/assets/antenna_db.json").readText()) }
private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
private fun ctrl(f: FakeGnssSource, epochs: Int = 5) = SurveyController(f, DB2, scope(), epochsPerPoint = epochs)

/** WP-3: 영역 높이 합산 (dp 계산). 에뮬레이터/실기 측정이 아니라 상수 계산이다. */
class LayoutMathTest {
    @Test fun constantsMatchPlan() {
        assertEquals(72, SurveyDims.MEASURE_BUTTON_DP); assertEquals(48, SurveyDims.MIN_TOUCH_DP)
        assertEquals(56, SurveyDims.POLE_STRIP_DP); assertEquals(204, SurveyDims.CONTROL_AREA_DP)
        assertEquals(52, SurveyDims.SECONDARY_ROW_DP)
    }
    @Test fun mapIsAtLeast60PercentInIdleAcquiringResultRecordingAt800dp() {
        for (p in listOf(Phase.IDLE, Phase.ACQUIRING, Phase.RESULT, Phase.RECORDING)) {
            val f = SurveyLayout.mapFraction(800, p)
            assertTrue("$p $f", f >= SurveyDims.MIN_MAP_FRACTION)
        }
        assertEquals(596, SurveyLayout.mapHeightDp(800, Phase.IDLE))
        assertEquals(540, SurveyLayout.mapHeightDp(800, Phase.RESULT))
    }
    @Test fun expandedSheetIsUnderHalfScreenAndMapStillVisible() {
        val info = SurveyLayout.infoHeightDp(Phase.RESULT_EXPANDED)
        assertTrue(info < 800 / 2)
        assertEquals(390, SurveyLayout.mapHeightDp(800, Phase.RESULT_EXPANDED))
        assertTrue("펼침 중엔 60% 미만(의도된 예외)", SurveyLayout.mapFraction(800, Phase.RESULT_EXPANDED) < 0.6)
    }
    @Test fun minimumScreenHeightFor60PercentIs680dp() {
        assertTrue(SurveyLayout.mapFraction(680, Phase.RESULT) >= 0.6)
        assertTrue(SurveyLayout.mapFraction(640, Phase.RESULT) < 0.6)
    }
}

class DrawerMenuTest {
    private val forbidden = listOf("성토", "절토", "시공", "준공", "검측", "측설", "노선", "도로")
    @Test fun requiredEntriesExist() {
        val labels = DrawerMenu.allItems.map { it.label }
        for (l in listOf("새 작업 만들기", "작업 열기 · 삭제", "작업 내보내기", "작업 정보", "점 목록", "데이터 백업 · 복구", "점 찾아가기", "계산기", "설정", "AI 어시스턴트", "진단", "안테나 높이"))
            assertTrue(l, l in labels)
        assertTrue(DrawerMenu.sections.map { it.title }.containsAll(listOf("즐겨찾기", "작업 관리", "측량", "AI · 점검")))
    }
    @Test fun aiItemsAreDisabledPlaceholders() {
        val ai = DrawerMenu.allItems.filter { it.id == "ai" || it.id == "diag" }
        assertEquals(2, ai.size); assertTrue(ai.none { it.enabled })
    }
    @Test fun idsUniqueAndNoForbiddenTerms() {
        assertEquals(DrawerMenu.allItems.size, DrawerMenu.allItems.map { it.id }.toSet().size)
        for (i in DrawerMenu.allItems) for (w in forbidden) assertFalse("${i.label} has $w", i.label.contains(w) || (i.note ?: "").contains(w))
    }
    @Test fun noForbiddenTermsInUiAndMapSources() {
        for (dir in listOf("src/main/kotlin/kr/archsurvey/ui", "src/main/kotlin/kr/archsurvey/map")) {
            File(dir).walk().filter { it.extension == "kt" }.forEach { f ->
                val txt = f.readText()
                // 조사가 붙은 일반 단어(예: "투명도로")는 오탐이므로 앞 글자가 한글이 아닐 때만 금지어로 본다
                for (w in forbidden) assertFalse("${f.name} contains $w", Regex("(?<![가-힣])$w").containsMatchIn(txt))
            }
        }
    }
    @Test fun noArtifactAttributeFieldsInSavedPoint() {
        // 점 속성에 수량·재질·크기·시대 등을 만들지 않는다 (MASTER-PLAN §1 #4)
        val names = SavedPoint::class.java.declaredFields.map { it.name.lowercase() }
        for (bad in listOf("count", "quantity", "material", "size", "era", "period", "layer", "collector")) assertTrue(bad, names.none { it.contains(bad) })
    }
}

class LocalProjectionTest {
    @Test fun roundTripAndScale() {
        val p = LocalProjection(36.5, 127.0)
        val xy = p.toLocal(36.5 + 0.0001, 127.0 + 0.0001)
        assertEquals(11.132, xy.y, 1e-3)
        assertEquals(11.132 * Math.cos(Math.toRadians(36.5)), xy.x, 1e-3)
        val (lat, lon) = p.toLatLon(xy)
        assertEquals(36.5001, lat, 1e-9); assertEquals(127.0001, lon, 1e-9)
    }
}

class SurveyControllerTest {
    @Test fun currentMarkerMovesWithFakeGnss() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk()); val c = ctrl(f)
        f.emitNext(); val a = c.mapState().current!!.at
        repeat(10) { f.emitNext() }
        val b = c.mapState().current!!.at
        assertTrue("이동 ${Math.hypot(b.x - a.x, b.y - a.y)}", Math.hypot(b.x - a.x, b.y - a.y) > 2.0)
        assertTrue(c.mapState().trail.size >= 10)
        assertNotNull(c.mapState().current!!.headingDeg)
    }
    @Test fun statusFollowsScenario() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk()); val c = ctrl(f)
        repeat(5) { f.emitNext() }; assertEquals(SurveyStatus.FIXED, c.gv.status.status)
        f.skipToNextSegment(); repeat(2) { f.emitNext() }; assertEquals(SurveyStatus.CAUTION, c.gv.status.status)
        f.skipToNextSegment(); repeat(2) { f.emitNext() }; assertEquals(SurveyStatus.UNAVAILABLE, c.gv.status.status)
        f.skipToNextSegment(); f.emitNext(); assertEquals(SurveyStatus.UNAVAILABLE, c.gv.status.status)
        assertEquals(StatusReason.LINK_DOWN, c.gv.status.reason)
    }
    @Test fun measureWhenFixedStoresPointWithAntenna_2_1991_and_groundHeight() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk()); val c = ctrl(f)
        repeat(3) { f.emitNext() }
        c.onMeasure(); assertEquals(Phase.ACQUIRING, c.phase)
        repeat(6) { f.emitNext() }
        assertEquals(Phase.RESULT, c.phase)
        val p = c.points.single()
        assertEquals("P-0001", p.name); assertEquals(SurveyStatus.FIXED, p.status); assertFalse(p.cautionFlag)
        assertEquals(2.1991, p.antenna.apcHeightM, 1e-9); assertEquals("bottom_of_quick_release", p.antenna.refPointId)
        assertEquals(64.0 - 2.1991, p.groundHeightM!!, 1e-6)
        assertEquals(5186, p.crsEpsg); assertEquals(5, p.epochs)
        assertEquals("P-0002", c.nextName)
    }
    @Test fun switchingToSp60ChangesRecordedOffset() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val c = ctrl(f)
        c.setAntennaModel("SPECTRA_SP60")
        assertEquals(2.000, c.antenna.inputValueM, 0.0); assertEquals("bottom_of_receiver_arp", c.antenna.refPointId)
        repeat(2) { f.emitNext() }; c.onMeasure(); repeat(6) { f.emitNext() }
        assertEquals(2.0687, c.points.single().antenna.apcHeightM, 1e-9)
        assertEquals(64.0 - 2.0687, c.points.single().groundHeightM!!, 1e-6)
    }
    @Test fun heightStepAndRefSwitchUpdateResultImmediately() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val c = ctrl(f)
        assertEquals(2.1991, c.antennaResult!!.totalToApcM, 1e-9)
        c.setRefPoint("bottom_of_antenna_mount"); assertEquals(2.1491, c.antennaResult!!.totalToApcM, 1e-9)
        c.stepAntennaCm(5); assertEquals(2.05, c.antenna.inputValueM, 1e-9); assertEquals(2.1991, c.antennaResult!!.totalToApcM, 1e-9)
        assertEquals(AntennaSource.USER, c.antenna.source)
    }
    @Test fun measureBlockedWhenUnavailable() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk()); val c = ctrl(f)
        repeat(2) { f.emitNext() }; f.skipToNextSegment(); f.skipToNextSegment(); f.emitNext()
        assertEquals(SurveyStatus.UNAVAILABLE, c.gv.status.status)
        c.onMeasure()
        assertEquals(Phase.IDLE, c.phase); assertTrue(c.points.isEmpty()); assertTrue(c.message!!.contains("측점할 수 없어요"))
    }
    @Test fun measureInCautionSavesWithFlag() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk()); val c = ctrl(f)
        repeat(2) { f.emitNext() }; f.skipToNextSegment(); f.emitNext()
        assertEquals(SurveyStatus.CAUTION, c.gv.status.status)
        c.onMeasure(); repeat(6) { f.emitNext() }
        assertTrue(c.points.single().cautionFlag); assertEquals(SurveyStatus.CAUTION, c.points.single().status)
    }
    @Test fun acquisitionAbortsWhenSignalBecomesUnavailable() {
        val sc = FakeScenario("abort", listOf(FakeSegment("고정", 3), FakeSegment("단독", 10, FixQuality.SINGLE, 1.5)))
        val f = FakeGnssSource(sc); val c = ctrl(f, 5)
        repeat(3) { f.emitNext() }; c.onMeasure(); repeat(4) { f.emitNext() }
        assertEquals(Phase.IDLE, c.phase); assertTrue(c.points.isEmpty()); assertTrue(c.message!!.contains("저장하지 않았어요"))
    }
    @Test fun undoRemovesLastAndNeverReusesNumber() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val c = ctrl(f)
        repeat(2) { f.emitNext() }
        c.onMeasure(); repeat(6) { f.emitNext() }; c.onMeasure(); repeat(6) { f.emitNext() }
        assertEquals(listOf("P-0001", "P-0002"), c.points.map { it.name })
        c.undo(); assertEquals(listOf("P-0001"), c.points.map { it.name }); assertEquals("P-0003", c.nextName) // 되돌려도 번호는 다시 쓰지 않음
    }
    @Test fun expandAndVoiceMockStates() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val c = ctrl(f)
        repeat(2) { f.emitNext() }; c.onMeasure(); repeat(6) { f.emitNext() }
        c.toggleExpand(); assertEquals(Phase.RESULT_EXPANDED, c.phase)
        c.toggleExpand(); assertEquals(Phase.RESULT, c.phase)
        c.toggleVoiceMock(); assertEquals(Phase.RECORDING, c.phase)
        c.toggleVoiceMock(); assertEquals(Phase.RESULT, c.phase)
    }
    @Test fun panDisablesFollowAndRecenterRestores() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val c = ctrl(f)
        repeat(3) { f.emitNext() }
        val c0 = c.mapState().center
        c.panBy(5.0, -3.0, c0); assertFalse(c.follow)
        assertEquals(c0.x + 5.0, c.mapState().center.x, 1e-9)
        c.recenter(); assertTrue(c.follow)
    }
}
