package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Session
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「iTwin으로 보내기」 한 번 누르기: 기본값으로 한 패키지를 만들어 zip 하나로.
 * 자동: 사진에 표적이 잡혔으면 앱 표시(block.xmlz + gcp_template), 없으면 iTwin 이 표적 잡는 패키지(photos + gcp.txt).
 */
object OneTap {
    enum class Mode(val label: String) { AUTO("자동"), APP_MARKS("앱 표시"), ITWIN_DETECT("iTwin이 표적 잡기") }
    @Volatile var mode = Mode.AUTO

    /** 실제로 쓸 방식 */
    fun resolve(s: Session, m: Mode = mode): Mode = when (m) {
        Mode.AUTO -> if (s.stills.any { it.tags.isNotEmpty() }) Mode.APP_MARKS else Mode.ITWIN_DETECT
        else -> m
    }

    class Result(val zip: File, val used: Mode, val photos: Int, val targets: Int, val bytes: Long)

    /** work = 임시 폴더(지움). 반환 zip = <work 부모>/<이름>_itwin.zip */
    fun build(s: Session, photoSrc: File, work: File, localTagsTxt: String? = null, scaleTxt: String? = null, m: Mode = mode): Result {
        val used = resolve(s, m)
        val dirName = s.name + if (used == Mode.APP_MARKS) "_itwin" else "_itwin_auto"
        val out = File(work, dirName); out.deleteRecursively()
        val r = if (used == Mode.APP_MARKS) {
            val r0 = ExportFolder.write(s, photoSrc, out, localTagsTxt = if (s.gcps.isEmpty()) localTagsTxt else null, scaleTxt = scaleTxt)
            if (s.gcps.isEmpty()) File(out, "block_tiepoints.xmlz").writeBytes(BlocksExchange.xmlz(BlocksExchange.write(s.stills, s.gcps, BlocksExchange.Options(s.name, null, BlocksExchange.MarkStyle.TIE_POINTS))))
            r0
        } else ExportFolder.writeAutoTargets(s, photoSrc, out)
        val zip = File(work.parentFile ?: work, "$dirName.zip"); zip.delete()
        ZipOutputStream(zip.outputStream().buffered(1 shl 16)).use { z ->
            z.setLevel(1) // JPG 는 더 안 줄어듦 → 빠르게
            out.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                z.putNextEntry(ZipEntry(dirName + "/" + f.relativeTo(out).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        out.deleteRecursively()
        return Result(zip, used, r.photos, r.targets.size, zip.length())
    }
}
