package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.tag.TargetNaming
import java.io.File

/** <작업명>_iTwin/ 폴더(JVM 파일만 — 단위시험·폰 같이 씀). 0.0.9: 방식 하나(M6·M7) — photos + block.xmlz + 공통 안내문 (+ 좌표 있으면 백업 TXT) */
object ExportFolder {
    data class Result(val dir: File, val photos: Int, val targets: List<Int>, val withCoords: Boolean)

    /** M5: 내보낼 수 없으면 이유 한 줄 */
    fun blockReason(s: Session): String? = if (s.stills.isEmpty()) "사진이 없어요 · 먼저 찍어 주세요" else null

    fun write(session: Session, photoSrcDir: File, out: File, scaleTxt: String? = null): Result {
        blockReason(session)?.let { throw IllegalStateException(it) }
        val photos = File(out, "photos"); if (!photos.isDirectory && !photos.mkdirs()) throw java.io.IOException("폴더를 만들 수 없음: ${photos.path}")
        for (s in session.stills) File(photoSrcDir, s.file).takeIf { it.exists() }?.copyTo(File(photos, s.file), overwrite = true)
        val seen = session.stills.flatMap { s -> s.tags.map { TargetNaming.targetNo(it.id) } }.toSet()
        val withCoords = session.gcps.isNotEmpty()
        val opts = BlocksExchange.Options(blockName = session.name, epsg = if (withCoords) session.epsg else null)
        val xml = BlocksExchange.write(session.stills, session.gcps, opts)
        File(out, "block.xmlz").writeBytes(BlocksExchange.xmlz(xml))
        if (withCoords) File(out, "기준점_EPSG${session.epsg}.txt").writeText(BlocksExchange.gcpTxt(session.gcps), Charsets.UTF_8)
        File(out, ItwinSheet.FILE).writeText(ItwinSheet.TEXT, Charsets.UTF_8)
        File(out, "작업정보.txt").writeText(info(session, seen, scaleTxt), Charsets.UTF_8)
        val targets = if (withCoords) session.gcps.map { it.target }.filter { it in seen }.sorted() else seen.sorted()
        return Result(out, session.stills.size, targets, withCoords)
    }

    fun info(s: Session, seen: Set<Int>, scaleTxt: String?): String = buildString {
        val nl = "\r\n"
        append("작업: ").append(s.name).append(nl)
        append("사진: ").append(s.stills.size).append("장").append(nl)
        append("사진 속 표적: ").append(if (seen.isEmpty()) "없음" else seen.sorted().joinToString(", ") { "Target #$it" }).append(nl)
        if (s.gcps.isNotEmpty()) {
            append("좌표계: ").append(BlocksExchange.srsName(s.epsg)).append(nl)
            append("좌표 있는 기준점: ").append(s.gcps.size).append("점 (block.xmlz 안에 들어 있음)").append(nl)
        } else append("좌표: 없음 (앱 「좌표 입력」에서 넣고 다시 보내면 block.xmlz 에 들어갑니다)").append(nl)
        scaleTxt?.let { append(it.trimEnd()).append(nl) }
    }
}
