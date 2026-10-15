package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.tag.TargetNaming
import java.io.File

/** <작업명>_itwin/ 폴더 만들기(JVM 파일만 — 단위시험·폰 같이 씀). 원본 사진은 바이트 그대로 복사 */
object ExportFolder {
    data class Result(val dir: File, val photos: Int, val targets: List<Int>, val withCoords: Boolean)

    fun write(session: Session, photoSrcDir: File, out: File, style: BlocksExchange.MarkStyle = BlocksExchange.MarkStyle.CONTROL_NO_POSITION,
              localTagsTxt: String? = null, scaleTxt: String? = null): Result {
        val photos = File(out, "photos"); photos.mkdirs()
        for (s in session.stills) File(photoSrcDir, s.file).takeIf { it.exists() }?.copyTo(File(photos, s.file), overwrite = true)
        val withCoords = session.gcps.isNotEmpty()
        val opts = BlocksExchange.Options(blockName = session.name, epsg = if (withCoords) session.epsg else null, style = style)
        val xml = BlocksExchange.write(session.stills, session.gcps, opts)
        File(out, "block.xml").writeText(xml, Charsets.UTF_8)
        File(out, "block.xmlz").writeBytes(BlocksExchange.xmlz(xml))
        val targets = session.stills.flatMap { s -> s.tags.map { TargetNaming.targetNo(it.id) } }.distinct().sorted()
        File(out, "gcp_template.txt").writeText(BlocksExchange.gcpTemplate(targets, session.gcps), Charsets.UTF_8)
        File(out, "README_ko.txt").writeText(BlocksExchange.readme(withCoords, style), Charsets.UTF_8)
        localTagsTxt?.let { File(out, "local_tags.txt").writeText(it, Charsets.UTF_8) }
        scaleTxt?.let { File(out, "scale.txt").writeText(it, Charsets.UTF_8) }
        return Result(out, session.stills.size, targets, withCoords)
    }
}
