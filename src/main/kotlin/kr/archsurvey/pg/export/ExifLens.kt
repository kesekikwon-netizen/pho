package kr.archsurvey.pg.export

import kotlin.math.roundToInt

/**
 * 0.0.12 사진 EXIF 렌즈·카메라 값. 사진 화소 초점(fx)과 센서 실제 크기(mm)로 계산.
 * FocalPlaneResolutionUnit = 3(cm). FocalLength(mm) × FocalPlaneXResolution(px/cm)/10 = fx 가 되게 맞춤 → iTwin 이 같은 화소 초점을 얻음.
 */
object ExifLens {
    data class Values(
        val focalMm: Double?, val focal35: Int, val planeXRes: Double?, val planeYRes: Double?,
        val subSec: String, val uniqueId: String, val dateTime: String,
    )

    /** sensorWmm/Hmm = 사진 가로/세로 방향 센서 크기(없으면 null) */
    fun compute(fx: Double, fy: Double, w: Int, h: Int, sensorWmm: Double?, sensorHmm: Double?, timeMs: Long, id: String): Values {
        val f = (fx + fy) / 2
        val diag = Math.hypot(w.toDouble(), h.toDouble())
        val f35 = (f / diag * Math.hypot(36.0, 24.0)).roundToInt()
        val xRes = sensorWmm?.takeIf { it > 0 }?.let { w / (it / 10.0) }
        val yRes = sensorHmm?.takeIf { it > 0 }?.let { h / (it / 10.0) }
        val mm = xRes?.let { fx / (it / 10.0) }
        val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
        return Values(mm, f35, xRes, yRes, String.format(java.util.Locale.US, "%03d", timeMs % 1000), id, sdf.format(java.util.Date(timeMs)))
    }

    /** ImageUniqueID: 32 자 16진 */
    fun uniqueId(job: String, file: String, timeMs: Long): String {
        val md = java.security.MessageDigest.getInstance("MD5").digest("$job/$file/$timeMs".toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    /** EXIF 유리수 문자열 */
    fun rational(v: Double, den: Int = 1000) = "${(v * den).roundToInt()}/$den"
}
