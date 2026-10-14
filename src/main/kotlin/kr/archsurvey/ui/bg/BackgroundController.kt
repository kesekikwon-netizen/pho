package kr.archsurvey.ui.bg

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kr.archsurvey.georef.*
import kr.archsurvey.map.LocalProjection
import kr.archsurvey.map.MapBgLayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 배경 관련 화면(아래 시트) 단계. NONE 이면 시트가 없다. */
enum class BgScreen { NONE, OPACITY, MANAGE, PICK_FILE, RESULT, WARNING, CRS_PICK }

/** 레이어 행. 사용자가 파일로 올린 도면만 있다(위성·지적도 타일은 이 버전에 없다). */
data class LayerRow(
    val id: String, val name: String,
    val enabled: Boolean, val opacity: Double,
    val badge: String?,
    val bg: BgLayer,
    val cornersLonLat: List<LonLat> = emptyList(),
)

/** 업로드 도중 상태 */
data class PendingUpload(val fileName: String, val kind: BgKind, val file: File, val outcome: AlignOutcome)

/**
 * 배경(도면) 레이어 시트의 상태·동작. Android 의존 없음 → JVM 단위 시험 가능.
 * 파일은 시스템 문서 선택기에서 고른 것을 앱 전용 폴더로 복사한 [File] 로 받는다([onPicked]).
 * 정합 판정(`UploadAligner`)은 georef 모듈 그대로, 레이어 저장은 [BgLayerStore] + 모서리 보조 파일.
 */
class BackgroundController(
    private val projection: () -> LocalProjection,
    private val store: BgLayerStore,
    /** 문서 선택기를 연다. 선택이 끝나면 (복사된 파일 목록) 으로 콜백. 선택기가 없는 환경이면 null */
    var pickFiles: ((cb: (List<File>) -> Unit) -> Unit)? = null,
    /** 레이어 삭제 때 원본 사본 폴더를 지우는 데 쓴다 */
    private val deleteFiles: (BgLayer) -> Unit = { runCatching { File(it.filePath).delete() } },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val cornersDir: File? = null,
    val workRadiusKm: Double = 20.0,
) {
    var screen by mutableStateOf(BgScreen.NONE)
    val layers = mutableStateListOf<LayerRow>()
    var pending by mutableStateOf<PendingUpload?>(null)
    var notice by mutableStateOf<String?>(null)
    /** 삭제 확인 대기 중인 레이어 id (한 번 더 누르면 지움) */
    var deleteArmedId by mutableStateOf<String?>(null)
    private var seq = 0

    val work: WorkArea get() { val p = projection(); return WorkArea(LonLat(p.lon0, p.lat0), workRadiusKm) }
    val uploads: List<LayerRow> get() = layers.toList()

    init { load() }

    private fun load() {
        for (b in store.list().sortedBy { it.createdAtMillis }) {
            val corners = readCorners(b.id) ?: b.cornersLonLat().orEmpty()
            layers += LayerRow(b.id, b.displayName, b.enabled, b.opacity, b.badge, b, corners)
            Regex("\\d+").find(b.id)?.value?.toIntOrNull()?.let { if (it > seq) seq = it }
        }
    }

    // ---------------- 시트 열고 닫기 ----------------
    fun openOpacity() { deleteArmedId = null; screen = BgScreen.OPACITY }
    fun openManage() { deleteArmedId = null; screen = BgScreen.MANAGE }
    fun close() { deleteArmedId = null; screen = BgScreen.NONE }
    fun openPicker() { screen = BgScreen.PICK_FILE }
    /** 레이어 버튼: 열려 있으면 닫고, 닫혀 있으면 투명도 시트 */
    fun toggle() { if (screen == BgScreen.NONE) openOpacity() else close() }

    // ---------------- 레이어 행 조작 ----------------
    private fun update(id: String, f: (LayerRow) -> LayerRow) {
        val i = layers.indexOfFirst { it.id == id }
        if (i >= 0) { layers[i] = f(layers[i]); layers[i].bg.let { store.put(it) } }
    }
    fun setEnabled(id: String, on: Boolean) = update(id) { it.copy(enabled = on, bg = it.bg.withEnabled(on)) }
    fun setOpacity(id: String, v: Double) {
        val o = v.coerceIn(0.0, 1.0)
        update(id) { it.copy(opacity = o, bg = it.bg.withOpacity(o)) }
    }
    /** 삭제는 두 번 눌러야 한다(실수 방지). */
    fun requestDelete(id: String) {
        val r = layers.firstOrNull { it.id == id } ?: return
        if (deleteArmedId == id) {
            layers.removeAll { it.id == id }; store.remove(id); cornersFile(id)?.delete(); deleteFiles(r.bg)
            deleteArmedId = null; notice = "\"${r.name}\" 을(를) 지웠어요"
        } else deleteArmedId = id
    }

    // ---------------- 파일 올리기 (문서 선택기 → 복사 → 정합 판정) ----------------
    /** [파일 고르기] 버튼. 문서 선택기가 없으면 안내만 한다. */
    fun launchPicker() {
        val p = pickFiles
        if (p == null) { notice = "이 기기에서는 파일 선택기를 열 수 없어요"; return }
        notice = null
        p { files -> onPicked(files) }
    }

    /** 선택된 파일들(도면 + 같은 이름의 .tfw/.prj 가 함께 있을 수 있다) 중 도면 하나를 골라 정합을 판정한다. */
    fun onPicked(files: List<File>) {
        if (files.isEmpty()) { notice = "고른 파일이 없어요"; screen = BgScreen.PICK_FILE; return }
        val main = files.firstOrNull { kindOf(it.name) == BgKind.GEOTIFF } ?: files.firstOrNull { kindOf(it.name) == BgKind.DXF }
        if (main == null) {
            notice = "지원하지 않는 파일이에요 — GeoTIFF(.tif, .tiff) 또는 DXF(.dxf) 만 올릴 수 있어요"
            screen = BgScreen.PICK_FILE; return
        }
        val kind = kindOf(main.name)!!
        val outcome = runCatching { analyze(kind, main, null) }.getOrElse {
            notice = "파일을 읽지 못했어요"; screen = BgScreen.PICK_FILE; return
        }
        notice = null
        pending = PendingUpload(main.name, kind, main, outcome)
        screen = if (outcome.displayable) BgScreen.RESULT else BgScreen.WARNING
    }

    private fun kindOf(name: String): BgKind? = when (name.substringAfterLast('.', "").lowercase()) {
        "tif", "tiff" -> BgKind.GEOTIFF
        "dxf" -> BgKind.DXF
        else -> null
    }

    private fun analyze(kind: BgKind, file: File, userCrs: CrsDef?): AlignOutcome =
        if (kind == BgKind.DXF) UploadAligner.analyzeDxf(file, work, userCrs) else UploadAligner.analyzeGeoTiff(file, work, userCrs)

    /** 좌표계 직접 지정 단계 열기. 후보가 없으면 열지 않는다. */
    fun openCrsPick() { if (pending?.outcome?.candidates?.isNotEmpty() == true) screen = BgScreen.CRS_PICK }

    fun chooseCrs(c: CrsDef) {
        val p = pending ?: return
        if (!c.supported) { notice = "EPSG:${c.epsg} 는 아직 변환할 수 없어요(측지계가 달라요)"; return }
        val o = analyze(p.kind, p.file, c)
        pending = p.copy(outcome = o)
        screen = if (o.displayable) BgScreen.RESULT else BgScreen.WARNING
    }

    /** 이 파일에서 "좌표계 직접 지정"이 의미가 있는가 (좌표가 아예 없는 파일은 아님) */
    val canPickCrs: Boolean get() = pending?.outcome?.let { it.candidates.isNotEmpty() && it.status != AlignStatus.NEEDS_MANUAL } == true

    fun cancelUpload() { pending?.let { runCatching { it.file.parentFile?.takeIf { d -> d.name.startsWith("bg_") }?.deleteRecursively() } }; pending = null; screen = BgScreen.MANAGE }

    /** [불러오기] — 정합된 파일만 레이어가 된다 */
    fun importPending() {
        val p = pending ?: return
        if (!p.outcome.displayable) return
        val id = "up${++seq}"
        val bg = BgLayer.fromOutcome(id, p.kind, p.fileName, p.file, p.outcome, nowMs())
        val corners = p.outcome.cornersLonLat.ifEmpty { cornersOfExtent(p.outcome) }
        store.put(bg); writeCorners(id, corners)
        layers += LayerRow(bg.id, bg.displayName, bg.enabled, bg.opacity, bg.badge, bg, corners)
        pending = null; deleteArmedId = null
        notice = "\"${bg.displayName}\" 을(를) 불러왔어요 · ${bg.badge}"
        screen = BgScreen.OPACITY
    }

    private fun cornersOfExtent(o: AlignOutcome): List<LonLat> {
        val e = o.extent ?: return emptyList(); val crs = o.crs ?: return emptyList()
        return e.corners().map { KoreanCrs.toLonLat(crs, it.x, it.y) }
    }

    // ---------------- 모서리 보조 파일 (DXF 는 모서리를 BgLayer 에서 계산할 수 없어 따로 보관) ----------------
    private fun cornersFile(id: String) = cornersDir?.let { File(it, "corners_${id.filter { c -> c.isLetterOrDigit() }}.json") }
    private fun writeCorners(id: String, c: List<LonLat>) {
        val f = cornersFile(id) ?: return
        runCatching { f.parentFile?.mkdirs(); kr.archsurvey.io.AtomicFiles.writeText(f, JSONArray().apply { c.forEach { put(JSONArray(listOf(it.lon, it.lat))) } }.toString()) }
    }
    private fun readCorners(id: String): List<LonLat>? {
        val f = cornersFile(id)?.takeIf { it.exists() } ?: return null
        return runCatching { val a = JSONArray(f.readText()); List(a.length()) { val p = a.getJSONArray(it); LonLat(p.getDouble(0), p.getDouble(1)) } }.getOrNull()
    }

    // ---------------- 지도에 올릴 도형 ----------------
    /** 켜져 있고 정합된 업로드 레이어. 미정합 배경은 절대 포함하지 않는다. */
    fun mapLayers(): List<MapBgLayer> {
        val pr = projection()
        return layers.filter { it.bg.shouldDraw && it.cornersLonLat.size >= 3 }
            .map { r -> MapBgLayer(r.id, r.name, r.cornersLonLat.map { pr.toLocal(it.lat, it.lon) }, r.opacity, preview = false) }
    }

    /** 지도 아래쪽 작은 안내 문구 (예: "업로드1 50% + 업로드2 100%") */
    fun mapCaption(): String? {
        val on = layers.filter { it.bg.shouldDraw }
        if (on.isEmpty()) return null
        return on.mapIndexed { i, r -> (if (on.size > 1) "업로드${i + 1}" else "업로드") + " ${Math.round(r.opacity * 100).toInt()}%" }.joinToString(" + ")
    }
}
