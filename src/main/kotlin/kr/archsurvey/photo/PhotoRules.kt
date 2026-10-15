package kr.archsurvey.photo

import kr.archsurvey.job.JobFolders
import java.security.MessageDigest
import java.util.Locale

/**
 * 0.1.59 사진 규칙(순수 Kotlin, JVM 시험).
 * - 위치: `발굴조사/발굴 측량/[폴더/]<작업>/사진/<점>_<n>.jpg` (앱 폴더 원본 + 공용 Documents 사본)
 * - 장수 제한 없음.
 */
object PhotoLayout {
    const val ROOT = "발굴조사"
    const val APP = "발굴 측량"
    const val PHOTOS = "사진"

    /** 작업 폴더 기록(「ExcavationSurvey/[폴더/]작업」) → 「발굴조사/발굴 측량/[폴더/]작업/사진」 */
    fun dirRel(jobRel: String): String {
        val (g, f) = JobFolders.split(jobRel.removePrefix(JobFolders.PUBLIC_PARENT + "/"))
        return listOfNotNull(ROOT, APP, g, f, PHOTOS).joinToString("/")
    }

    /** MediaStore RELATIVE_PATH(끝 / 포함) */
    fun publicRelative(jobRel: String): String = "${JobFolders.PUBLIC_PARENT}/${dirRel(jobRel)}/"

    fun isNewLayout(rel: String): Boolean = rel.startsWith("$ROOT/$APP/")

    /** 파일 이름에 쓸 점 이름(금지 문자 → _) */
    fun safePoint(point: String): String = JobFolders.sanitize(point, "점")

    /** 점의 다음 파일 이름 `<점>_<n>.jpg`. [taken] = 그 폴더에 이미 있는 이름들(대소문자 무시) */
    fun nextName(point: String, taken: Collection<String>): String {
        val base = safePoint(point)
        val rx = Regex("^" + Regex.escape(base.lowercase(Locale.ROOT)) + "_(\\d+)\\.jpg$")
        val t = taken.map { it.lowercase(Locale.ROOT) }
        val max = t.mapNotNull { rx.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        var n = max + 1
        while ("${base}_$n.jpg".lowercase(Locale.ROOT) in t) n++
        return "${base}_$n.jpg"
    }

    /** 내보내기 안 점별 하위 폴더 경로 `photos/<점>/<파일>` */
    fun exportPath(point: String, rel: String): String = "photos/${safePoint(point)}/${rel.substringAfterLast('/')}"
}

/** 사진 EXIF 에 넣을 측점 정보 */
data class PhotoPointInfo(
    val name: String, val lat: Double, val lon: Double,
    val northing: Double, val easting: Double, val epsg: Int,
    /** 지면 해발(없으면 타원체고) */
    val heightM: Double?, val heightIsMsl: Boolean,
    val sigmaHM: Double?, val sigmaVM: Double?, val fixQuality: Int,
)

object PhotoExif {
    fun fixLabel(q: Int) = when (q) { 4 -> "FIX"; 5 -> "FLOAT"; 2 -> "DGPS"; 1 -> "GPS"; 0 -> "NONE"; else -> "Q$q" }

    private fun f(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)

    /** 한 줄 설명(ASCII 외 글자 포함 가능 — XMP·UserComment 용) */
    fun description(p: PhotoPointInfo): String = buildString {
        append("점 ").append(p.name)
        append(" | N ").append(f(p.northing, 3)).append(" E ").append(f(p.easting, 3)).append(" EPSG:").append(p.epsg)
        p.heightM?.let { append(if (p.heightIsMsl) " | 해발 " else " | 타원체고 ").append(f(it, 3)) }
        append(" | σH ").append(p.sigmaHM?.let { f(it * 100, 1) + "cm" } ?: "-")
        append(" σV ").append(p.sigmaVM?.let { f(it * 100, 1) + "cm" } ?: "-")
        append(" | ").append(fixLabel(p.fixQuality))
    }

    /** EXIF ASCII 칸(ImageDescription)용: 비ASCII 는 \uXXXX */
    fun ascii(s: String): String = buildString { for (ch in s) if (ch.code in 0x20..0x7e) append(ch) else append("\\u%04X".format(ch.code)) }

    /** EXIF GPS 유리수 DMS 문자열("d/1,m/1,s/10000") */
    fun dms(deg: Double): String {
        val a = Math.abs(deg); val d = a.toInt(); val mF = (a - d) * 60; val m = mF.toInt()
        val s = Math.round((mF - m) * 60 * 10000)
        return "$d/1,$m/1,$s/10000"
    }

    /** XMP 패킷(UTF-8): dc:title=점 이름, dc:description=설명, archsurvey: 값 */
    fun xmp(p: PhotoPointInfo): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:as=\"http://archsurvey.kr/ns/1.0/\"" +
            " as:Point=\"${esc(p.name)}\" as:Northing=\"${f(p.northing, 4)}\" as:Easting=\"${f(p.easting, 4)}\" as:Epsg=\"${p.epsg}\"" +
            (p.heightM?.let { " as:Height=\"${f(it, 4)}\" as:HeightRef=\"${if (p.heightIsMsl) "MSL" else "ELLIPSOID"}\"" } ?: "") +
            (p.sigmaHM?.let { " as:SigmaH=\"${f(it, 4)}\"" } ?: "") + (p.sigmaVM?.let { " as:SigmaV=\"${f(it, 4)}\"" } ?: "") +
            " as:Fix=\"${fixLabel(p.fixQuality)}\">" +
            "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(p.name)}</rdf:li></rdf:Alt></dc:title>" +
            "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(description(p))}</rdf:li></rdf:Alt></dc:description>" +
            "</rdf:Description></rdf:RDF></x:xmpmeta>"
    }

    /** ExifInterface 는 XMP 를 ASCII 로 쓴다 → 비ASCII 는 XML 숫자 참조(&#xAC00;) 로 */
    fun xmpAscii(p: PhotoPointInfo): String = buildString { for (ch in xmp(p)) if (ch.code < 0x80) append(ch) else append("&#x%X;".format(ch.code)) }

    /** ExifInterface 태그 이름 → 값 (Android 쪽은 그대로 setAttribute) */
    fun tags(p: PhotoPointInfo): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["ImageDescription"] = ascii(description(p))
        m["UserComment"] = ascii(description(p))
        m["GPSLatitude"] = dms(p.lat); m["GPSLatitudeRef"] = if (p.lat >= 0) "N" else "S"
        m["GPSLongitude"] = dms(p.lon); m["GPSLongitudeRef"] = if (p.lon >= 0) "E" else "W"
        p.heightM?.let { m["GPSAltitude"] = "${Math.round(Math.abs(it) * 1000)}/1000"; m["GPSAltitudeRef"] = if (it >= 0) "0" else "1" }
        p.sigmaHM?.let { m["GPSHPositioningError"] = "${Math.round(it * 1000)}/1000" }
        m["GPSMapDatum"] = "EPSG:${p.epsg}"
        m["GPSProcessingMethod"] = "GNSS " + fixLabel(p.fixQuality)
        return m
    }
}

/** 측정 ↔ 촬영 시각 차이 한 줄 */
object PhotoGap {
    fun label(measuredAtMs: Long, photoAtMs: Long): String {
        if (measuredAtMs <= 0L || photoAtMs <= 0L) return "측정 시각 없음"
        val d = (photoAtMs - measuredAtMs) / 1000
        val sign = if (d < 0) "측정 전 " else "측정 후 "
        val a = Math.abs(d)
        val body = when {
            a < 60 -> "${a}초"
            a < 3600 -> "${a / 60}분" + (if (a % 60 != 0L) " ${a % 60}초" else "")
            a < 86400 -> "${a / 3600}시간" + (if ((a % 3600) / 60 != 0L) " ${(a % 3600) / 60}분" else "")
            else -> "${a / 86400}일"
        }
        return sign + body
    }
    /** 30분 넘게 떨어지면 다른 점 사진일 수 있음 */
    fun suspicious(measuredAtMs: Long, photoAtMs: Long): Boolean =
        measuredAtMs > 0 && photoAtMs > 0 && Math.abs(photoAtMs - measuredAtMs) > 30 * 60_000L
}

/** 사진 옮기기용 파일 작업(Android = 실제 파일, 시험 = 메모리) */
interface PhotoFileOps {
    fun exists(rel: String): Boolean
    fun list(dirRel: String): List<String>
    fun copy(fromRel: String, toRel: String)
    fun sha256(rel: String): String?
    fun size(rel: String): Long
    fun delete(rel: String)
}

data class PhotoRow(val id: Long, val pointName: String, val rel: String)

data class MigrationReport(val moved: Int, val skipped: Int, val failed: Int, val missing: Int) {
    val total get() = moved + skipped + failed + missing
}

/**
 * 옛 위치(<작업>/<점>/<점>_<시각>.jpg) → 새 위치 옮기기: 복사 → 크기·sha256 확인 → DB 갱신 → 옛 파일 삭제.
 * 어느 단계든 실패하면 새 사본만 지우고 옛 파일·DB 는 그대로(데이터 손실 없음). 다시 실행해도 안전.
 */
class PhotoMigration(private val ops: PhotoFileOps, private val updateRel: suspend (id: Long, newRel: String) -> Unit) {
    suspend fun run(rows: List<PhotoRow>, dirRel: String, onMoved: (PhotoRow, String) -> Unit = { _, _ -> }): MigrationReport {
        var moved = 0; var skipped = 0; var failed = 0; var missing = 0
        val taken = ops.list(dirRel).toMutableList()
        for (r in rows) {
            if (PhotoLayout.isNewLayout(r.rel)) { skipped++; continue }
            if (!ops.exists(r.rel)) { missing++; continue }
            val name = PhotoLayout.nextName(r.pointName, taken)
            val dst = "$dirRel/$name"
            try {
                ops.copy(r.rel, dst)
                val a = ops.sha256(r.rel); val b = ops.sha256(dst)
                if (a == null || a != b || ops.size(r.rel) != ops.size(dst)) error("verify")
                updateRel(r.id, dst)
            } catch (e: Exception) {
                runCatching { ops.delete(dst) }
                failed++; continue
            }
            taken.add(name)
            runCatching { ops.delete(r.rel) }
            onMoved(r, dst)
            moved++
        }
        return MigrationReport(moved, skipped, failed, missing)
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
