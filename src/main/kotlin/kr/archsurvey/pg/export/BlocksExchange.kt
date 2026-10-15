package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.tag.TargetNaming
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * iTwin Capture Modeler(ContextCapture) BlocksExchange XML 2.1 — 사용 설명서 「BlocksExchange XML/XMLZ format」 그대로.
 * - 사진: Photogroup(같은 크기·초점) / Photo(Id, ImagePath 상대 경로). 자세는 넣지 않는다(iTwin 자동 AT 가 구함).
 * - 표적: 「Target #N」 이름(iTwin 표적·사진 기준점 앱 TXT 와 같은 이름) + 사진별 Measurement(원본 화소, 왼쪽 위 화소 「중심」 = 0,0, y 아래).
 *   AprilTag 3 화소는 왼쪽 위 화소의 「모서리」 = 0,0 이라 0.5 를 뺀다(표적 그림 1 로 시험: 검은 네모 102..901 → 중심 501.5).
 * - 좌표가 있는 표적: Position(EPSG). 없는 표적: [MarkStyle] 에 따라 Position 없는 ControlPoint 또는 이름 붙은 TiePoint.
 * - XMLZ = 같은 XML 을 zip 으로 묶은 것(설명서: "XMLZ format is an extension of XML format adding file compression (zip)").
 */
object BlocksExchange {
    enum class MarkStyle { CONTROL_NO_POSITION, TIE_POINTS }
    const val PIXEL_SHIFT = -0.5

    data class Options(
        val blockName: String,
        val epsg: Int? = 5187,
        val style: MarkStyle = MarkStyle.CONTROL_NO_POSITION,
        val accuracyM: Double = 0.01,
        /** 이 번호들은 검사점(CheckPoint true) */
        val checkTargets: Set<Int> = emptySet(),
        val photoDir: String = "photos",
    )

    fun write(stills: List<Still>, gcps: List<Gcp>, o: Options): String {
        val nl = "\r\n"
        val sb = StringBuilder()
        fun line(ind: Int, s: String) { repeat(ind) { sb.append('\t') }; sb.append(s).append(nl) }
        fun tag(ind: Int, name: String, v: String) = line(ind, "<$name>${esc(v)}</$name>")
        val gmap = gcps.associateBy { it.target }
        val withCoords = o.epsg != null && gmap.isNotEmpty()

        line(0, "<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        line(0, "<BlocksExchange version=\"2.1\">")
        if (withCoords) {
            line(1, "<SpatialReferenceSystems>"); line(2, "<SRS>")
            tag(3, "Id", "0"); tag(3, "Name", srsName(o.epsg!!)); tag(3, "Definition", "EPSG:${o.epsg}")
            line(2, "</SRS>"); line(1, "</SpatialReferenceSystems>")
        }
        line(1, "<Block>")
        tag(2, "Name", o.blockName)
        tag(2, "Description", "사진측량 앱 (kr.archsurvey.pg) — AprilTag tag36h11 자동 표시")
        if (withCoords) tag(2, "SRSId", "0")
        line(2, "<Photogroups>")
        val groups = stills.groupBy { Triple(it.w, it.h, Math.round(it.fx)) }
        var gi = 0
        for ((k, list) in groups) {
            line(3, "<Photogroup>")
            tag(4, "Name", "Phone camera ${++gi} (${k.first}x${k.second})")
            line(4, "<ImageDimensions>"); tag(5, "Width", "${k.first}"); tag(5, "Height", "${k.second}"); line(4, "</ImageDimensions>")
            tag(4, "CameraModelType", "Perspective")
            tag(4, "CameraModelBand", "Visible")
            val f = list.map { (it.fx + it.fy) / 2 }.average()
            if (f > 0) tag(4, "FocalLengthPixels", num(f, 2))
            tag(4, "CameraOrientation", "XRightYDown")
            // 주점(ARCore CameraIntrinsics, 이 JPG 화소). 0.5 빼서 「왼쪽 위 화소 중심 = 0」
            val ppx = list.map { it.cx }.average(); val ppy = list.map { it.cy }.average()
            if (ppx > 0 && ppy > 0) { line(4, "<PrincipalPoint>"); tag(5, "x", num(ppx + PIXEL_SHIFT, 3)); tag(5, "y", num(ppy + PIXEL_SHIFT, 3)); line(4, "</PrincipalPoint>") }
            for (s in list) {
                line(4, "<Photo>")
                tag(5, "Id", "${s.id}")
                tag(5, "ImagePath", "${o.photoDir}/${s.file}")
                line(4, "</Photo>")
            }
            line(3, "</Photogroup>")
        }
        line(2, "</Photogroups>")

        // 표적 번호 → [(사진 id, x, y)]
        val marks = sortedMapOf<Int, MutableList<Triple<Int, Double, Double>>>()
        for (s in stills) for (t in s.tags) marks.getOrPut(TargetNaming.targetNo(t.id)) { mutableListOf() } += Triple(s.id, t.cx + PIXEL_SHIFT, t.cy + PIXEL_SHIFT)
        val known = marks.keys.filter { withCoords && it in gmap }
        // 0.0.9 M3: 좌표 없는 표적은 쓰지 않음. 좌표가 하나도 없을 때만 표적을 이름 붙은 TiePoint(좌표 없음)로 넣어 AT 를 도움
        val unknown = if (known.isEmpty()) marks.keys.toList() else emptyList()

        fun measurements(ind: Int, l: List<Triple<Int, Double, Double>>) = l.distinctBy { it.first }.forEach { (pid, x, y) ->
            line(ind, "<Measurement>"); tag(ind + 1, "PhotoId", "$pid"); tag(ind + 1, "x", num(x, 3)); tag(ind + 1, "y", num(y, 3)); line(ind, "</Measurement>")
        }
        // 좌표가 하나라도 있으면 좌표 없는 표적은 이름 붙은 TiePoint 로(위치 없는 ControlPoint 는 iTwin 이 좌표 0 으로 볼 위험) — 0.0.7
        val cpUnknown = false
        if (known.isNotEmpty() || (cpUnknown && unknown.isNotEmpty())) {
            line(2, "<ControlPoints>")
            for (no in known) {
                val g = gmap.getValue(no)
                line(3, "<ControlPoint>")
                tag(4, "Name", TargetNaming.name(TargetNaming.tagId(no)))
                tag(4, "Category", "Full")
                line(4, "<Position>"); tag(5, "x", num(g.e, 4)); tag(5, "y", num(g.n, 4)); tag(5, "z", num(g.h, 4)); line(4, "</Position>")
                tag(4, "HorizontalAccuracy", num(o.accuracyM, 3)); tag(4, "VerticalAccuracy", num(o.accuracyM, 3))
                tag(4, "CheckPoint", if (no in o.checkTargets) "true" else "false")
                measurements(4, marks.getValue(no))
                line(3, "</ControlPoint>")
            }
            if (cpUnknown) for (no in unknown) {
                line(3, "<ControlPoint>")
                tag(4, "Name", TargetNaming.name(TargetNaming.tagId(no)))
                tag(4, "Category", "Full")
                tag(4, "CheckPoint", "false")
                measurements(4, marks.getValue(no))
                line(3, "</ControlPoint>")
            }
            line(2, "</ControlPoints>")
        }
        if (!cpUnknown && unknown.isNotEmpty()) {
            line(2, "<TiePoints>")
            for (no in unknown) {
                line(3, "<TiePoint>")
                tag(4, "Name", TargetNaming.name(TargetNaming.tagId(no)))
                measurements(4, marks.getValue(no))
                line(3, "</TiePoint>")
            }
            line(2, "</TiePoints>")
        }
        line(1, "</Block>")
        line(0, "</BlocksExchange>")
        return sb.toString()
    }

    /** block.xmlz = zip 안에 block.xml 하나 */
    fun xmlz(xml: String, entry: String = "block.xml"): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry(entry)); z.write(xml.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        return bo.toByteArray()
    }

    /** 백업 TXT 「Target #N,E,N,H」(CRLF·머리줄 없음). 0.0.9 M3: 좌표 있는 점만 */
    fun gcpTxt(gcps: List<Gcp>): String = gcps.sortedBy { it.target }.joinToString("") { p ->
        "${TargetNaming.name(TargetNaming.tagId(p.target))},${f3(p.e)},${f3(p.n)},${f3(p.h)}\r\n"
    }

    fun srsName(epsg: Int): String = when (epsg) {
        5185 -> "Korea 2000 / West Belt 2010 (EPSG:5185)"
        5186 -> "Korea 2000 / Central Belt 2010 (EPSG:5186)"
        5187 -> "Korea 2000 / East Belt 2010 (EPSG:5187)"
        5188 -> "Korea 2000 / East Sea Belt 2010 (EPSG:5188)"
        5179 -> "Korea 2000 / Unified CS (EPSG:5179)"
        else -> "EPSG:$epsg"
    }
    private fun f3(v: Double) = String.format(Locale.US, "%.3f", v)
    fun num(v: Double, d: Int): String {
        val s = BigDecimal(v).setScale(d, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if ('.' in s) s else "$s.0"
    }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

}
