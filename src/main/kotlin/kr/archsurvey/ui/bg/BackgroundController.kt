package kr.archsurvey.ui.bg

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kr.archsurvey.georef.*
import kr.archsurvey.map.LocalProjection
import kr.archsurvey.map.LocalXY
import kr.archsurvey.map.MapBgLayer
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** 배경 관련 화면(아래 시트) 단계. NONE 이면 시트가 없다. */
enum class BgScreen { NONE, OPACITY, MANAGE, PICK_FILE, RESULT, WARNING, CRS_PICK, MANUAL }

enum class LayerKind { SATELLITE, CADASTRE, UPLOAD }

/** 레이어 행. 위성·지적도는 아직 연결 전 **가짜** 행이다(지도에 아무것도 올리지 않는다). 업로드 행만 [bg] 를 가진다. */
data class LayerRow(
    val id: String, val name: String, val kind: LayerKind,
    val enabled: Boolean, val opacity: Double,
    val badge: String?, val fake: Boolean,
    val bg: BgLayer? = null,
    val cornersLonLat: List<LonLat> = emptyList(),
) { val deletable get() = kind == LayerKind.UPLOAD }

/** 업로드 도중 상태 */
data class PendingUpload(val sample: FakeSample, val file: File, val outcome: AlignOutcome)

/** 수동 정렬 상태 */
data class ManualState(
    val pairs: List<ControlPoint> = emptyList(),
    val fit: FitOutcome? = null,
)

/**
 * 업로드 배경 정합 화면(s34c/d/e)과 배경 레이어 시트(s35/s36)의 상태·동작. Android 의존 없음 → JVM 단위 시험 가능.
 * **가짜 연결**: 파일 선택은 [FakeSample] 예시 파일, 수동 정렬의 "점 찍기"는 미리 정한 도면 위치 + 약간의 오차를 가진 지도 위치를 넣는 버튼.
 * 정합 판정(`UploadAligner`)과 변환 계산(`AffineFitter`)은 진짜 모듈을 그대로 쓴다.
 */
class BackgroundController(
    private val projection: LocalProjection,
    private val workDir: File,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** 작업 좌표계(수동 정렬 결과를 저장하는 좌표계) */
    val targetEpsg: Int = 5186,
    val workRadiusKm: Double = 20.0,
) {
    var screen by mutableStateOf(BgScreen.NONE)
    val layers = mutableStateListOf(
        LayerRow("sat", "위성", LayerKind.SATELLITE, enabled = true, opacity = 1.0, badge = "연결 전 · 예시", fake = true),
        LayerRow("cad", "지적도", LayerKind.CADASTRE, enabled = true, opacity = 0.6, badge = "연결 전 · 예시", fake = true),
    )
    var pending by mutableStateOf<PendingUpload?>(null)
    var manual by mutableStateOf(ManualState())
    var notice by mutableStateOf<String?>(null)
    /** 삭제 확인 대기 중인 레이어 id (한 번 더 누르면 지움) */
    var deleteArmedId by mutableStateOf<String?>(null)
    private var seq = 0

    val work: WorkArea get() = WorkArea(LonLat(projection.lon0, projection.lat0), workRadiusKm)
    val uploads: List<LayerRow> get() = layers.filter { it.kind == LayerKind.UPLOAD }

    // ---------------- 시트 열고 닫기 ----------------
    fun openOpacity() { deleteArmedId = null; screen = BgScreen.OPACITY }
    fun openManage() { deleteArmedId = null; screen = BgScreen.MANAGE }
    fun close() { deleteArmedId = null; screen = BgScreen.NONE }
    fun openPicker() { screen = BgScreen.PICK_FILE }

    // ---------------- 레이어 행 조작 ----------------
    private fun update(id: String, f: (LayerRow) -> LayerRow) {
        val i = layers.indexOfFirst { it.id == id }; if (i >= 0) layers[i] = f(layers[i])
    }
    fun setEnabled(id: String, on: Boolean) = update(id) { it.copy(enabled = on, bg = it.bg?.withEnabled(on)) }
    fun setOpacity(id: String, v: Double) {
        val o = v.coerceIn(0.0, 1.0)
        update(id) { it.copy(opacity = o, bg = it.bg?.withOpacity(o)) }
    }
    /** 삭제는 두 번 눌러야 한다(실수 방지). 업로드 행만 지울 수 있다. */
    fun requestDelete(id: String) {
        val r = layers.firstOrNull { it.id == id } ?: return
        if (!r.deletable) return
        if (deleteArmedId == id) { layers.removeAll { it.id == id }; deleteArmedId = null; notice = "\"${r.name}\" 을(를) 지웠어요" }
        else deleteArmedId = id
    }

    // ---------------- 파일 올리기 (가짜 파일 + 진짜 정합 판정) ----------------
    fun pick(sample: FakeSample) {
        val file = FakeUploads.create(sample, workDir, work.center)
        val outcome = analyze(sample, file, null)
        pending = PendingUpload(sample, file, outcome)
        screen = if (outcome.displayable) BgScreen.RESULT else BgScreen.WARNING
    }

    private fun analyze(sample: FakeSample, file: File, userCrs: CrsDef?): AlignOutcome =
        if (sample.kind == BgKind.DXF) UploadAligner.analyzeDxf(file, work, userCrs) else UploadAligner.analyzeGeoTiff(file, work, userCrs)

    /** 좌표계 직접 지정 단계 열기. 후보가 없으면 열지 않는다. */
    fun openCrsPick() { if (pending?.outcome?.candidates?.isNotEmpty() == true) screen = BgScreen.CRS_PICK }

    fun chooseCrs(c: CrsDef) {
        val p = pending ?: return
        if (!c.supported) { notice = "EPSG:${c.epsg} 는 아직 변환할 수 없어요(측지계가 달라요)"; return }
        val o = analyze(p.sample, p.file, c)
        pending = p.copy(outcome = o)
        screen = if (o.displayable) BgScreen.RESULT else BgScreen.WARNING
    }

    /** 이 파일에서 "좌표계 직접 지정"이 의미가 있는가 (좌표가 아예 없는 파일은 아님) */
    val canPickCrs: Boolean get() = pending?.outcome?.let { it.candidates.isNotEmpty() && it.status != AlignStatus.NEEDS_MANUAL } == true
    /** 수동 정렬은 래스터(화소 위치로 기준점을 짝짓는 방식)만 연결했다. DXF 수동 정렬은 다음 라운드 */
    val canManual: Boolean get() = pending?.sample?.kind == BgKind.GEOTIFF

    fun cancelUpload() { pending = null; manual = ManualState(); screen = if (layers.any { it.kind == LayerKind.UPLOAD }) BgScreen.MANAGE else BgScreen.NONE }

    /** s34c [불러오기] — 정합된 파일만 레이어가 된다 */
    fun importPending() {
        val p = pending ?: return
        if (!p.outcome.displayable) return
        val id = "up${++seq}"
        val base = BgLayer.fromOutcome(id, p.sample.kind, p.sample.fileName, p.file, p.outcome, nowMs())
        addUpload(base, p.outcome.cornersLonLat.ifEmpty { cornersOfExtent(p.outcome) })
    }

    private fun cornersOfExtent(o: AlignOutcome): List<LonLat> {
        val e = o.extent ?: return emptyList(); val crs = o.crs ?: return emptyList()
        return e.corners().map { KoreanCrs.toLonLat(crs, it.x, it.y) }
    }

    private fun addUpload(bg: BgLayer, corners: List<LonLat>) {
        layers += LayerRow(bg.id, bg.displayName, LayerKind.UPLOAD, enabled = true, opacity = bg.opacity, badge = bg.badge, fake = false, bg = bg, cornersLonLat = corners)
        pending = null; manual = ManualState(); deleteArmedId = null
        notice = "\"${bg.displayName}\" 을(를) 불러왔어요 · ${bg.badge}"
        screen = BgScreen.OPACITY
    }

    // ---------------- 수동 정렬 (s34e) ----------------
    fun startManual() {
        if (!canManual) return
        manual = ManualState(); screen = BgScreen.MANUAL
    }
    val manualMaxPairs get() = 4
    val manualCanAddPair get() = manual.pairs.size < manualMaxPairs

    /** 가짜 "점 찍기": 도면의 다음 기준 위치와, 실제 지도에서 찍은 위치(약간의 오차 포함)를 한 쌍으로 넣는다. */
    fun addFakePair() {
        if (!manualCanAddPair) return
        val i = manual.pairs.size
        val src = FakeManual.SRC[i]
        val t = FakeManual.truth(work.center).apply(src)
        val dst = XY(t.x + FakeManual.NOISE[i].x, t.y + FakeManual.NOISE[i].y)
        setPairs(manual.pairs + ControlPoint(src, dst))
    }
    fun removeLastPair() { if (manual.pairs.isNotEmpty()) setPairs(manual.pairs.dropLast(1)) }
    private fun setPairs(p: List<ControlPoint>) {
        manual = ManualState(p, if (p.size >= 2) AffineFitter.fit(p, flipSrcY = true) else null)
    }
    val manualFit: FitResult? get() = (manual.fit as? FitOutcome.Ok)?.result
    val manualRejected: String? get() = (manual.fit as? FitOutcome.Rejected)?.message
    val manualCanApply: Boolean get() = manualFit != null

    /** 쉬운 말 경고 문구 (건설 용어 없음) */
    fun manualHints(): List<String> {
        val f = manualFit ?: return emptyList()
        val out = mutableListOf<String>()
        for (w in f.warnings) out += when (w) {
            FitWarning.NO_REDUNDANCY -> "점이 3개면 오류를 잡지 못해요. 검산용 점 1개를 더 찍어 보세요"
            FitWarning.NEARLY_COLLINEAR -> "점들이 거의 한 줄에 있어요. 넓게 흩어진 점을 고르세요"
            FitWarning.LARGE_RESIDUAL -> "오차가 커요. 점을 잘못 찍었는지 확인하세요"
            FitWarning.ANISOTROPIC_SCALE -> "가로·세로 비율이 달라요. 점을 확인하세요"
            FitWarning.SHEARED -> "도면이 찌그러졌어요. 점을 확인하세요"
        }
        if (manual.pairs.size == 2) out += "점이 2개면 오차를 확인할 수 없어요. 3~4개를 권해요"
        return out
    }

    /** [정렬 적용] — 원본 파일은 그대로, 변환 값만 저장 */
    fun applyManual() {
        val p = pending ?: return; val f = manualFit ?: return
        val base = BgLayer.fromOutcome("up${++seq}", p.sample.kind, p.sample.fileName, p.file, p.outcome, nowMs())
        val bg = BgLayer.withManual(base, f, manual.pairs, targetEpsg)
        addUpload(bg, bg.cornersLonLat().orEmpty())
    }

    // ---------------- 지도에 올릴 도형 ----------------
    /** 켜져 있고 정합된 업로드 레이어 + (수동 정렬 중이면) 미리보기. 미정합 배경은 절대 포함하지 않는다. */
    fun mapLayers(): List<MapBgLayer> {
        val out = mutableListOf<MapBgLayer>()
        for (r in layers) {
            val bg = r.bg ?: continue
            if (!bg.shouldDraw || r.cornersLonLat.size < 3) continue
            out += MapBgLayer(r.id, r.name, r.cornersLonLat.map { projection.toLocal(it.lat, it.lon) }, r.opacity, preview = false)
        }
        if (screen == BgScreen.MANUAL) manualFit?.let { f ->
            val w = FakeUploads.RASTER_W.toDouble(); val h = FakeUploads.RASTER_H.toDouble()
            val c = KoreanCrs.byEpsg(targetEpsg)!!
            val poly = listOf(XY(0.0, 0.0), XY(w, 0.0), XY(w, h), XY(0.0, h)).map { val q = f.affine.apply(it); val ll = KoreanCrs.toLonLat(c, q.x, q.y); projection.toLocal(ll.lat, ll.lon) }
            out += MapBgLayer("preview", pending?.sample?.fileName ?: "미리보기", poly, 0.5, preview = true)
        }
        return out
    }

    /** 지도 아래쪽 작은 안내 문구 (예: "위성 35% + 업로드 100%") */
    fun mapCaption(): String? {
        val on = layers.filter { it.enabled && (it.bg?.shouldDraw ?: false) }
        if (on.isEmpty()) return null
        return on.mapIndexed { i, r -> (if (on.size > 1) "업로드${i + 1}" else "업로드") + " ${(r.opacity * 100).toInt()}%" }.joinToString(" + ")
    }
}

/** 수동 정렬 예시(가짜) 값: 도면 화소 위치, 실제 위치의 "참값" 변환(회전 4°, 0.5 m/화소), 점을 찍을 때 생기는 손 오차(m) */
object FakeManual {
    val SRC = listOf(XY(25.0, 20.0), XY(180.0, 30.0), XY(100.0, 135.0), XY(20.0, 120.0))
    val NOISE = listOf(XY(0.06, -0.04), XY(-0.05, 0.03), XY(0.02, 0.07), XY(-0.03, -0.05))
    private const val ROT_DEG = 4.0

    /** 화소(열,행) -> 5186 좌표. 도면 중심이 작업 위치 */
    fun truth(center: LonLat): Affine {
        val c = KoreanCrs.fromLonLat(KoreanCrs.EPSG_5186, center)
        val s = FakeUploads.PIXEL_M; val th = Math.toRadians(ROT_DEG)
        val cx = FakeUploads.RASTER_W / 2.0; val cy = FakeUploads.RASTER_H / 2.0
        // E = c.x + s(cosθ·(col-cx) + sinθ·(row-cy)),  N = c.y + s(sinθ·(col-cx) − cosθ·(row-cy))
        val a = s * cos(th); val b = s * sin(th); val d = s * sin(th); val e = -s * cos(th)
        return Affine(a, b, c.x - (a * cx + b * cy), d, e, c.y - (d * cx + e * cy))
    }
}
