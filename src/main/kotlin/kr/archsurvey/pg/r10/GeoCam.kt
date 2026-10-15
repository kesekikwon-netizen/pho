package kr.archsurvey.pg.r10

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.geo.V3
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** R10 원시 한 줄(도착 시각 = elapsedRealtime ms) */
data class Epoch(val tMs: Long, val lat: Double, val lon: Double, val altMsl: Double?, val quality: String, val fixed: Boolean,
                 val sigmaH: Double?, val sigmaU: Double?, val sats: Int = 0, val raw: String = "")
/** ARCore 카메라 자세(프레임 시각 ms, tx,ty,tz,qx,qy,qz,qw) */
data class ArSample(val tMs: Long, val pose: DoubleArray)

/** 0.0.14 원시 기록 파일(작업 폴더). 맞춤이 없어도 늘 남김 */
object RawLogs {
    const val R10 = "r10_raw.csv"; const val AR = "ar_track.csv"
    const val R10_HEADER = "t_ms,lat,lon,alt_msl,quality,fix,sigma_h,sigma_u,sats,nmea"
    const val AR_HEADER = "t_ms,tx,ty,tz,qx,qy,qz,qw"
    fun r10Line(e: Epoch) = String.format(Locale.US, "%d,%.9f,%.9f,%s,%s,%d,%s,%s,%d,\"%s\"", e.tMs, e.lat, e.lon, e.altMsl?.toString() ?: "", e.quality, if (e.fixed) 1 else 0,
        e.sigmaH?.toString() ?: "", e.sigmaU?.toString() ?: "", e.sats, e.raw.replace("\"", "").trim())
    fun arLine(s: ArSample) = s.tMs.toString() + "," + s.pose.joinToString(",") { String.format(Locale.US, "%.5f", it) }
    fun readR10(f: File): List<Epoch> = if (!f.isFile) emptyList() else f.readLines().drop(1).mapNotNull { l ->
        runCatching { val c = l.split(','); Epoch(c[0].toLong(), c[1].toDouble(), c[2].toDouble(), c[3].toDoubleOrNull(), c[4], c[5] == "1", c[6].toDoubleOrNull(), c[7].toDoubleOrNull(), c[8].toInt(), "") }.getOrNull() }
    fun readAr(f: File): List<ArSample> = if (!f.isFile) emptyList() else f.readLines().drop(1).mapNotNull { l ->
        runCatching { val c = l.split(','); ArSample(c[0].toLong(), DoubleArray(7) { c[it + 1].toDouble() }) }.getOrNull() }
}

/**
 * 0.0.14 저장 때 한 번: ARCore 궤적 ↔ R10(EPSG) 강체 맞춤(수직축 회전 + 3D 이동, 축척 1). 설계 19 §1–3.
 * - 표본: FIX 이고 σH ≤ 2 cm 인 R10 시각에 AR 궤적을 보간, AR 안테나 = AR 카메라 + R·L(L = 렌즈→안테나, 폰 몸체, 위상중심 포함)
 * - 20개 이상, 경로 3 m 이상(직선만이면 안 됨), 3σ 넘는 잔차 버리고 다시 맞춤
 * - 지연: −1 ~ +1 s, 10 ms 간격으로 RMSE 가 가장 작은 값
 * - 등급: RMSE ≤ 2 cm → XYZ, ≤ 5 cm → 수평만(높이는 R10), 넘으면 사진에 좌표 안 넣음(원시 기록만). 촬영은 막지 않음
 * - 레버암은 맞춤(방위)이 있을 때만 사진 위치에 적용: 카메라 = T(AR 카메라). 맞춤 없으면 사진엔 원시 R10 값만 기록
 */
object GeoCam {
    const val MAX_SIGMA = 0.02; const val MIN_N = 20; const val MIN_PATH = 3.0
    const val GOOD = 0.02; const val APPROX = 0.05
    enum class Tier { XYZ, XY, NONE }
    data class Cam(val e: Double, val n: Double, val h: Double?, val lat: Double, val lon: Double, val headingKnown: Boolean,
                   val tier: Tier = Tier.XYZ, val rmse: Double? = null, val lagMs: Int = 0, val yawDeg: Double? = null, val speed: Double? = null)
    data class Fit(val tier: Tier, val n: Int, val used: Int, val rmse: Double?, val maxRes: Double?, val lagMs: Int, val yaw: Double?, val t: V3?, val pathM: Double, val reason: String)
    data class Result(val epsg: Int, val headingRad: Double?, val cams: Map<Int, Cam>, val fit: Fit? = null)

    fun epsgFor(s: Session, epochs: List<Epoch> = emptyList()): Int {
        if (s.gcps.isNotEmpty()) return s.epsg
        val lon = s.stills.firstNotNullOfOrNull { it.gnss?.takeIf { g -> g.fixed }?.lon } ?: epochs.firstOrNull { it.fixed }?.lon ?: return s.epsg
        return GeoTag.zoneForLon(lon).epsg
    }

    /** 렌즈→안테나(폰 몸체, m) — 입력 레버암 + R10 위상중심 상수(위쪽) */
    fun lensToAntenna(s: Session) = Lever(s.lever.up + s.apcM, s.lever.fwd, s.lever.right)

    private fun en(lat: Double, lon: Double, epsg: Int) = GeoTag.en(GnssTag(lat, lon, null, "", true, null, null, 0, 0), epsg)

    fun interp(track: List<ArSample>, t: Long, maxGapMs: Long = 300): DoubleArray? {
        if (track.isEmpty()) return null
        var lo = 0; var hi = track.size - 1
        if (t < track[lo].tMs || t > track[hi].tMs) return null
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (track[m].tMs <= t) lo = m else hi = m }
        val a = track[lo]; val b = track[hi]
        if (t == a.tMs) return a.pose.copyOf(); if (t == b.tMs) return b.pose.copyOf()
        if (b.tMs - a.tMs > maxGapMs) return null
        val u = if (b.tMs == a.tMs) 0.0 else (t - a.tMs).toDouble() / (b.tMs - a.tMs)
        val p = DoubleArray(7) { a.pose[it] + (b.pose[it] - a.pose[it]) * u }
        // 쿼터니언은 가까운 쪽(짧은 보간은 정규화로 충분)
        val q = if (u < 0.5) a.pose else b.pose
        for (i in 3..6) p[i] = q[i]
        return p
    }

    /** AR 점(x 동', y 위, z 남') → ENU: yaw 회전 + 이동 */
    fun apply(yaw: Double, t: V3, w: V3): V3 { val r = LeverArm.toEnu(w, yaw); return V3(r.x + t.x, r.y + t.y, r.z + t.z) }

    private fun rigid(a: List<V3>, b: List<V3>): Pair<Double, V3> {
        val yaw = LeverArm.heading(a, b, 0.0) ?: 0.0
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in a.indices) { val r = LeverArm.toEnu(a[i], yaw); sx += b[i].x - r.x; sy += b[i].y - r.y; sz += b[i].z - r.z }
        return yaw to V3(sx / a.size, sy / a.size, sz / a.size)
    }

    fun fit(epochs: List<Epoch>, track: List<ArSample>, s: Session, epsg: Int, sameTime: Boolean = false): Fit {
        val good = epochs.filter { it.fixed && (it.sigmaH ?: 1.0) <= MAX_SIGMA && it.altMsl != null }.sortedBy { it.tMs }
        val L = lensToAntenna(s)
        val enu = good.map { val (e, n) = en(it.lat, it.lon, epsg); V3(e, n, it.altMsl!!) }
        var path = 0.0; for (i in 1 until enu.size) path += sqrt((enu[i].x - enu[i - 1].x).let { it * it } + (enu[i].y - enu[i - 1].y).let { it * it })
        if (good.size < MIN_N) return Fit(Tier.NONE, good.size, 0, null, null, 0, null, null, path, "FIX·σ≤2cm 표본 ${good.size}개(20개 필요)")
        if (path < MIN_PATH) return Fit(Tier.NONE, good.size, 0, null, null, 0, null, null, path, String.format(Locale.US, "움직인 거리 %.1f m(3 m 필요)", path))
        // 직선만이면 방위가 약함: 수평 분포의 짧은 축 표준편차 ≥ 0.2 m
        run {
            val cx = enu.sumOf { it.x } / enu.size; val cy = enu.sumOf { it.y } / enu.size
            var xx = 0.0; var yy = 0.0; var xy = 0.0
            for (p in enu) { val dx = p.x - cx; val dy = p.y - cy; xx += dx * dx; yy += dy * dy; xy += dx * dy }
            xx /= enu.size; yy /= enu.size; xy /= enu.size
            val minEig = (xx + yy) / 2 - sqrt(((xx - yy) / 2).let { it * it } + xy * xy)
            if (sqrt(maxOf(0.0, minEig)) < 0.2) return Fit(Tier.NONE, good.size, 0, null, null, 0, null, null, path, "한 줄로만 걸음(방향을 바꿔 걸어야 해요)")
        }
        var best: Fit? = null
        for (lag in if (sameTime) 0..0 else -1000..1000 step 10) {
            val a = ArrayList<V3>(); val b = ArrayList<V3>()
            for (i in good.indices) {
                val p = interp(track, good[i].tMs + lag, if (sameTime) 0 else 300) ?: continue
                a += V3(p[0], p[1], p[2]) + LeverArm.worldOffset(p, s.sensorDeg, L); b += enu[i]
            }
            if (a.size < MIN_N) continue
            var idx = a.indices.toList()
            var yaw = 0.0; var t = V3(0.0, 0.0, 0.0); var rmse = 0.0; var res = DoubleArray(0)
            repeat(4) {
                val (y2, t2) = rigid(idx.map { a[it] }, idx.map { b[it] }); yaw = y2; t = t2
                res = DoubleArray(a.size) { (apply(yaw, t, a[it]) - b[it]).norm() }
                rmse = sqrt(idx.sumOf { res[it] * res[it] } / idx.size)
                val keep = a.indices.filter { res[it] <= 3 * rmse + 1e-6 }
                if (keep.size == idx.size || keep.size < MIN_N) return@repeat
                idx = keep
            }
            if (idx.size < MIN_N) continue
            if (best == null || rmse < best.rmse!!) best = Fit(Tier.NONE, good.size, idx.size, rmse, idx.maxOf { res[it] }, lag, yaw, t, path, "")
        }
        val b = best ?: return Fit(Tier.NONE, good.size, 0, null, null, 0, null, null, path, "AR 궤적과 시각이 맞지 않음")
        val tier = when { b.rmse!! <= GOOD -> Tier.XYZ; b.rmse <= APPROX -> Tier.XY; else -> Tier.NONE }
        return b.copy(tier = tier, reason = when (tier) { Tier.XYZ -> "좋음(XYZ)"; Tier.XY -> "대략(수평만, 높이는 R10)"; Tier.NONE -> "RMSE 가 5 cm 넘음 · 사진에 좌표 안 넣음" })
    }

    /** epochs/track 이 없으면 사진마다 붙은 R10 값과 사진 자세로 대신(같은 시각, 지연 0) */
    fun compute(s: Session, epochs0: List<Epoch> = emptyList(), track0: List<ArSample> = emptyList()): Result {
        val fromStills = epochs0.isEmpty()
        val epochs = if (!fromStills) epochs0 else s.stills.mapNotNull { st -> st.gnss?.let { g -> Epoch(st.time, g.lat, g.lon, g.altMsl, g.quality, g.fixed, g.sigmaH, g.sigmaU, g.sats) } }
        val track = (if (track0.isNotEmpty()) track0 else s.stills.mapNotNull { st -> st.pose?.let { ArSample(st.time, it) } }).sortedBy { it.tMs }
        val epsg = epsgFor(s, epochs)
        if (epochs.none { it.fixed }) return Result(epsg, null, emptyMap(), if (epochs.isEmpty()) null else fit(epochs, track, s, epsg, fromStills))
        val f = fit(epochs, track, s, epsg, fromStills)
        if (f.tier == Tier.NONE) return Result(epsg, null, emptyMap(), f)
        val L = lensToAntenna(s); val out = HashMap<Int, Cam>()
        for (st in s.stills) {
            val pose = st.pose ?: continue
            val c = apply(f.yaw!!, f.t!!, V3(pose[0], pose[1], pose[2]))
            var h: Double? = c.z
            if (f.tier == Tier.XY) { // 높이는 R10(사진 순간 FIX) − 레버암 수직
                val g = st.gnss?.takeIf { it.fixed && it.altMsl != null }
                h = g?.let { it.altMsl!! - LeverArm.worldOffset(pose, s.sensorDeg, L).y }
            }
            val ll = toLatLon(c.x, c.y, epsg, epochs.first { it.fixed })
            out[st.id] = Cam(c.x, c.y, h, ll.first, ll.second, true, f.tier, f.rmse, f.lagMs, Math.toDegrees(f.yaw), st.speed)
        }
        return Result(epsg, f.yaw, out, f)
    }

    /** E,N → 위경도(가까운 R10 점에서 작은 이동, 두 번 반복) */
    fun toLatLon(e: Double, n: Double, epsg: Int, ref: Epoch): Pair<Double, Double> {
        var la = ref.lat; var lo = ref.lon
        repeat(3) { val (e0, n0) = en(la, lo, epsg); val r = GeoTag.shiftLatLon(la, lo, e - e0, n - n0); la = r.first; lo = r.second }
        return la to lo
    }

    fun report(f: Fit?): String = if (f == null) "" else String.format(Locale.US, "맞춤 %s · 표본 %d(사용 %d) · RMSE %s · 최대 %s · 지연 %d ms · 경로 %.1f m · %s",
        f.tier, f.n, f.used, f.rmse?.let { "%.1f cm".format(Locale.US, it * 100) } ?: "-", f.maxRes?.let { "%.1f cm".format(Locale.US, it * 100) } ?: "-", f.lagMs, f.pathM, f.reason)
}
