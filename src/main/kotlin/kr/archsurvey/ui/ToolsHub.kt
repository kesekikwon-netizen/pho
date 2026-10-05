package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kr.archsurvey.core.Calc
import kr.archsurvey.core.Guidance
import kr.archsurvey.core.NE

enum class ToolScreen { POINTS, POINT_DETAIL, GOTO, CALC, SETTINGS, JOB_INFO, JOB_OPEN, JOB_NEW, AREAS, GRID_WIZ, TYPES, VWORLD }
enum class CalcMode(val label: String) { DISTANCE("거리·방위"), AREA("면적·둘레"), OFFSET("방위·거리로 좌표") }

/** 찾아갈 목표: 저장된 점 또는 직접 입력한 좌표 */
sealed interface GotoTarget {
    val label: String
    data class OfPoint(val point: SavedPoint) : GotoTarget { override val label get() = point.name }
    data class Manual(val ne: NE) : GotoTarget { override val label get() = "입력한 좌표" }
}

/**
 * 서랍에서 여는 도구 화면들의 공통 상태 (점 목록·상세 편집·점 찾아가기·계산기·설정·작업 관리).
 * 화면이 하나씩 위에 얹히며, [back] 으로 한 단계씩 돌아간다.
 */
class ToolsHub(private val c: SurveyController) {
    var screen by mutableStateOf<ToolScreen?>(null); private set
    private var cameFrom: ToolScreen? = null

    fun open(s: ToolScreen) {
        if (!(s == ToolScreen.GOTO && screen == ToolScreen.GOTO)) cameFrom = if (s == ToolScreen.POINT_DETAIL || s == ToolScreen.GOTO) screen else null
        screen = s
    }
    fun back() { screen = when (screen) { ToolScreen.POINT_DETAIL -> ToolScreen.POINTS; ToolScreen.GOTO -> cameFrom?.takeIf { it == ToolScreen.POINT_DETAIL }; ToolScreen.GRID_WIZ -> ToolScreen.AREAS; else -> null } }
    fun closeAll() { screen = null }

    // ---------------- 점 목록 · 상세 · 편집 ----------------
    var query by mutableStateOf("")
    var selectedName by mutableStateOf<String?>(null)
    var editType by mutableStateOf("")
    var editMemo by mutableStateOf("")
    var saved by mutableStateOf(false); private set

    /** 최신 점이 위로 오는 목록, 이름·종류·메모에 검색어가 들어 있는 것만 */
    fun filtered(): List<SavedPoint> {
        val q = query.trim().lowercase()
        return c.points.asReversed().filter { q.isEmpty() || q in it.name.lowercase() || q in it.type.lowercase() || q in it.memo.lowercase() }
    }

    val selected: SavedPoint? get() = selectedName?.let { n -> c.points.firstOrNull { it.name == n } }

    fun openDetail(p: SavedPoint) { selectedName = p.name; editType = p.type; editMemo = p.memo; saved = false; open(ToolScreen.POINT_DETAIL) }
    val dirty: Boolean get() = selected?.let { it.type != editType.trim() || it.memo != editMemo.trim() } ?: false
    fun saveEdit() {
        val p = selected ?: return
        c.updatePointMeta(p.name, editType, editMemo); saved = true; editType = selected?.type ?: editType
        c.ui.toast("${p.name} 수정 저장됨 · 위치 종류 ${editType.trim().ifBlank { p.type }}" + if (editMemo.isNotBlank()) " · 비고" else "")
    }

    // ---------------- 점 찾아가기 ----------------
    var gotoTarget by mutableStateOf<GotoTarget?>(null)
    var gotoN by mutableStateOf("")
    var gotoE by mutableStateOf("")
    var gotoPickerOpen by mutableStateOf(false)

    fun startGoto(p: SavedPoint) { gotoTarget = GotoTarget.OfPoint(p); open(ToolScreen.GOTO) }
    fun setManualGoto(): Boolean {
        val n = gotoN.trim().toDoubleOrNull(); val e = gotoE.trim().toDoubleOrNull() ?: return false
        if (n == null) return false
        gotoTarget = GotoTarget.Manual(NE(n, e)); return true
    }
    fun gotoTargetNE(): NE? = when (val t = gotoTarget) { is GotoTarget.OfPoint -> c.pointNE(t.point); is GotoTarget.Manual -> t.ne; null -> null }
    /** 현재 위치에서 목표까지 안내. 위치나 목표가 없으면 null */
    fun guidance(): Guidance? {
        val cur = c.currentNE() ?: return null
        val tgt = gotoTargetNE() ?: return null
        return Guidance.from(cur, null, tgt, null)
    }

    // ---------------- 계산기 ----------------
    var calcMode by mutableStateOf(CalcMode.DISTANCE)
    var calcA by mutableStateOf<SavedPoint?>(null)
    var calcB by mutableStateOf<SavedPoint?>(null)
    var calcUseCurrentA by mutableStateOf(false)
    val areaPicks = mutableStateListOf<SavedPoint>()
    var offBearing by mutableStateOf("")
    var offDist by mutableStateOf("")
    var calcPicking by mutableStateOf<String?>(null) // "A" | "B" | "BASE" | "AREA"

    private fun neA(): NE? = if (calcUseCurrentA) c.currentNE() else calcA?.let { c.pointNE(it) }
    private fun neB(): NE? = calcB?.let { c.pointNE(it) }

    data class DistResult(val dist: Double, val bearing: Double, val dN: Double, val dE: Double)
    fun distResult(): DistResult? {
        val a = neA() ?: return null; val b = neB() ?: return null
        return DistResult(Calc.distance(a, b), Calc.bearingDeg(a, b), b.n - a.n, b.e - a.e)
    }

    data class AreaResult(val area: Double, val perimeter: Double, val crossing: Boolean)
    fun areaResult(): AreaResult? {
        if (areaPicks.size < 3) return null
        val pts = areaPicks.map { c.pointNE(it) }
        return AreaResult(Calc.polygonArea(pts), Calc.perimeter(pts, true), Calc.selfIntersects(pts))
    }

    fun offsetResult(): NE? {
        val base = calcA?.let { c.pointNE(it) } ?: return null
        val b = offBearing.trim().toDoubleOrNull() ?: return null
        val d = offDist.trim().toDoubleOrNull() ?: return null
        if (d < 0) return null
        return Calc.offset(base, b, d)
    }

    fun pick(p: SavedPoint) {
        when (calcPicking) {
            "A", "BASE" -> { calcA = p; calcUseCurrentA = false }
            "B" -> calcB = p
            "AREA" -> { areaPicks.add(p); return }
        }
        calcPicking = null
    }
    fun finishAreaPick() { calcPicking = null }
}
