package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class BackupItem(val name: String, val path: String, val createdAtMs: Long, val points: Int, val photos: Int, val voices: Int, val sizeBytes: Long)

/** 3-J 백업·복구에 필요한 Android 기능. 시험에서는 가짜로 바꾼다. */
interface BackupPlatform {
    suspend fun list(): List<BackupItem>
    suspend fun create(): BackupItem
    /** 검증 → 복구 직전 백업 → 파일 교체. 반환값은 "복구 직전" 백업. 앱은 이어서 다시 시작해야 한다 */
    suspend fun restore(item: BackupItem): BackupItem
    /** 문서 선택기로 고른 백업 ZIP을 앱 폴더로 복사하고 검사. 취소하면 null, 잘못된 파일이면 예외(사용자 문구) */
    suspend fun importPicked(): BackupItem?
    suspend fun delete(item: BackupItem)
    fun share(item: BackupItem)
    fun saveAs(item: BackupItem, onDone: (Boolean) -> Unit)
    fun restartApp()
}

class BackupController(private val platform: BackupPlatform, private val scope: CoroutineScope, private val restartDelayMs: Long = 1800L) {
    var open by mutableStateOf(false)
    var items by mutableStateOf<List<BackupItem>>(emptyList()); private set
    var running by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var confirmRestore by mutableStateOf<BackupItem?>(null)
    var confirmDelete by mutableStateOf<BackupItem?>(null)
    var restarting by mutableStateOf(false); private set

    fun openScreen() { open = true; notice = null; error = null; refresh() }
    fun close() { if (!restarting) open = false }
    fun refresh() { scope.launch { items = runCatching { platform.list() }.getOrDefault(emptyList()) } }

    private fun <T> guarded(label: String, block: suspend () -> T, ok: (T) -> Unit = {}) {
        if (running || restarting) return
        running = true; error = null; notice = null
        scope.launch {
            try { ok(block()) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = (e as? kr.archsurvey.data.BackupException)?.message ?: "$label 중 문제가 생겼어요 (${e.javaClass.simpleName}). 저장 공간을 확인하세요."
            } finally { running = false }
        }
    }

    fun create() = guarded("백업", { platform.create() }) { notice = "백업을 만들었어요: ${it.name}"; refresh() }

    fun askRestore(item: BackupItem) { confirmRestore = item }

    /** 확인 후 복구: 현재 데이터를 먼저 "복구 직전" 백업으로 남기고 교체한 뒤 앱을 다시 시작 */
    fun restore() {
        val item = confirmRestore ?: return
        confirmRestore = null
        guarded("복구", { platform.restore(item) }) { safety ->
            notice = "복구했어요. 복구 직전 상태는 ${safety.name} 로 보관했어요. 앱을 다시 시작해요."
            restarting = true
            scope.launch { delay(restartDelayMs); platform.restartApp() }
        }
    }

    fun pickFile() = guarded("파일 불러오기", { platform.importPicked() }) { it?.let { b -> notice = "백업 파일을 가져왔어요: ${b.name}"; refresh(); confirmRestore = b } }

    fun askDelete(item: BackupItem) { confirmDelete = item }
    fun delete() {
        val item = confirmDelete ?: return
        confirmDelete = null
        guarded("삭제", { platform.delete(item) }) { notice = "백업 파일을 지웠어요"; refresh() }
    }

    fun share(item: BackupItem) = platform.share(item)
    fun saveAs(item: BackupItem) = platform.saveAs(item) { ok -> notice = if (ok) "파일로 저장했어요" else "저장을 취소했거나 실패했어요" }
}
