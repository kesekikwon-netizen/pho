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
        assertEquals("측점 버튼 한 줄(위 10 + 72 + 아래 12)", 94, SurveyDims.CONTROL_AREA_DP)
    }
    @Test fun mapIsAtLeast60PercentInIdleAcquiringResultRecordingAt800dp() {
        for (p in listOf(Phase.IDLE, Phase.ACQUIRING, Phase.RESULT, Phase.RECORDING)) {
            val f = SurveyLayout.mapFraction(800, p)
            assertTrue("$p $f", f >= SurveyDims.MIN_MAP_FRACTION)
        }
        // 0.1.24 UX-0124-2: 머리글(97) + 판정 줄 FULL(64)=STATUS_BAND
        assertEquals(161, SurveyLayout.topHeightDp(Phase.IDLE))
        assertEquals(545, SurveyLayout.mapHeightDp(800, Phase.IDLE))
        assertEquals("결과 띠는 측점 영역 위에 얹힌다(161+56+94)", 489, SurveyLayout.mapHeightDp(800, Phase.RESULT))
    }
    @Test fun expandedSheetIsUnderHalfScreenAndMapStillVisible() {
        // 펼치면 머리글·판정 줄을 한 줄씩(57+30)으로 줄여 지도 40% 이상을 지킨다
        assertEquals(87, SurveyLayout.topHeightDp(Phase.RESULT_EXPANDED))
        assertEquals(329, SurveyLayout.mapHeightDp(800, Phase.RESULT_EXPANDED))
        assertTrue(SurveyLayout.mapFraction(800, Phase.RESULT_EXPANDED) >= 0.4)
        assertTrue("펼침 중엔 60% 미만(의도된 예외)", SurveyLayout.mapFraction(800, Phase.RESULT_EXPANDED) < 0.6)
    }
    @Test fun minimumScreenHeightFor60PercentIs778dpForResultStrip() {
        // UX-0124-2: 머리글 161 + 결과 띠 56 + 측점 영역 94 = 311dp → 778dp부터 지도 60%(대기 638dp)
        assertTrue(SurveyLayout.mapFraction(778, Phase.RESULT) >= 0.6)
        assertTrue(SurveyLayout.mapFraction(777, Phase.RESULT) < 0.6)
        assertTrue(SurveyLayout.mapFraction(638, Phase.IDLE) >= 0.6)
    }

    @Test fun statusLineFullMatchesStatusBand() {
        assertEquals("StatusBand 두 줄 높이 = STATUS_BAND", SurveyDims.STATUS_BAND_DP, StatusLineDims.FULL_DP)
    }
}

class DrawerMenuTest {
    private val forbidden = listOf("성토", "절토", "시공", "준공", "검측", "측설", "노선", "도로")
    @Test fun requiredEntriesExist() {
        val labels = DrawerMenu.allItems.map { it.label }
        // 0.1.38: 메뉴 하나(겹치는 칸 정리). 점 목록·계산기·작업 정보·안테나 높이·위치 종류는 다른 곳과 겹쳐 뺐다
        for (l in listOf("+ 새 작업", "작업 열기 · 삭제", "PC로 보내기 (예전 내보내기)", "백업 · 복구", "측량한 것", "구역 · 그리드", "지도에 보이기", "점 찾아가기", "수신기 연결 · VRS", "설정 · 구글 드라이브"))
            assertTrue(l, l in labels)
        assertEquals(listOf("작업", "측량", "장비 · 설정"), DrawerMenu.sections.map { it.title })
    }
    @Test fun drawerHasNoPlaceholderOrAiItemsAndNoHome() {
        assertTrue(DrawerMenu.allItems.none { it.id.startsWith("ai") || it.id == "diag" || it.id == "home" })
        assertTrue(DrawerMenu.allItems.all { it.enabled })
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
        // 0.1.29 T19: 사용자가 정하지 않은 폴 높이는 SP60 기본 1.8 m
        assertEquals(1.800, c.antenna.inputValueM, 1e-9); assertEquals("bottom_of_receiver_arp", c.antenna.refPointId)
        repeat(2) { f.emitNext() }; c.onMeasure(); repeat(6) { f.emitNext() }
        assertEquals(1.8687, c.points.single().antenna.apcHeightM, 1e-9)
        assertEquals(64.0 - 1.8687, c.points.single().groundHeightM!!, 1e-6)
    }
    @Test fun heightStepAndRefSwitchUpdateResultImmediately() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val c = ctrl(f)
        assertEquals(2.1991, c.antennaResult!!.totalToApcM, 1e-9)
        c.setRefPoint("bottom_of_antenna_mount"); assertEquals(2.1491, c.antennaResult!!.totalToApcM, 1e-9)
        c.stepAntennaCm(10); assertEquals(2.1, c.antenna.inputValueM, 1e-9); assertEquals(2.2491, c.antennaResult!!.totalToApcM, 1e-9) // 0.1.12: 10 cm 단위
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
        c.onMeasure()
        assertNotNull(c.ui.confirm) // 0.1.26: 주의는 확인 후 저장
        c.ui.answer(true)
        repeat(6) { f.emitNext() }
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

    /** 0.1.21: 핀치 줌 scaleBy — 배율·클램프·초점 보정(지도 제스처 로직) */
    @Test fun scaleByZoomsAndKeepsFocus() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val c = ctrl(f)
        repeat(3) { f.emitNext() }
        val before = c.dpPerM
        val c0 = c.mapState().center
        c.scaleBy(2.0, focusXM = 10.0, focusYM = -4.0)
        assertFalse(c.follow)
        assertEquals(before * 2.0, c.dpPerM, 1e-9)
        // focus stays: center moves by focus * (1 - 1/factor)
        assertEquals(c0.x + 10.0 * 0.5, c.mapState().center.x, 1e-9)
        assertEquals(c0.y + (-4.0) * 0.5, c.mapState().center.y, 1e-9)
        c.scaleBy(1000.0) // clamp at DP_PER_M_MAX
        assertEquals(kr.archsurvey.ui.SurveyController.DP_PER_M_MAX, c.dpPerM, 1e-9)
        c.scaleBy(1e-9) // clamp at DP_PER_M_MIN
        assertEquals(kr.archsurvey.ui.SurveyController.DP_PER_M_MIN, c.dpPerM, 1e-9)
    }
}
