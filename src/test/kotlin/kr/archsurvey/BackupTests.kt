package kr.archsurvey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kr.archsurvey.data.*
import kr.archsurvey.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 백업→변경→복구 후 동일한지 (Robolectric 의 실제 SQLite 파일 + WAL). 실기기 저장소·앱 재시작은 시험하지 않았다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupServiceTest {
    private lateinit var ctx: Context
    private lateinit var svc: BackupService

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SurveyDb.closeInstance()
        ctx.getDatabasePath(BackupService.DB_NAME).parentFile?.listFiles()?.forEach { it.delete() }
        svc = BackupService(ctx)
        svc.dir.listFiles()?.forEach { it.delete() }
    }
    @After fun tearDown() { SurveyDb.closeInstance() }

    private fun pt(id: String) = PointEntity(
        project = "작업1", pointId = id, positionType = "유구 경계점", memo = "메모-$id", lat = 37.0, lon = 127.0,
        ellipsoidalHeight = 50.0, altitudeMsl = 30.0, geoidSeparation = 20.0, northing = 500000.0, easting = 200000.0, crsEpsg = 5186,
        fixQuality = 4, allEpochsFixed = true, satellites = 20, hdop = 0.8, sigmaE = 0.01, sigmaN = 0.01, sigmaH = 0.015, sigmaU = 0.02,
        correctionAgeSec = 1.0, baseStationId = null, epochs = 5, antennaHeight = 2.199, device = "Trimble R10", measuredAtUtcMs = 1_700_000_000_000L,
    )
    private val pics get() = File(ctx.getExternalFilesDir("Pictures"), "")

    @Test fun backupThenModifyThenRestoreGivesSameData() = runBlocking {
        val dao = SurveyDb.get(ctx).dao()
        val id1 = dao.insertPointRaw(pt("P-0001")); dao.insertPointRaw(pt("P-0002"))
        File(pics, "작업1/P-0001").mkdirs(); File(pics, "작업1/P-0001/a.jpg").writeBytes(ByteArray(64) { 9 })
        dao.insertPhoto(PhotoEntity(pointRowId = id1, relPath = "작업1/P-0001/a.jpg", thumbRelPath = null, source = "CAMERA", addedAtUtcMs = 1, width = 1, height = 1))
        val b = svc.create(versionName = "0.1.2")
        assertEquals(2, b.points); assertEquals(1, b.photos)
        // 변경: 점 추가, 사진 파일 삭제
        dao.insertPointRaw(pt("P-0003")); File(pics, "작업1/P-0001/a.jpg").delete()
        assertEquals(3, dao.pointsOf("작업1").size)
        var restarted = false
        val safety = svc.restore(b.file) { restarted = true }
        assertTrue(restarted)
        val dao2 = SurveyDb.get(ctx).dao()
        assertEquals(listOf("P-0001", "P-0002"), dao2.pointsOf("작업1").map { it.pointId })
        assertEquals("메모-P-0001", dao2.pointsOf("작업1").first().memo)
        assertEquals(64, File(pics, "작업1/P-0001/a.jpg").length().toInt())
        // 복구 직전 상태(3점)가 따로 보관되었다
        assertEquals(3, safety.points); assertTrue(safety.name.startsWith("before_restore"))
        assertTrue(svc.list().any { it.name == safety.name })
    }

    @Test fun walContentIsIncludedInBackup() = runBlocking {
        val dao = SurveyDb.get(ctx).dao()
        repeat(5) { dao.insertPointRaw(pt("P-000${it + 1}")) } // 아직 체크포인트 전 WAL 에 있을 수 있는 쓰기
        val b = svc.create()
        SurveyDb.closeInstance()
        svc.restore(b.file)
        assertEquals(5, SurveyDb.get(ctx).dao().pointsOf("작업1").size)
    }

    @Test fun rejectsNonBackupZipAndGarbageAndZipSlip() {
        val bad = File(svc.dir, "bad.zip"); bad.writeText("not a zip")
        assertTrue(runCatching { svc.validate(bad) }.exceptionOrNull() is BackupException)
        val noManifest = File(svc.dir, "nm.zip")
        ZipOutputStream(noManifest.outputStream()).use { it.putNextEntry(ZipEntry("x.txt")); it.write(1); it.closeEntry() }
        assertTrue(runCatching { svc.validate(noManifest) }.exceptionOrNull()!!.message!!.contains("백업이 아니에요"))
        val slip = File(svc.dir, "slip.zip")
        ZipOutputStream(slip.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.json")); it.write("""{"format":1,"dbVersion":2}""".toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("survey.db")); it.write(1); it.closeEntry()
            it.putNextEntry(ZipEntry("../evil.txt")); it.write(1); it.closeEntry()
        }
        assertTrue(runCatching { svc.validate(slip) }.exceptionOrNull()!!.message!!.contains("안전하지"))
    }

    @Test fun corruptDatabaseInBackupIsRejectedAndCurrentDataKept() = runBlocking {
        val dao = SurveyDb.get(ctx).dao(); dao.insertPointRaw(pt("P-0001"))
        val corrupt = File(svc.dir, "corrupt.zip")
        ZipOutputStream(corrupt.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.json")); it.write("""{"format":1,"dbVersion":2}""".toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("survey.db")); it.write(ByteArray(4096) { 5 }); it.closeEntry()
        }
        val e = runCatching { svc.restore(corrupt) }.exceptionOrNull()
        assertTrue(e is BackupException)
        assertEquals(1, SurveyDb.get(ctx).dao().pointsOf("작업1").size) // 현재 데이터는 그대로
    }

    @Test fun newerAppBackupIsRejected() {
        val f = File(svc.dir, "future.zip")
        ZipOutputStream(f.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.json")); it.write("""{"format":1,"dbVersion":99}""".toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("survey.db")); it.write(1); it.closeEntry()
        }
        assertTrue(runCatching { svc.validate(f) }.exceptionOrNull()!!.message!!.contains("새 버전"))
    }
}

class BackupControllerTest {
    private class FakeBackup : BackupPlatform {
        val items = ArrayList<BackupItem>(); var restored = 0; var restarts = 0; var fail: Exception? = null; var picked: BackupItem? = null
        private fun item(n: String) = BackupItem(n, "/b/$n", 1_700_000_000_000L, 3, 2, 1, 1024)
        override suspend fun list() = items.toList()
        override suspend fun create(): BackupItem { fail?.let { throw it }; return item("backup_${items.size}.zip").also { items.add(it) } }
        override suspend fun restore(item: BackupItem): BackupItem { fail?.let { throw it }; restored++; return item("before_restore_1.zip").also { items.add(it) } }
        override suspend fun importPicked(): BackupItem? { fail?.let { throw it }; return picked }
        override suspend fun delete(item: BackupItem) { items.remove(item) }
        override fun share(item: BackupItem) {}
        override fun saveAs(item: BackupItem, onDone: (Boolean) -> Unit) = onDone(true)
        override fun restartApp() { restarts++ }
    }
    private fun bc(p: FakeBackup) = BackupController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), restartDelayMs = 0)

    @Test fun createListsNewBackup() {
        val p = FakeBackup(); val c = bc(p)
        c.openScreen(); assertTrue(c.items.isEmpty())
        c.create(); assertEquals(1, c.items.size); assertTrue(c.notice!!.contains("만들었어요"))
    }
    @Test fun restoreNeedsConfirmationThenRestarts() {
        val p = FakeBackup(); val c = bc(p); c.openScreen(); c.create()
        c.askRestore(c.items.first()); assertEquals(0, p.restored)
        c.restore()
        assertEquals(1, p.restored); assertTrue(c.restarting); assertEquals(1, p.restarts); assertTrue(c.notice!!.contains("복구 직전"))
    }
    @Test fun cancelledConfirmDoesNothingAndErrorsAreShown() {
        val p = FakeBackup(); val c = bc(p); c.openScreen(); c.create()
        c.askRestore(c.items.first()); c.confirmRestore = null; c.restore(); assertEquals(0, p.restored)
        p.fail = kr.archsurvey.data.BackupException("백업 안의 데이터베이스가 손상되어 있어요")
        c.askRestore(c.items.first()); c.restore()
        assertEquals(0, p.restored); assertFalse(c.restarting); assertTrue(c.error!!.contains("손상"))
    }
    @Test fun pickedFileOpensRestoreConfirm() {
        val p = FakeBackup(); val c = bc(p); c.openScreen()
        p.picked = BackupItem("imported_1.zip", "/b/i", 1L, 1, 0, 0, 10)
        c.pickFile(); assertEquals("imported_1.zip", c.confirmRestore?.name)
        p.picked = null; c.confirmRestore = null; c.pickFile(); assertNull(c.confirmRestore) // 취소
    }
    @Test fun deleteAsksFirst() {
        val p = FakeBackup(); val c = bc(p); c.openScreen(); c.create()
        c.askDelete(c.items.first()); assertEquals(1, p.items.size); c.delete(); assertEquals(0, p.items.size)
    }
}
