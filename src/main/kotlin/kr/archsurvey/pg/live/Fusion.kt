package kr.archsurvey.pg.live

import kr.archsurvey.pg.geo.M3
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.geo.triangulateRays
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 실시간 3D (ARCore 세계 좌표: 미터, Y = 위(중력), 오른손).
 * 발굴면은 대부분 2.5D(땅·벽 아래쪽)라서 P0 은 5 cm 높이칸(sparse, 칸 해시)에 깊이 점을 누적 평균해 면을 만든다.
 * (TSDF 전체 부피 대신 — 폰 CPU 에서 키프레임마다 수 ms. 세운 벽·처마 같은 겹친 면은 P2 의 TSDF 로)
 */
class HeightField(val cell: Double = 0.05, var maxCells: Int = 250_000) {
    class C { var sumY = 0.0; var n = 0; val mean get() = sumY / n }
    val cells = HashMap<Long, C>()
    /** 칸 수 상한에 걸려 버린 점 수(메모리 보호: 5 cm 칸 25만 개 ≈ 625 m²) */
    var dropped = 0L; private set
    @Volatile var version = 0; private set

    fun key(ix: Int, iz: Int): Long = (ix.toLong() shl 32) xor (iz.toLong() and 0xffffffffL)
    fun ix(x: Double) = floor(x / cell).toInt()

    fun add(x: Double, y: Double, z: Double) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val k = key(ix(x), ix(z))
        val c = cells[k] ?: run { if (cells.size >= maxCells) { dropped++; return }; C().also { cells[k] = it } }
        if (c.n >= 6 && abs(y - c.mean) > 0.15) return // 튀는 깊이값 버림
        if (c.n >= 60) { c.sumY -= c.mean; c.n-- }     // 오래된 값이 서서히 빠지게
        c.sumY += y; c.n++
    }
    fun bump() { version++ }
    fun height(ix: Int, iz: Int): Double? = cells[key(ix, iz)]?.takeIf { it.n >= 2 }?.mean
}

enum class Suff { GREEN, YELLOW, RED }

/** 10×10 cm 면 조각마다 몇 장의 사진에 어떤 각도로 찍혔나 */
class Coverage(val hf: HeightField, val cell: Double = 0.10) {
    class Cov { val dirs = ArrayList<V3>(); var gsdOk = 0; var lastStill = -1 }
    val cov = HashMap<Long, Cov>()
    /** 사진 i 가 본 칸들(겹침 계산) */
    private val seenByStill = ArrayList<Set<Long>>()
    private val ratio = (cell / hf.cell).toInt().coerceAtLeast(1)

    data class Cam(val pos: V3, /** 카메라 → 세계 회전 (OpenGL 카메라 축: x 오른쪽, y 위, −z 앞, 센서 방향 그대로) */ val r: M3,
                   val fx: Double, val fy: Double, val cx: Double, val cy: Double, val w: Int, val h: Int)

    /** 화면 좌표(센서 화소, y 아래). 앞에 없으면 null */
    fun project(cam: Cam, p: V3): DoubleArray? {
        val q = cam.r.transpose() * (p - cam.pos)
        if (q.z > -0.05) return null
        val u = cam.fx * q.x / -q.z + cam.cx
        val v = cam.cy - cam.fy * q.y / -q.z
        return doubleArrayOf(u, v, -q.z)
    }

    /** 칸 중심(면 높이 포함) */
    fun center(key: Long): V3? {
        val ix = (key shr 32).toInt(); val iz = key.toInt()
        var s = 0.0; var n = 0
        for (a in 0 until ratio) for (b in 0 until ratio) hf.height(ix * ratio + a, iz * ratio + b)?.let { s += it; n++ }
        if (n == 0) return null
        return V3((ix + 0.5) * cell, s / n, (iz + 0.5) * cell)
    }
    fun covKey(ix: Int, iz: Int) = (ix.toLong() shl 32) xor (iz.toLong() and 0xffffffffL)

    /** 지금 면이 있는 칸 목록 */
    fun surfaceKeys(): Set<Long> {
        val out = HashSet<Long>()
        for ((k, c) in hf.cells) if (c.n >= 2) {
            val ix = (k shr 32).toInt(); val iz = k.toInt()
            out += covKey(Math.floorDiv(ix, ratio), Math.floorDiv(iz, ratio))
        }
        return out
    }

    /** 사진 한 장을 찍었을 때 */
    fun addStill(stillIndex: Int, cam: Cam, maxDist: Double = 5.0, gsdMax: Double = 0.002) {
        val seen = HashSet<Long>()
        for (k in surfaceKeys()) {
            val p = center(k) ?: continue
            val uv = project(cam, p) ?: continue
            val mx = cam.w * 0.03; val my = cam.h * 0.03
            if (uv[0] < mx || uv[0] > cam.w - mx || uv[1] < my || uv[1] > cam.h - my) continue
            val d = uv[2]
            if (d > maxDist) continue
            val toCam = (cam.pos - p).unit()
            if (acosDeg(toCam.y) > 60.0) continue // 면(위쪽) 기준 60° 넘게 비스듬하면 제외
            val c = cov.getOrPut(k) { Cov() }
            if (c.lastStill == stillIndex) continue
            c.lastStill = stillIndex
            // 같은 칸 방향은 12개까지(각도 폭 계산 O(n²) 막기) — 넘치면 가장 비슷한 방향을 바꿔 넣지 않고 버림
            if (c.dirs.size < 12) c.dirs += toCam
            if (d / cam.fx <= gsdMax) c.gsdOk++
            seen += k
        }
        while (seenByStill.size <= stillIndex) seenByStill.add(emptySet())
        seenByStill[stillIndex] = seen
    }

    fun suff(k: Long): Suff {
        val c = cov[k] ?: return Suff.RED
        val n = c.dirs.size
        if (n >= 3 && spreadDeg(c.dirs) >= 15.0 && c.gsdOk >= 3) return Suff.GREEN
        if (n >= 2) return Suff.YELLOW
        return Suff.RED
    }

    /** 연속 사진 겹침(뒤 사진이 본 칸 중 앞 사진도 본 비율) */
    fun overlaps(): List<Double> = (1 until seenByStill.size).mapNotNull { i ->
        val a = seenByStill[i - 1]; val b = seenByStill[i]
        if (b.isEmpty()) null else b.count { it in a }.toDouble() / b.size
    }

    companion object {
        fun acosDeg(c: Double) = Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
        fun spreadDeg(d: List<V3>): Double {
            var best = 0.0
            for (i in d.indices) for (j in i + 1 until d.size) best = max(best, acosDeg(d[i].dot(d[j])))
            return best
        }
    }
}

/** 사진마다 본 표적 → 3D 위치(광선 교차), 꼭짓점(축척용), 각도 폭 */
class TagMap {
    class Obs(val still: Int, val camPos: V3, val dir: V3, val cornerDirs: List<V3>)
    val obs = HashMap<Int, ArrayList<Obs>>() // 키 = 표적 번호 N (Target #N)

    fun add(target: Int, o: Obs) { obs.getOrPut(target) { ArrayList() }.let { l -> if (l.none { it.still == o.still }) l += o } }
    fun removeStill(still: Int) { obs.values.forEach { l -> l.removeAll { it.still == still } }; obs.entries.removeAll { it.value.isEmpty() } }

    fun count(target: Int) = obs[target]?.size ?: 0
    fun spreadDeg(target: Int): Double = Coverage.spreadDeg(obs[target].orEmpty().map { it.dir * -1.0 })
    fun position(target: Int): V3? {
        val l = obs[target] ?: return null
        if (l.size < 2 || spreadDeg(target) < 2.0) return null
        return triangulateRays(l.map { it.camPos }, l.map { it.dir })
    }
    fun positions(): Map<Int, V3> = obs.keys.mapNotNull { k -> position(k)?.let { k to it } }.toMap()

    /** 검은 네모 한 변 길이(로컬 미터) — 꼭짓점 4개를 각각 교차 */
    fun edgeLength(target: Int): Double? {
        val l = obs[target] ?: return null
        if (l.size < 2 || spreadDeg(target) < 5.0) return null
        val cs = (0 until 4).map { ci -> triangulateRays(l.map { it.camPos }, l.map { it.cornerDirs[ci] }) ?: return null }
        return (0 until 4).sumOf { (cs[it] - cs[(it + 1) % 4]).norm() } / 4
    }
}

/** 축척(좌표 없음 모드). 결과 s: 실제 = s × ARCore. scatter = 표적끼리 흩어짐(%) */
data class ScaleEst(val s: Double, val scatterPct: Double?, val source: String)

object ScaleSolver {
    fun fromTagSize(tags: TagMap, sizeM: Double): ScaleEst? {
        val e = tags.obs.keys.mapNotNull { tags.edgeLength(it) }.filter { it > 0.01 }
        if (e.isEmpty()) return null
        val ss = e.map { sizeM / it }
        val m = ss.average()
        val sd = if (ss.size >= 2) sqrt(ss.sumOf { (it - m) * (it - m) } / (ss.size - 1)) else null
        return ScaleEst(m, sd?.let { it / m * 100 }, "표적 ${(sizeM * 100).toInt()} cm")
    }
    /** 막대 양 끝 표적 = 서로 가장 가까운 두 표적 */
    fun fromBar(tags: TagMap, barM: Double): Pair<ScaleEst, Pair<Int, Int>>? {
        val p = tags.positions().entries.toList()
        var best: Triple<Int, Int, Double>? = null
        for (i in p.indices) for (j in i + 1 until p.size) {
            val d = (p[i].value - p[j].value).norm()
            if (d > 0.05 && (best == null || d < best.third)) best = Triple(p[i].key, p[j].key, d)
        }
        val b = best ?: return null
        return ScaleEst(barM / b.third, null, "막대 ${barM} m (#${b.first}–#${b.second})") to (b.first to b.second)
    }
}

/** 화면에 보일 진행 상태 */
data class Progress(
    val pct: Int, val done: Boolean, val photos: Int, val overlapPct: Int, val tagsOk: Int, val tagsTotal: Int, val holesPct: Int,
    val greenPct: Int, val hint: String?, /** 다음 촬영 목표(세계 좌표) */ val target: V3?,
)

object Gates {
    const val GREEN = 0.90; const val HOLES = 0.05; const val OVERLAP_MED = 0.70; const val TAG_N = 3; const val TAG_SPREAD = 20.0

    fun progress(c: Coverage, tags: TagMap, photos: Int, needTags: Int): Progress {
        val surf = c.surfaceKeys()
        if (surf.isEmpty()) return Progress(0, false, photos, 0, 0, tags.obs.size, 100, 0, if (photos == 0) "천천히 둘러보며 찍기 시작" else "바닥을 비춰 주세요", null)
        // 작업 범위: 표적 3개 이상이면 표적을 둘러싼 네모 + 30 cm, 아니면 면이 있는 모든 칸의 네모
        val tp = tags.positions().values
        val keys = surf.map { ((it shr 32).toInt()) to it.toInt() }
        val (x0, x1, z0, z1) = if (tp.size >= 3) {
            val pad = 0.3
            listOf(floor((tp.minOf { it.x } - pad) / c.cell).toInt(), floor((tp.maxOf { it.x } + pad) / c.cell).toInt(),
                floor((tp.minOf { it.z } - pad) / c.cell).toInt(), floor((tp.maxOf { it.z } + pad) / c.cell).toInt())
        } else listOf(keys.minOf { it.first }, keys.maxOf { it.first }, keys.minOf { it.second }, keys.maxOf { it.second })
        var total = 0; var have = 0; var green = 0
        val bad = HashMap<Long, Int>() // 칸 → 부족 무게(빨강 2, 노랑 1, 구멍 2)
        for (ix in x0..x1) for (iz in z0..z1) {
            total++
            val k = c.covKey(ix, iz)
            if (k !in surf) { bad[k] = 2; continue }
            have++
            when (c.suff(k)) { Suff.GREEN -> green++; Suff.YELLOW -> bad[k] = 1; Suff.RED -> bad[k] = 2 }
        }
        val greenF = green.toDouble() / total
        val holes = 1.0 - have.toDouble() / total
        val ov = c.overlaps().sorted()
        val ovMed = if (ov.isEmpty()) 0.0 else ov[ov.size / 2]
        val tagIds = tags.obs.keys
        val tagsOk = tagIds.count { tags.count(it) >= TAG_N && tags.spreadDeg(it) >= TAG_SPREAD }
        val g1 = min(1.0, greenF / GREEN)
        val g2 = if (holes <= HOLES) 1.0 else max(0.0, (1 - holes) / (1 - HOLES))
        val g3 = if (photos < 2) 0.0 else min(1.0, ovMed / OVERLAP_MED)
        val g4 = if (tagIds.isEmpty()) (if (needTags > 0) 0.0 else 1.0) else
            tagIds.sumOf { min(1.0, tags.count(it).toDouble() / TAG_N) * (if (tags.spreadDeg(it) >= TAG_SPREAD) 1.0 else 0.85) } / tagIds.size
        val g5 = if (needTags > 0) min(1.0, tp.size.toDouble() / needTags) else 1.0
        val gates = listOf(g1, g2, g3, g4, g5)
        val done = greenF >= GREEN && holes <= HOLES && ovMed >= OVERLAP_MED && tagsOk == tagIds.size && (needTags == 0 || tp.size >= needTags)
        val pct = if (done) 100 else min(99, (gates.min() * 100).toInt())
        // 다음 촬영: 부족한 칸의 가장 큰 덩어리(4이웃) 무게중심
        var target: V3? = null; var need = 0
        if (!done && bad.isNotEmpty()) {
            val left = HashSet(bad.keys); var bestW = 0; var bestC: List<Long> = emptyList()
            while (left.isNotEmpty()) {
                val s = left.first(); left.remove(s)
                val comp = ArrayList<Long>(); val q = ArrayDeque(listOf(s))
                while (q.isNotEmpty()) {
                    val k = q.removeFirst(); comp += k
                    val ix = (k shr 32).toInt(); val iz = k.toInt()
                    for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) { val n = c.covKey(ix + dx, iz + dz); if (left.remove(n)) q += n }
                }
                val w = comp.sumOf { bad.getValue(it) }
                if (w > bestW) { bestW = w; bestC = comp }
            }
            val ys = bestC.mapNotNull { c.center(it)?.y }
            val y = if (ys.isEmpty()) surf.mapNotNull { c.center(it)?.y }.average() else ys.average()
            target = V3(bestC.sumOf { ((it shr 32).toInt() + 0.5) * c.cell } / bestC.size, y, bestC.sumOf { (it.toInt() + 0.5) * c.cell } / bestC.size)
            need = bestC.map { 3 - (c.cov[it]?.dirs?.size ?: 0) }.sorted().let { it[it.size / 2] }.coerceIn(1, 3)
        }
        val hint = when {
            done -> null
            tagIds.any { tags.count(it) < TAG_N } -> "표적 #${tagIds.first { tags.count(it) < TAG_N }} 을 ${TAG_N - tags.count(tagIds.first { tags.count(it) < TAG_N })}장 더"
            target != null -> "${need}장 더"
            else -> null
        }
        return Progress(pct, done, photos, (ovMed * 100).toInt(), tagsOk, tagIds.size, (holes * 100).toInt(), (greenF * 100).toInt(), hint, target)
    }

    /** 화면 기준 방향 글(센서 화면이 아니라 폰 화면 기준 dx 오른쪽+, dy 아래+) */
    fun arrowText(dx: Double, dy: Double, lower: Boolean): String {
        val a = Math.toDegrees(kotlin.math.atan2(-dy, dx)) // 0 = 오른쪽, 90 = 위
        val (g, t) = when {
            a >= -22.5 && a < 22.5 -> "→" to "오른쪽"
            a >= 22.5 && a < 67.5 -> "↗" to "오른쪽 위"
            a >= 67.5 && a < 112.5 -> "↑" to "위쪽"
            a >= 112.5 && a < 157.5 -> "↖" to "왼쪽 위"
            a >= -67.5 && a < -22.5 -> "↘" to "오른쪽 아래"
            a >= -112.5 && a < -67.5 -> "↓" to "아래쪽"
            a >= -157.5 && a < -112.5 -> "↙" to "왼쪽 아래"
            else -> "←" to "왼쪽"
        }
        return "$g ${t}를${if (lower) " 낮게" else ""}"
    }
}
