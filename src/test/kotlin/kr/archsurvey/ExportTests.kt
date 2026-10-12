package kr.archsurvey

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kr.archsurvey.data.*
import kr.archsurvey.export.ExportService
import kr.archsurvey.photo.PhotoStore
import kr.archsurvey.ui.*
import kr.archsurvey.voice.VoiceStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.ZipFile

/** 내보내기 ZIP 실제 생성(Robolectric 파일시스템 + 메모리 Room). 공유 시트·문서 선택기는 시험하지 않았다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportServiceTest {
    private lateinit var db: SurveyDb
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, SurveyDb::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close() }

    private fun pt(id: String, device: String = "Trimble R10") = PointEntity(
        project = "작업1", pointId = id, positionType = "유구 경계점", memo = "", lat = 37.0, lon = 127.0,
        ellipsoidalHeight = 50.0, altitudeMsl = 30.0, geoidSeparation = 20.0, northing = 500000.0, easting = 200000.0, crsEpsg = 5186,
        fixQuality = 4, allEpochsFixed = true, satellites = 20, hdop = 0.8, sigmaE = 0.01, sigmaN = 0.01, sigmaH = 0.015, sigmaU = 0.02,
        correctionAgeSec = 1.0, baseStationId = null, epochs = 5, antennaHeight = 2.199, device = device, measuredAtUtcMs = 1_700_000_000_000L,
        groundMsl = 28.0, groundEllipsoidalHeight = 48.0, heightModel = "RECEIVER",
    )

    private fun seed(photos: PhotoStore, voices: VoiceStore): Long = runBlocking {
        val dao = db.dao()
        val id = dao.insertPointRaw(pt("P-0001"))
        val pRel = "작업1/P-0001/P-0001_1.jpg"; photos.file(pRel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100) { 7 }) }
        dao.insertPhoto(PhotoEntity(pointRowId = id, relPath = pRel, thumbRelPath = null, source = "CAMERA", addedAtUtcMs = 1, width = 10, height = 10))
        val vRel = "작업1/P-0001/P-0001_v.m4a"; voices.file(vRel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(50) { 3 }) }
        dao.insertVoice(VoiceEntity(pointRowId = id, relPath = vRel, durationMs = 4200, addedAtUtcMs = 2))
        id
    }

    @Test fun zipHasAllFilesWhenPhotosAndVoicesIncluded() = runBlocking {
        val photos = PhotoStore(ctx, db.dao()); val voices = VoiceStore(ctx, db.dao())
        seed(photos, voices)
        val r = ExportService(ctx, db.dao(), photos, voices).export("작업1", includePhotos = true, includeVoices = true)
        assertEquals(1, r.pointCount); assertEquals(1, r.photoCount); assertEquals(1, r.voiceCount)
        val names = ZipFile(r.zip).use { z -> z.entries().toList().map { it.name } }
        for (n in listOf("points.csv", "points.dxf", "points.geojson", "photos.csv", "voice.csv", "README.txt", "photos/P-0001_1.jpg", "voices/P-0001_v.m4a"))
            assertTrue("$n in $names", n in names)
        ZipFile(r.zip).use { z ->
            val csv = z.getInputStream(z.getEntry("voice.csv")).readBytes().toString(Charsets.UTF_8)
            assertTrue(csv.contains("P-0001") && csv.contains("voices/P-0001_v.m4a") && csv.contains("4.2"))
            assertEquals(100, z.getEntry("photos/P-0001_1.jpg").size.toInt())
        }
    }

    @Test fun excludingPhotosAndVoicesLeavesThemOut() = runBlocking {
        val photos = PhotoStore(ctx, db.dao()); val voices = VoiceStore(ctx, db.dao())
        seed(photos, voices)
        val r = ExportService(ctx, db.dao(), photos, voices).export("작업1", includePhotos = false, includeVoices = false)
        val names = ZipFile(r.zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.none { it.startsWith("photos/") || it.startsWith("voices/") } && "voice.csv" !in names)
        assertTrue("points.csv" in names && "points.dxf" in names)
    }

    /** 0.1.14 목업 11: 고른 양식만 ZIP 에 들어간다 + Shapefile 구조(헤더 9994/1000, 점 유형 1, 좌표 X=동 Y=북) */
    @Test fun onlySelectedFormatsAndValidShapefile() = runBlocking {
        val photos = PhotoStore(ctx, db.dao()); val voices = VoiceStore(ctx, db.dao())
        db.dao().insertPointRaw(pt("P-0001")); db.dao().insertPointRaw(pt("P-0002").copy(northing = 500010.0, easting = 200005.0))
        val fs = setOf(kr.archsurvey.export.ExportFormat.SHP, kr.archsurvey.export.ExportFormat.KML, kr.archsurvey.export.ExportFormat.REPORT)
        val r = ExportService(ctx, db.dao(), photos, voices).export("작업1", includePhotos = false, includeVoices = false, formats = fs)
        val names = ZipFile(r.zip).use { z -> z.entries().toList().map { it.name } }
        for (n in listOf("points_5186.shp", "points_5186.shx", "points_5186.dbf", "points_5186.prj", "points_5186.cpg", "points.kml", "point_report.csv", "README.txt")) assertTrue("$n in $names", n in names)
        for (n in listOf("points.csv", "points.dxf", "points.geojson", "points.shp")) assertFalse("$n not in $names", n in names)
        ZipFile(r.zip).use { z ->
            val shp = java.nio.ByteBuffer.wrap(z.getInputStream(z.getEntry("points_5186.shp")).readBytes())
            assertEquals(9994, shp.order(java.nio.ByteOrder.BIG_ENDIAN).getInt(0))
            assertEquals(shp.capacity() / 2, shp.getInt(24))
            shp.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(1000, shp.getInt(28)); assertEquals(11, shp.getInt(32)) // PointZ (pt 에 groundMsl 있음)
            assertEquals(200000.0, shp.getDouble(36), 1e-9); assertEquals(500010.0, shp.getDouble(60), 1e-9) // Xmin, Ymax
            assertEquals(100 + 2 * 44, shp.capacity())
            assertEquals(200005.0, shp.getDouble(100 + 44 + 12), 1e-9) // 두 번째 점 X
            val dbf = z.getInputStream(z.getEntry("points_5186.dbf")).readBytes()
            assertEquals(2, java.nio.ByteBuffer.wrap(dbf).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4))
            assertEquals(0x1A, dbf.last().toInt())
            assertEquals("UTF-8", z.getInputStream(z.getEntry("points_5186.cpg")).readBytes().toString(Charsets.US_ASCII).trim())
            val prj = z.getInputStream(z.getEntry("points_5186.prj")).readBytes().toString(Charsets.US_ASCII)
            assertTrue(prj.contains("KGD2002_Central_Belt_2010") && prj.contains("127.0") && prj.contains("600000.0"))
            assertTrue(z.getInputStream(z.getEntry("README.txt")).readBytes().toString(Charsets.UTF_8).contains("hgis(Strata)에 넣기"))
            val kml = z.getInputStream(z.getEntry("points.kml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(kml.contains("<name>P-0002</name>") && kml.contains("127.000000000,37.000000000"))
            val rep = z.getInputStream(z.getEntry("point_report.csv")).readBytes().toString(Charsets.UTF_8)
            assertTrue(rep.startsWith("\uFEFF번호,위치 종류") && rep.contains("P-0001"))
        }
    }


    @Test fun hiddenPointsAreNotExported() = runBlocking {
        val photos = PhotoStore(ctx, db.dao()); val voices = VoiceStore(ctx, db.dao())
        val id = db.dao().insertPointRaw(pt("P-0001")); db.dao().insertPointRaw(pt("P-0002"))
        db.dao().hidePoint(id, 5L)
        val r = ExportService(ctx, db.dao(), photos, voices).export("작업1", includePhotos = false)
        assertEquals(1, r.pointCount)
    }
}

class ExportControllerTest {
    private class FakeExport(var points: Int = 3, var fail: Boolean = false) : ExportPlatform {
        var exports = ArrayList<Pair<Boolean, Boolean>>(); var shared = 0; var saveOk = true
        override suspend fun summary() = ExportSummary("작업1", points, 4, 1, 1)
        override suspend fun export(includePhotos: Boolean, includeVoices: Boolean): ExportOutput {
            if (fail) error("disk")
            exports.add(includePhotos to includeVoices); return ExportOutput("a.zip", 2048, "/x/a.zip", points, 4, 1)
        }
        override fun share(out: ExportOutput) { shared++ }
        override fun saveAs(out: ExportOutput, onDone: (Boolean) -> Unit) = onDone(saveOk)
    }
    private fun ec(p: FakeExport) = ExportController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Test fun summaryLoadsAndExportProducesOutput() {
        val p = FakeExport(); val e = ec(p)
        e.openScreen(); assertEquals(3, e.summary!!.points)
        e.run(); assertEquals("a.zip", e.output!!.fileName); assertFalse(e.running)
        e.share(); assertEquals(1, p.shared)
        e.saveAs(); assertEquals("파일로 저장했어요", e.notice)
        p.saveOk = false; e.saveAs(); assertTrue(e.notice!!.contains("취소"))
    }
    @Test fun optionsArePassedThrough() {
        val p = FakeExport(); val e = ec(p)
        e.openScreen(); e.includePhotos = false; e.run()
        assertEquals(false to true, p.exports.single())
    }
    @Test fun emptyJobRefusesAndFailureShowsMessage() {
        val p = FakeExport(points = 0); val e = ec(p)
        e.openScreen(); e.run(); assertNull(e.output); assertTrue(e.error!!.contains("점이 없어요"))
        val q = FakeExport(fail = true); val f = ec(q)
        f.openScreen(); f.run(); assertNull(f.output); assertTrue(f.error!!.contains("실패")); assertFalse(f.running)
    }
    @Test fun sizeText() {
        assertEquals("512 B", ExportController.sizeText(512)); assertEquals("1.5 KB", ExportController.sizeText(1536)); assertEquals("2.0 MB", ExportController.sizeText(2 * 1048576L))
    }
    @Test fun runHgisSelectsShpAndCsvAndShowsFolder() {
        val p = object : ExportPlatform {
            var last: Set<kr.archsurvey.export.ExportFormat>? = null
            override suspend fun summary() = ExportSummary("작업1", 2, 0, 0, 0)
            override suspend fun export(includePhotos: Boolean, includeVoices: Boolean) = error("no")
            override suspend fun exportFormats(formats: Set<kr.archsurvey.export.ExportFormat>, includePhotos: Boolean, includeVoices: Boolean): ExportOutput {
                last = formats
                return ExportOutput("a.zip", 100, "/x/a.zip", 2, 0, 0, "Documents/ExcavationSurvey/hgis/작업1_t")
            }
            override fun share(out: ExportOutput) {}
            override fun saveAs(out: ExportOutput, onDone: (Boolean) -> Unit) = onDone(true)
        }
        val e = ExportController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        e.openScreen(); e.runHgis()
        assertEquals(kr.archsurvey.export.ExportFormat.HGIS, p.last)
        assertEquals("Documents/ExcavationSurvey/hgis/작업1_t", e.output!!.publicFolder)
        assertTrue(e.notice!!.contains("Documents/ExcavationSurvey/hgis/작업1_t"))
    }
}
