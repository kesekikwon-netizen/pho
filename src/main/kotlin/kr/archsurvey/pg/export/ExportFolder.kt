package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.tag.TargetNaming
import java.io.File

/** <작업명>_itwin/ 폴더 만들기(JVM 파일만 — 단위시험·폰 같이 씀). 원본 사진은 바이트 그대로 복사 */
object ExportFolder {
    data class Result(val dir: File, val photos: Int, val targets: List<Int>, val withCoords: Boolean)

    /** 「iTwin이 표적 잡기」: photos/ + gcp.txt(좌표 있는 것만, 없으면 표적 목록에 0.000) + README. block.xmlz 없음 */
    fun writeAutoTargets(session: Session, photoSrcDir: File, out: File): Result {
        val photos = File(out, "photos"); if (!photos.isDirectory && !photos.mkdirs()) throw java.io.IOException("폴더를 만들 수 없음: ${photos.path}")
        for (s in session.stills) File(photoSrcDir, s.file).takeIf { it.exists() }?.copyTo(File(photos, s.file), overwrite = true)
        val targets = (session.stills.flatMap { s -> s.tags.map { TargetNaming.targetNo(it.id) } } + session.gcps.map { it.target }).distinct().sorted()
        // 0.0.7: 표적·좌표가 하나도 없으면 빈 gcp.txt 를 만들지 않음(0바이트 파일은 iTwin 불러오기 실패)
        if (targets.isNotEmpty()) File(out, "gcp.txt").writeText(BlocksExchange.gcpTemplate(targets, session.gcps), Charsets.UTF_8)
        File(out, "README_ko.txt").writeText(BlocksExchange.readmeAuto(), Charsets.UTF_8)
        return Result(out, session.stills.size, targets, session.gcps.isNotEmpty())
    }

    fun write(session: Session, photoSrcDir: File, out: File, style: BlocksExchange.MarkStyle = BlocksExchange.MarkStyle.CONTROL_NO_POSITION,
              localTagsTxt: String? = null, scaleTxt: String? = null): Result {
        val photos = File(out, "photos"); if (!photos.isDirectory && !photos.mkdirs()) throw java.io.IOException("폴더를 만들 수 없음: ${photos.path}")
        for (s in session.stills) File(photoSrcDir, s.file).takeIf { it.exists() }?.copyTo(File(photos, s.file), overwrite = true)
        val withCoords = session.gcps.isNotEmpty()
        val opts = BlocksExchange.Options(blockName = session.name, epsg = if (withCoords) session.epsg else null, style = style)
        val xml = BlocksExchange.write(session.stills, session.gcps, opts)
        File(out, "block.xml").writeText(xml, Charsets.UTF_8)
        File(out, "block.xmlz").writeBytes(BlocksExchange.xmlz(xml))
        val targets = (session.stills.flatMap { s -> s.tags.map { TargetNaming.targetNo(it.id) } } + session.gcps.map { it.target }).distinct().sorted()
        File(out, "gcp_template.txt").writeText(BlocksExchange.gcpTemplate(targets, session.gcps), Charsets.UTF_8)
        File(out, "README_ko.txt").writeText(BlocksExchange.readme(withCoords, style, session.epsg), Charsets.UTF_8)
        localTagsTxt?.let { File(out, "local_tags.txt").writeText(it, Charsets.UTF_8) }
        scaleTxt?.let { File(out, "scale.txt").writeText(it, Charsets.UTF_8) }
        return Result(out, session.stills.size, targets, withCoords)
    }
}
