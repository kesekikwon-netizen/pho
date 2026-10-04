package kr.archsurvey

import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import org.junit.Assert.*
import org.junit.Test

private val DB: Map<String, AntennaModel> by lazy { AntennaDb.parse(java.io.File("src/main/assets/antenna_db.json").readText()) }
private val R10 get() = DB["TRIMBLE_R10"]!!
private val SP60 get() = DB["SPECTRA_SP60"]!!

class AntennaDbTest {
    @Test fun bundledDbMatchesResearchValues() {
        assertEquals(2, DB.size)
        assertEquals(0.1491, R10.ref("bottom_of_antenna_mount").pcoV, 1e-9)
        assertEquals(0.1991, R10.ref("bottom_of_quick_release").pcoV, 1e-9)
        assertEquals(0.0687, SP60.ref("bottom_of_receiver_arp").pcoV, 1e-9)
        assertEquals("R10 기본은 마운트 하단", "bottom_of_antenna_mount", R10.defaultRefPoint)
        assertEquals("bottom_of_receiver_arp", SP60.defaultRefPoint)
        assertEquals(0.0804, AntennaMath.apcDifference(R10, SP60), 1e-9)
        assertEquals(0.073, SP60.ref("slant_height_mark").tapeCorrection!!, 1e-9)
        assertFalse(R10.nmeaCarriesAntennaHeight)
    }
    @Test fun advancedRefsHiddenFromNormalList() {
        assertTrue(R10.visibleRefs.map { it.id }.containsAll(listOf("bottom_of_antenna_mount", "bottom_of_quick_release")))
        assertTrue(R10.visibleRefs.none { it.slant })
        assertTrue(SP60.advancedRefs.any { it.id == "slant_height_mark" })
    }
    @Test fun unknownRefFallsBackToDefault() { assertEquals("bottom_of_antenna_mount", R10.ref("zzz").id) }
}

class AntennaMathTest {
    @Test fun parseMetersAndCentimeters() {
        assertEquals(1.8, (AntennaMath.parse("1.8", HeightUnit.M) as AntennaMath.ParseResult.Ok).meters, 1e-9)
        assertEquals(1.8, (AntennaMath.parse("180", HeightUnit.CM) as AntennaMath.ParseResult.Ok).meters, 1e-9)
        assertEquals(1.8, (AntennaMath.parse(" 1,8 ", HeightUnit.M) as AntennaMath.ParseResult.Ok).meters, 1e-9)
    }
    @Test fun validation() {
        for (bad in listOf("-1", "", "abc", "NaN", "6", "Infinity")) assertTrue(bad, AntennaMath.parse(bad, HeightUnit.M) is AntennaMath.ParseResult.Error)
        assertTrue(AntennaMath.parse("600", HeightUnit.CM) is AntennaMath.ParseResult.Error)
        assertTrue(AntennaMath.parse("0", HeightUnit.M) is AntennaMath.ParseResult.Ok)
        assertTrue(AntennaMath.isUnusual(0.5)); assertTrue(AntennaMath.isUnusual(3.5)); assertFalse(AntennaMath.isUnusual(2.0))
    }
    @Test fun stepClamps() {
        assertEquals(1.81, AntennaMath.step(1.8, 1), 1e-9)
        assertEquals(1.7, AntennaMath.step(1.8, -10), 1e-9)
        assertEquals(0.0, AntennaMath.step(0.0, -1), 1e-9)
        assertEquals(5.0, AntennaMath.step(4.995, 10), 1e-9)
    }
    @Test fun verticalAppliesModelOffset() {
        val r = AntennaMath.compute(AntennaConfig(2.0, "bottom_of_antenna_mount"), R10)!!
        assertEquals(2.1491, r.totalToApcM, 1e-9); assertEquals("vertical", r.method); assertEquals(0.1491, r.pcoV, 1e-9)
        val s = AntennaMath.compute(AntennaConfig(2.0, "bottom_of_receiver_arp"), SP60)!!
        assertEquals(2.0687, s.totalToApcM, 1e-9)
        assertEquals("같은 폴 높이의 APC 차이 약 8.04cm", 0.0804, r.totalToApcM - s.totalToApcM, 1e-9)
        assertEquals(2.1991, AntennaMath.compute(AntennaConfig(2.0, "bottom_of_quick_release"), R10)!!.totalToApcM, 1e-9)
    }
    // ---- 필수 3종 (antenna-height.md §10.6): 어댑터 두께 이중 가산 금지 ----
    @Test fun mustHave1_quickReleaseBase_2000_gives_2_1991() {
        assertEquals(2.1991, AntennaMath.compute(AntennaConfig(2.000, "bottom_of_quick_release"), R10)!!.totalToApcM, 1e-9)
    }
    @Test fun mustHave2_mountBottom_2050_gives_2_1991() {
        assertEquals(2.1991, AntennaMath.compute(AntennaConfig(2.050, "bottom_of_antenna_mount"), R10)!!.totalToApcM, 1e-9)
    }
    @Test fun mustHave3_sp60Arp_2000_gives_2_0687() {
        assertEquals(2.0687, AntennaMath.compute(AntennaConfig(2.000, "bottom_of_receiver_arp"), SP60)!!.totalToApcM, 1e-9)
    }
    @Test fun adapterHeightIsNeverAddedByTheMath() {
        val ad = R10.adapters.first { it.id == "trimble_qr_89845" }
        assertEquals(0.05, ad.heightM!!, 1e-9); assertTrue(ad.doNotAddSeparately)
        // QR 기준 pcoV = 마운트 기준 pcoV + QR 두께 (이미 포함)
        assertEquals(R10.ref("bottom_of_antenna_mount").pcoV + ad.heightM!!, R10.ref("bottom_of_quick_release").pcoV, 1e-9)
    }
    @Test fun mismatchWarningOnlyWarnsAndNeverChangesValue() {
        val qr205 = AntennaConfig(2.05, "bottom_of_quick_release")
        assertNotNull(AntennaMath.mismatchWarning(qr205, R10))
        assertEquals(2.05, qr205.inputValueM, 0.0)
        assertNotNull(AntennaMath.mismatchWarning(AntennaConfig(2.00, "bottom_of_antenna_mount"), R10))
        assertNull(AntennaMath.mismatchWarning(AntennaConfig(2.00, "bottom_of_quick_release"), R10))
        assertNull(AntennaMath.mismatchWarning(AntennaConfig(2.05, "bottom_of_antenna_mount"), R10))
        assertNull("SP60 은 경고 대상 아님", AntennaMath.mismatchWarning(AntennaConfig(2.0, "bottom_of_receiver_arp"), SP60))
    }
    @Test fun baseExtensionAndExtraPartsAddedToMeasuredLength() {
        val r = AntennaMath.compute(AntennaConfig(1.2, "bottom_of_quick_release", baseExtension = true, extraPartsM = 0.25), R10)!!
        assertEquals(1.2 + 0.15 + 0.25 + 0.1991, r.totalToApcM, 1e-9)
        assertEquals(0.15, r.baseExtM, 1e-9); assertEquals(0.25, r.extraPartsM, 1e-9)
    }
    @Test fun summaryLine() {
        val r = AntennaMath.compute(AntennaConfig(2.000, "bottom_of_quick_release"), R10)!!
        assertEquals("폴 2.000 m + 어댑터·기기 보정 0.199 m = 안테나 위상중심 2.199 m", AntennaMath.summary(r))
    }
    @Test fun slantFormulasMatchResearch() {
        // R10 레버: Hv = sqrt(Hs^2 - 0.12^2) + 0.3491
        val hs = 1.5
        val r = AntennaMath.compute(AntennaConfig(hs, "lever_of_r10_extension"), R10)!!
        assertEquals(Math.sqrt(hs * hs - 0.12 * 0.12) + 0.3491, r.totalToApcM, 1e-9)
        assertEquals("slant", r.method)
        // SP60 높이표시 + 줄자 보정 0.073
        val a = AntennaMath.compute(AntennaConfig(1.5, "slant_height_mark", tapeOn = true), SP60)!!
        val e = Math.sqrt(1.573 * 1.573 - 0.10169 * 0.10169) + 0.02795
        assertEquals(e, a.totalToApcM, 1e-9)
        val b = AntennaMath.compute(AntennaConfig(1.5, "slant_height_mark", tapeOn = false), SP60)!!
        assertTrue(a.totalToApcM > b.totalToApcM)
        assertNull("사선이 수평 오프셋보다 짧으면 계산 불가", AntennaMath.compute(AntennaConfig(0.05, "lever_of_r10_extension"), R10))
    }
    @Test fun groundHeight() {
        val cfg = AntennaConfig(2.0, "bottom_of_antenna_mount")
        val res = AntennaMath.compute(cfg, R10)
        assertEquals(100.0 - 2.1491, AntennaMath.groundHeight(100.0, cfg, res)!!, 1e-9)
        assertEquals("수신기 반영이면 이중 적용 금지", 100.0, AntennaMath.groundHeight(100.0, cfg.copy(receiverApplied = true), res)!!, 1e-9)
        assertNull(AntennaMath.groundHeight(null, cfg, res))
        assertNull(AntennaMath.groundHeight(100.0, cfg, null))
    }
    @Test fun formatUnits() {
        assertEquals("1.800 m", AntennaMath.format(1.8, HeightUnit.M))
        assertEquals("180.0 cm", AntennaMath.format(1.8, HeightUnit.CM))
    }
    @Test fun instructionText() {
        assertEquals("R10 · 마운트 하단부터 재세요", AntennaMath.instruction("R10", R10.defaultRef))
        assertEquals("SP60 · 수신기 바닥면부터 재세요", AntennaMath.instruction("SP60", SP60.defaultRef))
        assertTrue(AntennaMath.MODEL_CHANGE_NOTICE.contains("약 8cm"))
    }
}

class AntennaResolverTest {
    @Test fun initialUsesModelDefaultRef() {
        assertEquals("bottom_of_receiver_arp", AntennaResolver.initial(SP60).refPointId)
        assertEquals(AntennaSource.DEFAULT, AntennaResolver.initial(R10).source)
    }
    @Test fun proposalOnModelChangeKeepsHeightButResetsRef() {
        val cur = AntennaConfig(1.65, "bottom_of_quick_release", source = AntennaSource.USER)
        val p = AntennaResolver.proposeDefault(cur, SP60)
        assertEquals(1.65, p.inputValueM, 1e-9); assertEquals("bottom_of_receiver_arp", p.refPointId); assertEquals(AntennaSource.DEFAULT, p.source)
    }
    @Test fun receiverReportOverridesDefaultButNotUser() {
        val d = AntennaConfig(1.8, "x", source = AntennaSource.DEFAULT)
        val r = AntennaResolver.applyReceiverReport(d, 1.65)
        assertEquals(1.65, r.inputValueM, 1e-9); assertEquals(AntennaSource.RECEIVER, r.source)
        val u = AntennaConfig(1.5, "x", source = AntennaSource.USER)
        assertSame(u, AntennaResolver.applyReceiverReport(u, 1.65))
        assertSame(d, AntennaResolver.applyReceiverReport(d, null))
        assertSame(d, AntennaResolver.applyReceiverReport(d, -3.0))
    }
    @Test fun userEditWinsAndConfirmOnModelChange() {
        val edited = AntennaResolver.userEdit(AntennaConfig(1.5, "bottom_of_quick_release"))
        assertEquals(AntennaSource.USER, edited.source)
        val proposed = AntennaResolver.proposeDefault(edited, R10)
        assertTrue(AntennaResolver.needsOverwriteConfirm(edited, proposed))
        assertFalse(AntennaResolver.needsOverwriteConfirm(AntennaConfig(1.8, "bottom_of_quick_release", source = AntennaSource.DEFAULT), proposed))
        assertFalse(AntennaResolver.needsOverwriteConfirm(AntennaConfig(1.8, "bottom_of_antenna_mount", source = AntennaSource.USER), proposed))
    }
}
