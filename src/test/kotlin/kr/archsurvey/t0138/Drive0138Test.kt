package kr.archsurvey.t0138

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.38 구글 드라이브: 작업 폴더를 같은 구조로(가짜 드라이브 — 실제 구글 OAuth·업로드는 시험 안 함) */
class Drive0138Test {
    class MemSettings : DriveSettings {
        override var autoBackup = false
        override var autoUploadPoints = false
        override var accountEmail = ""
        override var folderId = ""
        override var lastBackupAt = 0L
        override var lastUploadAt = 0L
        override fun pointsFileId(project: String): String? = null
        override fun setPointsFileId(project: String, id: String?) {}
        override fun clearIds() { folderId = ""; accountEmail = ""; man.clear() }
        override var autoJobUpload = false
        override var lastJobUploadAt = 0L
        val man = HashMap<String, String>()
        override fun mirrorManifest(jobRel: String) = man[jobRel]
        override fun setMirrorManifest(jobRel: String, json: String) { man[jobRel] = json }
    }

    /** 폴더 트리를 흉내: id = 경로 */
    class FakeDrive : DrivePlatform {
        var email: String? = "field@example.com"
        val folders = ArrayList<String>()
        val uploads = ArrayList<String>() // "folder/name"
        var updates = 0
        override fun currentEmail() = email
        override fun beginSignIn(onDone: (Result<DriveAccount>) -> Unit) { email = "field@example.com"; onDone(Result.success(DriveAccount(email!!))) }
        override fun signOut() { email = null }
        override suspend fun ensureAppFolder(cachedId: String?) = "ExcavationSurvey"
        override suspend fun ensureFolder(parentId: String, name: String): String = "$parentId/$name".also { if (it !in folders) folders.add(it) }
        override suspend fun uploadNew(folderId: String, fileName: String, mime: String, bytes: ByteArray): DriveFileRef {
            uploads.add("$folderId/$fileName"); return DriveFileRef("id:$folderId/$fileName", fileName)
        }
        override suspend fun uploadOrUpdate(folderId: String, fileName: String, mime: String, bytes: ByteArray, existingFileId: String?): DriveFileRef {
            if (existingFileId != null) { updates++; uploads.add("$folderId/$fileName"); return DriveFileRef(existingFileId, fileName) }
            return uploadNew(folderId, fileName, mime, bytes)
        }
    }

    private fun mf(path: String, size: Long, mtime: Long) = MirrorFile(path, size, mtime) { ByteArray(size.toInt()) }

    @Test fun mirror_sameStructureAsPhone_onlyChangedFiles() = runBlocking {
        val p = FakeDrive(); val m = DriveMirror(p); val man = HashMap<String, String>()
        val snap = JobFolderSnapshot("ExcavationSurvey/광령리 유적/3지구", listOf(
            mf("측점_3지구.csv", 10, 1), mf("사진/P-0001_a.jpg", 20, 1), mf("내보내기/3지구_20261012/points.csv", 5, 1)))
        val r1 = m.upload("ExcavationSurvey", snap, man)
        assertEquals(3, r1.uploaded)
        assertTrue(p.uploads.contains("ExcavationSurvey/광령리 유적/3지구/측점_3지구.csv"))
        assertTrue(p.uploads.contains("ExcavationSurvey/광령리 유적/3지구/사진/P-0001_a.jpg"))
        assertTrue(p.uploads.contains("ExcavationSurvey/광령리 유적/3지구/내보내기/3지구_20261012/points.csv"))
        // 두 번째: 안 바뀌면 안 올림
        val r2 = m.upload("ExcavationSurvey", snap, man)
        assertEquals(0, r2.uploaded); assertEquals(3, r2.skipped)
        // CSV 만 바뀜 → 그 파일만 덮어쓰기(같은 드라이브 파일)
        val snap3 = snap.copy(files = listOf(mf("측점_3지구.csv", 12, 2)) + snap.files.drop(1))
        val r3 = m.upload("ExcavationSurvey", snap3, man)
        assertEquals(1, r3.uploaded); assertEquals(1, p.updates)
        // manifest 는 JSON 으로 저장·복원
        assertEquals(man, DriveMirror.decode(DriveMirror.encode(man)))
    }

    private fun ctrl(p: FakeDrive, s: MemSettings, online: () -> Boolean = { true }, snap: JobFolderSnapshot? = JobFolderSnapshot("ExcavationSurvey/3지구", listOf(mf("측점_3지구.csv", 3, 1)))) =
        DriveSyncController(
            platform = p, settings = s, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            projectName = { "3지구" }, createBackupZip = { error("no") }, pointsCsvBytes = { ByteArray(1) },
            clock = { 5_000L }, debounceMs = 0L,
            jobFolder = snap?.let { sn -> { sn } }, isOnline = online,
        )

    @Test fun uploadNow_uploadsJobFolder_showsEmailAndTime() {
        val p = FakeDrive(); val s = MemSettings(); val c = ctrl(p, s)
        assertTrue(c.signedIn); assertEquals("field@example.com", c.email)
        c.uploadNow()
        assertEquals(listOf("ExcavationSurvey/3지구/측점_3지구.csv"), p.uploads)
        assertEquals(5_000L, s.lastJobUploadAt)
        assertTrue(c.notice!!.contains("1개 올림"))
        c.uploadNow()
        assertTrue(c.notice!!.contains("이미 최신"))
    }

    @Test fun uploadNow_offline_orSignedOut_explains() {
        val p = FakeDrive(); val s = MemSettings(); val c = ctrl(p, s, online = { false })
        c.uploadNow(); assertEquals("인터넷이 연결돼 있지 않아요", c.error); assertTrue(p.uploads.isEmpty())
        p.email = null
        val c2 = ctrl(p, MemSettings())
        c2.uploadNow(); assertTrue(c2.error!!.contains("구글 드라이브 연결")); assertTrue(p.uploads.isEmpty())
    }

    @Test fun autoUpload_onlyWhenOnAndOnline() {
        val p = FakeDrive(); val s = MemSettings()
        var online = false
        val c = ctrl(p, s, online = { online })
        c.notifyPointSaved(); assertTrue("꺼져 있으면 안 올림", p.uploads.isEmpty())
        c.changeAutoUpload(true); assertTrue(s.autoJobUpload)
        c.notifyPointSaved(); assertTrue("오프라인이면 안 올림", p.uploads.isEmpty())
        online = true
        c.notifyPointSaved(); assertEquals(1, p.uploads.size)
        c.onAppBackground() // 바뀐 게 없으면 다시 안 올림
        assertEquals(1, p.uploads.size)
    }

    @Test fun legacyTwoToggles_migrateToSingleAutoUpload() {
        val s = MemSettings().apply { autoBackup = true }
        val c = ctrl(FakeDrive(), s)
        assertTrue(c.autoUpload); assertTrue(s.autoJobUpload); assertFalse(s.autoBackup); assertFalse(s.autoUploadPoints)
    }

    @Test fun signOut_clearsManifest_soNewAccountGetsEverything() {
        val p = FakeDrive(); val s = MemSettings(); val c = ctrl(p, s)
        c.uploadNow(); assertFalse(s.man.isEmpty())
        c.signOut(); assertTrue(s.man.isEmpty()); assertFalse(c.signedIn)
    }

    @Test fun noPasswordField_anywhereInDriveUi_source() {
        // 구글 비밀번호를 받는 칸이 없어야 함(구글 정책). 화면 소스에서 확인
        val src = File("src/main/kotlin/kr/archsurvey/ui/BackupScreen.kt").readText()
        val card = src.substringAfter("fun DriveConnectCard").substringBefore("\n}\n")
        assertFalse(card.contains("PasswordVisualTransformation")); assertFalse(card.contains("OutlinedTextField"))
        assertTrue(card.contains("구글 드라이브 연결")); assertTrue(card.contains("인터넷 되면 자동 올리기")); assertTrue(card.contains("지금 올리기"))
    }
}
