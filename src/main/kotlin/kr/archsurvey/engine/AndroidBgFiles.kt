package kr.archsurvey.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 배경(도면) 파일 가져오기: 시스템 문서 선택기에서 고른 파일들을 앱 전용 폴더(`bg_files/bg_<시각>/`)로 복사한다.
 * 원본은 건드리지 않는다. 같은 폴더에 두므로 `.tfw` / `.prj` 가 도면 옆에 놓여 georef 모듈이 찾는다.
 */
class AndroidBgFiles(private val ctx: Context, private val docs: DocBridge, private val scope: CoroutineScope) {
    val root: File get() = File(ctx.filesDir, "bg_files")
    val layerDir: File get() = File(ctx.filesDir, "bg_layers")

    fun pick(cb: (List<File>) -> Unit) {
        docs.openMany { uris ->
            if (uris.isEmpty()) { cb(emptyList()); return@openMany }
            scope.launch {
                val files = withContext(Dispatchers.IO) { copyAll(uris) }
                cb(files)
            }
        }
    }

    private fun copyAll(uris: List<Uri>): List<File> {
        val dir = File(root, "bg_${System.currentTimeMillis()}").also { it.mkdirs() }
        val out = mutableListOf<File>()
        for (u in uris) {
            val name = displayName(u)?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "file_${out.size}"
            val dst = File(dir, name)
            runCatching { ctx.contentResolver.openInputStream(u)?.use { i -> dst.outputStream().use { o -> i.copyTo(o) } } }
                .onSuccess { if (dst.exists()) out += dst }
        }
        return out
    }

    private fun displayName(u: Uri): String? = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: u.lastPathSegment?.substringAfterLast('/')

    /** 레이어를 지울 때 사본 폴더째 지운다(앱 전용 폴더 안일 때만) */
    fun deleteCopy(l: kr.archsurvey.georef.BgLayer) {
        runCatching {
            val f = File(l.filePath); val d = f.parentFile
            if (d != null && d.parentFile?.canonicalPath == root.canonicalPath) d.deleteRecursively() else f.delete()
        }
    }
}
