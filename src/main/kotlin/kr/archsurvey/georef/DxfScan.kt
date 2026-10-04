package kr.archsurvey.georef

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.math.max
import kotlin.math.min

data class Extent(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val centerX get() = (minX + maxX) / 2
    val centerY get() = (minY + maxY) / 2
    fun swapped() = Extent(minY, minX, maxY, maxX)
    fun corners() = listOf(XY(minX, minY), XY(maxX, minY), XY(maxX, maxY), XY(minX, maxY))
}

data class DxfScanResult(
    /** ENTITIES 구간 정점 전체의 범위 */
    val extent: Extent?,
    /** 정점 X/Y 각각 1%~99% 분위로 자른 범위(원점에 떨어진 이상점 대응). 정점이 50개 미만이면 extent 와 같다 */
    val robustExtent: Extent?,
    val vertexCount: Int,
    val entityCounts: Map<String, Int>,
    /** $INSUNITS (6=미터). 헤더에 없으면 null */
    val insunits: Int?,
    val codepage: String?,
    val acadVersion: String?,
    val skippedEntityTypes: Set<String>,
    val warnings: List<String>,
)

/**
 * DXF 소형 파서 — **좌표 범위 계산 전용**(표시용 벡터 추출은 후속). ASCII DXF 만 지원, 바이너리 DXF 는 거부.
 * 대상 엔티티: LINE·LWPOLYLINE·POLYLINE/VERTEX·POINT·CIRCLE·ARC·TEXT·MTEXT·INSERT·SPLINE(제어점)·3DFACE. ELLIPSE 등은 건너뛰고 알린다.
 * BLOCKS 구간(블록 로컬 좌표)은 제외하고 ENTITIES 만 본다. INSERT 는 삽입점만 반영(블록 전개 없음 — 후속).
 * 문자 인코딩: $DWGCODEPAGE 가 ANSI_949 면 CP949, ANSI_1252/없음이면 UTF-8 로 시도 후 ISO-8859-1 (좌표 숫자는 모든 경우 동일).
 */
object DxfScan {
    class BinaryDxfException : RuntimeException("바이너리 DXF 는 지원하지 않아요. ASCII DXF 로 저장하세요")

    fun scan(file: File): DxfScanResult = file.inputStream().buffered(1 shl 16).use { scan(it) }

    private val Z_ENTITIES = setOf("LINE", "LWPOLYLINE", "VERTEX", "POINT", "CIRCLE", "ARC", "TEXT", "MTEXT", "INSERT", "SPLINE", "3DFACE", "POLYLINE", "ATTRIB", "SOLID")

    fun scan(input: InputStream): DxfScanResult {
        val bytes = input.readBytes()
        if (bytes.size >= 22 && String(bytes, 0, 22, Charsets.ISO_8859_1) == "AutoCAD Binary DXF\r\n") throw BinaryDxfException()
        if (bytes.size >= 18 && String(bytes, 0, 18, Charsets.ISO_8859_1) == "AutoCAD Binary DXF") throw BinaryDxfException()
        // 헤더의 코드페이지를 먼저 훑는다(ISO-8859-1 은 항상 바이트 보존).
        val text = String(bytes, Charsets.ISO_8859_1)
        val lines = text.split('\n')
        var codepage: String? = null
        var insunits: Int? = null
        var acad: String? = null
        var section = ""
        var i = 0
        var ent = ""
        var inPolyline = false
        val counts = HashMap<String, Int>()
        val skipped = linkedSetOf<String>()
        // 큰 파일에서 boxing 을 피하려고 직접 증가 배열
        var vx = DoubleArray(4096); var vy = DoubleArray(4096); var vn = 0
        fun addPt(x: Double, y: Double) {
            if (!x.isFinite() || !y.isFinite()) return
            if (vn == vx.size) { vx = vx.copyOf(vn * 2); vy = vy.copyOf(vn * 2) }
            vx[vn] = x; vy[vn] = y; vn++
        }
        // 엔티티별 보류 값
        var px: Double? = null; var py: Double? = null; var qx: Double? = null; var qy: Double? = null; var radius: Double? = null
        var pendingX: Double? = null  // 반복 10/20 쌍 처리용
        var headerVar = ""
        fun flushEntity() {
            when (ent) {
                "LINE" -> { px?.let { x -> py?.let { y -> addPt(x, y) } }; qx?.let { x -> qy?.let { y -> addPt(x, y) } } }
                "CIRCLE", "ARC" -> {
                    val cx = px; val cy = py; val r = radius
                    if (cx != null && cy != null) { if (r != null && r > 0) { addPt(cx - r, cy - r); addPt(cx + r, cy + r) } else addPt(cx, cy) }
                }
                "POINT", "TEXT", "MTEXT", "INSERT", "ATTRIB" -> px?.let { x -> py?.let { y -> addPt(x, y) } }
                else -> {}
            }
            px = null; py = null; qx = null; qy = null; radius = null; pendingX = null
        }
        while (i + 1 < lines.size) {
            val code = lines[i].trim().toIntOrNull()
            if (code == null) { i += 2; continue }
            val v = lines[i + 1].trim('\r', ' ', '\t')
            i += 2
            if (code == 0) {
                if (section == "ENTITIES") flushEntity()
                when (v) {
                    "SECTION" -> {}
                    "ENDSEC" -> { section = ""; ent = ""; inPolyline = false }
                    "SEQEND" -> inPolyline = false
                    "EOF" -> break
                    else -> {
                        ent = v
                        if (section == "ENTITIES") {
                            counts.merge(v, 1, Int::plus)
                            if (v !in Z_ENTITIES) skipped += v
                            if (v == "POLYLINE") inPolyline = true
                        }
                    }
                }
                continue
            }
            if (code == 2 && section == "" ) { section = v; continue }
            if (section == "HEADER") {
                if (code == 9) { headerVar = v; continue }
                when (headerVar) {
                    "\$INSUNITS" -> if (code == 70) insunits = v.toIntOrNull()
                    "\$DWGCODEPAGE" -> if (code == 3) codepage = v
                    "\$ACADVER" -> if (code == 1) acad = v
                }
                continue
            }
            if (section != "ENTITIES") continue
            val d = v.toDoubleOrNull()
            when (ent) {
                "LWPOLYLINE", "SPLINE", "3DFACE", "SOLID" -> when (code) {
                    10, 11, 12, 13 -> pendingX = d
                    20, 21, 22, 23 -> { val x = pendingX; if (x != null && d != null) addPt(x, d); pendingX = null }
                }
                "VERTEX" -> when (code) { 10 -> pendingX = d; 20 -> { val x = pendingX; if (x != null && d != null) addPt(x, d); pendingX = null } }
                "POLYLINE" -> {} // POLYLINE 헤더의 10/20 은 0,0 요소점(고도) — 정점은 VERTEX 에서 읽는다
                "LINE" -> when (code) { 10 -> px = d; 20 -> py = d; 11 -> qx = d; 21 -> qy = d }
                "TEXT", "MTEXT", "ATTRIB" -> when (code) { 10 -> px = d; 20 -> py = d } // 11/21(정렬점)은 쓰지 않는다
                "CIRCLE", "ARC" -> when (code) { 10 -> px = d; 20 -> py = d; 40 -> radius = d }
                else -> when (code) { 10 -> px = d; 20 -> py = d }
            }
        }
        if (section == "ENTITIES") flushEntity()
        // POLYLINE 의 VERTEX 등은 위에서 처리됨. 마지막 엔티티 flush 보장
        val warnings = mutableListOf<String>()
        if (skipped.isNotEmpty()) warnings += "범위 계산에서 제외한 엔티티: ${skipped.joinToString()}"
        if ((counts["INSERT"] ?: 0) > 0) warnings += "블록 삽입(INSERT)은 삽입점만 반영했어요(블록 내부 도형은 범위에 안 들어감)"
        if (vn == 0) return DxfScanResult(null, null, 0, counts, insunits, codepage, acad, skipped, warnings + "읽을 수 있는 좌표가 없어요")
        var mnx = Double.MAX_VALUE; var mny = Double.MAX_VALUE; var mxx = -Double.MAX_VALUE; var mxy = -Double.MAX_VALUE
        for (k in 0 until vn) { mnx = min(mnx, vx[k]); mxx = max(mxx, vx[k]); mny = min(mny, vy[k]); mxy = max(mxy, vy[k]) }
        val ext = Extent(mnx, mny, mxx, mxy)
        val robust = if (vn < 50) ext else {
            val sx = vx.copyOf(vn).also { it.sort() }; val sy = vy.copyOf(vn).also { it.sort() }
            val lo = (vn * 0.01).toInt(); val hi = (vn * 0.99).toInt().coerceAtMost(vn - 1)
            Extent(sx[lo], sy[lo], sx[hi], sy[hi])
        }
        return DxfScanResult(ext, robust, vn, counts, insunits, codepage, acad, skipped, warnings)
    }

    /** 코드페이지 이름 -> Charset (문자열 읽기용; 범위 계산에는 불필요) */
    fun charsetFor(codepage: String?): Charset = when (codepage?.uppercase()) {
        "ANSI_949" -> Charset.forName("x-windows-949")
        "ANSI_1252" -> Charset.forName("windows-1252")
        else -> Charsets.UTF_8
    }
}
