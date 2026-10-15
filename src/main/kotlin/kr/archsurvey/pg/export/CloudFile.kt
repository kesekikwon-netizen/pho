package kr.archsurvey.pg.export

import kr.archsurvey.pg.geo.Helmert
import kr.archsurvey.pg.geo.V3
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.floor

/**
 * 0.0.12 점군: 촬영 화면을 닫을 때 신뢰도 0.66 이상 2 cm 칸(x,y,z,r,g,b 로컬)을 작업 폴더 cloud/part_<시각>.bin 에 붙여 저장.
 * 내보내기: 조각을 모아 2 cm 칸으로 다시 합치고(먼저 온 값 유지), 좌표 맞춤이 있으면 EPSG 로 옮겨 ASCII PLY(double) 로.
 */
object CloudFile {
    const val DIR = "cloud"
    const val EXPORT = "pointcloud.ply"
    const val VOXEL = 0.02

    fun savePart(jobDir: File, xyzrgb: FloatArray, now: Long = System.currentTimeMillis()): File? {
        val n = xyzrgb.size / 6
        if (n == 0) return null
        val d = File(jobDir, DIR).apply { mkdirs() }
        val f = File(d, "part_$now.bin"); val tmp = File(f.path + ".part")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { o -> o.writeInt(n); for (v in xyzrgb.copyOf(n * 6)) o.writeFloat(v) }
        return if (tmp.renameTo(f)) f else null
    }

    fun loadAll(jobDir: File): FloatArray {
        val parts = File(jobDir, DIR).listFiles { f -> f.name.endsWith(".bin") }.orEmpty().sortedBy { it.name }
        val seen = HashSet<Long>(); val out = ArrayList<Float>()
        for (p in parts) runCatching {
            DataInputStream(p.inputStream().buffered(1 shl 16)).use { i ->
                val n = i.readInt()
                repeat(n) {
                    val v = FloatArray(6) { i.readFloat() }
                    val k = key(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                    if (seen.add(k)) v.forEach { out += it }
                }
            }
        }
        return out.toFloatArray()
    }

    fun key(x: Double, y: Double, z: Double): Long {
        fun k(v: Double) = floor(v / VOXEL).toLong() and 0x1FFFFF
        return (k(x) shl 42) or (k(y) shl 21) or k(z)
    }

    /** ASCII PLY. tf 있으면 EPSG(x=동, y=북, z=높이) */
    fun writePly(out: File, pts: FloatArray, tf: Helmert?): Int {
        val n = pts.size / 6
        out.bufferedWriter(Charsets.US_ASCII, 1 shl 16).use { w ->
            w.write("ply\nformat ascii 1.0\ncomment kr.archsurvey.pg ${if (tf != null) "EPSG (x=E y=N z=H)" else "local ARCore metres (Y up)"}\n")
            w.write("element vertex $n\nproperty double x\nproperty double y\nproperty double z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n")
            for (i in 0 until n) {
                val b = 6 * i
                val p = V3(pts[b].toDouble(), pts[b + 1].toDouble(), pts[b + 2].toDouble()).let { tf?.apply(it) ?: it }
                fun c(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()
                w.write(String.format(Locale.US, "%.4f %.4f %.4f %d %d %d\n", p.x, p.y, p.z, c(pts[b + 3]), c(pts[b + 4]), c(pts[b + 5])))
            }
        }
        return n
    }
}
