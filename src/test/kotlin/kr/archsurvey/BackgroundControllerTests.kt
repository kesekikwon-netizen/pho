package kr.archsurvey

import kr.archsurvey.georef.*
import kr.archsurvey.map.LocalProjection
import kr.archsurvey.ui.bg.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 배경 레이어 시트의 상태 흐름. 시험용 작은 파일(FakeUploads, test 전용)을 문서 선택기 대신 onPicked 로 넣고, 정합 판정은 진짜 모듈. */
class BackgroundControllerTests {
    @get:Rule val tmp = TemporaryFolder()
    private val storeDir by lazy { tmp.newFolder() }
    private val workDir by lazy { tmp.newFolder() }
    private fun make(store: BgLayerStore = JsonFileBgLayerStore(storeDir)): BackgroundController =
        BackgroundController({ LocalProjection(36.5, 127.0) }, store, nowMs = { 1234L }, cornersDir = storeDir)
    private fun BackgroundController.pick(s: FakeSample) { onPicked(listOf(FakeUploads.create(s, workDir, work.center))) }

    @Test fun tinyGeoTiffRoundTripsThroughRealReader() {
        val f = tmp.newFile("a.tif"); f.writeBytes(TinyGeoTiff.write(200, 150, 0.5, 200000.0, 500000.0, 5186))
        val r = GeoTiffReader.read(f) as GeoTiffReader.Result.Ok
        assertEquals(200, r.info.width); assertEquals(150, r.info.height)
        val g = tmp.newFile("b.tif"); g.writeBytes(TinyGeoTiff.write(200, 150, 0.5, 0.0, 0.0, null))
        val o = UploadAligner.analyzeGeoTiff(g, null)
        assertEquals(AlignStatus.NEEDS_MANUAL, o.status)
    }

    @Test fun startsEmptyWithNoFakeRows() {
        val b = make()
        assertTrue(b.layers.isEmpty()); assertTrue(b.mapLayers().isEmpty()); assertNull(b.mapCaption())
    }

    @Test fun goodGeoTiffGoesToResultThenImportsWithHalfOpacity() {
        val b = make(); b.openManage(); b.openPicker()
        b.pick(FakeSample.GOOD_TIF)
        assertEquals(BgScreen.RESULT, b.screen)
        assertEquals(AlignStatus.ALIGNED_AUTO, b.pending!!.outcome.status)
        assertEquals(5186, b.pending!!.outcome.crs!!.epsg)
        assertTrue("작업 위치 근처", b.pending!!.outcome.distanceToWorkKm!! < 1.0)
        assertTrue("대기 중에는 지도에 올리지 않음", b.mapLayers().isEmpty())
        b.importPending()
        assertEquals(BgScreen.OPACITY, b.screen)
        val up = b.uploads.single()
        assertEquals(0.5, up.opacity, 0.0); assertEquals("정합됨(자동)", up.badge); assertTrue(up.bg.shouldDraw)
        val poly = b.mapLayers().single().polygon
        assertEquals(4, poly.size)
        val cx = poly.sumOf { it.x } / 4; val cy = poly.sumOf { it.y } / 4
        assertTrue("중심이 원점 근처 ($cx, $cy)", Math.hypot(cx, cy) < 1.0)
        assertEquals(100.0, Math.hypot(poly[1].x - poly[0].x, poly[1].y - poly[0].y), 0.5)
        assertEquals("업로드 50%", b.mapCaption())
        // 0.1.58 B2: GeoTIFF 는 실제 그림 경로 + 화소 모서리(좌상·우상·우하·좌하)
        val ml = b.mapLayers().single()
        assertEquals(up.bg.filePath, ml.imagePath); assertEquals(4, ml.imageCorners.size)
        assertTrue("좌상이 좌하보다 북쪽", ml.imageCorners[0].y > ml.imageCorners[3].y)
        assertTrue("좌상이 우상보다 서쪽", ml.imageCorners[0].x < ml.imageCorners[1].x)
    }

    @Test fun opacityToggleDeleteAreSavedAndReloaded() {
        val store = JsonFileBgLayerStore(storeDir)
        val b = make(store); b.pick(FakeSample.GOOD_TIF); b.importPending()
        val id = b.uploads.single().id
        b.setOpacity(id, 1.7); assertEquals(1.0, b.layers.first { it.id == id }.opacity, 0.0)
        b.setOpacity(id, 0.0); assertTrue("투명도 0 이면 그리지 않음", b.mapLayers().isEmpty())
        b.setOpacity(id, 0.45); assertEquals(1, b.mapLayers().size)
        b.setEnabled(id, false); assertTrue(b.mapLayers().isEmpty()); assertNull(b.mapCaption())
        b.setEnabled(id, true)
        // 앱을 다시 켠 것처럼 새 컨트롤러로 읽기
        val b2 = make(JsonFileBgLayerStore(storeDir))
        assertEquals(1, b2.layers.size); assertEquals(0.45, b2.layers[0].opacity, 1e-9); assertTrue(b2.layers[0].enabled)
        assertEquals("모서리까지 복원되어 지도에 다시 올라간다", 1, b2.mapLayers().size)
        b.requestDelete(id); assertEquals("첫 번째 누름은 확인만", 1, b.layers.size); assertEquals(id, b.deleteArmedId)
        b.requestDelete(id); assertTrue(b.layers.isEmpty()); assertTrue(b.mapLayers().isEmpty())
        assertTrue("저장소에서도 삭제", make(JsonFileBgLayerStore(storeDir)).layers.isEmpty())
    }

    @Test fun noCoordinatesGoesToWarningAndNeverDrawn() {
        val b = make(); b.pick(FakeSample.NO_COORD_TIF)
        assertEquals(BgScreen.WARNING, b.screen)
        assertEquals(AlignStatus.NEEDS_MANUAL, b.pending!!.outcome.status)
        assertFalse("좌표 없는 파일은 좌표계 지정이 의미 없음", b.canPickCrs)
        b.importPending()
        assertTrue(b.uploads.isEmpty()); assertTrue(b.mapLayers().isEmpty())
        b.cancelUpload(); assertNull(b.pending); assertEquals(BgScreen.MANAGE, b.screen)
    }

    @Test fun wrongPlace100kmIsMismatchAndOffersCrs() {
        val b = make(); b.pick(FakeSample.WRONG_PLACE_TIF)
        assertEquals(BgScreen.WARNING, b.screen)
        val o = b.pending!!.outcome
        assertEquals(AlignStatus.MISMATCH, o.status)
        assertTrue(AlignWarning.FAR_FROM_WORK in o.warnings)
        assertTrue("약 100 km", o.distanceToWorkKm!! in 95.0..105.0)
        assertTrue(b.canPickCrs)
        b.importPending(); assertTrue(b.uploads.isEmpty())
    }

    @Test fun dxfNeedsCrsThenUserCrsAlignsAndSurvivesReload() {
        val b = make(); b.pick(FakeSample.MAP_DXF)
        assertEquals(BgScreen.WARNING, b.screen)
        assertEquals(AlignStatus.NEEDS_CRS, b.pending!!.outcome.status)
        assertTrue(b.canPickCrs)
        b.openCrsPick(); assertEquals(BgScreen.CRS_PICK, b.screen)
        b.chooseCrs(KoreanCrs.EPSG_5174)
        assertNotNull("변환 불가 좌표계는 안내만", b.notice); assertEquals(BgScreen.CRS_PICK, b.screen)
        b.chooseCrs(KoreanCrs.EPSG_5186)
        assertEquals(BgScreen.RESULT, b.screen)
        assertEquals(AlignStatus.ALIGNED_USER_CRS, b.pending!!.outcome.status)
        b.importPending()
        assertEquals("정합됨(좌표계 지정)", b.uploads.single().badge)
        assertEquals(4, b.mapLayers().single().polygon.size)
        assertEquals("DXF 도 모서리가 보관되어 다시 켜면 보인다", 4, make().mapLayers().single().polygon.size)
    }

    @Test fun dxfWrongCrsChoiceStaysInWarning() {
        val b = make(); b.pick(FakeSample.MAP_DXF)
        b.openCrsPick(); b.chooseCrs(KoreanCrs.EPSG_5185)
        assertEquals(BgScreen.WARNING, b.screen)
        assertTrue(b.uploads.isEmpty())
    }

    @Test fun unsupportedFileAndEmptyPickAreRejectedWithMessage() {
        val b = make()
        b.onPicked(listOf(tmp.newFile("memo.txt"))); assertEquals(BgScreen.PICK_FILE, b.screen); assertNotNull(b.notice); assertNull(b.pending)
        b.onPicked(emptyList()); assertNull(b.pending)
        val c = make(); c.pickFiles = null; c.launchPicker(); assertNotNull(c.notice)
    }

    @Test fun pickerCallbackFlowsThroughLauncher() {
        val b = make()
        b.pickFiles = { cb -> cb(listOf(FakeUploads.create(FakeSample.GOOD_TIF, workDir, b.work.center))) }
        b.launchPicker()
        assertEquals(BgScreen.RESULT, b.screen)
    }
}
