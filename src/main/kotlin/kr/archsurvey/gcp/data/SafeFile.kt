package kr.archsurvey.gcp.data

import java.io.File
import java.io.FileOutputStream

/**
 * 0.1.8 충돌에 안전한 파일 쓰기: 같은 폴더의 .tmp 에 다 쓰고 fsync 한 뒤 이름을 바꾼다.
 * 쓰는 도중 앱이 죽거나 전원이 꺼져도 예전 파일은 온전히 남고, 반쯤 쓴 파일이 생기지 않는다.
 */
object SafeFile {
    fun write(target: File, text: String) = writeWith(target) { it.write(text.toByteArray(Charsets.UTF_8)) }

    fun writeWith(target: File, body: (FileOutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out -> body(out); out.flush(); out.fd.sync() }
            if (!tmp.renameTo(target)) {
                // 일부 파일 시스템은 덮어쓰기 rename 을 못 한다 → 지우고 다시
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed: ${target.name}")
            }
        } finally { if (tmp.exists()) tmp.delete() }
    }
}
