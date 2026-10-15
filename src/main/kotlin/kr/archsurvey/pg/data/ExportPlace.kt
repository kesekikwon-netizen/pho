package kr.archsurvey.pg.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** 저장 칸 하나(폴더). 이름으로 찾기·지우기·이름 바꾸기·쓰기 */
interface Shelf {
    fun exists(name: String): Boolean
    fun delete(name: String): Boolean
    fun rename(from: String, to: String): Boolean
    fun write(name: String, src: File)
    fun names(): List<String>
}

/** 0.0.9 공용 저장 위치·덮어쓰기 규칙(M1·M8). 순수 로직 → 단위시험 */
object ExportPlace {
    const val ROOT = "Documents/발굴조사"
    const val SUB = "사진측량"
    fun relative(job: String) = "$ROOT/$job/$SUB/"
    fun pcPath(job: String, file: String) = "내 파일 › Documents › 발굴조사 › $job › $SUB › $file"
    fun bakName(name: String) = name.substringBeforeLast('.') + ".bak." + name.substringAfterLast('.')

    data class Put(val name: String, val bak: Boolean, val replaced: Boolean)

    /** 같은 이름 있으면: 옛 .bak 지움 → 지금 것을 .bak 로 → 새로 씀. 이름 바꾸기 실패면 지우고 씀. 「이름 (1).zip」 사본도 정리 */
    fun put(shelf: Shelf, src: File): Put {
        val name = src.name; val bak = bakName(name)
        val had = shelf.exists(name)
        var bakOk = false
        if (had) {
            if (shelf.exists(bak)) shelf.delete(bak)
            bakOk = shelf.rename(name, bak)
            if (!bakOk) shelf.delete(name)
        }
        val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.')
        val dup = Regex("^" + Regex.escape(stem) + """ \(\d+\)\.""" + Regex.escape(ext) + "$")
        shelf.names().filter { dup.matches(it) }.forEach { shelf.delete(it) }
        shelf.write(name, src)
        return Put(name, bakOk, had)
    }
}

class FileShelf(private val dir: File) : Shelf {
    private fun f(n: String) = File(dir, n)
    override fun exists(name: String) = f(name).exists()
    override fun delete(name: String) = f(name).delete()
    override fun rename(from: String, to: String) = f(from).renameTo(f(to))
    override fun write(name: String, src: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("폴더를 만들 수 없음: ${dir.path}")
        val tmp = f("$name.part"); src.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(f(name))) { src.copyTo(f(name), overwrite = true); tmp.delete() }
    }
    override fun names(): List<String> = dir.list()?.toList() ?: emptyList()
}

/** Android 10+: MediaStore Files, RELATIVE_PATH = Documents/발굴조사/<작업>/사진측량/ */
class MediaShelf(ctx: Context, private val rel: String) : Shelf {
    private val cr = ctx.contentResolver
    private val col = MediaStore.Files.getContentUri("external")
    private fun id(name: String): Long? = runCatching {
        cr.query(col, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(name, rel), null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()
    private fun uri(i: Long) = ContentUris.withAppendedId(col, i)
    override fun exists(name: String) = id(name) != null
    override fun delete(name: String) = id(name)?.let { runCatching { cr.delete(uri(it), null, null) > 0 }.getOrDefault(false) } ?: false
    override fun rename(from: String, to: String): Boolean {
        val i = id(from) ?: return false
        return runCatching { cr.update(uri(i), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, to) }, null, null) > 0 }.getOrDefault(false) && exists(to)
    }
    override fun write(name: String, src: File) {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".zip")) "application/zip" else "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val u = cr.insert(col, cv) ?: throw java.io.IOException("저장 위치에 만들 수 없음: $rel$name")
        try {
            (cr.openOutputStream(u) ?: throw java.io.IOException("쓸 수 없음: $name")).use { o -> src.inputStream().use { it.copyTo(o) } }
            cr.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) { runCatching { cr.delete(u, null, null) }; throw t }
    }
    override fun names(): List<String> = runCatching {
        cr.query(col, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(rel), null)
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } ?: emptyList()
    }.getOrDefault(emptyList())
}
