package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Google Drive 연동 예외 — 사용자에게 그대로 보여 주는 문구 */
class DriveException(message: String) : Exception(message)

data class DriveAccount(val email: String)
data class DriveFileRef(val id: String, val name: String)

/**
 * Google Drive OAuth·업로드. 시험에서는 가짜로 바꾼다.
 * 액세스 토큰은 구현체가 Google Sign-In 으로만 다루고, 로그·SharedPreferences 에 넣지 않는다.
 */
interface DrivePlatform {
    /** 이미 로그인돼 있으면 이메일, 아니면 null */
    fun currentEmail(): String?
    fun beginSignIn(onDone: (Result<DriveAccount>) -> Unit)
    fun signOut()
    /** 앱 전용 폴더 id (없으면 만들고 저장). 반환 = folderId */
    suspend fun ensureAppFolder(cachedId: String?): String
    /** 새 파일 업로드. 반환 = file id */
    suspend fun uploadNew(folderId: String, fileName: String, mime: String, bytes: ByteArray): DriveFileRef
    /** 있으면 덮어쓰기, 없으면 새로. 반환 = file id */
    suspend fun uploadOrUpdate(folderId: String, fileName: String, mime: String, bytes: ByteArray, existingFileId: String?): DriveFileRef
    /** 0.1.38: [parentId] 안의 [name] 폴더(앱이 만든 것). 없으면 만든다. 반환 = 폴더 id */
    suspend fun ensureFolder(parentId: String, name: String): String =
        throw DriveException("이 빌드는 드라이브 폴더 만들기를 지원하지 않아요")
}

/** 0.1.38 작업 폴더 안 파일 하나(드라이브에 같은 구조로 올림). [relPath] = 작업 폴더 기준(「사진/P-0001_a.jpg」) */
data class MirrorFile(val relPath: String, val size: Long, val modifiedMs: Long, val mime: String = "application/octet-stream", val read: () -> ByteArray)

/** 작업 폴더 한 벌: [jobRel] = 「ExcavationSurvey/[상위/]작업」, [files] = 그 안 파일 */
data class JobFolderSnapshot(val jobRel: String, val files: List<MirrorFile>)

data class MirrorResult(val uploaded: Int, val skipped: Int)

/**
 * 0.1.38 작업 폴더 → 드라이브 「ExcavationSurvey/[상위/]작업/…」(폰 폴더와 같은 구조).
 * manifest(작업별): 파일 상대 경로 → 「크기:수정시각|드라이브 파일 id」. 바뀐 파일만 올린다(덮어쓰기).
 */
class DriveMirror(private val platform: DrivePlatform) {
    suspend fun upload(rootId: String, snap: JobFolderSnapshot, manifest: MutableMap<String, String>): MirrorResult {
        val segs = snap.jobRel.split('/').filter { it.isNotBlank() }.let { if (it.firstOrNull() == kr.archsurvey.job.JobFolders.ROOT) it.drop(1) else it }
        val dirIds = HashMap<String, String>()
        suspend fun dirId(path: List<String>): String {
            var id = rootId; val acc = ArrayList<String>()
            for (seg in path) { acc.add(seg); val k = acc.joinToString("/"); id = dirIds[k] ?: platform.ensureFolder(id, seg).also { dirIds[k] = it } }
            return id
        }
        var up = 0; var skip = 0
        for (f in snap.files.sortedBy { it.relPath }) {
            val sig = "${f.size}:${f.modifiedMs}"
            val prev = manifest[f.relPath]
            if (prev != null && prev.substringBefore('|') == sig) { skip++; continue }
            val parts = f.relPath.split('/').filter { it.isNotBlank() }
            val folder = dirId(segs + parts.dropLast(1))
            val ref = platform.uploadOrUpdate(folder, parts.last(), f.mime, f.read(), prev?.substringAfter('|', "")?.ifBlank { null })
            manifest[f.relPath] = "$sig|${ref.id}"
            up++
        }
        return MirrorResult(up, skip)
    }

    companion object {
        fun encode(m: Map<String, String>): String = org.json.JSONObject(m as Map<*, *>).toString()
        fun decode(s: String?): MutableMap<String, String> {
            val out = LinkedHashMap<String, String>()
            if (s.isNullOrBlank()) return out
            runCatching { val o = org.json.JSONObject(s); for (k in o.keys()) out[k] = o.getString(k) }
            return out
        }
    }
}

/** Drive 자동 백업·측점 업로드에 쓰는 설정·파일 id 저장 */
interface DriveSettings {
    var autoBackup: Boolean
    var autoUploadPoints: Boolean
    var accountEmail: String
    var folderId: String
    var lastBackupAt: Long
    var lastUploadAt: Long
    fun pointsFileId(project: String): String?
    fun setPointsFileId(project: String, id: String?)
    fun clearIds()
    /** 0.1.38 「인터넷 되면 자동 올리기」(작업 폴더) */
    var autoJobUpload: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var lastJobUploadAt: Long
        get() = 0L
        set(@Suppress("UNUSED_PARAMETER") v) {}
    /** 작업 폴더 → 드라이브 올린 기록(JSON). 키 = 작업 폴더 상대 경로 */
    fun mirrorManifest(jobRel: String): String? = null
    fun setMirrorManifest(jobRel: String, json: String) {}
}

/**
 * 0.1.15 Google Drive 동기화.
 * - 자동 백업: 앱이 백그라운드로 갈 때(간격 제한) ZIP 만들어 업로드
 * - 측점 자동 업로드: 점 저장 후 debounce 로 작업 CSV 업로드/갱신
 * - 토글이 꺼져 있거나 미연결이면 조용히 건너뜀
 */
class DriveSyncController(
    private val platform: DrivePlatform,
    private val settings: DriveSettings,
    private val scope: CoroutineScope,
    private val projectName: () -> String,
    private val createBackupZip: suspend () -> BackupItem,
    private val pointsCsvBytes: suspend (String) -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = 8_000L,
    private val backupMinIntervalMs: Long = 30L * 60L * 1000L,
    /** 0.1.38 지금 작업 폴더(측점·내보내기·사진·음성·백업) 모으기 + 파일 목록 */
    private val jobFolder: (suspend () -> JobFolderSnapshot)? = null,
    /** 0.1.38 인터넷 연결 여부(자동 올리기는 연결됐을 때만) */
    private val isOnline: () -> Boolean = { true },
    /** 0.1.38 파일 읽기·업로드를 돌릴 곳(Android = Dispatchers.IO, 시험 = 그대로) */
    private val io: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
) {
    private val mirror = DriveMirror(platform)
    /** 0.1.38 하나뿐인 자동 토글: 인터넷 되면 작업 폴더 자동 올리기 */
    var autoUpload by mutableStateOf(settings.autoJobUpload); private set
    var lastJobUploadAt by mutableStateOf(settings.lastJobUploadAt); private set
    private var jobUploadJob: Job? = null
    val jobFolderSupported: Boolean get() = jobFolder != null
    var email by mutableStateOf(settings.accountEmail.ifBlank { platform.currentEmail().orEmpty() }); private set
    var autoBackup by mutableStateOf(settings.autoBackup); private set
    var autoUploadPoints by mutableStateOf(settings.autoUploadPoints); private set
    var running by mutableStateOf(false); private set
    var runningLabel by mutableStateOf(""); private set
    var notice by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var lastBackupAt by mutableStateOf(settings.lastBackupAt); private set
    var lastUploadAt by mutableStateOf(settings.lastUploadAt); private set
    var onResult: ((Boolean, String) -> Unit)? = null

    private var uploadJob: Job? = null
    private var dirtyPoints = false
    private var backupJob: Job? = null

    val signedIn: Boolean get() = email.isNotBlank()

    init {
        // 앱 재시작 때 Google 세션이 남아 있으면 이메일을 맞춘다
        platform.currentEmail()?.let { e ->
            if (e != email) { email = e; settings.accountEmail = e }
        }
        // 0.1.38: 옛 토글 둘(자동 백업 ZIP·측점 CSV)은 화면에서 뺐다 → 켜 두었던 사람은 하나뿐인 「자동 올리기」로 옮김(숨은 동작이 남지 않게)
        if (jobFolder != null && (settings.autoBackup || settings.autoUploadPoints)) {
            settings.autoBackup = false; settings.autoUploadPoints = false; autoBackup = false; autoUploadPoints = false
            settings.autoJobUpload = true; autoUpload = true
        }
    }

    fun changeAutoBackup(v: Boolean) {
        autoBackup = v; settings.autoBackup = v
        onResult?.invoke(true, "Drive 자동 백업: " + if (v) "켬" else "끔")
    }

    fun changeAutoUploadPoints(v: Boolean) {
        autoUploadPoints = v; settings.autoUploadPoints = v
        onResult?.invoke(true, "측점 Drive 자동 업로드: " + if (v) "켬" else "끔")
        if (v && dirtyPoints) schedulePointsUpload()
    }

    fun signIn() {
        if (running) return
        running = true; runningLabel = "Google 계정 연결"; error = null; notice = null
        platform.beginSignIn { r ->
            running = false
            r.fold(
                onSuccess = {
                    email = it.email; settings.accountEmail = it.email
                    notice = "Google Drive 연결됨 · ${it.email}"
                    onResult?.invoke(true, notice!!)
                },
                onFailure = {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    error = (it as? DriveException)?.message ?: "Google 계정 연결에 실패했어요"
                    onResult?.invoke(false, error!!)
                },
            )
        }
    }

    fun signOut() {
        platform.signOut()
        email = ""; settings.accountEmail = ""; settings.clearIds()
        notice = "Google Drive 연결을 끊었어요"; error = null
        onResult?.invoke(true, notice!!)
    }

    /** 점 저장 직후. 자동 업로드가 켜져 있고 연결돼 있으면 debounce 후 CSV 반영 */
    fun notifyPointSaved() {
        dirtyPoints = true
        if (autoUpload && signedIn) scheduleJobUpload()
        if (!autoUploadPoints || !signedIn) return
        schedulePointsUpload()
    }

    fun changeAutoUpload(v: Boolean) {
        autoUpload = v; settings.autoJobUpload = v
        onResult?.invoke(true, "인터넷 되면 자동 올리기: " + if (v) "켬" else "끔")
        if (v && signedIn) scheduleJobUpload()
    }

    /** 「지금 올리기」: 지금 작업 폴더를 드라이브에 */
    fun uploadNow() {
        if (!signedIn) { error = "먼저 「구글 드라이브 연결」을 누르세요"; onResult?.invoke(false, error!!); return }
        if (jobFolder == null) { uploadPointsNow(); return } // 작업 폴더가 없는 빌드(시험) = 옛 측점 CSV
        if (!isOnline()) { error = "인터넷이 연결돼 있지 않아요"; onResult?.invoke(false, error!!); return }
        if (running) return
        scope.launch { runJobUpload(silentOk = false) }
    }

    private fun scheduleJobUpload() {
        if (jobFolder == null) return
        jobUploadJob?.cancel()
        jobUploadJob = scope.launch {
            delay(debounceMs)
            if (!autoUpload || !signedIn || !isOnline()) return@launch
            runJobUpload(silentOk = true)
        }
    }

    private suspend fun runJobUpload(silentOk: Boolean) {
        val src = jobFolder ?: run { error = "작업 폴더 올리기는 이 빌드에 없어요"; onResult?.invoke(false, error!!); return }
        if (running) return
        running = true; runningLabel = "드라이브에 올리는 중"; error = null; if (!silentOk) notice = null
        try {
            val snap = kotlinx.coroutines.withContext(io) { src() }
            val root = platform.ensureAppFolder(settings.folderId.ifBlank { null }).also { if (it != settings.folderId) settings.folderId = it }
            val man = DriveMirror.decode(settings.mirrorManifest(snap.jobRel))
            val r = try { kotlinx.coroutines.withContext(io) { mirror.upload(root, snap, man) } } finally { settings.setMirrorManifest(snap.jobRel, DriveMirror.encode(man)) }
            dirtyPoints = false
            lastJobUploadAt = clock(); settings.lastJobUploadAt = lastJobUploadAt
            notice = if (r.uploaded == 0) "드라이브가 이미 최신이에요 · ${snap.jobRel}" else "드라이브에 ${r.uploaded}개 올림 · ${snap.jobRel}"
            if (!silentOk) onResult?.invoke(true, notice!!)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = (e as? DriveException)?.message ?: "드라이브 올리기에 실패했어요 (${e.javaClass.simpleName})"
            if (!silentOk) onResult?.invoke(false, error!!)
        } finally { running = false }
    }

    /** 앱이 백그라운드로 갈 때. 자동 올리기(0.1.38) 또는 옛 자동 백업이 켜져 있으면 */
    fun onAppBackground() {
        if (autoUpload && signedIn && isOnline() && jobFolder != null && jobUploadJob?.isActive != true)
            jobUploadJob = scope.launch { runJobUpload(silentOk = true) }
        if (!autoBackup || !signedIn) return
        val now = clock()
        if (lastBackupAt > 0L && now - lastBackupAt < backupMinIntervalMs) return
        if (backupJob?.isActive == true) return
        backupJob = scope.launch { runBackupUpload(silentOk = true) }
    }

    fun backupNow() {
        if (!signedIn) { error = "먼저 Google 계정을 연결하세요"; onResult?.invoke(false, error!!); return }
        if (running) return
        scope.launch { runBackupUpload(silentOk = false) }
    }

    fun uploadPointsNow() {
        if (!signedIn) { error = "먼저 Google 계정을 연결하세요"; onResult?.invoke(false, error!!); return }
        if (running) return
        dirtyPoints = true
        scope.launch { runPointsUpload(silentOk = false) }
    }

    private fun schedulePointsUpload() {
        uploadJob?.cancel()
        uploadJob = scope.launch {
            delay(debounceMs)
            if (!dirtyPoints || !autoUploadPoints || !signedIn) return@launch
            runPointsUpload(silentOk = true)
        }
    }

    private suspend fun runBackupUpload(silentOk: Boolean) {
        if (running) return
        running = true; runningLabel = "Drive 백업 업로드"; error = null; notice = null
        try {
            val zip = createBackupZip()
            val folder = platform.ensureAppFolder(settings.folderId.ifBlank { null }).also {
                if (it != settings.folderId) settings.folderId = it
            }
            val bytes = kotlinx.coroutines.withContext(io) { java.io.File(zip.path).readBytes() }
            val ref = platform.uploadNew(folder, zip.name, "application/zip", bytes)
            lastBackupAt = clock(); settings.lastBackupAt = lastBackupAt
            notice = "Drive 백업 완료 · ${ref.name}"
            onResult?.invoke(true, notice!!)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = (e as? DriveException)?.message ?: "Drive 백업에 실패했어요 (${e.javaClass.simpleName})"
            onResult?.invoke(false, error!!)
        } finally { running = false }
    }

    private suspend fun runPointsUpload(silentOk: Boolean) {
        if (running) return
        running = true; runningLabel = "Drive 측점 업로드"; error = null
        try {
            val project = projectName()
            val bytes = pointsCsvBytes(project)
            val folder = platform.ensureAppFolder(settings.folderId.ifBlank { null }).also {
                if (it != settings.folderId) settings.folderId = it
            }
            val name = "points_${sanitize(project)}.csv"
            val existing = settings.pointsFileId(project)
            val ref = platform.uploadOrUpdate(folder, name, "text/csv", bytes, existing)
            settings.setPointsFileId(project, ref.id)
            dirtyPoints = false
            lastUploadAt = clock(); settings.lastUploadAt = lastUploadAt
            notice = "Drive 측점 반영 · $name"
            if (!silentOk) onResult?.invoke(true, notice!!)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = (e as? DriveException)?.message ?: "Drive 측점 업로드에 실패했어요 (${e.javaClass.simpleName})"
            onResult?.invoke(false, error!!)
        } finally { running = false }
    }

    private fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "job" }
}
