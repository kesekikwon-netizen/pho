package kr.archsurvey.job

import kr.archsurvey.ui.MirrorFile
import java.io.File

/**
 * 0.1.38 작업 폴더의 기기 안 원본(앱 폴더 아래 Documents/ExcavationSurvey/…). java.io 만 써서 JVM 시험 가능.
 * 공용 Documents 로 내보내기(MediaStore)는 engine/AndroidJobFolder 가 바뀐 파일만 한다.
 */
class JobFolderWriter(val localRoot: File) {
    fun dir(rel: String): File = File(localRoot, rel)

    /** 내용이 바뀌었을 때만 쓰기. 반환 = 쓴 파일(안 바뀌면 null) */
    fun writeIfChanged(rel: String, sub: String?, name: String, bytes: ByteArray): File? {
        val d = if (sub == null) dir(rel) else File(dir(rel), sub)
        d.mkdirs()
        val f = File(d, name)
        if (f.isFile && f.length() == bytes.size.toLong() && f.readBytes().contentEquals(bytes)) return null
        kr.archsurvey.io.AtomicFiles.writeBytes(f, bytes)
        return f
    }

    /** [src] 를 작업 폴더 [sub]/ 로 복사(같은 크기면 건너뜀). 반환 = 새로 복사한 파일 */
    fun copyIn(rel: String, sub: String, src: File, name: String = src.name): File? {
        if (!src.isFile) return null
        val d = File(dir(rel), sub).also { it.mkdirs() }
        val f = File(d, name)
        if (f.isFile && f.length() == src.length()) return null
        src.inputStream().use { i -> kr.archsurvey.io.AtomicFiles.copy(i, f) }
        return f
    }

    /** [srcRoot] 아래 모든 파일(숨김 폴더 제외)을 [sub]/ 로 평평하게 복사. 반환 = 새로 복사한 파일들 */
    fun copyTree(rel: String, sub: String, srcRoot: File): List<File> {
        if (!srcRoot.isDirectory) return emptyList()
        return srcRoot.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 }
            .mapNotNull { copyIn(rel, sub, it) }.toList()
    }

    /** 작업 폴더 안 모든 파일 → 드라이브 올리기 목록 */
    fun snapshot(rel: String): List<MirrorFile> {
        val root = dir(rel)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile }.map { f ->
            MirrorFile(f.relativeTo(root).path.replace('\\', '/'), f.length(), f.lastModified(), mimeOf(f.name)) { f.readBytes() }
        }.toList()
    }

    companion object {
        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "csv" -> "text/csv"; "txt" -> "text/plain"; "zip" -> "application/zip"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
            "m4a" -> "audio/mp4"; "dxf" -> "application/dxf"; "kml" -> "application/vnd.google-earth.kml+xml"; "geojson", "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}
