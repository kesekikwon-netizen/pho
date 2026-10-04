package kr.archsurvey.georef

import kotlin.math.abs

/** WKT 노드: NAME[arg, arg, ...]. arg 는 Node / String / Double */
class WktNode(val name: String, val args: List<Any>) {
    fun children(name: String) = args.filterIsInstance<WktNode>().filter { it.name.equals(name, ignoreCase = true) }
    fun child(name: String) = children(name).firstOrNull()
    fun str(i: Int = 0) = args.getOrNull(i) as? String
    fun num(i: Int) = (args.getOrNull(i) as? Double)
}

/** 아주 작은 WKT 파서(WKT1·ESRI WKT·WKT2 공통 문법). 검증용 오류는 null 반환. */
object Wkt {
    fun parse(text: String): WktNode? = try {
        val p = P(text.trim().trimStart('\uFEFF')); val n = p.node(); p.ws(); if (p.i < p.s.length || n == null) null else n
    } catch (e: IllegalStateException) { null }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun node(): WktNode? {
            ws(); val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            if (i == st) return null
            val name = s.substring(st, i); ws()
            if (i >= s.length || (s[i] != '[' && s[i] != '(')) return WktNode(name, emptyList())
            val close = if (s[i] == '[') ']' else ')'; i++
            val args = ArrayList<Any>()
            while (true) {
                ws(); if (i >= s.length) error("eof")
                if (s[i] == close) { i++; break }
                if (s[i] == ',') { i++; continue }
                if (s[i] == '"') {
                    val sb = StringBuilder(); i++
                    while (true) {
                        if (i >= s.length) error("quote")
                        if (s[i] == '"') { if (i + 1 < s.length && s[i + 1] == '"') { sb.append('"'); i += 2; continue } else { i++; break } }
                        sb.append(s[i]); i++
                    }
                    args += sb.toString()
                } else if (s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i].isDigit()) {
                    val a = i; i++
                    while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
                    args += (s.substring(a, i).toDoubleOrNull() ?: error("num"))
                } else args += (node() ?: error("node"))
            }
            return WktNode(name, args)
        }
    }
}

enum class PrjMatch { AUTHORITY, NAME, PARAMETERS, NONE }

data class PrjResult(
    /** 확정된 카탈로그 좌표계. 없으면 null */
    val crs: CrsDef?, val epsgCode: Int?, val match: PrjMatch,
    /** 확정 못 했을 때 보여 줄 후보(파라미터가 비슷한 순) */
    val candidates: List<CrsDef>, val notes: List<String>,
)

/** .prj(WKT) -> 좌표계. 우선순위: AUTHORITY/ID 코드 -> 투영 이름 -> 투영 파라미터. 실패하면 후보 목록(§3.5.1). */
object PrjParser {
    private val NAME_KEYS: Map<String, Int> = mapOf(
        // 정규화(소문자·영숫자만) 이름 -> EPSG. 한글 소프트웨어가 내는 이름 계열 포함
        "korea2000koreawestbelt2010" to 5185, "korea2000koreacentralbelt2010" to 5186,
        "korea2000koreaeastbelt2010" to 5187, "korea2000koreaeastseabelt2010" to 5188,
        "kgd2002westbelt2010" to 5185, "kgd2002centralbelt2010" to 5186,
        "kgd2002eastbelt2010" to 5187, "kgd2002eastseabelt2010" to 5188,
        "korea2000koreacentralbelt" to 5181, "korea2000koreaeastbelt" to 5183,
        "kgd2002centralbelt" to 5181, "kgd2002eastbelt" to 5183,
        "korea2000koreaunifiedcs" to 5179, "kgd2002unifiedcs" to 5179, "kgd2002unifiedcoordinatesystem" to 5179,
        "korean1985modifiedkoreacentralbelt" to 5174,
    )

    fun parse(text: String): PrjResult {
        val root = Wkt.parse(text) ?: return PrjResult(null, null, PrjMatch.NONE, emptyList(), listOf("WKT 로 읽을 수 없어요"))
        val rn = root.name.uppercase()
        // 1) 권한 코드 (루트의 직계 자식만: 중첩된 GEOGCS 의 4737 등은 무시)
        authority(root)?.let { code ->
            val c = KoreanCrs.byEpsg(code)
            return PrjResult(c, code, PrjMatch.AUTHORITY, if (c == null) paramCandidates(root) else emptyList(),
                if (c == null) listOf("EPSG:$code 는 앱 목록에 없어요") else emptyList())
        }
        val isProj = rn == "PROJCS" || rn == "PROJCRS" || rn == "PROJECTEDCRS"
        val isGeo = rn == "GEOGCS" || rn == "GEOGCRS" || rn == "GEODETICCRS"
        if (!isProj && !isGeo) return PrjResult(null, null, PrjMatch.NONE, emptyList(), listOf("지원하지 않는 좌표계 종류($rn)"))
        // 2) 이름
        if (isProj) {
            val key = norm(root.str() ?: "")
            NAME_KEYS[key]?.let { e -> KoreanCrs.byEpsg(e)?.let { return PrjResult(it, e, PrjMatch.NAME, emptyList(), emptyList()) } }
        }
        // 3) 파라미터
        if (isProj) {
            val cands = paramCandidates(root)
            val exact = cands.firstOrNull { paramsMatch(root, it) }
            return if (exact != null && paramsMatch(root, exact)) PrjResult(exact, exact.epsg, PrjMatch.PARAMETERS, emptyList(), emptyList())
            else PrjResult(null, null, PrjMatch.NONE, cands, listOf("투영 파라미터가 목록의 좌표계와 일치하지 않아요"))
        }
        // 지리 좌표계만 있을 때
        val gname = norm(root.str() ?: "")
        return when {
            "wgs84" in gname || gname == "gcswgs1984" -> PrjResult(KoreanCrs.WGS84, 4326, PrjMatch.NAME, emptyList(), emptyList())
            gname == "kgd2002" || gname == "gcskorea2000" -> PrjResult(KoreanCrs.KGD2002_GEO, 4737, PrjMatch.NAME, emptyList(), emptyList())
            else -> PrjResult(null, null, PrjMatch.NONE, emptyList(), listOf("지리 좌표계 이름을 알 수 없어요"))
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun authority(root: WktNode): Int? {
        for (n in root.args.filterIsInstance<WktNode>()) {
            val u = n.name.uppercase()
            if (u == "AUTHORITY" || u == "ID") {
                if (n.str(0).equals("EPSG", ignoreCase = true)) {
                    val v = n.args.getOrNull(1)
                    return when (v) { is Double -> v.toInt(); is String -> v.toIntOrNull(); else -> null }
                }
            }
        }
        return null
    }

    private class P(val lat0: Double?, val lon0: Double?, val k0: Double?, val fe: Double?, val fn: Double?, val bessel: Boolean)

    private fun readParams(root: WktNode): P {
        val m = HashMap<String, Double>()
        fun grab(n: WktNode) {
            for (p in n.args.filterIsInstance<WktNode>()) {
                if (p.name.equals("PARAMETER", ignoreCase = true)) {
                    val k = norm(p.str() ?: ""); val v = p.num(1); if (v != null) m[k] = v
                } else if (p.name.equals("CONVERSION", ignoreCase = true)) grab(p)
            }
        }
        grab(root)
        fun g(vararg keys: String) = keys.firstNotNullOfOrNull { m[it] }
        // 지리 좌표계의 회전타원체 장반경으로 Bessel 판별
        fun findSpheroid(n: WktNode): WktNode? {
            for (c in n.args.filterIsInstance<WktNode>()) {
                if (c.name.equals("SPHEROID", true) || c.name.equals("ELLIPSOID", true)) return c
                findSpheroid(c)?.let { return it }
            }
            return null
        }
        val sph = findSpheroid(root)
        val semi = sph?.num(1)
        val bessel = (semi != null && abs(semi - 6377397.155) < 1.0) || (sph?.str()?.contains("bessel", true) == true)
        return P(g("latitudeoforigin", "latitudeofnaturalorigin"), g("centralmeridian", "longitudeofnaturalorigin"),
            g("scalefactor", "scalefactoratnaturalorigin"), g("falseeasting"), g("falsenorthing"), bessel)
    }

    private fun projectionIsTm(root: WktNode): Boolean {
        val proj = root.child("PROJECTION")?.str() ?: root.child("CONVERSION")?.child("METHOD")?.str() ?: return false
        return norm(proj).contains("transversemercator")
    }

    private fun paramsMatch(root: WktNode, c: CrsDef): Boolean {
        if (!projectionIsTm(root)) return false
        val p = readParams(root)
        fun near(a: Double?, b: Double, tol: Double) = a != null && abs(a - b) <= tol
        val datumOk = (c.datum == Datum.KOREAN1985_BESSEL) == p.bessel
        return datumOk && near(p.lat0, c.lat0, 1e-6) && near(p.lon0, c.lon0, 1e-6) && near(p.k0, c.k0, 1e-9) &&
            near(p.fe, c.fe, 1e-3) && near(p.fn, c.fn, 1e-3)
    }

    /** 파라미터가 일부라도 맞는 카탈로그 투영 좌표계(많이 맞는 순) */
    private fun paramCandidates(root: WktNode): List<CrsDef> {
        if (!projectionIsTm(root)) return emptyList()
        val p = readParams(root)
        fun near(a: Double?, b: Double, tol: Double) = if (a != null && abs(a - b) <= tol) 1 else 0
        return KoreanCrs.projected.map { c ->
            c to (near(p.lat0, c.lat0, 1e-6) + near(p.lon0, c.lon0, 0.01) + near(p.k0, c.k0, 1e-9) + near(p.fe, c.fe, 1e-3) + near(p.fn, c.fn, 1e-3) +
                if ((c.datum == Datum.KOREAN1985_BESSEL) == p.bessel) 1 else 0)
        }.filter { it.second >= 4 }.sortedByDescending { it.second }.map { it.first }
    }
}
