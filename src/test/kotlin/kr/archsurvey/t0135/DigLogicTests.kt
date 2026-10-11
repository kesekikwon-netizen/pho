package kr.archsurvey.t0135

import kr.archsurvey.core.NE
import kr.archsurvey.dig.*
import org.junit.Assert.*
import org.junit.Test

/** 0.1.35 발굴 전용 로직(화면 없음): 종류·유구 이름·유물 번호·유구 안 판정·깊이·기준점 확인·말뚝 안내·토층 단면·오늘 준비·도면 레이어 */
class DigLogicTests {
    private val eps = 1e-9

    // ---------- 종류 ----------
    @Test fun kindsAreArchaeologyEight() {
        // 0.1.36(의도된 변경): 「사진 기준점」(AprilTag) 추가 → 9종
        assertEquals(listOf("유구 경계", "유물", "레벨", "토층 단면", "트렌치", "그리드 말뚝", "기준점", "조사구역", "사진 기준점"), DigKind.entries.map { it.labelKo })
        assertTrue(DigKind.FEATURE.outlineKind && DigKind.TRENCH.outlineKind && DigKind.AREA.outlineKind)
        assertFalse(DigKind.ARTIFACT.outlineKind || DigKind.LEVEL.outlineKind)
    }
    @Test fun legacyPositionTypesMapToKinds() {
        assertEquals(DigKind.FEATURE, DigKind.fromPositionType("유구 경계점"))
        assertEquals(DigKind.ARTIFACT, DigKind.fromPositionType("유물 출토 위치"))
        assertEquals(DigKind.BM, DigKind.fromPositionType("기준점"))
        assertEquals(DigKind.LEVEL, DigKind.fromPositionType("레벨점"))
        assertEquals(DigKind.STAKE, DigKind.fromPositionType("그리드 모서리"))
        assertNull(DigKind.fromPositionType("기타"))
        assertNull(DigKind.fromPositionType("내가 만든 종류"))
        // 저장된 id 가 우선
        assertEquals(DigKind.SECTION, DigKind.resolve("section", "기타"))
        assertEquals(DigKind.ARTIFACT, DigKind.resolve("", "유물 출토 위치"))
        assertNull(DigKind.resolve("", "기타"))
    }

    // ---------- 유구 이름 ----------
    @Test fun featureNamesFollowNoHoType() {
        assertEquals("2호 수혈", FeatureNaming.name(2, "수혈"))
        assertEquals("가-3", FeatureNaming.provisional(3))
        val p = FeatureNaming.parse("12호 수혈주거지")
        assertEquals(12, p.no); assertEquals("수혈주거지", p.type); assertFalse(p.provisional)
        val q = FeatureNaming.parse("가-4"); assertEquals(4, q.no); assertTrue(q.provisional)
        val old = FeatureNaming.parse("E-001"); assertNull(old.no); assertEquals("E-001", old.type)
    }
    @Test fun nextFeatureNumberIsPerType() {
        val names = listOf("1호 수혈", "2호 수혈", "1호 주거지", "가-1", "E-001")
        assertEquals(3, FeatureNaming.nextNo(names, "수혈"))
        assertEquals(2, FeatureNaming.nextNo(names, "주거지"))
        assertEquals(1, FeatureNaming.nextNo(names, "주혈"))
        assertEquals(2, FeatureNaming.nextProvisional(names))
        assertEquals("가-2", FeatureNaming.newName(names, "수혈", provisional = true))
        assertEquals("3호 수혈", FeatureNaming.newName(names, "수혈", provisional = false))
    }
    @Test fun renameKeepsHistoryOnce() {
        val h1 = FeatureNaming.withHistory("", "가-3")
        assertEquals("가-3", h1)
        val h2 = FeatureNaming.withHistory(h1, "2호 수혈")
        assertEquals(listOf("가-3", "2호 수혈"), FeatureNaming.history(h2))
        assertEquals(h2, FeatureNaming.withHistory(h2, "2호 수혈")) // 같은 이름은 두 번 넣지 않음
        assertEquals("이름을 입력하세요", FeatureNaming.renameProblem(" ", listOf("1호 수혈")))
        assertEquals("같은 이름의 유구가 이미 있어요", FeatureNaming.renameProblem("1호 수혈", listOf("1호 수혈")))
        assertNull(FeatureNaming.renameProblem("3호 주거지", listOf("1호 수혈")))
    }
    @Test fun featureTypeListEncodeDecode() {
        assertTrue("수혈" in FeatureNaming.DEFAULT_TYPES && "주거지" in FeatureNaming.DEFAULT_TYPES)
        assertEquals(listOf("수혈", "고분"), FeatureNaming.decodeTypes("수혈\n 고분 \n\n수혈"))
        assertEquals(FeatureNaming.DEFAULT_TYPES, FeatureNaming.decodeTypes(null))
    }

    // ---------- 유물 번호 ----------
    @Test fun artifactNumberJobSerialByDefault() {
        val existing = listOf(ArtifactNo(10L, 11), ArtifactNo(10L, 12), ArtifactNo(20L, 3), ArtifactNo(null, 13))
        assertEquals(14, ArtifactNumbering.next(existing, featureId = 20L, mode = ArtifactMode.JOB_SERIAL))
        assertEquals(1, ArtifactNumbering.next(emptyList(), 10L, ArtifactMode.JOB_SERIAL))
    }
    @Test fun artifactNumberPerFeature() {
        val existing = listOf(ArtifactNo(10L, 11), ArtifactNo(10L, 12), ArtifactNo(20L, 3), ArtifactNo(null, 1))
        assertEquals(13, ArtifactNumbering.next(existing, 10L, ArtifactMode.PER_FEATURE))
        assertEquals(4, ArtifactNumbering.next(existing, 20L, ArtifactMode.PER_FEATURE))
        assertEquals(2, ArtifactNumbering.next(existing, null, ArtifactMode.PER_FEATURE)) // 유구 밖끼리
        assertEquals(1, ArtifactNumbering.next(existing, 30L, ArtifactMode.PER_FEATURE))
    }

    // ---------- 유구 안 판정 ----------
    private val square = listOf(NE(0.0, 0.0), NE(0.0, 4.0), NE(4.0, 4.0), NE(4.0, 0.0))
    private val small = listOf(NE(1.0, 1.0), NE(1.0, 2.0), NE(2.0, 2.0), NE(2.0, 1.0))
    @Test fun pointInPolygon() {
        assertTrue(DigGeometry.inside(NE(2.0, 2.0), square))
        assertFalse(DigGeometry.inside(NE(5.0, 2.0), square))
        assertFalse(DigGeometry.inside(NE(2.0, 2.0), square.take(2)))
    }
    @Test fun assignPrefersSmallestClosedFeatureContainingPoint() {
        val f = listOf(FeatureShape(1, "1호 주거지", true, square), FeatureShape(2, "1호 주혈", true, small))
        assertEquals(Assignment(2L, auto = true, outsideSelected = false), DigGeometry.assign(NE(1.5, 1.5), f, selected = null))
        assertEquals(Assignment(1L, auto = true, outsideSelected = false), DigGeometry.assign(NE(3.0, 3.0), f, selected = 2L))
    }
    @Test fun assignFallsBackToSelectedOpenFeatureOrNone() {
        val f = listOf(FeatureShape(1, "1호 주거지", true, square), FeatureShape(3, "2호 수혈", false, listOf(NE(10.0, 10.0), NE(10.0, 12.0))))
        assertEquals(Assignment(3L, auto = false, outsideSelected = false), DigGeometry.assign(NE(20.0, 20.0), f, selected = 3L))
        assertEquals(Assignment(null, auto = false, outsideSelected = false), DigGeometry.assign(NE(20.0, 20.0), f, selected = null))
        // 닫힌 유구를 골랐는데 면 밖: 고른 유구는 지키되 표시
        assertEquals(Assignment(1L, auto = false, outsideSelected = true), DigGeometry.assign(NE(20.0, 20.0), f, selected = 1L))
    }

    // ---------- 깊이 ----------
    @Test fun depthFromDetectionLevelsElseBoundary() {
        assertEquals(152.31, DigDepth.detectionRef(listOf(152.30, 152.32), listOf(150.0))!!, 1e-9)
        assertEquals(152.0, DigDepth.detectionRef(emptyList(), listOf(151.9, 152.1))!!, 1e-9)
        assertNull(DigDepth.detectionRef(emptyList(), emptyList()))
        assertEquals(0.42, DigDepth.depthBelow(152.32, 151.90)!!, 1e-9)
        assertEquals(-0.21, DigDepth.relative(152.31, 152.10)!!, 1e-9)
        assertNull(DigDepth.depthBelow(null, 151.9))
        assertEquals(151.91, DigDepth.mean(listOf(151.90, 151.92))!!, 1e-9)
        assertNull(DigDepth.mean(emptyList()))
    }

    // ---------- 기준점 확인 ----------
    @Test fun bmCheckPassesWithinTolerance() {
        val r = BmCheck.evaluate(NEH(100.0, 200.0, 50.0), NEH(100.006, 199.996, 50.011))
        assertEquals(0.006, r.dN, 1e-9); assertEquals(-0.004, r.dE, 1e-9); assertEquals(0.011, r.dH!!, 1e-9)
        assertTrue(r.pass)
        assertEquals("ΔN +0.6 · ΔE −0.4 · Δ해발 +1.1 cm", BmCheck.deltaText(r))
    }
    @Test fun bmCheckFailsOnHeightOrHorizontal() {
        assertFalse(BmCheck.evaluate(NEH(0.0, 0.0, 10.0), NEH(0.0, 0.0, 10.05)).pass)
        assertFalse(BmCheck.evaluate(NEH(0.0, 0.0, 10.0), NEH(0.03, 0.03, 10.0)).pass) // 수평 4.2 cm
        assertTrue(BmCheck.evaluate(NEH(0.0, 0.0, 10.0), NEH(0.03, 0.03, 10.0), tolM = 0.05).pass)
        assertTrue(BmCheck.evaluate(NEH(0.0, 0.0, null), NEH(0.01, 0.0, 12.0)).pass) // 높이 모름 → 수평만
        assertEquals(0.03, BmCheck.DEFAULT_TOL_M, eps)
    }

    // ---------- 그리드 말뚝 찾아가기 ----------
    @Test fun stakeGuidanceDistanceDirectionArrival() {
        val g = StakeGuide.of(NE(100.0, 100.0), NE(97.8, 99.02))
        assertEquals(Math.hypot(2.2, 0.98), g.distanceM, 1e-9)
        assertEquals("남서", g.dirKo)
        assertEquals("남 2.20 m · 서 0.98 m", g.offsetText)
        assertFalse(g.arrived)
        assertTrue(StakeGuide.of(NE(0.0, 0.0), NE(0.02, 0.0)).arrived)
        assertEquals("북", StakeGuide.of(NE(0.0, 0.0), NE(5.0, 0.1)).dirKo)
        assertEquals("동", StakeGuide.of(NE(0.0, 0.0), NE(0.0, 5.0)).dirKo)
    }

    // ---------- 토층 단면선 ----------
    @Test fun sectionLabelsPairUp() {
        assertEquals("A", SectionLabels.next(emptyList()))
        assertEquals("A′", SectionLabels.next(listOf("A")))
        assertEquals("B", SectionLabels.next(listOf("A", "A′")))
        assertEquals("B′", SectionLabels.next(listOf("A", "A′", "B")))
        assertEquals("A", SectionLabels.lineOf("A′"))
        assertEquals("A — A′", SectionLabels.lineName("A"))
        assertEquals(listOf("A"), SectionLabels.completeLines(listOf("A", "A′", "B")))
    }

    // ---------- 오늘 준비 5단계 ----------
    private fun prep(recv: Boolean = true, ntripOn: Boolean = true, ntripOk: Boolean = true, pole: Boolean = false, crs: Boolean = true, bm: Boolean? = null) =
        PrepInput(receiverLive = recv, receiverLabel = "R10", ntripEnabled = ntripOn, ntripReceiving = ntripOk, ntripLabel = "RTS1",
            poleM = 1.6, modelShort = "R10", poleConfirmedToday = pole, epsg = 5187, heightLabel = "수신기 값", crsReady = crs,
            bmName = "BM-1", bmCheckedToday = bm != null, bmPassedToday = bm, bmKnown = true)
    @Test fun todayPrepMarksFirstOpenStepAsNext() {
        val s = TodayPrep.steps(prep())
        assertEquals(listOf("receiver", "vrs", "pole", "crs", "bm"), s.map { it.id })
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.NEXT, StepState.DONE, StepState.TODO), s.map { it.state })
        assertEquals(3, TodayPrep.doneCount(s))
        assertEquals("맞아요", s[2].action)
        assertEquals("재기", s[4].action)
    }
    @Test fun todayPrepAllDoneAndVrsOffCountsAsDone() {
        val s = TodayPrep.steps(prep(ntripOn = false, pole = true, bm = true))
        assertTrue(s.all { it.state == StepState.DONE })
        assertTrue(TodayPrep.allDone(s))
        assertEquals("VRS 안 씀 · 수신기 보정만", s[1].sub)
        val fail = TodayPrep.steps(prep(pole = true, bm = false))
        assertEquals(StepState.NEXT, fail[4].state)
        assertTrue(fail[4].sub.contains("다시"))
        val noRecv = TodayPrep.steps(prep(recv = false))
        assertEquals(StepState.NEXT, noRecv[0].state)
        assertEquals("연결", noRecv[0].action)
    }

    // ---------- 도면 ----------
    @Test fun drawingLayersAndScaleTextHeights() {
        assertEquals("유구", DigLayers.of(DigKind.FEATURE, null))
        assertEquals("유물", DigLayers.of(DigKind.ARTIFACT, null))
        assertEquals("레벨", DigLayers.of(DigKind.LEVEL, LevelKind.FLOOR))
        assertEquals("토층", DigLayers.of(DigKind.SECTION, null))
        assertEquals("그리드", DigLayers.of(DigKind.STAKE, null))
        assertEquals("기준점", DigLayers.of(DigKind.BM, null))
        assertEquals("트렌치", DigLayers.of(DigKind.TRENCH, null))
        assertEquals("조사구역", DigLayers.of(DigKind.AREA, null))
        assertEquals("기타", DigLayers.of(null, null))
        assertEquals(listOf(20, 100, 200), DrawingScale.CHOICES)
        assertEquals(0.05, DrawingScale.textHeightM(20), 1e-9)
        assertEquals(0.25, DrawingScale.textHeightM(100), 1e-9)
        assertEquals(0.5, DrawingScale.textHeightM(200), 1e-9)
    }

    // ---------- 측량 중 좌표계 잠금 · 볼륨 키 ----------
    @Test fun crsLockAndVolumeKeyGate() {
        assertFalse(CrsLock.locked(pointCount = 0, unlocked = false))
        assertTrue(CrsLock.locked(pointCount = 3, unlocked = false))
        assertFalse(CrsLock.locked(pointCount = 3, unlocked = true))
        assertTrue(VolumeKeyGate.shouldMeasure(enabled = true, foreground = true, route = "survey", repeatCount = 0))
        assertFalse(VolumeKeyGate.shouldMeasure(enabled = false, foreground = true, route = "survey", repeatCount = 0))
        assertFalse(VolumeKeyGate.shouldMeasure(enabled = true, foreground = false, route = "survey", repeatCount = 0))
        assertFalse(VolumeKeyGate.shouldMeasure(enabled = true, foreground = true, route = "home", repeatCount = 0))
        assertFalse(VolumeKeyGate.shouldMeasure(enabled = true, foreground = true, route = "survey", repeatCount = 1))
    }

    // ---------- 원인 한 줄 ----------
    @Test fun causeLineExplainsWhyNoFix() {
        assertNull(CauseLine.of(CauseInput(noReceiver = false, fixed = true, floatOrWorse = false, ntripEnabled = true, ntripReceiving = true, ntripRetrying = false, rtcmAgeSec = 1.0, correctionAgeSec = 1.0, satellites = 20, rtcmStalled = false)))
        assertEquals(CauseLine.NO_RECEIVER, CauseLine.of(CauseInput(noReceiver = true)))
        assertEquals(CauseLine.INTERNET, CauseLine.of(CauseInput(floatOrWorse = true, ntripEnabled = true, ntripRetrying = true)))
        assertEquals(CauseLine.STALLED, CauseLine.of(CauseInput(floatOrWorse = true, ntripEnabled = true, ntripReceiving = true, rtcmStalled = true)))
        assertEquals("보정이 12초 늦게 와요 · 인터넷 신호를 확인하세요", CauseLine.of(CauseInput(floatOrWorse = true, ntripEnabled = true, ntripReceiving = true, rtcmAgeSec = 12.0)))
        assertEquals(CauseLine.SKY, CauseLine.of(CauseInput(floatOrWorse = true, ntripEnabled = true, ntripReceiving = true, rtcmAgeSec = 1.0, correctionAgeSec = 1.0, satellites = 9)))
        assertEquals(CauseLine.WAIT_FIX, CauseLine.of(CauseInput(floatOrWorse = true, ntripEnabled = true, ntripReceiving = true, rtcmAgeSec = 1.0, correctionAgeSec = 1.0, satellites = 22)))
    }

    // ---------- 하루 기록 ----------
    @Test fun dayKeyIsLocalCalendarDay() {
        val tz = java.util.TimeZone.getTimeZone("Asia/Seoul")
        // 2026-10-11 23:30 KST = 14:30 UTC
        val ms = java.time.ZonedDateTime.of(2026, 10, 11, 23, 30, 0, 0, java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        assertEquals("20261011", DayKey.of(ms, tz))
        assertEquals("20261012", DayKey.of(ms + 3600_000L, tz))
    }
}
