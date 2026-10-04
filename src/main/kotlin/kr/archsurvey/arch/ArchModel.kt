package kr.archsurvey.arch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.archsurvey.core.NE

/** 위치 종류 이름 규칙·기본 목록. 유물 수량·재질 같은 속성은 두지 않는다 — 종류 이름 하나뿐. */
object ArchTypes {
    const val OUTLINE_POINT = "유구 경계점"
    const val ARTIFACT = "유물 출토 위치"
    const val GRID_CORNER = "그리드 모서리"
    const val MAX_LEN = 8
    /** 고고학 3종이 앞쪽·가운데에 들어 있는 기본 목록 */
    val seed = listOf(OUTLINE_POINT, ARTIFACT, "기준점", "레벨점", GRID_CORNER, "기타")

    fun problem(raw: String, existing: Collection<String>): String? {
        val n = raw.trim()
        return when {
            n.isEmpty() -> "이름을 입력하세요"
            n.length > MAX_LEN -> "이름은 ${MAX_LEN}자까지예요"
            n.contains('\n') || n.contains(',') -> "쉼표와 줄바꿈은 쓸 수 없어요"
            existing.any { it == n } -> "같은 이름이 이미 있어요"
            else -> null
        }
    }
    fun encode(list: List<String>) = list.joinToString("\n")
    fun decode(raw: String?): List<String> = raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() } ?: seed
}

data class Area(val id: Long, val name: String, val colorIdx: Int)

data class Outline(
    val id: Long,
    val label: String,
    val areaId: Long?,
    val closed: Boolean,
    val finished: Boolean,
    /** 꼭짓점 점의 rowId, 취득 순서 */
    val pointRowIds: List<Long>,
)

data class Revision(
    val revNo: Int, val measuredAtMs: Long, val northing: Double, val easting: Double, val groundHeightM: Double?, val sigmaHM: Double?,
    val fixQuality: Int, val allFixed: Boolean, val epochs: Int, val device: String, val cell: String,
)

data class ArchSnapshot(val areas: List<Area>, val grids: List<GridDef>, val outlines: List<Outline>, val revisionCounts: Map<Long, Int>)

/** 구역·그리드·외곽·이력 저장소. Android 의존 없음(시험용 가짜 구현 가능) */
interface ArchStore {
    suspend fun load(): ArchSnapshot
    suspend fun addArea(name: String, colorIdx: Int): Long
    suspend fun renameArea(id: Long, name: String)
    /** 구역과 그 그리드를 지운다. 점은 지우지 않고 소속만 비운다 */
    suspend fun deleteArea(id: Long)
    suspend fun addGrid(g: GridDef): Long
    suspend fun deleteGrid(id: Long)
    suspend fun startOutline(areaId: Long?, label: String): Long
    suspend fun addVertex(outlineId: Long, pointRowId: Long)
    suspend fun removeVertex(outlineId: Long, pointRowId: Long)
    suspend fun finishOutline(id: Long, closed: Boolean)
    suspend fun deleteOutline(id: Long)
    suspend fun revisions(pointRowId: Long): List<Revision>
}

/** 지도에 그리는 외곽 */
data class OutlineView(val outline: Outline, val vertices: List<NE>, val summary: OutlineMath.Summary)

/**
 * 고고학 상태: 발굴 구역 › 그리드 › 외곽. SurveyController 가 하나 가진다.
 * 활성 구역·그리드는 작업별로 기억한다([onActiveChanged]).
 */
class ArchController(
    private val ctl: kr.archsurvey.ui.SurveyController,
    private val store: ArchStore?,
    private val scope: CoroutineScope,
    private val activeProvider: () -> Pair<Long?, Long?> = { null to null },
    private val onActiveChanged: (Long?, Long?) -> Unit = { _, _ -> },
) {
    val areas = mutableStateListOf<Area>()
    val grids = mutableStateListOf<GridDef>()
    val outlines = mutableStateListOf<Outline>()
    var revisionCounts by mutableStateOf<Map<Long, Int>>(emptyMap()); private set

    var activeAreaId by mutableStateOf(activeProvider().first); private set
    var activeGridId by mutableStateOf(activeProvider().second); private set

    /** 외곽 이어서 취득 켜짐 */
    var outlineMode by mutableStateOf(false)
    var activeOutlineId by mutableStateOf<Long?>(null); private set
    /** 방금 끝낸 외곽의 요약(화면에 한 줄로 보여줌) */
    var lastSummary by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    val activeArea: Area? get() = areas.firstOrNull { it.id == activeAreaId }
    /** 그리드의 좌표 원점(EPSG)이 지금 설정과 다르면 쓰지 않는다 */
    val gridMismatch: Boolean get() = grids.firstOrNull { it.id == activeGridId }?.let { it.crsEpsg != 0 && it.crsEpsg != ctl.zone.epsg } ?: false
    val activeGrid: GridDef? get() = grids.firstOrNull { it.id == activeGridId }?.takeIf { !gridMismatch }
    val activeOutline: Outline? get() = outlines.firstOrNull { it.id == activeOutlineId }
    val enabled: Boolean get() = store != null

    fun gridsOf(areaId: Long) = grids.filter { it.areaId == areaId }

    fun reload() {
        val st = store ?: return
        activeProvider().let { activeAreaId = it.first; activeGridId = it.second }
        outlineMode = false; activeOutlineId = null; lastSummary = null
        scope.launch {
            runCatching { st.load() }.onSuccess { s ->
                areas.clear(); areas.addAll(s.areas); grids.clear(); grids.addAll(s.grids); outlines.clear(); outlines.addAll(s.outlines)
                revisionCounts = s.revisionCounts
                if (activeAreaId != null && areas.none { it.id == activeAreaId }) activeAreaId = null
                if (activeGridId != null && grids.none { it.id == activeGridId }) activeGridId = null
                activeOutlineId = outlines.firstOrNull { !it.finished }?.id
            }.onFailure { error = "구역·그리드를 불러오지 못했어요" }
        }
    }

    fun setActive(areaId: Long?, gridId: Long?) {
        activeAreaId = areaId
        activeGridId = gridId
        onActiveChanged(areaId, gridId)
    }

    fun selectArea(id: Long?) {
        // 구역을 바꾸면 그 구역의 첫 그리드를 기본으로
        setActive(id, id?.let { gridsOf(it).firstOrNull()?.id })
    }
    fun selectGrid(g: GridDef) = setActive(g.areaId, g.id)

    fun addArea(name: String, onDone: (Long) -> Unit = {}) {
        val st = store ?: return
        val n = name.trim()
        val problem = when {
            n.isEmpty() -> "구역 이름을 입력하세요"
            n.length > 20 -> "구역 이름은 20자까지예요"
            areas.any { it.name == n } -> "같은 이름의 구역이 이미 있어요"
            else -> null
        }
        if (problem != null) { error = problem; return }
        error = null
        scope.launch {
            runCatching { st.addArea(n, areas.size % 6) }.onSuccess { id ->
                areas.add(Area(id, n, areas.size % 6)); setActive(id, null); onDone(id)
            }.onFailure { error = "구역을 만들지 못했어요" }
        }
    }

    fun deleteArea(id: Long) {
        val st = store ?: return
        scope.launch {
            runCatching { st.deleteArea(id) }.onSuccess {
                areas.removeAll { it.id == id }; grids.removeAll { it.areaId == id }
                outlines.replaceAll { if (it.areaId == id) it.copy(areaId = null) else it }
                if (activeAreaId == id) setActive(null, null)
                ctl.reloadFromStore()
            }.onFailure { error = "구역을 지우지 못했어요" }
        }
    }

    fun addGrid(g: GridDef, onDone: (GridDef) -> Unit = {}) {
        val st = store ?: return
        val withCrs = g.copy(crsEpsg = ctl.zone.epsg)
        scope.launch {
            runCatching { st.addGrid(withCrs) }.onSuccess { id ->
                val saved = withCrs.copy(id = id); grids.add(saved); setActive(saved.areaId, saved.id); onDone(saved)
            }.onFailure { error = "그리드를 저장하지 못했어요" }
        }
    }

    fun deleteGrid(id: Long) {
        val st = store ?: return
        scope.launch {
            runCatching { st.deleteGrid(id) }.onSuccess {
                grids.removeAll { it.id == id }; if (activeGridId == id) setActive(activeAreaId, null); ctl.reloadFromStore()
            }.onFailure { error = "그리드를 지우지 못했어요" }
        }
    }

    // ---------------- 칸 판정 ----------------

    fun locate(p: NE, sigmaM: Double?): CellResult = activeGrid?.let { GridMath.locate(it, p, sigmaM) } ?: CellResult(CellKind.NO_GRID)

    /** 지금 위치가 속한 칸 (위치가 없거나 그리드가 없으면 NO_GRID) */
    fun currentCell(): CellResult {
        val ne = ctl.currentNE() ?: return CellResult(CellKind.NO_GRID)
        return locate(ne, ctl.gv.status.sigmaHM)
    }

    /** 화면 한 줄: 발굴 구역 A › 그리드 G1 · 칸 A-3 · 다음 P-0038 */
    fun contextLine(): String? {
        val a = activeArea ?: if (outlineMode) null else return null
        val sb = StringBuilder()
        if (a != null) sb.append("발굴 구역 ${a.name}")
        val g = activeGrid
        if (g != null) {
            val cell = currentCell()
            sb.append(" › 그리드 ${g.name}")
            if (cell.kind != CellKind.NO_GRID) sb.append(" · 칸 ${cell.label}")
        } else if (gridMismatch) sb.append(" › 그리드 좌표 원점이 달라요")
        if (sb.isNotEmpty()) sb.append(" · ")
        sb.append("다음 ${ctl.nextName}")
        return sb.toString()
    }

    // ---------------- 외곽 ----------------

    fun outlineViews(): List<OutlineView> = outlines.mapNotNull { o ->
        val pts = o.pointRowIds.mapNotNull { id -> ctl.points.firstOrNull { it.rowId == id } }.map { ctl.pointNE(it) }
        if (pts.isEmpty()) null else OutlineView(o, pts, OutlineMath.summarize(pts, o.closed))
    }

    fun activeOutlineVertices(): List<NE> = activeOutline?.pointRowIds.orEmpty().mapNotNull { id -> ctl.points.firstOrNull { it.rowId == id } }.map { ctl.pointNE(it) }

    /** 방금 저장한 점을 외곽 꼭짓점으로 이어 붙인다 (외곽 모드 + 유구 경계점일 때) */
    suspend fun attachVertex(rowId: Long) {
        val st = store ?: return
        if (rowId <= 0) return
        runCatching {
            var o = activeOutline
            if (o == null) {
                val label = OutlineMath.label(outlines.size + 1)
                val id = st.startOutline(activeAreaId, label)
                o = Outline(id, label, activeAreaId, closed = false, finished = false, pointRowIds = emptyList())
                outlines.add(o); activeOutlineId = id
            }
            st.addVertex(o.id, rowId)
            replaceOutline(o.copy(pointRowIds = o.pointRowIds + rowId))
        }.onFailure { error = "외곽 꼭짓점을 저장하지 못했어요" }
    }

    private fun replaceOutline(o: Outline) { val i = outlines.indexOfFirst { it.id == o.id }; if (i >= 0) outlines[i] = o }

    /** 점을 되돌렸을 때 외곽에서도 뺀다 */
    fun detachVertex(rowId: Long) {
        val st = store ?: return
        val o = outlines.firstOrNull { rowId in it.pointRowIds } ?: return
        replaceOutline(o.copy(pointRowIds = o.pointRowIds - rowId))
        scope.launch { runCatching { st.removeVertex(o.id, rowId) } }
    }

    /** 외곽 끝내기. close=true 면 면(닫힘), false 면 선. 점이 모자라면 false */
    fun finishOutline(close: Boolean): Boolean {
        val st = store ?: return false
        val o = activeOutline ?: return false
        val pts = activeOutlineVertices()
        if (pts.size < 2) { error = "꼭짓점이 2개 이상 있어야 해요"; return false }
        if (close && pts.size < 3) { error = "면으로 닫으려면 꼭짓점이 3개 이상 필요해요"; return false }
        error = null
        val sum = OutlineMath.summarize(pts, close)
        replaceOutline(o.copy(closed = sum.closed, finished = true)); activeOutlineId = null
        lastSummary = summaryText(o.label, sum)
        scope.launch { runCatching { st.finishOutline(o.id, sum.closed) }.onFailure { error = "외곽을 저장하지 못했어요" } }
        return true
    }

    fun deleteOutline(id: Long) {
        val st = store ?: return
        scope.launch { runCatching { st.deleteOutline(id) }.onSuccess { outlines.removeAll { it.id == id }; if (activeOutlineId == id) activeOutlineId = null } }
    }

    fun outlineOf(rowId: Long): Pair<Outline, Int>? =
        outlines.firstNotNullOfOrNull { o -> o.pointRowIds.indexOf(rowId).takeIf { it >= 0 }?.let { o to it + 1 } }

    fun summaryText(label: String, s: OutlineMath.Summary): String =
        if (s.closed) "$label 면으로 닫음 · 꼭짓점 ${s.vertices}개 · 면적 ${"%.2f".format(s.areaSqm)} ㎡ · 둘레 ${"%.2f".format(s.perimeterM)} m" + if (s.selfCrossing) " · 변이 서로 교차해요, 순서를 확인하세요" else ""
        else "$label 선으로 끝냄 · 꼭짓점 ${s.vertices}개 · 길이 ${"%.2f".format(s.perimeterM)} m" + if (s.selfCrossing) " · 변이 서로 교차해요" else ""

    // ---------------- 재측량 이력 ----------------

    var revisions by mutableStateOf<List<Revision>>(emptyList()); private set
    fun loadRevisions(rowId: Long) {
        val st = store ?: run { revisions = emptyList(); return }
        scope.launch { revisions = runCatching { st.revisions(rowId) }.getOrDefault(emptyList()) }
    }
    fun bumpRevision(rowId: Long) { revisionCounts = revisionCounts + (rowId to ((revisionCounts[rowId] ?: 0) + 1)) }
    fun dropRevision(rowId: Long) { val n = (revisionCounts[rowId] ?: 0) - 1; revisionCounts = if (n <= 0) revisionCounts - rowId else revisionCounts + (rowId to n) }
}
