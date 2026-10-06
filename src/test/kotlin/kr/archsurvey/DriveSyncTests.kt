package kr.archsurvey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kr.archsurvey.ui.BackupItem
import kr.archsurvey.ui.DriveAccount
import kr.archsurvey.ui.DriveException
import kr.archsurvey.ui.DriveFileRef
import kr.archsurvey.ui.DrivePlatform
import kr.archsurvey.ui.DriveSettings
import kr.archsurvey.ui.DriveSyncController
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.15 Drive 동기화 로직(가짜 플랫폼). 실기기·실제 Google OAuth/Drive 는 시험하지 않음. */
class DriveSyncTests {
    private class MemSettings : DriveSettings {
        override var autoBackup = false
        override var autoUploadPoints = false
        override var accountEmail = ""
        override var folderId = ""
        override var lastBackupAt = 0L
        override var lastUploadAt = 0L
        private val pts = HashMap<String, String>()
        override fun pointsFileId(project: String) = pts[project]
        override fun setPointsFileId(project: String, id: String?) { if (id == null) pts.remove(project) else pts[project] = id }
        override fun clearIds() { folderId = ""; accountEmail = ""; pts.clear() }
    }

    private class FakeDrive : DrivePlatform {
        var email: String? = null
        var folderCalls = 0
        var uploads = ArrayList<Triple<String, String, Int>>() // folder, name, bytes
        var updates = 0
        var failUpload: Exception? = null
        override fun currentEmail() = email
        override fun beginSignIn(onDone: (Result<DriveAccount>) -> Unit) {
            email = "field@example.com"
            onDone(Result.success(DriveAccount(email!!)))
        }
        override fun signOut() { email = null }
        override suspend fun ensureAppFolder(cachedId: String?): String {
            folderCalls++; return cachedId?.takeIf { it.isNotBlank() } ?: "folder1"
        }
        override suspend fun uploadNew(folderId: String, fileName: String, mime: String, bytes: ByteArray): DriveFileRef {
            failUpload?.let { throw it }
            uploads += Triple(folderId, fileName, bytes.size)
            return DriveFileRef("f_${uploads.size}", fileName)
        }
        override suspend fun uploadOrUpdate(folderId: String, fileName: String, mime: String, bytes: ByteArray, existingFileId: String?): DriveFileRef {
            failUpload?.let { throw it }
            if (existingFileId != null) { updates++; uploads += Triple(folderId, fileName, bytes.size); return DriveFileRef(existingFileId, fileName) }
            return uploadNew(folderId, fileName, mime, bytes)
        }
    }

    private fun zipFile(): BackupItem {
        val f = File.createTempFile("backup_test_", ".zip")
        f.writeBytes(ByteArray(32) { 7 })
        return BackupItem(f.name, f.path, 1L, 1, 0, 0, f.length())
    }

    private fun ctrl(p: FakeDrive, s: MemSettings, debounce: Long = 30L, interval: Long = 1_000L, clock: () -> Long = { 1_000L }) =
        DriveSyncController(
            platform = p, settings = s,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            projectName = { "작업1" },
            createBackupZip = { zipFile() },
            pointsCsvBytes = { "point_id\nP-1\n".toByteArray() },
            clock = clock, debounceMs = debounce, backupMinIntervalMs = interval,
        )

    @Test fun signInAndTogglesPersist() {
        val p = FakeDrive(); val s = MemSettings(); val c = ctrl(p, s)
        assertFalse(c.signedIn)
        c.signIn(); assertTrue(c.signedIn); assertEquals("field@example.com", s.accountEmail)
        c.changeAutoBackup(true); assertTrue(s.autoBackup)
        c.changeAutoUploadPoints(true); assertTrue(s.autoUploadPoints)
        c.signOut(); assertFalse(c.signedIn); assertEquals("", s.accountEmail)
    }

    @Test fun pointSaveUploadsWhenEnabled() = runBlocking {
        val p = FakeDrive(); val s = MemSettings(); s.autoUploadPoints = true
        p.email = "a@b.c"; s.accountEmail = "a@b.c"
        val c = ctrl(p, s, debounce = 20L)
        c.notifyPointSaved()
        delay(50)
        assertEquals(1, p.uploads.size)
        assertTrue(p.uploads[0].second.startsWith("points_"))
        assertTrue(c.lastUploadAt > 0L)
        // 두 번째: 기존 file id 로 update
        c.notifyPointSaved(); delay(50)
        assertEquals(1, p.updates)
    }

    @Test fun pointSaveSkippedWhenOffOrSignedOut() = runBlocking {
        val p = FakeDrive(); val s = MemSettings()
        val c = ctrl(p, s, debounce = 10L)
        c.notifyPointSaved(); delay(30); assertEquals(0, p.uploads.size)
        p.email = "a@b.c"; s.accountEmail = "a@b.c"; s.autoUploadPoints = false
        c.notifyPointSaved(); delay(30); assertEquals(0, p.uploads.size)
    }

    @Test fun backgroundBackupRespectsInterval() = runBlocking {
        var now = 10_000L
        val p = FakeDrive(); val s = MemSettings(); s.autoBackup = true
        p.email = "a@b.c"; s.accountEmail = "a@b.c"
        val c = ctrl(p, s, interval = 5_000L, clock = { now })
        c.onAppBackground(); delay(20)
        assertEquals(1, p.uploads.size)
        now = 12_000L // 간격 안
        c.onAppBackground(); delay(20)
        assertEquals(1, p.uploads.size)
        now = 16_000L
        c.onAppBackground(); delay(20)
        assertEquals(2, p.uploads.size)
    }

    @Test fun backupNowFailsWithoutSignIn() {
        val c = ctrl(FakeDrive(), MemSettings())
        var ok: Boolean? = null
        c.onResult = { o, _ -> ok = o }
        c.backupNow()
        assertEquals(false, ok)
        assertTrue(c.error!!.contains("연결"))
    }

    @Test fun uploadErrorSurfaces() = runBlocking {
        val p = FakeDrive(); val s = MemSettings(); s.autoUploadPoints = true
        p.email = "a@b.c"; s.accountEmail = "a@b.c"
        p.failUpload = DriveException("네트워크가 없어요")
        val c = ctrl(p, s, debounce = 10L)
        var msg: String? = null
        c.onResult = { ok, m -> if (!ok) msg = m }
        c.notifyPointSaved(); delay(40)
        assertTrue(msg!!.contains("네트워크"))
    }
}
