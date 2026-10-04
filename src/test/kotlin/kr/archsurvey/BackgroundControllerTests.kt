package kr.archsurvey

import kr.archsurvey.georef.*
import kr.archsurvey.map.LocalProjection
import kr.archsurvey.ui.bg.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** WP-8 후속 UI: 업로드 배경 정합 화면(s34c/d/e)·배경 레이어 시트(s35/s36)의 상태 흐름. 가짜 예시 파일 + 진짜 정합 모듈. */
class BackgroundControllerTests {
    @get:Rule val tmp = TemporaryFolder()
    private fun make(): BackgroundController = BackgroundController(LocalProjection(36.5, 127.0), tmp.newFolder(), nowMs = { 1234L })

    @Test fun tinyGeoTiffRoundTripsThroughRealReader() {
        val f = tmp.newFile("a.tif"); f.writeBytes(TinyGeoTiff.write(200, 150, 0.5, 200000.0, 500000.0, 5186))
        val r = GeoTiffReader.read(f) as GeoTiffReader.Result.Ok
        assertEquals(200, r.info.width); assertEquals(150, r.info.height)
        val g = tmp.newFile("b.tif"); g.writeBytes(TinyGeoTiff.write(200, 150, 0.5, 0.0, 0.0, null))
        val o = UploadAligner.analyzeGeoTiff(g, null)
        assertEquals(AlignStatus.NEEDS_MANUAL, o.status)
    }

    @Test fun initialLayersAreFakeSatelliteThenCadastre() {
        val b = make()
        assertEquals(listOf("위성", "지적도"), b.layers.map { it.name })
        assertTrue(b.layers.all { it.fake && !it.deletable })
        assertEquals(1.0, b.layers[0].opacity, 0.0); assertEquals(0.6, b.layers[1].opacity, 0.0)
        assertTrue(b.mapLayers().isEmpty()); assertNull(b.mapCaption())
    }

    @Test fun goodGeoTiffGoesToResultThenImportsWithHalfOpacity() {
        val b = make(); b.openManage(); b.openPicker()
        b.pick(FakeSample.GOOD_TIF)
        assertEquals(BgScreen.RESULT, b.screen)
        assertEquals(AlignStatus.ALIGNED_AUTO, b.pending!!.outcome.status)
        assertEquals(5186, b.pending!!.outcome.crs!!.epsg)
        assertTrue("작업 위치 근처", b.pending!!.outcome.distanceToWorkKm!! < 1.0)
        assertTrue("미정합/대기 중에는 지도에 올리지 않음", b.mapLayers().isEmpty())
        b.importPending()
        assertEquals(BgScreen.OPACITY, b.screen)
        val up = b.uploads.single()
        assertEquals(0.5, up.opacity, 0.0); assertEquals("정합됨(자동)", up.badge); assertTrue(up.bg!!.shouldDraw)
        val poly = b.mapLayers().single().polygon
        assertEquals(4, poly.size)
        // 100 m x 75 m 도면이 작업 위치(지도 원점) 둘레에 놓인다
        val cx = poly.sumOf { it.x } / 4; val cy = poly.sumOf { it.y } / 4
        assertTrue("중심이 원점 근처 ($cx, $cy)", Math.hypot(cx, cy) < 1.0)
        val w = Math.hypot(poly[1].x - poly[0].x, poly[1].y - poly[0].y)
        assertEquals(100.0, w, 0.5)
        assertEquals("업로드 50%", b.mapCaption())
    }

    @Test fun opacityAndToggleAndDelete() {
        val b = make(); b.pick(FakeSample.GOOD_TIF); b.importPending()
        val id = b.uploads.single().id
        b.setOpacity(id, 1.7); assertEquals(1.0, b.layers.first { it.id == id }.opacity, 0.0)
        b.setOpacity(id, 0.0); assertTrue("투명도 0 이면 그리지 않음", b.mapLayers().isEmpty())
        b.setOpacity(id, 0.45); assertEquals(1, b.mapLayers().size)
        b.setEnabled(id, false); assertTrue(b.mapLayers().isEmpty()); assertNull(b.mapCaption())
        b.setEnabled(id, true)
        b.requestDelete("sat"); assertEquals("위성은 지울 수 없음", 3, b.layers.size)
        b.requestDelete(id); assertEquals("첫 번째 누름은 확인만", 3, b.layers.size); assertEquals(id, b.deleteArmedId)
        b.requestDelete(id); assertEquals(2, b.layers.size); assertTrue(b.mapLayers().isEmpty())
    }

    @Test fun noCoordinatesGoesToWarningAndNeverDrawn() {
        val b = make(); b.pick(FakeSample.NO_COORD_TIF)
        assertEquals(BgScreen.WARNING, b.screen)
        assertEquals(AlignStatus.NEEDS_MANUAL, b.pending!!.outcome.status)
        assertFalse("좌표 없는 파일은 좌표계 지정이 의미 없음", b.canPickCrs); assertTrue(b.canManual)
        b.importPending()                     // 정합 안 된 것은 불러올 수 없다
        assertTrue(b.uploads.isEmpty()); assertTrue(b.mapLayers().isEmpty())
        b.cancelUpload(); assertNull(b.pending); assertEquals(BgScreen.MANAGE, b.screen)
    }

    @Test fun wrongPlace100kmIsMismatchAndOffersCrsAndManual() {
        val b = make(); b.pick(FakeSample.WRONG_PLACE_TIF)
        assertEquals(BgScreen.WARNING, b.screen)
        val o = b.pending!!.outcome
        assertEquals(AlignStatus.MISMATCH, o.status)
        assertTrue(AlignWarning.FAR_FROM_WORK in o.warnings)
        assertTrue("약 100 km", o.distanceToWorkKm!! in 95.0..105.0)
        assertTrue(b.canPickCrs && b.canManual)
        b.importPending(); assertTrue(b.uploads.isEmpty())
    }

    @Test fun dxfNeedsCrsThenUserCrsAligns() {
        val b = make(); b.pick(FakeSample.MAP_DXF)
        assertEquals(BgScreen.WARNING, b.screen)
        assertEquals(AlignStatus.NEEDS_CRS, b.pending!!.outcome.status)
        assertTrue(b.canPickCrs); assertFalse("DXF 수동 정렬은 아직 연결 안 함", b.canManual)
        b.openCrsPick(); assertEquals(BgScreen.CRS_PICK, b.screen)
        b.chooseCrs(KoreanCrs.EPSG_5174)
        assertNotNull("변환 불가 좌표계는 안내만", b.notice); assertEquals(BgScreen.CRS_PICK, b.screen)
        b.chooseCrs(KoreanCrs.EPSG_5186)
        assertEquals(BgScreen.RESULT, b.screen)
        assertEquals(AlignStatus.ALIGNED_USER_CRS, b.pending!!.outcome.status)
        b.importPending()
        val up = b.uploads.single()
        assertEquals("정합됨(좌표계 지정)", up.badge)
        assertEquals(4, b.mapLayers().single().polygon.size)
    }

    @Test fun dxfWrongCrsChoiceStaysInWarning() {
        val b = make(); b.pick(FakeSample.MAP_DXF)
        b.openCrsPick(); b.chooseCrs(KoreanCrs.EPSG_5185)   // 서부원점으로 읽으면 작업 위치에서 수백 km
        assertEquals(BgScreen.WARNING, b.screen)
        assertTrue(b.uploads.isEmpty())
    }

    @Test fun manualAlignmentTwoThreeFourPairs() {
        val b = make(); b.pick(FakeSample.NO_COORD_TIF); b.startManual()
        assertEquals(BgScreen.MANUAL, b.screen); assertFalse(b.manualCanApply)
        b.addFakePair(); assertNull(b.manualFit); assertFalse(b.manualCanApply)
        b.addFakePair()
        assertEquals(FitKind.SIMILARITY_2PT, b.manualFit!!.kind); assertTrue(b.manualCanApply)
        assertTrue(b.manualHints().any { it.contains("2개") })
        assertEquals("미리보기 윤곽이 지도에 올라간다", true, b.mapLayers().any { it.preview })
        b.addFakePair()
        assertEquals(FitKind.AFFINE_3PT_EXACT, b.manualFit!!.kind)
        assertTrue(b.manualHints().any { it.contains("검산") })
        assertEquals("3점은 잔차 0", 0.0, b.manualFit!!.rms, 1e-6)
        b.addFakePair()
        val f = b.manualFit!!
        assertEquals(FitKind.AFFINE_LSQ, f.kind); assertEquals(4, f.residuals.size)
        assertTrue("손 오차가 있어 잔차가 0 보다 크고 작다 (${f.rms})", f.rms in 0.001..0.2)
        assertFalse(b.manualCanAddPair)
        b.removeLastPair(); assertEquals(FitKind.AFFINE_3PT_EXACT, b.manualFit!!.kind)
        b.addFakePair()
        // 적용: 원본 파일 그대로, 변환만 저장, 배지에 RMS
        b.applyManual()
        val up = b.uploads.single()
        assertEquals(BgStatus.ALIGNED_MANUAL, up.bg!!.status)
        assertTrue(up.badge!!.startsWith("정합됨(수동, RMS ") && up.badge!!.endsWith(" m)"))
        assertEquals(0.5, up.opacity, 0.0)
        assertEquals(BgScreen.OPACITY, b.screen); assertTrue(b.mapLayers().none { it.preview })
        // 참값 변환과 비교: 복원된 도면 중심이 작업 위치 근처(손 오차 수준)
        val poly = b.mapLayers().single().polygon
        val cx = poly.sumOf { it.x } / 4; val cy = poly.sumOf { it.y } / 4
        assertTrue("복원 중심 오차 ${Math.hypot(cx, cy)} m", Math.hypot(cx, cy) < 0.3)
    }

    @Test fun manualNotAvailableForDxfAndCancelKeepsLayersUntouched() {
        val b = make(); b.pick(FakeSample.MAP_DXF); b.startManual()
        assertEquals("DXF 는 수동 정렬 화면으로 가지 않음", BgScreen.WARNING, b.screen)
        b.cancelUpload(); assertEquals(2, b.layers.size)
    }

    @Test fun uiTextHasNoBannedWordsAndSheetsStayBelowHalfScreen() {
        val banned = listOf("공사", "굴착", "시공", "측설", "성토", "절토", "준공", "검측", "노선", "도로")
        val src = java.io.File("src/main/kotlin/kr/archsurvey/ui/bg").listFiles()!!.joinToString("\n") { it.readText() }
        for (w in banned) assertFalse("금지어 '$w'", src.contains(w))
        assertEquals(0.5f, SHEET_MAX_FRACTION, 0f)
    }
}
