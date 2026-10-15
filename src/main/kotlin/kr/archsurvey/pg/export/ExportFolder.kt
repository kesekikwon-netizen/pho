package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.tag.TargetNaming
import java.io.File

/** <작업명>_iTwin/ 폴더(JVM 파일만 — 단위시험·폰 같이 씀). 0.0.9: 방식 하나(M6·M7) — photos + block.xmlz + 공통 안내문 (+ 좌표 있으면 백업 TXT) */
object ExportFolder {
    data class Result(val dir: File, val photos: Int, val targets: List<Int>, val withCoords: Boolean)

    /** M5: 내보낼 수 없으면 이유 한 줄 */
    fun blockReason(s: Session): String? = if (s.stills.isEmpty()) "사진이 없어요 · 먼저 찍어 주세요" else null

    /** 폰에서 MainActivity 가 넣음: (사진, 카메라 위치, R10 원시) → EXIF GPS + XMP */
    @Volatile var exifGps: ((File, kr.archsurvey.pg.r10.GeoCam.Cam, kr.archsurvey.pg.r10.GnssTag?) -> Unit)? = null

    fun write(session: Session, photoSrcDir: File, out: File, scaleTxt: String? = null): Result {
        blockReason(session)?.let { throw IllegalStateException(it) }
        val photos = File(out, "photos"); if (!photos.isDirectory && !photos.mkdirs()) throw java.io.IOException("폴더를 만들 수 없음: ${photos.path}")
        for (s in session.stills) File(photoSrcDir, s.file).takeIf { it.exists() }?.copyTo(File(photos, s.file), overwrite = true)
        val seen = session.stills.flatMap { s -> s.tags.map { TargetNaming.targetNo(it.id) } }.toSet()
        val withCoords = session.gcps.isNotEmpty()
        val fit = Poses.fit(session)
        // 0.0.14: R10 — 저장 때 AR↔EPSG 맞춤(원시 R10·AR 궤적 파일이 있으면 그것으로, 없으면 사진마다 붙은 값)
        val sesDir = photoSrcDir.parentFile
        val rawR10 = sesDir?.let { File(it, kr.archsurvey.pg.r10.RawLogs.R10) }; val rawAr = sesDir?.let { File(it, kr.archsurvey.pg.r10.RawLogs.AR) }
        val geo = kr.archsurvey.pg.r10.GeoCam.compute(session,
            rawR10?.let { kr.archsurvey.pg.r10.RawLogs.readR10(it) } ?: emptyList(), rawAr?.let { kr.archsurvey.pg.r10.RawLogs.readAr(it) } ?: emptyList())
        // 원시 기록은 늘 같이 내보냄(맞춤이 없어도)
        for (f in listOfNotNull(rawR10, rawAr)) if (f.isFile) runCatching { f.copyTo(File(out, f.name), overwrite = true) }
        // 내보낸 사진 EXIF GPS = 맞춤된 카메라 위치(맞춤 없으면 촬영 때 넣은 원시값 그대로)
        exifGps?.let { w -> for ((id, c) in geo.cams) session.stills.firstOrNull { it.id == id }?.let { st -> runCatching { w(File(photos, st.file), c, st.gnss) } } }
        // 자세 = 지금처럼(기준점 맞춤이 기준). R10 위치는 사진별 GPS 보조값으로만
        val poses = Poses.forExport(session)
        val useEpsg = if (withCoords) session.epsg else null
        val opts = BlocksExchange.Options(blockName = session.name, epsg = useEpsg, poses = poses,
            gpsPriors = geo.cams.mapValues { (_, c) -> Triple(c.lat, c.lon, c.h) })
        val xml = BlocksExchange.write(session.stills, session.gcps, opts)
        File(out, "block.xmlz").writeBytes(BlocksExchange.xmlz(xml))
        if (withCoords) File(out, "기준점_EPSG${session.epsg}.txt").writeText(BlocksExchange.gcpTxt(session.gcps), Charsets.UTF_8)
        File(out, PoseLog.FILE).writeText(PoseLog.csv(session, geo), Charsets.UTF_8)
        // 0.0.12: 점군(작업 폴더 cloud/ 조각, 신뢰도 0.66·2 cm). 좌표 있으면 맞춤 성공 때만 EPSG 로, 아니면 로컬
        val jobDir = photoSrcDir.parentFile
        val cloud = jobDir?.let { CloudFile.loadAll(it) } ?: FloatArray(0)
        if (cloud.isNotEmpty() && (!withCoords || fit != null)) CloudFile.writePly(File(out, CloudFile.EXPORT), cloud, if (withCoords) fit?.tf else null)
        File(out, ItwinSheet.FILE).writeText(ItwinSheet.TEXT, Charsets.UTF_8)
        File(out, "작업정보.txt").writeText(info(session, seen, scaleTxt) + SiteFit.text(fit, poses.size, session.stills.size) + r10Text(session, geo), Charsets.UTF_8)
        val targets = if (withCoords) session.gcps.map { it.target }.filter { it in seen }.sorted() else seen.sorted()
        return Result(out, session.stills.size, targets, withCoords)
    }

    fun r10Text(s: Session, g: kr.archsurvey.pg.r10.GeoCam.Result): String {
        val tagged = s.stills.count { it.gnss != null }
        if (tagged == 0 && g.fit == null) return ""
        val nl = "\r\n"
        return "R10: 위치 기록 ${tagged}장 · FIX ${s.stills.count { it.gnss?.fixed == true }}장 · 사진 위치 ${g.cams.size}장 (EPSG:${g.epsg}, 보조값 — 절대 좌표는 기준점이 정함)" + nl +
            kr.archsurvey.pg.r10.GeoCam.report(g.fit) + nl +
            String.format(java.util.Locale.US, "렌즈→안테나 레버암 위 %.1f · 앞 %.1f · 오른쪽 %.1f cm + 위상중심 상수 %.1f cm · 방위 %s", s.lever.up * 100, s.lever.fwd * 100, s.lever.right * 100, s.apcM * 100,
                g.headingRad?.let { "%.1f°".format(java.util.Locale.US, Math.toDegrees(it)) } ?: "모름(레버암 미적용, 원시값만)") + nl +
            "원시 기록: r10_raw.csv · ar_track.csv (맞춤 없어도 늘 남김) · 사진별 속도는 poses.csv" + nl
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
