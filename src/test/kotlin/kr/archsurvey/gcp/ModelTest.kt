package kr.archsurvey.gcp

import kr.archsurvey.gcp.model.*
import org.junit.Assert.*
import org.junit.Test

class ModelTest {
    private val site = Site(1, "강릉 ○○유적", "GN", 5187, 0)
    private val feat = Obj(10, 1, 2, "GN-A002", ObjKind.FEATURE, "2호 수혈")
    private val sec = Obj(11, 1, 7, "GN-A007", ObjKind.SECTION, "B—B′ 토층")
    private val s2 = Session(100, 10, 2, "완토", listOf(1, 2, 3, 4), createdAt = 0)

    @Test fun ids_formatPadAndOperator() {
        assertEquals("GN-A012", ObjIds.format("GN", "A", 12))
        assertEquals("GN-012", ObjIds.format("GN", "", 12))
        assertEquals("GN-1234", ObjIds.format("GN", "", 1234))
        assertEquals(1, ObjIds.nextSeq(emptyList()))
        assertEquals(13, ObjIds.nextSeq(listOf(3, 12, 7)))   // 빈 번호를 채우지 않음(지운 번호 재사용 금지)
        assertEquals("GN-" to "A012", ObjIds.split("GN-A012"))
    }

    @Test fun siteCode_rules() {
        assertNull(ObjIds.codeProblem("gn", emptyList()))
        assertEquals("GN", ObjIds.normalizeCode(" g-n "))
        assertNotNull(ObjIds.codeProblem("", emptyList()))
        assertNotNull(ObjIds.codeProblem("1A", emptyList()))
        assertNotNull(ObjIds.codeProblem("ABCDE", emptyList()))
        assertNotNull(ObjIds.codeProblem("gn", listOf("GN")))
    }

    @Test fun names_folderXmlTxtPhotoPaths() {
        assertEquals("GN-A002_2호수혈", Names.objFolder(feat))
        assertEquals("GN-A002_2호수혈_2차", Names.photoFolder(feat, s2))
        assertEquals("GN-A002_2호수혈_2차_EPSG5187.xml", Names.xmlName(feat, s2, 5187))
        assertEquals("GN-A002_2호수혈_2차_EPSG5187.txt", Names.txtName(feat, s2, 5187))
        assertEquals("GN-A007_BB토층", Names.objFolder(sec))
        assertEquals("iTwinGCP/강릉 ○○유적/GN-A002_2호수혈", Names.objRel(site, feat))
        assertEquals("강릉유적_목록.csv", Names.listCsvName(site))
        assertEquals("a b", Names.siteFolder(site.copy(name = "a/ b")))
        assertEquals("대상", Names.safe("—′ "))
    }

    @Test fun autoNumber_featureAndSection() {
        assertEquals(5, AutoNumber.nextFeatureNo(listOf("1호 주거지", "4호 수혈", "2호 수혈")))
        assertEquals(1, AutoNumber.nextFeatureNo(emptyList()))
        assertEquals("C", AutoNumber.nextSectionLetter(listOf("A—A′ 토층", "B—B′ 토층")))
        assertEquals("AA", AutoNumber.nextSectionLetter(listOf("Z—Z′ 토층")))
        assertEquals("A", AutoNumber.nextSectionLetter(emptyList()))
        assertEquals("5호 수혈", AutoNumber.featureName(5, "수혈"))
        assertEquals("C—C′ 토층", AutoNumber.sectionName("C"))
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
        val near = Obj(2, 1, 3, "GN-A003", ObjKind.FEATURE, "3호 수혈") to listOf(shot(1, 9, 1, 100.0, 100.0, 0.0), shot(2, 9, 2, 105.0, 100.0, 0.0))
        val far = Obj(3, 1, 4, "GN-A004", ObjKind.FEATURE, "4호") to listOf(shot(3, 8, 3, 500.0, 500.0, 0.0))
        val c = TargetChecks.clashes(1, 110.0, 100.0, listOf(near, far))
        assertEquals(1, c.size); assertEquals("GN-A003", c[0].objCode); assertEquals(10.0, c[0].distanceM, 1e-9)
        assertTrue(TargetChecks.clashes(3, 110.0, 100.0, listOf(near, far)).isEmpty())   // 3번은 500 m 밖
        assertTrue(TargetChecks.clashes(1, 130.1, 100.0, listOf(near)).isEmpty())          // 20 m 밖
        assertEquals(setOf(1, 2), TargetChecks.nearbyTags(110.0, 100.0, listOf(near, far)))
        assertEquals(listOf(3, 4, 5, 6), TargetChecks.suggestSlots(setOf(1, 2)))
        assertEquals(listOf(1, 2, 3, 4), TargetChecks.suggestSlots(emptySet()))
        assertEquals(7, TargetChecks.nextFree(setOf(5, 6), 5))
        assertFalse(TargetChecks.validTag(10000)); assertTrue(TargetChecks.validTag(0))
    }

    @Test fun qrPayload_compactRanges() {
        assertEquals("GN-A002|2차|EPSG5187|T1-4", QrPayload.of("GN-A002", 2, 5187, listOf(4, 2, 3, 1)))
        assertEquals("1-2,5,7-9", QrPayload.ranges(listOf(1, 2, 5, 7, 8, 9)))
        assertEquals("-", QrPayload.ranges(emptyList()))
    }
}
