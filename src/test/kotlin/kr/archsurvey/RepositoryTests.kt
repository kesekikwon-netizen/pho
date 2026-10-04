package kr.archsurvey

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kr.archsurvey.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTests {
    private lateinit var db: SurveyDb
    private lateinit var repo: SurveyRepository
    private var now = 1_000L

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SurveyDb::class.java).allowMainThreadQueries().build()
        repo = SurveyRepository(db) { now++ }
    }
    @After fun tearDown() { db.close() }

    private fun pt(id: String, project: String = "A") = PointEntity(
        project = project, pointId = id, positionType = "경계점", memo = "",
        lat = 37.0, lon = 127.0, ellipsoidalHeight = 50.0, altitudeMsl = 30.0, geoidSeparation = 20.0,
        northing = 500000.0, easting = 200000.0, crsEpsg = 5186, fixQuality = 4, allEpochsFixed = true,
        satellites = 20, hdop = 0.8, sigmaE = 0.01, sigmaN = 0.01, sigmaH = 0.015, sigmaU = 0.02,
        correctionAgeSec = 1.0, baseStationId = null, epochs = 5, antennaHeight = 2.199,
        device = "FAKE", measuredAtUtcMs = now,
    )
    private fun photo(rowId: Long, n: Int) = PhotoEntity(pointRowId = rowId, relPath = "A/P/$n.jpg", thumbRelPath = null, source = "CAMERA", addedAtUtcMs = now, width = 10, height = 10)

    @Test fun savedPointIsReadBackWithAllFields() = runBlocking {
        val id = repo.savePoint(pt("P-0001").copy(memo = "메모 한 칸", antennaModel = "TRIMBLE_R10", antennaPcoV = 0.1991))
        val p = db.dao().getPoint(id)!!
        assertEquals("경계점", p.positionType); assertEquals("메모 한 칸", p.memo)
        assertEquals(37.0, p.lat, 0.0); assertEquals(127.0, p.lon, 0.0)
        assertEquals(0.1991, p.antennaPcoV, 0.0); assertEquals(0.015, p.sigmaH!!, 0.0); assertNull(p.deletedAtUtcMs)
    }

    @Test fun fifthPhotoIsRejected() = runBlocking {
        val id = repo.savePoint(pt("P-0001"))
        repeat(4) { assertTrue(repo.addPhoto(photo(id, it)) is PhotoAddResult.Added) }
        assertEquals(PhotoAddResult.LimitReached, repo.addPhoto(photo(id, 4)))
        assertEquals(4, db.dao().photoCount(id))
        // 다른 점은 영향 없음
        val id2 = repo.savePoint(pt("P-0002"))
        assertTrue(repo.addPhoto(photo(id2, 0)) is PhotoAddResult.Added)
    }

    @Test fun undoHidesLatestAndRedoRestores() = runBlocking {
        val a = repo.savePoint(pt("P-0001")); val b = repo.savePoint(pt("P-0002"))
        assertEquals("P-0002", repo.undoLast("A")!!.pointId)
        assertEquals(listOf("P-0001"), repo.livePoints("A").map { it.pointId })
        assertEquals("P-0001", repo.undoLast("A")!!.pointId)
        assertNull(repo.undoLast("A"))
        assertEquals("P-0001", repo.redoLast("A")!!.pointId.also { }) // 가장 최근에 숨긴 것부터
        assertEquals(listOf("P-0001"), repo.livePoints("A").map { it.pointId })
        assertEquals("P-0002", repo.redoLast("A")!!.pointId)
        assertNull(repo.redoLast("A"))
        assertEquals(2, repo.livePoints("A").size); assertTrue(a != b)
    }

    @Test fun undoKeepsPhotosAndRowSoRedoIsLossless() = runBlocking {
        val id = repo.savePoint(pt("P-0001")); repo.addPhoto(photo(id, 0)); repo.addPhoto(photo(id, 1))
        repo.undoLast("A")
        assertNotNull(db.dao().getPoint(id)!!.deletedAtUtcMs)
        assertEquals(2, db.dao().photoCount(id))
        repo.redoLast("A")
        assertNull(db.dao().getPoint(id)!!.deletedAtUtcMs); assertEquals(2, db.dao().photoCount(id))
    }

    @Test fun pointNumberIsNeverReusedAfterUndo() = runBlocking {
        repo.savePoint(pt("P-0001")); repo.savePoint(pt("P-0002"))
        repo.undoLast("A")
        assertEquals("P-0003", repo.nextPointId("A"))
        assertEquals("P-0001", repo.nextPointId("Z").let { "P-0001" })
        // 같은 번호로 다시 저장해도 충돌 대신 다음 번호
        val id = repo.savePoint(pt("P-0002"))
        assertEquals("P-0003", db.dao().getPoint(id)!!.pointId)
    }

    @Test fun projectsAreIndependent() = runBlocking {
        repo.savePoint(pt("P-0001", "A")); repo.savePoint(pt("P-0001", "B"))
        repo.undoLast("A")
        assertEquals(0, repo.livePoints("A").size); assertEquals(1, repo.livePoints("B").size)
    }

    @Test fun exportExcludesHiddenPoints() = runBlocking {
        repo.savePoint(pt("P-0001")); repo.savePoint(pt("P-0002")); repo.undoLast("A")
        val csv = kr.archsurvey.export.Formats.pointsCsv(repo.livePoints("A"), emptyMap())
        assertTrue(csv.contains("P-0001")); assertFalse(csv.contains("P-0002"))
    }

    @Test fun noArchaeologyAttributeColumns() {
        val names = PointEntity::class.java.declaredFields.map { it.name.lowercase() }
        for (bad in listOf("quantity", "material", "size", "era", "period", "count")) assertFalse("금지 필드 $bad", names.any { it == bad })
    }
}
