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
    @Volatile var progress: Progress = Progress(0, false, 0, 0, 0, 0, 100, 0, null, null); private set
    @Volatile var fit: Helmert.Fit? = null; private set
    @Volatile var scale: ScaleEst? = null; private set
    /** 표적 로컬 위치(GL 스레드가 읽는 사본) */
    @Volatile var tagPos: Map<Int, V3> = emptyMap(); private set
    @Volatile var tagCounts: Map<Int, Int> = emptyMap(); private set
    var onChange: (() -> Unit)? = null
    private var lastMeshAt = 0L
    private var dirty = false

    init { exec.execute { initial.stills.forEach { addStillModel(it) }; recompute(true) } }

    fun post(r: () -> Unit) { if (!exec.isShutdown) exec.execute { runCatching(r).onFailure { kr.archsurvey.pg.diag.PgLog.e("live task failed", it) } } }
    fun close() { exec.shutdownNow() }

    /** 실시간 3D 보기: 색 점(기본) · 충분 색 점. (0.0.5: 높이격자 면은 없앰) */
    enum class View3D(val label: String) { POINTS("색 점"), POINTS_COV("충분 색 점") }
    @Volatile var view3d = View3D.POINTS
    /** 즉시 보이는 점: ARCore 특징점(ID 로 중복 제거) */
    val features = FeatureCloud()
    /** 면을 채우는 점: 깊이 2 cm 복셀 */
    val voxels = DepthVoxels()
    @Volatile var depthBatches = 0L; private set
    @Volatile var featureBatches = 0L; private set
    @Volatile var voxelCount = 0; private set
    @Volatile var featureCount = 0; private set

    /** 특징점 묶음: ids[i], xyz, rgb(null=연파랑) */
    fun addFeatures(ids: IntArray, xyz: FloatArray, rgb: FloatArray?, n: Int) = post {
        for (i in 0 until n) {
            val r = rgb?.get(3 * i) ?: 0.85f; val g = rgb?.get(3 * i + 1) ?: 0.92f; val b = rgb?.get(3 * i + 2) ?: 1f
            features.put(ids[i], xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2], r, g, b)
            hf.add(xyz[3 * i].toDouble(), xyz[3 * i + 1].toDouble(), xyz[3 * i + 2].toDouble())
        }
        featureBatches++; featureCount = features.size; hf.bump(); dirty = true
        maybeRecompute()
    }

    /** 깊이 묶음(신뢰도 ≥0.4 만 들어옴). fromPhoto = 사진 찍을 때 붙인 깊이 */
    fun addDepth(xyz: FloatArray, rgb: FloatArray?, conf: FloatArray, n: Int, fromPhoto: Boolean) = post {
        val tint = view3d == View3D.POINTS_COV
        for (i in 0 until n) {
            val x = xyz[3 * i]; val y = xyz[3 * i + 1]; val z = xyz[3 * i + 2]
            var r = rgb?.get(3 * i) ?: 0.85f; var g = rgb?.get(3 * i + 1) ?: 0.92f; var b = rgb?.get(3 * i + 2) ?: 1f
            if (tint) { val t = tintAt(x, z); r = r * 0.35f + t[0] * 0.65f; g = g * 0.35f + t[1] * 0.65f; b = b * 0.35f + t[2] * 0.65f }
            voxels.add(x, y, z, r, g, b, conf[i])
            hf.add(x.toDouble(), y.toDouble(), z.toDouble())
        }
        depthBatches++; voxelCount = voxels.size; hf.bump(); dirty = true
        if (fromPhoto) kr.archsurvey.pg.diag.PgLog.d("depth@photo n=$n voxels=${voxels.size}")
        maybeRecompute()
    }

    private fun tintAt(x: Float, z: Float): FloatArray = color(cov.covKey(Math.floorDiv(hf.ix(x.toDouble()), 2), Math.floorDiv(hf.ix(z.toDouble()), 2)))
    private fun maybeRecompute() { val now = System.currentTimeMillis(); if (now - lastMeshAt > meshIntervalMs) recompute(false) }

    /** 보기 전환·충분 색 갱신: 깊이 점을 다시 칠함 */
    fun refreshCloud() = post { recolorAll() }
    private fun recolorAll() {
        if (view3d == View3D.POINTS_COV) voxels.recolor { i, c -> val p = voxels.pos(i); val t = tintAt(p[0], p[2]); floatArrayOf(c[0] * 0.35f + t[0] * 0.65f, c[1] * 0.35f + t[1] * 0.65f, c[2] * 0.35f + t[2] * 0.65f) }
        else voxels.recolor { _, c -> c }
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
        kr.archsurvey.pg.diag.PgLog.d("S4 coverage pct=${progress.pct} green=${progress.greenPct} overlap=${progress.overlapPct} holes=${progress.holesPct} tags=${progress.tagsOk}/${progress.tagsTotal} photos=${progress.photos} done=${progress.done} | voxels=${voxels.size} features=${features.size} cells=${hf.cells.size}")
        if (view3d == View3D.POINTS_COV && force) recolorAll()
        onChange?.invoke()
    }

    private fun color(k: Long): FloatArray = when (cov.suff(k)) {
        Suff.GREEN -> floatArrayOf(0.12f, 0.48f, 0.23f, 0.62f)
        Suff.YELLOW -> floatArrayOf(0.66f, 0.39f, 0f, 0.62f)
        Suff.RED -> floatArrayOf(0.71f, 0.14f, 0.09f, 0.55f)
    }

    /** 메모리 부족: 오래된(점 적은) 칸부터 줄여 절반으로 */
    fun trim() = post {
        val before = hf.cells.size
        if (before > 20_000) { val keep = hf.cells.entries.sortedByDescending { it.value.n }.take(before / 2).map { it.key }.toHashSet(); hf.cells.keys.retainAll(keep) }
        hf.maxCells = maxOf(20_000, hf.cells.size + 50_000)
        kr.archsurvey.pg.diag.PgLog.w("memory trim cells $before -> ${hf.cells.size}")
        recompute(true)
    }
    /** 발열 단계에 맞춰 면 갱신 간격 */
    @Volatile var meshIntervalMs = 1000L

    /** 좌표 없음 모드: 표적 로컬 위치(Z 위, 미터, 축척 적용) — 참고용 TXT */
    fun localTagsTxt(): String {
        val s = scale?.s ?: 1.0
        return tags.positions().toSortedMap().entries.joinToString("") { (no, p) ->
            String.format(java.util.Locale.US, "%s,%.3f,%.3f,%.3f\r\n", TargetNaming.name(TargetNaming.tagId(no)), p.x * s, -p.z * s, p.y * s)
        }
    }

}
