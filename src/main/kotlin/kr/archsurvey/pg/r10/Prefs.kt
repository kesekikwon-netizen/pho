package kr.archsurvey.pg.r10

import android.content.Context

/** 0.0.14 R10 설정(이 앱만): 레버암(폰 몸체, m), 폴 높이(m), 평균 횟수 */
object R10Prefs {
    private fun p(c: Context) = c.getSharedPreferences("pg_r10", Context.MODE_PRIVATE)
    fun lever(c: Context) = p(c).let { Lever(it.getFloat("up", 0f).toDouble(), it.getFloat("fwd", 0f).toDouble(), it.getFloat("right", 0f).toDouble()) }
    fun setLever(c: Context, l: Lever) = p(c).edit().putFloat("up", l.up.toFloat()).putFloat("fwd", l.fwd.toFloat()).putFloat("right", l.right.toFloat()).apply()
    fun pole(c: Context): Double = p(c).getFloat("pole", kr.archsurvey.core.DevicePoleDefaults.R10_M.toFloat()).toDouble()
    fun setPole(c: Context, v: Double) = p(c).edit().putFloat("pole", v.toFloat()).apply()
    /** R10 위상중심 상수(m): 레버암 끝점(퀵릴리스 바닥) → APC. 기본은 안테나 DB, 사용자가 바꿀 수 있음(레버암과 따로) */
    fun apc(c: Context): Double = p(c).getFloat("apc", Float.NaN).toDouble().takeIf { !it.isNaN() } ?: apcDefault(c)
    fun setApc(c: Context, v: Double) = p(c).edit().putFloat("apc", v.toFloat()).apply()
    fun apcDefault(c: Context): Double = runCatching {
        val db = kr.archsurvey.data.AntennaDb.parse(c.assets.open("antenna_db.json").bufferedReader().use { it.readText() })
        val m = db["TRIMBLE_R10"]!!
        kr.archsurvey.core.AntennaRecord.of(m, kr.archsurvey.core.AntennaConfig(0.0, m.defaultRefPoint))!!.apcHeightM
    }.getOrDefault(0.0)
    /** 0.0.17 내보내기 높이 기준(MSL/ELLIPSOID) */
    fun heightRef(c: Context): String = kr.archsurvey.pg.data.HeightRef.norm(p(c).getString("heightRef", null))
    fun setHeightRef(c: Context, v: String) = p(c).edit().putString("heightRef", kr.archsurvey.pg.data.HeightRef.norm(v)).apply()
    fun epochs(c: Context): Int = p(c).getInt("epochs", 10).coerceIn(1, 100)
}

object Sensor {
    /** 뒷카메라 SENSOR_ORIENTATION(보통 90) */
    fun backDeg(c: Context): Int {
        val m = c.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        for (id in m.cameraIdList) {
            val ch = m.getCameraCharacteristics(id)
            if (ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK)
                return ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        }
        return 90
    }
}
