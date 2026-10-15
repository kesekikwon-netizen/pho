package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Session
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 「iTwin으로 보내기」 한 번 누르기 → <작업명>_iTwin.zip 하나(방식 선택 없음, M7) */
object OneTap {
    class Result(val zip: File, val photos: Int, val targets: Int, val bytes: Long, val withCoords: Boolean)

    fun zipName(s: Session) = "${s.name}_iTwin.zip"

    /** work = 임시 폴더(지움). 반환 zip = <work 부모>/<이름>_iTwin.zip */
    fun build(s: Session, photoSrc: File, work: File, scaleTxt: String? = null): Result {
        ExportFolder.blockReason(s)?.let { throw IllegalStateException(it) }
        val dirName = "${s.name}_iTwin"
        val out = File(work, dirName); out.deleteRecursively()
        val r = ExportFolder.write(s, photoSrc, out, scaleTxt)
        val zip = File(work.parentFile ?: work, zipName(s)); zip.delete()
        ZipOutputStream(zip.outputStream().buffered(1 shl 16)).use { z ->
            z.setLevel(1)
            out.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                z.putNextEntry(ZipEntry(dirName + "/" + f.relativeTo(out).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        out.deleteRecursively()
        return Result(zip, r.photos, r.targets.size, zip.length(), r.withCoords)
    }
}
