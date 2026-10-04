package kr.archsurvey.georef

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GeoTIFF 태그 리더. **화소는 읽지 않는다.** 첫 IFD(주 영상)의 헤더·태그만 읽어 대용량 파일에서도 안전하다.
 * 지원: 클래식 TIFF(42)·BigTIFF(43), 리틀/빅 엔디언. 압축 방식과 무관(화소를 안 읽으므로).
 * 해석: ModelPixelScale(33550)+ModelTiepoint(33922) 또는 ModelTransformation(34264), GeoKeyDirectory(34735).
 * 변환은 항상 **화소 모서리 기준**(GDAL 규약)으로 돌려준다. PixelIsPoint 이면 GDAL 기본 동작과 같게 반 화소 되돌린다.
 */
object GeoTiffReader {
    private const val TAG_WIDTH = 256
    private const val TAG_HEIGHT = 257
    private const val TAG_PIXEL_SCALE = 33550
    private const val TAG_TIEPOINT = 33922
    private const val TAG_MODEL_TRANSFORM = 34264
    private const val TAG_GEOKEYS = 34735
    private const val TAG_GEODOUBLES = 34736

    private const val KEY_MODEL_TYPE = 1024
    private const val KEY_RASTER_TYPE = 1025
    private const val KEY_GEOGRAPHIC_TYPE = 2048
    private const val KEY_PROJECTED_CS = 3072
    private const val KEY_PROJ_LINEAR_UNITS = 3076
    private const val USER_DEFINED = 32767

    sealed class Result {
        data class Ok(val info: GeoTiffInfo) : Result()
        data class Fail(val reason: FailReason, val message: String) : Result()
    }
    enum class FailReason { NOT_TIFF, TRUNCATED, UNSUPPORTED }

    fun read(file: File): Result = try {
        RandomAccessFile(file, "r").use { read(it) }
    } catch (e: IOException) {
        Result.Fail(FailReason.TRUNCATED, "파일을 읽을 수 없어요: ${e.message}")
    }

    fun read(raf: RandomAccessFile): Result {
        val len = raf.length()
        if (len < 8) return Result.Fail(FailReason.NOT_TIFF, "TIFF 파일이 아니에요")
        val head = ByteArray(16); raf.seek(0); raf.read(head, 0, minOf(16L, len).toInt())
        val order = when {
            head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return Result.Fail(FailReason.NOT_TIFF, "TIFF 파일이 아니에요")
        }
        val hb = ByteBuffer.wrap(head).order(order)
        val magic = hb.getShort(2).toInt() and 0xffff
        val big = magic == 43
        if (magic != 42 && !big) return Result.Fail(FailReason.NOT_TIFF, "TIFF 파일이 아니에요")
        val ifdOffset = if (big) {
            if (len < 16 || hb.getShort(4).toInt() != 8) return Result.Fail(FailReason.UNSUPPORTED, "BigTIFF 형식을 해석하지 못했어요")
            hb.getLong(8)
        } else hb.getInt(4).toLong() and 0xffffffffL
        if (ifdOffset <= 0 || ifdOffset >= len) return Result.Fail(FailReason.TRUNCATED, "TIFF 목록 위치가 잘못됐어요")

        // IFD 읽기
        raf.seek(ifdOffset)
        val cntBytes = if (big) 8 else 2
        val cb = ByteArray(cntBytes); raf.readFully(cb)
        val nEntries = (if (big) ByteBuffer.wrap(cb).order(order).getLong() else (ByteBuffer.wrap(cb).order(order).getShort().toLong() and 0xffff))
        if (nEntries < 1 || nEntries > 4096) return Result.Fail(FailReason.UNSUPPORTED, "TIFF 태그 수가 비정상이에요($nEntries)")
        val entSize = if (big) 20 else 12
        val eb = ByteArray((nEntries * entSize).toInt()); raf.readFully(eb)
        val buf = ByteBuffer.wrap(eb).order(order)

        data class Entry(val tag: Int, val type: Int, val count: Long, val valuePos: Int)
        val entries = HashMap<Int, Entry>()
        for (i in 0 until nEntries.toInt()) {
            val p = i * entSize
            val tag = buf.getShort(p).toInt() and 0xffff
            val type = buf.getShort(p + 2).toInt() and 0xffff
            val count = if (big) buf.getLong(p + 4) else buf.getInt(p + 4).toLong() and 0xffffffffL
            entries[tag] = Entry(tag, type, count, p + (if (big) 12 else 8))
        }
        fun typeSize(t: Int) = when (t) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12, 16, 17, 18 -> 8; else -> 0 }

        /** 값 바이트열 (인라인이면 엔트리 안, 아니면 오프셋) */
        fun valueBytes(en: Entry): ByteBuffer? {
            val sz = typeSize(en.type); if (sz == 0) return null
            val total = en.count * sz
            if (total > 16L * 1024 * 1024) return null
            val inline = if (big) 8 else 4
            return if (total <= inline) ByteBuffer.wrap(eb, en.valuePos, total.toInt()).slice().order(order)
            else {
                val off = if (big) buf.getLong(en.valuePos) else buf.getInt(en.valuePos).toLong() and 0xffffffffL
                if (off < 0 || off + total > len) return null
                val b = ByteArray(total.toInt()); raf.seek(off); raf.readFully(b); ByteBuffer.wrap(b).order(order)
            }
        }
        fun ints(tag: Int): IntArray? {
            val en = entries[tag] ?: return null; val b = valueBytes(en) ?: return null
            return when (en.type) {
                3 -> IntArray(en.count.toInt()) { b.getShort(it * 2).toInt() and 0xffff }
                4 -> IntArray(en.count.toInt()) { b.getInt(it * 4) }
                16 -> IntArray(en.count.toInt()) { b.getLong(it * 8).toInt() }
                1 -> IntArray(en.count.toInt()) { b.get(it).toInt() and 0xff }
                else -> null
            }
        }
        fun doubles(tag: Int): DoubleArray? {
            val en = entries[tag] ?: return null; if (en.type != 12) return null
            val b = valueBytes(en) ?: return null
            return DoubleArray(en.count.toInt()) { b.getDouble(it * 8) }
        }

        val width = ints(TAG_WIDTH)?.firstOrNull() ?: return Result.Fail(FailReason.UNSUPPORTED, "영상 크기 태그가 없어요")
        val height = ints(TAG_HEIGHT)?.firstOrNull() ?: return Result.Fail(FailReason.UNSUPPORTED, "영상 크기 태그가 없어요")
        val notes = mutableListOf<String>()

        // GeoKeys
        val keys = HashMap<Int, Int>()
        ints(TAG_GEOKEYS)?.let { d ->
            if (d.size >= 4) {
                val nk = d[3]
                for (i in 0 until nk) {
                    val o = 4 + i * 4; if (o + 3 >= d.size) break
                    val keyId = d[o]; val loc = d[o + 1]; val value = d[o + 3]
                    if (loc == 0) keys[keyId] = value // 값이 직접 들어 있는 키만 사용(문자·실수 참조 키는 무시)
                }
            }
        }
        val modelType = keys[KEY_MODEL_TYPE]
        val pixelIsPoint = keys[KEY_RASTER_TYPE] == 2
        val projCs = keys[KEY_PROJECTED_CS]
        val geoCs = keys[KEY_GEOGRAPHIC_TYPE]
        val userDefined = (modelType == 1 && projCs == USER_DEFINED) || (modelType == 2 && geoCs == USER_DEFINED) ||
            (modelType == 1 && projCs == null) || (modelType == 2 && geoCs == null)
        val epsg: Int? = when (modelType) {
            1 -> projCs?.takeIf { it in 1..32766 }
            2 -> geoCs?.takeIf { it in 1..32766 }
            else -> null
        }
        val linearUnits = keys[KEY_PROJ_LINEAR_UNITS]
        if (linearUnits != null && linearUnits != 9001 && modelType == 1) notes += "선형 단위 코드 $linearUnits (미터 아님 가능)"

        // 변환
        var affine: Affine? = null
        val mt = doubles(TAG_MODEL_TRANSFORM)
        val scale = doubles(TAG_PIXEL_SCALE)
        val tie = doubles(TAG_TIEPOINT)
        val tieCount = (tie?.size ?: 0) / 6
        var gcps = false
        if (mt != null && mt.size >= 16) {
            affine = Affine(mt[0], mt[1], mt[3], mt[4], mt[5], mt[7])
        } else if (scale != null && scale.size >= 2 && tie != null && tieCount >= 1) {
            if (tieCount > 1) { gcps = true; notes += "타이포인트가 ${tieCount}개(기준점 방식) — 아핀 변환으로 단순화하지 않음" }
            else {
                val i = tie[0]; val j = tie[1]; val x = tie[3]; val y = tie[4]
                val sx = scale[0]; val sy = scale[1]
                affine = Affine(sx, 0.0, x - sx * i, 0.0, -sy, y + sy * j)
            }
        } else if (tieCount > 1) {
            gcps = true; notes += "타이포인트 ${tieCount}개만 있음(기준점 방식)"
        }
        if (affine != null && pixelIsPoint) {
            // GDAL 기본(GTIFF_POINT_GEO_IGNORE=NO): 화소 중심 기준 -> 모서리 기준으로 반 화소 이동
            affine = Affine(affine.a, affine.b, affine.c - 0.5 * affine.a - 0.5 * affine.b,
                affine.d, affine.e, affine.f - 0.5 * affine.d - 0.5 * affine.e)
            notes += "PixelIsPoint: 반 화소 이동 적용(GDAL 기본과 동일)"
        }
        return Result.Ok(GeoTiffInfo(width, height, big, order == ByteOrder.LITTLE_ENDIAN, modelType, pixelIsPoint,
            epsg, userDefined && epsg == null && keys.isNotEmpty(), affine, gcps, keys, notes))
    }
}

data class GeoTiffInfo(
    val width: Int, val height: Int, val bigTiff: Boolean, val littleEndian: Boolean,
    /** 1=투영, 2=지리, 3=지심, null=GeoKey 없음 */
    val modelType: Int?, val pixelIsPoint: Boolean,
    /** 코드가 EPSG 로 확정되면 번호, 아니면 null */
    val epsg: Int?,
    /** 사용자 정의 좌표계(32767) 또는 코드 누락 */
    val userDefinedCrs: Boolean,
    /** 화소(열,행) 모서리 -> 파일 좌표계. 없으면 null */
    val affine: Affine?, val hasMultipleTiepoints: Boolean,
    val geoKeys: Map<Int, Int>, val notes: List<String>,
) {
    val hasGeoreference: Boolean get() = affine != null
    val isRotated: Boolean get() = affine?.let { Math.abs(it.b) > 1e-12 * (Math.abs(it.a) + 1e-300) || Math.abs(it.d) > 1e-12 * (Math.abs(it.e) + 1e-300) } ?: false
}
