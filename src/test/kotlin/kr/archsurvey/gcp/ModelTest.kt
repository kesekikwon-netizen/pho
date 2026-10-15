package kr.archsurvey.gcp

import kr.archsurvey.gcp.model.*
import org.junit.Assert.*
import org.junit.Test

class ModelTest {
    private val site = Site(1, "강릉 성남동 유적", 5187, 0)
    private val feat = Obj(10, 1, ObjKind.PIT, 2)
    private val dwell = Obj(12, 1, ObjKind.DWELLING, 12)
    private val sec = Obj(11, 1, ObjKind.SECTION, 2)
    private val s2 = Session(100, 10, 2, "완토", listOf(1, 2, 3, 4), createdAt = 0)

    @Test fun objNames_koreanLabelsShortAndFolders() {
        assertEquals("2호 수혈", feat.name); assertEquals("2호", feat.short); assertEquals("002호_수혈", feat.code)
        assertEquals("12호 주거지", dwell.name); assertEquals("012호_주거지", dwell.code)
        assertEquals("B—B′ 토층", sec.name); assertEquals("B—B′", sec.short); assertEquals("B_토층", sec.code)
        assertEquals("999호_가마", ObjNames.folder(ObjKind.KILN, 999))
        assertEquals("A", ObjNames.letter(1)); assertEquals("Z", ObjNames.letter(26)); assertEquals("AA", ObjNames.letter(27)); assertEquals("AZ", ObjNames.letter(52)); assertEquals("BA", ObjNames.letter(53))
        assertEquals(listOf("주거지", "수혈", "구", "주혈", "건물지", "무덤", "가마", "토층", "기타"), ObjKind.entries.map { it.ko })
    }

    @Test fun objNumbers_nextAfterMax_noReuse_cap999() {
        assertEquals(1, ObjNames.next(emptyList()))
        assertEquals(13, ObjNames.next(listOf(3, 12, 7)))      // 빈 번호를 채우지 않음
        assertEquals(1, ObjNames.next((2..999).toList()))     // 999 다 쓰면 빈 번호
        assertTrue(ObjNames.valid(1)); assertTrue(ObjNames.valid(999)); assertFalse(ObjNames.valid(0)); assertFalse(ObjNames.valid(1000))
        // 폴더 이름 정렬 = 호수 순
        val l = listOf(ObjNames.folder(ObjKind.PIT, 10), ObjNames.folder(ObjKind.DWELLING, 2), ObjNames.folder(ObjKind.PIT, 100))
        assertEquals(listOf("002호_주거지", "010호_수혈", "100호_수혈"), l.sorted())
    }

    @Test fun names_folderXmlTxtPhotoPaths() {
        assertEquals("002호_수혈", Names.objFolder(feat))
        assertEquals("002호_수혈_2차", Names.photoFolder(feat, s2))
        assertEquals("002호_수혈_2차_EPSG5187.xml", Names.xmlName(feat, s2, 5187))
        assertEquals("002호_수혈_2차_EPSG5187.txt", Names.txtName(feat, s2, 5187))
        assertEquals("B_토층_2차_EPSG5186.xml", Names.xmlName(sec, s2, 5186))
        assertEquals("발굴조사/사진 기준점/강릉 성남동 유적/012호_주거지", Names.objRel(site, dwell))
        assertEquals("강릉성남동유적_목록.csv", Names.listCsvName(site))
        assertEquals("a b", Names.siteFolder(site.copy(name = "a/ b")))
        assertEquals("유적", Names.siteFolder(site.copy(name = "/")))
    }

    @Test fun sessionLabels_tilesAndNext() {
        assertEquals(listOf("검출면", "중간", "완토", "바닥"), SessionLabels.ALL)
        assertEquals("중간", SessionLabels.next("검출면")); assertEquals("검출면", SessionLabels.next(null)); assertEquals("바닥", SessionLabels.next("바닥"))
    }

    @Test fun siteNames_fromAddressRecentAndDate_plusDistrict() {
        val a = AddressParts("강원특별자치도", "강릉시", "성남동", "중앙동")
        val today = java.time.LocalDate.of(2026, 10, 12)
        assertEquals(listOf("강릉 성남동 유적", "성남동 유적", "강릉 중앙동 유적", "광령리 유적", "유적 2026-10-12"), SiteNames.suggest(a, listOf("광령리 유적 3지구", "광령리 유적"), today))
        assertEquals(listOf("유적 2026-10-12"), SiteNames.suggest(null, emptyList(), today))
        assertEquals("수원", SiteNames.city("수원시 팔달구")); assertEquals("양평", SiteNames.city("양평군")); assertEquals("중구", SiteNames.city("중구"))
        assertEquals("성남동 유적 2지구", SiteNames.withDistrict("성남동 유적", 2)); assertEquals("성남동 유적", SiteNames.withDistrict("성남동 유적", null))
        assertEquals("가 (2)", SiteNames.unique("가", listOf("가"))); assertEquals("가 (3)", SiteNames.unique("가", listOf("가", "가 (2)"))); assertEquals("나", SiteNames.unique("나", listOf("가")))
    }

    @Test fun vworldAddress_parsesParcelLevels() {
        val json = """{"response" : {"status" : "OK", "result" : [{"type" : "parcel", "text" : "강원특별자치도 강릉시 성남동 108-8", "structure" : {"level0" : "대한민국", "level1" : "강원특별자치도", "level2" : "강릉시", "level3" : "", "level4L" : "성남동", "level4A" : "중앙동", "level5" : "108-8"}}]}}"""
        assertEquals(AddressParts("강원특별자치도", "강릉시", "성남동", "중앙동"), kr.archsurvey.gcp.engine.VWorldAddress.parse(json))
        assertNull(kr.archsurvey.gcp.engine.VWorldAddress.parse("""{"response":{"status":"NOT_FOUND"}}"""))
        assertTrue(kr.archsurvey.gcp.engine.VWorldAddress.url("K", 37.75, 128.89).contains("point=128.890000,37.750000"))
    }

    @Test fun epsg_fromLongitude() {
        assertEquals(5185, kr.archsurvey.gcp.ui.GcpController.epsgForLon(125.5)); assertEquals(5186, kr.archsurvey.gcp.ui.GcpController.epsgForLon(127.0))
        assertEquals(5187, kr.archsurvey.gcp.ui.GcpController.epsgForLon(128.9)); assertEquals(5188, kr.archsurvey.gcp.ui.GcpController.epsgForLon(130.9))
    }

    @Test fun pipeline_measurePhotoHandoffDone() {
        val s = Session(1, 1, 1, "검출면", listOf(1, 2, 3, 4), createdAt = 10, changedAt = 10)
        assertEquals(Stage.MEASURE, Pipeline.stage(2, s))
        assertEquals(Stage.PHOTO, Pipeline.stage(3, s))
        assertEquals(Stage.HANDOFF, Pipeline.stage(3, s.copy(photoDoneAt = 20)))
        assertEquals(Stage.DONE, Pipeline.stage(4, s.copy(photoDoneAt = 20, deliveredAt = 30)))
        // 보낸 뒤 다시 재면 다시 전달 필요
        assertEquals(Stage.HANDOFF, Pipeline.stage(4, s.copy(photoDoneAt = 20, deliveredAt = 30, changedAt = 40)))
        assertFalse(Pipeline.canMarkPhoto(2)); assertTrue(Pipeline.canMarkPhoto(3))
    }

    @Test fun activeTags_latestPerTagOnly() {
        val l = listOf(shot(1, 1, 1, 0.0, 0.0, 0.0, at = 1, active = false), shot(2, 1, 1, 1.0, 0.0, 0.0, at = 2), shot(3, 1, 3, 0.0, 0.0, 0.0), shot(4, 2, 1, 9.0, 9.0, 9.0))
        val a = Pipeline.activeTags(l, 1)
        assertEquals(listOf(1, 3), a.map { it.tag }); assertEquals(1.0, a[0].e, 0.0)
    }

    @Test fun checks_movedOver2cm() {
        val p = shot(1, 1, 1, 100.0, 200.0, 5.0)
        assertNull(TargetChecks.moved(null, 0.0, 0.0, 0.0))
        assertNull(TargetChecks.moved(p, 100.012, 200.0, 5.015))          // 1.9 cm
        assertNull(TargetChecks.moved(p, 100.02, 200.0, 5.0))             // 정확히 2 cm = 괜찮음
        assertEquals(0.03, TargetChecks.moved(p, 100.0, 200.03, 5.0)!!, 1e-9)
    }

    @Test fun checks_clashWithin20m_andSuggestFreeNumbers() {
        val near = Obj(2, 1, ObjKind.PIT, 3) to listOf(shot(1, 9, 1, 100.0, 100.0, 0.0), shot(2, 9, 2, 105.0, 100.0, 0.0))
        val far = Obj(3, 1, ObjKind.PIT, 4) to listOf(shot(3, 8, 3, 500.0, 500.0, 0.0))
        val c = TargetChecks.clashes(1, 110.0, 100.0, listOf(near, far))
        assertEquals(1, c.size); assertEquals("3호 수혈", c[0].objName); assertEquals(10.0, c[0].distanceM, 1e-9)
        assertTrue(TargetChecks.clashes(3, 110.0, 100.0, listOf(near, far)).isEmpty())   // 3번은 500 m 밖
        assertTrue(TargetChecks.clashes(1, 130.1, 100.0, listOf(near)).isEmpty())          // 20 m 밖
        assertEquals(setOf(1, 2), TargetChecks.nearbyTags(110.0, 100.0, listOf(near, far)))
        assertEquals(listOf(3, 4, 5, 6), TargetChecks.suggestSlots(setOf(1, 2)))
        assertEquals(listOf(1, 2, 3, 4), TargetChecks.suggestSlots(emptySet()))
        assertEquals(7, TargetChecks.nextFree(setOf(5, 6), 5))
        assertFalse(TargetChecks.validTag(1000)); assertFalse(TargetChecks.validTag(0)); assertTrue(TargetChecks.validTag(999))
    }

    @Test fun qrPayload_compactRanges() {
        assertEquals("002호_수혈|2차|EPSG5187|T1-4", QrPayload.of(feat.code, 2, 5187, listOf(4, 2, 3, 1)))
        assertEquals("1-2,5,7-9", QrPayload.ranges(listOf(1, 2, 5, 7, 8, 9)))
        assertEquals("-", QrPayload.ranges(emptyList()))
    }
}
