package kr.archsurvey.pg.live

import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.geo.Helmert
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.geo.quatToM3
import kr.archsurvey.pg.tag.TargetNaming
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * 촬영 중 계산(작업 스레드 하나): 깊이 점 → 높이칸 면, 사진 → 충분 칸·표적, 0.5초마다 색칠한 면(그릴 버퍼) 다시 만들기.
 * GL 스레드는 [mesh] 를 읽기만 한다(통째로 바꿔 끼움).
 */
class LiveEngine(initial: Session) {
    val hf = HeightField(0.05)
    val cov = Coverage(hf, 0.10)
    val tags = TagMap()
    @Volatile var session: Session = initial; private set
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "pg-live").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile var mesh: Mesh? = null; private set
    @Volatile var progress: Progress = Progress(0, false, 0, 0, 0, 0, 100, 0, null, null); private set
    @Volatile var fit: Helmert.Fit? = null; private set
    @Volatile var scale: ScaleEst? = null; private set
    /** 표적 로컬 위치(GL 스레드가 읽는 사본) */
    @Volatile var tagPos: Map<Int, V3> = emptyMap(); private set
    @Volatile var tagCounts: Map<Int, Int> = emptyMap(); private set
    var onChange: (() -> Unit)? = null
    private var lastMeshAt = 0L
    private var dirty = false

    class Mesh(val tri: FloatBuffer, val triCount: Int, val pts: FloatBuffer, val ptCount: Int)

    init { exec.execute { initial.stills.forEach { addStillModel(it) }; recompute(true) } }

    fun post(r: () -> Unit) { if (!exec.isShutdown) exec.execute { runCatching(r) } }
    fun close() { exec.shutdownNow() }

    /** 깊이/특징점 묶음(세계 좌표 x,y,z 반복) */
    fun addPoints(xyz: FloatArray, n: Int) = post {
        for (i in 0 until n) hf.add(xyz[3 * i].toDouble(), xyz[3 * i + 1].toDouble(), xyz[3 * i + 2].toDouble())
        hf.bump(); dirty = true
        val now = System.currentTimeMillis()
        if (now - lastMeshAt > meshIntervalMs) recompute(false)
    }

    fun cam(s: Still): Coverage.Cam? {
        val p = s.pose ?: return null
        return Coverage.Cam(V3(p[0], p[1], p[2]), quatToM3(p[6], p[3], p[4], p[5]), s.fx, s.fy, s.cx, s.cy, s.w, s.h)
    }

    /** 화소 → 세계 방향(센서 화상, y 아래) */
    fun ray(c: Coverage.Cam, u: Double, v: Double): V3 = c.r * V3((u - c.cx) / c.fx, -(v - c.cy) / c.fy, -1.0)

    private fun addStillModel(s: Still) {
        val c = cam(s) ?: return
        cov.addStill(s.id, c)
        for (t in s.tags) {
            val corners = (0 until 4).map { k -> ray(c, t.corners[2 * k], t.corners[2 * k + 1]) }
            tags.add(TargetNaming.targetNo(t.id), TagMap.Obs(s.id, c.pos, ray(c, t.cx, t.cy), corners))
        }
    }

    fun addStill(s: Still, save: (Session) -> Unit) = post {
        session = session.copy(stills = session.stills + s)
        addStillModel(s)
        save(session)
        recompute(true)
    }

    /** 마지막 사진 되돌리기(파일은 지우지 않고 목록에서만 빼서 내보내기에서 제외) */
    fun undo(save: (Session) -> Unit) = post {
        val last = session.stills.lastOrNull() ?: return@post
        session = session.copy(stills = session.stills.dropLast(1))
        // 칸 기록은 다시 계산
        cov.cov.clear(); tags.obs.clear()
        session.stills.forEach { addStillModel(it) }
        save(session); recompute(true)
    }

    fun setGcps(g: List<Gcp>, save: (Session) -> Unit) = post { session = session.copy(gcps = g); save(session); recompute(true) }
    fun setScale(tagSizeM: Double?, barM: Double?, save: (Session) -> Unit) = post { session = session.copy(tagSizeM = tagSizeM, barM = barM); save(session); recompute(true) }

    private fun recompute(force: Boolean) {
        if (!force && !dirty) return
        dirty = false; lastMeshAt = System.currentTimeMillis()
        val s = session
        val need = if (s.gcps.size >= 3) 3 else 0
        progress = Gates.progress(cov, tags, s.stills.size, need)
        val local = tags.positions()
        tagPos = local
        tagCounts = tags.obs.mapValues { it.value.size }
        fit = if (s.gcps.size >= 3) Helmert.fit(local, s.gcps.associate { it.target to V3(it.e, it.n, it.h) }) else null
        scale = s.barM?.let { ScaleSolver.fromBar(tags, it)?.first } ?: s.tagSizeM?.let { ScaleSolver.fromTagSize(tags, it) }
        val t0 = System.nanoTime()
        mesh = buildMesh()
        val ms = (System.nanoTime() - t0) / 1e6
        val m = mesh
        kr.archsurvey.pg.diag.PgLog.d("mesh update %.1f ms cells=${hf.cells.size} tris=${m?.triCount} pts=${m?.ptCount} | coverage pct=${progress.pct} green=${progress.greenPct} overlap=${progress.overlapPct} holes=${progress.holesPct} tags=${progress.tagsOk}/${progress.tagsTotal} photos=${progress.photos} done=${progress.done}".format(ms))
        onChange?.invoke()
    }

    private fun color(k: Long): FloatArray = when (cov.suff(k)) {
        Suff.GREEN -> floatArrayOf(0.12f, 0.48f, 0.23f, 0.62f)
        Suff.YELLOW -> floatArrayOf(0.66f, 0.39f, 0f, 0.62f)
        Suff.RED -> floatArrayOf(0.71f, 0.14f, 0.09f, 0.55f)
    }

    /** 칸마다 색은 한 번만 계산(칸 4개가 같은 색 칸을 나눔), 박싱 없는 FloatArray 로 쌓는다 */
    private class FA(cap: Int) { var a = FloatArray(maxOf(64, cap)); var n = 0
        fun add(x: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = x } }
    private fun buildMesh(): Mesh {
        val sz = hf.cell
        val tri = FA(hf.cells.size * 30); val pts = FA(hf.cells.size * 7)
        val colCache = HashMap<Long, FloatArray>()
        var nt = 0; var np = 0
        for ((k, cell) in hf.cells) {
            if (cell.n < 2) continue
            val ix = (k shr 32).toInt(); val iz = k.toInt()
            val h00 = cell.mean
            val ck = cov.covKey(Math.floorDiv(ix, 2), Math.floorDiv(iz, 2))
            val col = colCache.getOrPut(ck) { color(ck) }
            pts.add(((ix + 0.5) * sz).toFloat()); pts.add(h00.toFloat()); pts.add(((iz + 0.5) * sz).toFloat()); pts.add(col[0]); pts.add(col[1]); pts.add(col[2]); pts.add(0.95f); np++
            val h10 = hf.height(ix + 1, iz) ?: continue
            val h01 = hf.height(ix, iz + 1) ?: continue
            val h11 = hf.height(ix + 1, iz + 1) ?: continue
            val mx = maxOf(maxOf(h00, h10), maxOf(h01, h11)); val mn = minOf(minOf(h00, h10), minOf(h01, h11))
            if (mx - mn > 0.12) continue // 큰 단(벽 끝)은 잇지 않음
            fun v(a: Int, b: Int, h: Double) { tri.add(((ix + a + 0.5) * sz).toFloat()); tri.add(h.toFloat()); tri.add(((iz + b + 0.5) * sz).toFloat()); tri.add(col[0]); tri.add(col[1]); tri.add(col[2]); tri.add(col[3]) }
            v(0, 0, h00); v(0, 1, h01); v(1, 0, h10)
            v(1, 0, h10); v(0, 1, h01); v(1, 1, h11)
            nt += 2
        }
        return Mesh(fb(tri.a, tri.n), nt, fb(pts.a, pts.n), np)
    }
    private fun fb(a: FloatArray, n: Int): FloatBuffer {
        val b = ByteBuffer.allocateDirect(maxOf(4, n * 4)).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(a, 0, n); b.position(0); return b
    }
    /** 시험·진단용: 면 다시 만들기 시간(ms) */
    fun benchMesh(): Double { val t = System.nanoTime(); mesh = buildMesh(); return (System.nanoTime() - t) / 1e6 }
    /** 메모리 부족: 오래된(점 적은) 칸부터 줄여 절반으로 */
    fun trim() = post {
        val before = hf.cells.size
        if (before > 20_000) { val keep = hf.cells.entries.sortedByDescending { it.value.n }.take(before / 2).map { it.key }.toHashSet(); hf.cells.keys.retainAll(keep) }
        hf.maxCells = maxOf(20_000, hf.cells.size + 50_000)
        kr.archsurvey.pg.diag.PgLog.w("memory trim cells $before -> ${hf.cells.size}")
        recompute(true)
    }
    /** 발열 단계에 맞춰 면 갱신 간격 */
    @Volatile var meshIntervalMs = 500L

    /** 좌표 없음 모드: 표적 로컬 위치(Z 위, 미터, 축척 적용) — 참고용 TXT */
    fun localTagsTxt(): String {
        val s = scale?.s ?: 1.0
        return tags.positions().toSortedMap().entries.joinToString("") { (no, p) ->
            String.format(java.util.Locale.US, "%s,%.3f,%.3f,%.3f\r\n", TargetNaming.name(TargetNaming.tagId(no)), p.x * s, -p.z * s, p.y * s)
        }
    }

}
