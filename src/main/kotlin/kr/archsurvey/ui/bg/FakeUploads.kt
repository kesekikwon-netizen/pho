package kr.archsurvey.ui.bg

import kr.archsurvey.georef.BgKind
import kr.archsurvey.georef.KoreanCrs
import kr.archsurvey.georef.LonLat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * 파일 선택 화면이 아직 없어서(다음 라운드) 쓰는 **예시 파일** 4종. 실제 현장 파일이 아니다.
 * 파일은 실행 중에 작은 크기로 만들어 실제 정합 로직(`UploadAligner`)에 그대로 넣는다 — 판정 결과만 가짜가 아니라 진짜 계산이다.
 */
enum class FakeSample(val fileName: String, val kind: BgKind, val summary: String) {
    GOOD_TIF("현장_도면A.tif", BgKind.GEOTIFF, "GeoTIFF · 좌표 있음 (작업 위치 근처)"),
    NO_COORD_TIF("area_A_ortho.tif", BgKind.GEOTIFF, "GeoTIFF · 좌표 정보 없음"),
    WRONG_PLACE_TIF("구도면_B.tif", BgKind.GEOTIFF, "GeoTIFF · 좌표는 있지만 위치가 100 km 어긋남"),
    MAP_DXF("수치지형도_도엽.dxf", BgKind.DXF, "DXF · 좌표계 정보 없음"),
}

object FakeUploads {
    const val RASTER_W = 200
    const val RASTER_H = 150
    const val PIXEL_M = 0.5

    /** 예시 파일을 [dir] 에 만든다. [work] 는 현재 작업 위치(위경도). 중부원점(EPSG:5186) 좌표로 그 근처에 배치한다. */
    fun create(s: FakeSample, dir: File, work: LonLat): File {
        dir.mkdirs()
        val f = File(dir, s.fileName)
        val c = KoreanCrs.fromLonLat(KoreanCrs.EPSG_5186, work)
        val left = c.x - RASTER_W * PIXEL_M / 2; val top = c.y + RASTER_H * PIXEL_M / 2
        when (s) {
            FakeSample.GOOD_TIF -> f.writeBytes(TinyGeoTiff.write(RASTER_W, RASTER_H, PIXEL_M, left, top, 5186))
            FakeSample.NO_COORD_TIF -> f.writeBytes(TinyGeoTiff.write(RASTER_W, RASTER_H, PIXEL_M, 0.0, 0.0, null))
            FakeSample.WRONG_PLACE_TIF -> f.writeBytes(TinyGeoTiff.write(RASTER_W, RASTER_H, PIXEL_M, left, top - 100_000.0, 5186))
            FakeSample.MAP_DXF -> f.writeText(dxf(left, top - RASTER_H * PIXEL_M, left + RASTER_W * PIXEL_M, top))
        }
        return f
    }

    private fun dxf(x0: Double, y0: Double, x1: Double, y1: Double): String {
        val sb = StringBuilder("0\nSECTION\n2\nHEADER\n9\n\$INSUNITS\n70\n6\n0\nENDSEC\n0\nSECTION\n2\nENTITIES\n")
        fun line(a: Double, b: Double, c: Double, d: Double) {
            sb.append(String.format(Locale.US, "0\nLINE\n8\n0\n10\n%.3f\n20\n%.3f\n30\n0.0\n11\n%.3f\n21\n%.3f\n31\n0.0\n", a, b, c, d))
        }
        line(x0, y0, x1, y0); line(x1, y0, x1, y1); line(x1, y1, x0, y1); line(x0, y1, x0, y0)
        for (i in 1 until 10) { // 안쪽 격자
            val x = x0 + (x1 - x0) * i / 10; val y = y0 + (y1 - y0) * i / 10
            line(x, y0, x, y1); line(x0, y, x1, y)
        }
        sb.append("0\nENDSEC\n0\nEOF\n")
        return sb.toString()
    }
}

/**
 * 시험·예시용 아주 작은 GeoTIFF 쓰기(8비트 흑백, 압축 없음, 태그 ModelPixelScale/ModelTiepoint/GeoKeyDirectory).
 * [epsg] 가 null 이면 지오 태그를 넣지 않는다(좌표 없는 TIFF).
 */
object TinyGeoTiff {
    fun write(w: Int, h: Int, pixelM: Double, leftX: Double, topY: Double, epsg: Int?): ByteArray {
        data class E(val tag: Int, val type: Int, val count: Int, val value: ByteArray?, val inline: Int = 0)
        val geo = epsg != null
        val nEntries = 9 + if (geo) 3 else 0
        val ifdSize = 2 + nEntries * 12 + 4
        var ext = 8 + ifdSize // 바깥 데이터 시작 위치
        fun le(vararg d: Double) = ByteBuffer.allocate(d.size * 8).order(ByteOrder.LITTLE_ENDIAN).also { b -> d.forEach { b.putDouble(it) } }.array()
        fun les(vararg s: Int) = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> s.forEach { b.putShort(it.toShort()) } }.array()
        val scaleB = le(pixelM, pixelM, 0.0)
        val tieB = le(0.0, 0.0, 0.0, leftX, topY, 0.0)
        val keysB = les(1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, epsg ?: 0)
        val scaleOff = ext; ext += scaleB.size
        val tieOff = ext; ext += tieB.size
        val keysOff = ext; ext += keysB.size
        val pixOff = if (geo) ext else 8 + ifdSize
        val pixels = ByteArray(w * h) { i -> val x = i % w; val y = i / w; (if (x % 20 == 0 || y % 20 == 0) 60 else 215 + (x + y) % 20).toByte() }
        val entries = mutableListOf(
            E(256, 4, 1, null, w), E(257, 4, 1, null, h), E(258, 3, 1, null, 8), E(259, 3, 1, null, 1), E(262, 3, 1, null, 1),
            E(273, 4, 1, null, pixOff), E(277, 3, 1, null, 1), E(278, 4, 1, null, h), E(279, 4, 1, null, w * h),
        )
        if (geo) {
            entries += E(33550, 12, 3, scaleB, scaleOff); entries += E(33922, 12, 6, tieB, tieOff); entries += E(34735, 3, keysB.size / 2, keysB, keysOff)
        }
        entries.sortBy { it.tag }
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(8 + ifdSize).order(ByteOrder.LITTLE_ENDIAN)
        head.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        head.putShort(entries.size.toShort())
        for (e in entries) { head.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count); head.putInt(e.inline) }
        head.putInt(0)
        out.write(head.array())
        if (geo) { out.write(scaleB); out.write(tieB); out.write(keysB) }
        out.write(pixels)
        return out.toByteArray()
    }
}
