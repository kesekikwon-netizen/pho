package kr.archsurvey.pg.data

import kr.archsurvey.pg.tag.TargetNaming
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 0.0.9 M4·M10·M11: 기준점 파일 불러오기. TXT(「Target #N,E,N,H」)·XML(사진 기준점 SurveysData / BlocksExchange)·zip(안의 XML 우선, 없으면 TXT).
 * 좌표계는 파일에서: XML 의 SRS Definition 「EPSG:xxxx」 > 파일 이름 「_EPSGxxxx」. 못 찾으면 null(작업 값 유지).
 */
object GcpImport {
    data class Result(val gcps: List<Gcp>, val epsg: Int?, val message: String)

    private val fileEpsg = Regex("""_EPSG(\d{4,5})""", RegexOption.IGNORE_CASE)
    private val defEpsg = Regex("""EPSG:\s*(\d{4,5})""", RegexOption.IGNORE_CASE)

    fun epsgFromName(fileName: String): Int? = fileEpsg.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

    fun read(fileName: String, bytes: ByteArray): Result {
        val lower = fileName.lowercase()
        if (lower.endsWith(".zip") || (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())) {
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e = z.nextEntry; var total = 0L
                while (e != null) {
                    if (!e.isDirectory) { val b = z.readBytes(); total += b.size; if (total > 20_000_000) break; entries[e.name] = b }
                    e = z.nextEntry
                }
            }
            // 블록 xmlz 안의 block.xml 도 받음
            val xml = entries.entries.firstOrNull { it.key.lowercase().endsWith(".xml") }
                ?: entries.entries.firstOrNull { it.key.lowercase().endsWith(".xmlz") }?.let { x -> read(x.key, x.value).let { r -> return r.copy(epsg = r.epsg ?: epsgFromName(fileName)) } }
            val pick = xml ?: entries.entries.firstOrNull { it.key.lowercase().endsWith(".txt") && !it.key.contains("불러오기") && !it.key.contains("README", true) }
                ?: return Result(emptyList(), null, "zip 안에 기준점 파일(XML·TXT)이 없어요")
            val r = read(pick.key.substringAfterLast('/'), pick.value)
            return r.copy(epsg = r.epsg ?: epsgFromName(fileName))
        }
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (lower.endsWith(".xml") || text.trimStart().startsWith("<")) return readXml(fileName, text)
        val rep = GcpTxt.report(text)
        return Result(rep.gcps, epsgFromName(fileName), rep.message)
    }

    private fun readXml(fileName: String, text: String): Result {
        val f = DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = runCatching { f.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))) }.getOrNull()
            ?: return Result(emptyList(), null, "XML 을 읽지 못했어요")
        val defs = doc.getElementsByTagName("Definition")
        val epsg = (0 until defs.length).firstNotNullOfOrNull { defEpsg.find(defs.item(it).textContent)?.groupValues?.get(1)?.toIntOrNull() } ?: epsgFromName(fileName)
        val cps = doc.getElementsByTagName("ControlPoint")
        val out = LinkedHashMap<Int, Gcp>()
        for (i in 0 until cps.length) {
            val cp = cps.item(i) as org.w3c.dom.Element
            val name = cp.getElementsByTagName("Name").item(0)?.textContent ?: continue
            val no = TargetNaming.parseTargetNo(name) ?: continue
            val pos = cp.getElementsByTagName("Position").item(0) as? org.w3c.dom.Element ?: continue
            fun v(k: String) = pos.getElementsByTagName(k).item(0)?.textContent?.trim()?.toDoubleOrNull()
            val x = v("x") ?: continue; val y = v("y") ?: continue; val z = v("z") ?: continue
            if (x == 0.0 && y == 0.0) continue
            out[no] = Gcp(no, x, y, z)
        }
        val g = out.values.sortedBy { it.target }
        return Result(g, epsg, if (g.isEmpty()) "XML 에 좌표 있는 기준점이 없어요" else "좌표 ${g.size}점 불러왔어요")
    }

    fun beltName(epsg: Int) = when (epsg) { 5185 -> "서부"; 5186 -> "중부"; 5187 -> "동부"; 5188 -> "동해"; 5179 -> "통합"; else -> "EPSG" }
    /** M4 화면 한 줄: 「중부 5186으로 맞췄어요」 */
    fun epsgLine(epsg: Int) = "${beltName(epsg)} ${epsg}으로 맞췄어요"

    /** M11: 사진에 잡힌 표적과 불러온 좌표 번호가 하나도 안 겹치면 빨간 경고 */
    fun mismatch(gcps: List<Gcp>, seenTargets: Set<Int>): String? {
        if (gcps.isEmpty() || seenTargets.isEmpty()) return null
        return if (gcps.none { it.target in seenTargets })
            "불러온 좌표(${gcps.joinToString(",") { "#${it.target}" }.take(40)})와 사진 속 표적(${seenTargets.sorted().joinToString(",") { "#$it" }.take(40)})이 하나도 안 맞아요"
        else null
    }
}
