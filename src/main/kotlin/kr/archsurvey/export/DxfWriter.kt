package kr.archsurvey.export

import java.nio.charset.Charset
import java.util.Locale

/** 축 순서. EAST_X: X=동·Y=북(CAD 기본). NORTH_X: X=북·Y=동(국내 좌표 관행) */
enum class DxfAxisOrder { EAST_X, NORTH_X }

data class DxfPoint(val id: String, val eastM: Double, val northM: Double, val heightM: Double?)

data class DxfOptions(
    val axis: DxfAxisOrder = DxfAxisOrder.EAST_X,
    /** true 면 점들을 순서대로 이은 폐합 LWPOLYLINE 을 PT_LINE 레이어에 추가(3점 이상) */
    val closedOutline: Boolean = false,
    val labelHeightM: Double = 0.15,
    /** 점번호·표고 글자가 점에서 떨어지는 거리(도면 단위, m) */
    val labelOffsetM: Double = 0.1,
)

/**
 * 자체 ASCII DXF 라이터 (WP-7). 외부 라이브러리 없음.
 * - 버전 R2000(AC1015), $INSUNITS=6(미터), $DWGCODEPAGE=ANSI_949, 파일은 CP949 원문.
 * - 레이어는 영문: PT(POINT), PT_NO(점번호 TEXT), PT_ELEV(표고 TEXT), PT_LINE(선택 윤곽).
 * - CP949 로 표현 못 하는 글자는 \U+XXXX 로 바꾼다.
 * 검증: ezdxf strict 로드·audit 오류 0·GDAL/OGR 판독 (tools/validate_dxf.py). 실제 CAD 열람은 현장 단계.
 */
object DxfWriter {
    val LAYERS = listOf("PT" to 1, "PT_NO" to 2, "PT_ELEV" to 3, "PT_LINE" to 6)

    fun write(points: List<DxfPoint>, opt: DxfOptions = DxfOptions(), charset: Charset = cp949()): ByteArray =
        build(points, opt, charset).toByteArray(charset)

    fun cp949(): Charset = runCatching { Charset.forName("MS949") }.getOrElse { Charset.forName("x-windows-949") }

    fun build(points: List<DxfPoint>, opt: DxfOptions = DxfOptions(), charset: Charset = cp949()): String {
        val w = W(charset)
        fun xy(e: Double, n: Double) = if (opt.axis == DxfAxisOrder.NORTH_X) n to e else e to n
        val xs = points.map { xy(it.eastM, it.northM) }
        val minX = xs.minOfOrNull { it.first } ?: 0.0; val maxX = xs.maxOfOrNull { it.first } ?: 0.0
        val minY = xs.minOfOrNull { it.second } ?: 0.0; val maxY = xs.maxOfOrNull { it.second } ?: 0.0

        w.sec("HEADER")
        w.g(9, "\$ACADVER"); w.g(1, "AC1015")
        w.g(9, "\$HANDSEED"); w.g(5, "FFFFF")
        w.g(9, "\$DWGCODEPAGE"); w.g(3, "ANSI_949")
        w.g(9, "\$INSUNITS"); w.g(70, 6)
        w.g(9, "\$MEASUREMENT"); w.g(70, 1)
        w.g(9, "\$EXTMIN"); w.g(10, w.n(minX)); w.g(20, w.n(minY)); w.g(30, "0.0")
        w.g(9, "\$EXTMAX"); w.g(10, w.n(maxX)); w.g(20, w.n(maxY)); w.g(30, "0.0")
        w.endsec()
        w.sec("CLASSES"); w.endsec()

        w.sec("TABLES")
        w.g(0, "TABLE"); w.g(2, "LTYPE"); w.g(5, "5"); w.g(100, "AcDbSymbolTable"); w.g(70, 1)
        w.g(0, "LTYPE"); w.g(5, w.handle()); w.g(100, "AcDbSymbolTableRecord"); w.g(100, "AcDbLinetypeTableRecord")
        w.g(2, "CONTINUOUS"); w.g(70, 0); w.g(3, "Solid line"); w.g(72, 65); w.g(73, 0); w.g(40, "0.0")
        w.g(0, "ENDTAB")
        w.g(0, "TABLE"); w.g(2, "LAYER"); w.g(5, w.handle()); w.g(100, "AcDbSymbolTable"); w.g(70, LAYERS.size + 1)
        for ((name, color) in listOf("0" to 7) + LAYERS) {
            w.g(0, "LAYER"); w.g(5, w.handle()); w.g(100, "AcDbSymbolTableRecord"); w.g(100, "AcDbLayerTableRecord")
            w.g(2, name); w.g(70, 0); w.g(62, color); w.g(6, "CONTINUOUS")
        }
        w.g(0, "ENDTAB")
        w.g(0, "TABLE"); w.g(2, "STYLE"); w.g(5, w.handle()); w.g(100, "AcDbSymbolTable"); w.g(70, 1)
        w.g(0, "STYLE"); w.g(5, w.handle()); w.g(100, "AcDbSymbolTableRecord"); w.g(100, "AcDbTextStyleTableRecord")
        w.g(2, "STANDARD"); w.g(70, 0); w.g(40, "0.0"); w.g(41, "1.0"); w.g(50, "0.0"); w.g(71, 0); w.g(42, "0.2"); w.g(3, "txt.shx"); w.g(4, "")
        w.g(0, "ENDTAB")
        w.g(0, "TABLE"); w.g(2, "APPID"); w.g(5, w.handle()); w.g(100, "AcDbSymbolTable"); w.g(70, 1)
        w.g(0, "APPID"); w.g(5, w.handle()); w.g(100, "AcDbSymbolTableRecord"); w.g(100, "AcDbRegAppTableRecord"); w.g(2, "ACAD"); w.g(70, 0)
        w.g(0, "ENDTAB")
        val modelBr = w.handle(); val paperBr = w.handle()
        w.g(0, "TABLE"); w.g(2, "BLOCK_RECORD"); w.g(5, w.handle()); w.g(100, "AcDbSymbolTable"); w.g(70, 2)
        for ((nm, hh) in listOf("*Model_Space" to modelBr, "*Paper_Space" to paperBr)) {
            w.g(0, "BLOCK_RECORD"); w.g(5, hh); w.g(100, "AcDbSymbolTableRecord"); w.g(100, "AcDbBlockTableRecord"); w.g(2, nm)
        }
        w.g(0, "ENDTAB")
        w.endsec()

        w.sec("BLOCKS")
        for (nm in listOf("*Model_Space", "*Paper_Space")) {
            w.g(0, "BLOCK"); w.g(5, w.handle()); w.g(100, "AcDbEntity"); w.g(8, "0"); w.g(100, "AcDbBlockBegin")
            w.g(2, nm); w.g(70, 0); w.g(10, "0.0"); w.g(20, "0.0"); w.g(30, "0.0"); w.g(3, nm); w.g(1, "")
            w.g(0, "ENDBLK"); w.g(5, w.handle()); w.g(100, "AcDbEntity"); w.g(8, "0"); w.g(100, "AcDbBlockEnd")
        }
        w.endsec()

        w.sec("ENTITIES")
        fun ent(type: String, layer: String, sub: String) {
            w.g(0, type); w.g(5, w.handle()); w.g(330, modelBr); w.g(100, "AcDbEntity"); w.g(8, layer); w.g(100, sub)
        }
        for (p in points) {
            val (x, y) = xy(p.eastM, p.northM)
            val z = p.heightM ?: 0.0
            ent("POINT", "PT", "AcDbPoint")
            w.g(10, w.n(x)); w.g(20, w.n(y)); w.g(30, w.n(z))
            val tx = xy(p.eastM + opt.labelOffsetM, p.northM + opt.labelOffsetM)
            ent("TEXT", "PT_NO", "AcDbText")
            w.g(10, w.n(tx.first)); w.g(20, w.n(tx.second)); w.g(30, "0.0"); w.g(40, w.n(opt.labelHeightM, 3)); w.g(1, w.text(p.id)); w.g(100, "AcDbText")
            if (p.heightM != null) {
                val ez = xy(p.eastM + opt.labelOffsetM, p.northM - opt.labelOffsetM - opt.labelHeightM)
                ent("TEXT", "PT_ELEV", "AcDbText")
                w.g(10, w.n(ez.first)); w.g(20, w.n(ez.second)); w.g(30, "0.0"); w.g(40, w.n(opt.labelHeightM * 0.8, 3))
                w.g(1, String.format(Locale.US, "%.3f", p.heightM)); w.g(100, "AcDbText")
            }
        }
        if (opt.closedOutline && points.size >= 3) {
            ent("LWPOLYLINE", "PT_LINE", "AcDbPolyline")
            w.g(90, points.size); w.g(70, 1)
            for (p in points) { val (x, y) = xy(p.eastM, p.northM); w.g(10, w.n(x)); w.g(20, w.n(y)) }
        }
        w.endsec()
        w.sec("OBJECTS")
        w.g(0, "DICTIONARY"); w.g(5, "C"); w.g(330, "0"); w.g(100, "AcDbDictionary"); w.g(281, 1)
        w.endsec()
        w.g(0, "EOF")
        return w.toString()
    }

    private class W(private val cs: Charset) {
        private val sb = StringBuilder()
        private var h = 0x100
        private val enc = cs.newEncoder()
        fun g(code: Int, v: Any) { sb.append(String.format(Locale.US, "%3d", code)).append('\n').append(v).append('\n') }
        fun handle(): String { h++; return Integer.toHexString(h).uppercase(Locale.US) }
        fun sec(name: String) { g(0, "SECTION"); g(2, name) }
        fun endsec() = g(0, "ENDSEC")
        fun n(v: Double, d: Int = 4): String = String.format(Locale.US, "%.${d}f", v)
        /** 개행·제어문자 제거, CP949 로 못 쓰는 글자는 \U+XXXX */
        fun text(s: String): String {
            val sbd = StringBuilder()
            for (c in s) when {
                c == '\n' || c == '\r' || c == '\t' -> sbd.append(' ')
                c.code < 0x20 -> {}
                c.code < 0x80 || enc.canEncode(c) -> sbd.append(c)
                else -> sbd.append(String.format(Locale.US, "\\U+%04X", c.code))
            }
            return sbd.toString()
        }
        override fun toString() = sb.toString()
    }
}
