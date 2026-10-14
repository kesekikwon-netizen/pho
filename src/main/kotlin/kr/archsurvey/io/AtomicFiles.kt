package kr.archsurvey.io

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * 0.1.46 크래시-안전 파일 쓰기: 같은 폴더의 임시 파일에 다 쓴 뒤 fsync → rename.
 * 배터리 방전·강제 종료 중에도 원래 파일은 그대로이거나 완전한 새 파일이다(반쪽 파일 없음).
 */
object AtomicFiles {
    fun write(target: File, block: (OutputStream) -> Unit) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("상위 폴더 없음: $target")
        dir.mkdirs()
        val tmp = File(dir, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { fos ->
                val buf = fos.buffered()
                block(buf)
                buf.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // 일부 파일 시스템은 기존 파일 위로 rename 을 거부한다
                if (target.exists() && !target.delete()) throw IOException("기존 파일을 바꿀 수 없음: $target")
                if (!tmp.renameTo(target)) throw IOException("이름 바꾸기 실패: $target")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun writeBytes(target: File, bytes: ByteArray) = write(target) { it.write(bytes) }
    fun writeText(target: File, text: String, cs: Charset = Charsets.UTF_8) = writeBytes(target, text.toByteArray(cs))
    /** ZIP 을 원자적으로 쓴다. block 이 끝나면 finish() 후 fsync·rename */
    fun zip(target: File, block: (java.util.zip.ZipOutputStream) -> Unit) = write(target) { os ->
        val z = java.util.zip.ZipOutputStream(object : java.io.FilterOutputStream(os) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() { flush() }
        })
        block(z); z.finish(); z.flush()
    }

    fun copy(src: java.io.InputStream, target: File) = write(target) { src.copyTo(it) }
}
