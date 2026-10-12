package kr.archsurvey.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kr.archsurvey.data.BackupException
import kr.archsurvey.data.BackupInfo
import kr.archsurvey.data.BackupService
import kr.archsurvey.ui.BackupItem
import kr.archsurvey.ui.BackupPlatform
import java.io.File
import kotlin.coroutines.resume

class AndroidBackupPlatform(private val ctx: Context, private val docs: DocBridge,
    /** 0.1.38 백업 ZIP 사본을 지금 작업 폴더 백업/ 에도 */
    private val jobFolder: AndroidJobFolder? = null, private val project: () -> String = { "" }) : BackupPlatform {
    private val svc = BackupService(ctx)
    private val versionName: String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")

    private fun BackupInfo.toItem() = BackupItem(name, file.path, createdAtMs, points, photos, voices, sizeBytes)

    override suspend fun list() = withContext(Dispatchers.IO) { svc.list().map { it.toItem() } }
    override suspend fun create() = withContext(Dispatchers.IO) {
        val b = svc.create(versionName = versionName)
        jobFolder?.let { f -> runCatching { f.addBackup(project(), b.file) }.onFailure { kr.archsurvey.diag.CrashLog.record("작업 폴더 백업 사본", it) } }
        b.toItem()
    }
    override suspend fun restore(item: BackupItem) = withContext(Dispatchers.IO) { svc.restore(File(item.path), versionName).toItem() }
    override suspend fun delete(item: BackupItem) = withContext(Dispatchers.IO) { svc.delete(File(item.path)) }

    override suspend fun importPicked(): BackupItem? {
        val uri: Uri = suspendCancellableCoroutine { c -> docs.open { c.resume(it) } } ?: return null
        return withContext(Dispatchers.IO) {
            val dst = File(svc.dir, "imported_" + System.currentTimeMillis() + ".zip")
            try {
                ctx.contentResolver.openInputStream(uri)?.use { i -> dst.outputStream().use { o -> i.copyTo(o) } } ?: throw BackupException("파일을 열 수 없어요")
                svc.validate(dst)
                svc.list().firstOrNull { it.file.path == dst.path }?.toItem() ?: throw BackupException("백업 파일을 확인하지 못했어요")
            } catch (e: Exception) { dst.delete(); throw e }
        }
    }

    override fun share(item: BackupItem) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(item.path))
        ctx.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "백업 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun saveAs(item: BackupItem, onDone: (Boolean) -> Unit) {
        val f = File(item.path)
        docs.create(item.name) { uri ->
            if (uri == null) onDone(false)
            else onDone(runCatching { ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } != null }.getOrDefault(false))
        }
    }

    override fun restartApp() {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (i != null) ctx.startActivity(i)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
