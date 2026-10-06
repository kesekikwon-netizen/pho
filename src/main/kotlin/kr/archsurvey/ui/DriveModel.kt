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
) {
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
        if (!autoUploadPoints || !signedIn) return
        schedulePointsUpload()
    }

    /** 앱이 백그라운드로 갈 때. 자동 백업이 켜져 있고 간격이 지났으면 ZIP 업로드 */
    fun onAppBackground() {
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
            val bytes = java.io.File(zip.path).readBytes()
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
