package kr.archsurvey.georef

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

/** 0.1.58 B2: 줄여 읽은 화소. [argb] = width*height, 0xAARRGGBB. [srcWidth]/[srcHeight] = 원본 크기 */
class RasterImage(val width: Int, val height: Int, val argb: IntArray, val srcWidth: Int, val srcHeight: Int)

class TiffUnsupportedException(msg: String) : Exception(msg)

/**
 * 0.1.58 B2: GeoTIFF 화소 읽기(자체 구현). 지도 배경용으로 긴 변을 [maxSide] 이하로 줄여(가까운 화소) 돌려준다.
 * 지원: 클래식 TIFF, 스트립(타일 아님), 압축 없음(1)·LZW(5)·Deflate(8, 32946), 예측자 1·2, 8비트,
 * 흑백(0·1)·RGB(2)·팔레트(3), 채널 1–4(알파), 평면 구성 1, GDAL 무자료값(42113) → 투명.
 * 줄 단위로 흘려 읽어 원본 전체를 메모리에 올리지 않는다(8000×5000·125 MB 도 안전).
 * 지원하지 않는 형식은 [TiffUnsupportedException] — 화면은 윤곽+안내로 남긴다.
 */
object TiffRaster {
    const val DEFAULT_MAX_SIDE = 2048

    private class Ifd(val tags: Map<Int, LongArray>, val ascii: Map<Int, String>)

    fun decode(file: File, maxSide: Int = DEFAULT_MAX_SIDE): RasterImage {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(8); raf.readFully(head)
            val order = when { head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> throw TiffUnsupportedException("TIFF 파일이 아니에요") }
            val hb = ByteBuffer.wrap(head).order(order)
            val magic = hb.getShort(2).toInt() and 0xFFFF
            if (magic == 43) throw TiffUnsupportedException("BigTIFF 는 아직 그림을 그리지 못해요")
            if (magic != 42) throw TiffUnsupportedException("TIFF 파일이 아니에요")
            val ifd = readIfd(raf, order, hb.getInt(4).toLong() and 0xFFFFFFFFL)
            return render(raf, ifd, maxSide)
        }
    }

    private fun readIfd(raf: RandomAccessFile, order: ByteOrder, off: Long): Ifd {
        raf.seek(off)
        val nb = ByteArray(2); raf.readFully(nb)
        val n = ByteBuffer.wrap(nb).order(order).short.toInt() and 0xFFFF
        val ent = ByteArray(n * 12); raf.readFully(ent)
        val b = ByteBuffer.wrap(ent).order(order)
        val tags = HashMap<Int, LongArray>(); val ascii = HashMap<Int, String>()
        for (i in 0 until n) {
            val p = i * 12
            val tag = b.getShort(p).toInt() and 0xFFFF
            val type = b.getShort(p + 2).toInt() and 0xFFFF
            val count = (b.getInt(p + 4).toLong() and 0xFFFFFFFFL).toInt()
            val size = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; else -> 1 }
            val total = size.toLong() * count
            val data: ByteBuffer = if (total <= 4) ByteBuffer.wrap(ent, p + 8, 4).slice().order(order) else {
                if (total > 64L * 1024 * 1024) continue
                val pos = raf.filePointer
                raf.seek(b.getInt(p + 8).toLong() and 0xFFFFFFFFL)
                val arr = ByteArray(total.toInt()); raf.readFully(arr); raf.seek(pos)
                ByteBuffer.wrap(arr).order(order)
            }
            when (type) {
                2 -> { val arr = ByteArray(count); data.get(arr, 0, minOf(count, data.remaining())); ascii[tag] = String(arr, Charsets.ISO_8859_1).trimEnd('\u0000') }
                1, 7 -> tags[tag] = LongArray(count) { (data.get(it).toLong() and 0xFF) }
                3 -> tags[tag] = LongArray(count) { (data.getShort(it * 2).toLong() and 0xFFFF) }
                4 -> tags[tag] = LongArray(count) { (data.getInt(it * 4).toLong() and 0xFFFFFFFFL) }
                else -> {}
            }
        }
        return Ifd(tags, ascii)
    }

    private fun Ifd.one(tag: Int, def: Long? = null): Long = tags[tag]?.firstOrNull() ?: def ?: throw TiffUnsupportedException("필수 태그 $tag 없음")

    private fun render(raf: RandomAccessFile, ifd: Ifd, maxSide: Int): RasterImage {
        val w = ifd.one(256).toInt(); val h = ifd.one(257).toInt()
        if (w <= 0 || h <= 0) throw TiffUnsupportedException("그림 크기가 잘못됐어요")
        if (ifd.tags.containsKey(322) || ifd.tags.containsKey(324)) throw TiffUnsupportedException("타일 TIFF 는 아직 그림을 그리지 못해요")
        val comp = ifd.one(259, 1).toInt()
        if (comp !in setOf(1, 5, 8, 32946)) throw TiffUnsupportedException("압축 방식 $comp 는 아직 그리지 못해요 (LZW·Deflate·무압축만)")
        val spp = ifd.one(277, 1).toInt()
        val bps = ifd.tags[258] ?: longArrayOf(1)
        if (bps.any { it != 8L }) throw TiffUnsupportedException("8비트 그림만 그려요 (지금 ${bps.joinToString("/")}비트)")
        if (ifd.one(284, 1).toInt() != 1) throw TiffUnsupportedException("채널이 나뉜 TIFF 는 아직 그리지 못해요")
        val photo = ifd.one(262, if (spp >= 3) 2 else 1).toInt()
        if (photo !in setOf(0, 1, 2, 3)) throw TiffUnsupportedException("색 방식 $photo 는 아직 그리지 못해요")
        if (spp !in 1..4) throw TiffUnsupportedException("채널 수 $spp 는 아직 그리지 못해요")
        val predictor = ifd.one(317, 1).toInt()
        if (predictor !in setOf(1, 2)) throw TiffUnsupportedException("예측자 $predictor 는 아직 그리지 못해요")
        val offs = ifd.tags[273] ?: throw TiffUnsupportedException("스트립 정보가 없어요")
        val counts = ifd.tags[279] ?: throw TiffUnsupportedException("스트립 정보가 없어요")
        val rps = ifd.one(278, h.toLong()).coerceAtMost(h.toLong()).toInt().coerceAtLeast(1)
        val cmap = ifd.tags[320]
        if (photo == 3 && (cmap == null || cmap.size < 3 * 256)) throw TiffUnsupportedException("팔레트가 없어요")
        val extra = ifd.tags[338]?.firstOrNull()
        val hasAlpha = (spp == 4 && photo == 2) || (spp == 2 && photo <= 1) || (extra != null && (extra == 1L || extra == 2L))
        val nodata = ifd.ascii[42113]?.trim()?.toDoubleOrNull()?.takeIf { it in 0.0..255.0 && it == Math.floor(it) }?.toInt()

        val scale = maxOf(1.0, maxOf(w, h).toDouble() / maxSide)
        val ow = maxOf(1, Math.round(w / scale).toInt()); val oh = maxOf(1, Math.round(h / scale).toInt())
        val out = IntArray(ow * oh)
        // 출력 열 → 원본 열
        val colMap = IntArray(ow) { ((it + 0.5) * w / ow).toInt().coerceIn(0, w - 1) }
        // 원본 행 → 그 행을 쓰는 출력 행들(가까운 화소)
        val rowTargets = HashMap<Int, MutableList<Int>>()
        for (oy in 0 until oh) rowTargets.getOrPut(((oy + 0.5) * h / oh).toInt().coerceIn(0, h - 1)) { ArrayList(1) } += oy

        val rowBytes = w * spp
        val row = ByteArray(rowBytes)
        var y = 0
        for (s in offs.indices) {
            if (y >= h) break
            val rowsHere = minOf(rps, h - y)
            val raw = openStrip(raf, offs[s], counts.getOrElse(s) { 0L })
            val inp: InputStream = when (comp) {
                5 -> LzwInputStream(raw)
                8, 32946 -> InflaterInputStream(raw)
                else -> raw
            }
            for (r in 0 until rowsHere) {
                readFully(inp, row)
                if (predictor == 2) for (i in spp until rowBytes) row[i] = (row[i] + row[i - spp]).toByte()
                val targets = rowTargets[y]
                if (targets != null) {
                    val base0 = targets[0] * ow
                    for (ox in 0 until ow) {
                        val p = colMap[ox] * spp
                        out[base0 + ox] = pixel(row, p, spp, photo, cmap, hasAlpha, nodata)
                    }
                    for (k in 1 until targets.size) System.arraycopy(out, base0, out, targets[k] * ow, ow)
                }
                y++
            }
        }
        return RasterImage(ow, oh, out, w, h)
    }

    private fun pixel(row: ByteArray, p: Int, spp: Int, photo: Int, cmap: LongArray?, hasAlpha: Boolean, nodata: Int?): Int {
        fun u(i: Int) = row[p + i].toInt() and 0xFF
        if (nodata != null) {
            val n = if (photo == 2) minOf(3, spp) else 1
            var all = true
            for (i in 0 until n) if (u(i) != nodata) { all = false; break }
            if (all) return 0
        }
        return when (photo) {
            2 -> {
                val r = u(0); val g = if (spp > 1) u(1) else r; val b = if (spp > 2) u(2) else r
                val a = if (hasAlpha && spp >= 4) u(3) else 255
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            3 -> {
                val i = u(0)
                val r = (cmap!![i] shr 8).toInt(); val g = (cmap[256 + i] shr 8).toInt(); val b = (cmap[512 + i] shr 8).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> {
                var v = u(0); if (photo == 0) v = 255 - v
                val a = if (hasAlpha && spp >= 2) u(1) else 255
                (a shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var o = 0
        while (o < buf.size) {
            val n = inp.read(buf, o, buf.size - o)
            if (n < 0) { java.util.Arrays.fill(buf, o, buf.size, 0); return } // 잘린 스트립: 남은 곳은 0(투명 아님 · 검정)
            o += n
        }
    }

    private fun openStrip(raf: RandomAccessFile, off: Long, count: Long): InputStream = object : InputStream() {
        var pos = off; val end = off + count
        override fun read(): Int { if (pos >= end) return -1; raf.seek(pos); val v = raf.read(); if (v >= 0) pos++; return v }
        override fun read(b: ByteArray, o: Int, l: Int): Int {
            if (pos >= end) return -1
            raf.seek(pos); val n = raf.read(b, o, minOf(l.toLong(), end - pos).toInt())
            if (n > 0) pos += n
            return n
        }
    }.buffered(64 * 1024)
}

/**
 * TIFF 식 LZW 풀기(코드 9–12비트, 큰 자리 먼저, 「한 칸 일찍」 폭 늘리기, Clear 256 · EOI 257). 흘려 읽기.
 */
class LzwInputStream(private val src: InputStream) : InputStream() {
    private val prefix = IntArray(4096); private val suffix = ByteArray(4096); private val length = IntArray(4096)
    private var next = 258; private var width = 9
    private var bitBuf = 0L; private var bitCount = 0
    private var prev = -1
    private val stack = ByteArray(4096); private var sp = 0; private var spEnd = 0
    private var eof = false

    init { for (i in 0 until 256) { suffix[i] = i.toByte(); length[i] = 1; prefix[i] = -1 } }

    private fun code(): Int {
        while (bitCount < width) {
            val b = src.read(); if (b < 0) return 257
            bitBuf = (bitBuf shl 8) or b.toLong(); bitCount += 8
        }
        val c = ((bitBuf shr (bitCount - width)) and ((1L shl width) - 1)).toInt()
        bitCount -= width
        return c
    }

    private fun emit(c: Int) {
        var k = c; val len = length[c]; var i = len - 1
        while (i >= 0) { stack[i] = suffix[k]; k = prefix[k]; i-- }
        sp = 0; spEnd = len
    }

    private fun firstByte(c: Int): Byte { var k = c; while (prefix[k] >= 0) k = prefix[k]; return suffix[k] }

    private fun fill(): Boolean {
        if (eof) return false
        var c = code()
        if (c == 256) { next = 258; width = 9; prev = -1; c = code(); if (c == 257) { eof = true; return false }; emit(c); prev = c; return true }
        if (c == 257) { eof = true; return false }
        if (prev < 0) { if (c >= 256) { eof = true; return false }; emit(c); prev = c; return true }
        if (c < next) {
            emit(c)
            add(prev, stack[0])
        } else if (c == next) {
            add(prev, firstByte(prev))
            emit(c)
        } else { eof = true; return false } // 깨진 자료
        prev = c
        return true
    }

    private fun add(p: Int, b: Byte) {
        if (next >= 4096) return
        prefix[next] = p; suffix[next] = b; length[next] = length[p] + 1; next++
        width = when { next >= 2047 -> 12; next >= 1023 -> 11; next >= 511 -> 10; else -> 9 }
    }

    override fun read(): Int {
        if (sp >= spEnd && !fill()) return -1
        return stack[sp++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            if (sp >= spEnd && !fill()) break
            val k = minOf(len - n, spEnd - sp)
            System.arraycopy(stack, sp, b, off + n, k); sp += k; n += k
        }
        return if (n == 0) -1 else n
    }

    override fun close() = src.close()
}
