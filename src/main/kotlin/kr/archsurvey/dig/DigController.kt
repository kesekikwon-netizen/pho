package kr.archsurvey.dig

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kr.archsurvey.arch.Outline
import kr.archsurvey.core.NE
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.ui.SavedPoint
import kr.archsurvey.ui.SurveyController
import java.util.Locale

/** 앱 설정 저장소(SharedPreferences)의 작은 창. 시험은 메모리 */
interface DigPrefs {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}
class MemoryDigPrefs : DigPrefs {
    val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** 「최근 · 한 번에 바꾸기」 한 칸 */
data class DigChoice(val kind: DigKind, val featureId: Long?, val level: LevelKind?)

/** 측량한 것 › 유구 카드 */
data class FeatureCard(
    val id: Long, val name: String, val type: String, val kind: String, val open: Boolean, val closed: Boolean,
    val boundary: Int, val artifacts: List<Int>, val levels: Int, val areaSqm: Double?, val perimeterM: Double?,
    val depthM: Double?, val floorMeanM: Double?, val fixedCount: Int, val total: Int, val history: List<String>,
    /** 0.1.36 사진 기준점(AprilTag) 번호(마지막 값 기준, 오름차순) */
    val gcpTags: List<Int> = emptyList(),
)

/**
 * 0.1.35 발굴 측량 상태: 「지금 재는 것」(종류·유구·레벨), 점의 발굴 속성 붙이기, 유구 면 닫기/되돌리기, 유구 이름 바꾸기,
 * 오늘 준비(폴 확인·기준점 확인), 지도 레이어 보이기, 말뚝 찾아가기. Android 의존 없음(시험 가능).
 * 점 이름(P-0001)은 바꾸지 않는다 — 유구·칸·번호는 점의 속성이다.
 */
class DigController(private val c: SurveyController) {
    /** 설정 저장소. 앱은 SharedPreferences, 시험은 메모리 */
    var prefs: DigPrefs = MemoryDigPrefs()
        set(v) { field = v; loadGlobals(); restoreSelection() }
    /** 오늘 날짜 열쇠(시험에서 바꾼다) */
    var today: () -> String = { DayKey.of(c.nowMs()) }

    // ---------------- 지금 재는 것 ----------------
    /** 지금 종류. 위치 종류 글자와 같이 움직인다(옛 작업·사용자 종류 호환) */
    val kind: DigKind? get() = DigKind.fromPositionType(c.typeName)
    var featureId by mutableStateOf<Long?>(null); private set
    var level by mutableStateOf(LevelKind.DETECT); private set
    var bmName by mutableStateOf("BM-1")
    val recent = mutableStateListOf<DigChoice>()

    var pickerOpen by mutableStateOf(false)
    var poleOpen by mutableStateOf(false)
    var layersOpen by mutableStateOf(false)
    var statusOpen by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var renameFor by mutableStateOf<Long?>(null)
    /** 「면 닫기」를 한 번 눌러 미리보기 중(측점 단추가 「면 닫기 확인」) */
    var closeArmed by mutableStateOf(false)
    /** 방금 닫은 유구(되돌리기 = 다시 열기) */
    var justClosed by mutableStateOf<Long?>(null); private set
    /** 방금 끝난 기준점 확인(지도 위 카드) */
    var bmResult by mutableStateOf<Pair<String, BmResult>?>(null)
    /** 「측량 지도의 좌표계 잠금」을 이번 실행에서 풀었는가(X11, 길게 눌러 풀기) */
    var crsUnlocked by mutableStateOf(false)
    /** 말뚝 찾아가기 목표 */
    var stakeTarget by mutableStateOf<StakeNodes.Node?>(null)
    /** 0.1.36 사진 기준점: 다음에 잴 AprilTag 번호(장갑 낀 채 −/+ 로 바꿈, 재면 자동으로 다음 번호) */
    var tagId by mutableStateOf(AprilTagNaming.DEFAULT_FIRST); private set
    var showGcp by mutableStateOf(true)
    /**
     * 0.1.37 지도 「Target 1·2·3·4」 단추로 잰 측점: 지금 재는 종류(유구 경계 등)는 그대로 두고 이번 한 점만 사진 기준점 [quickTag] 번으로 저장.
     * 저장·취소되면 null
     */
    var quickTag by mutableStateOf<Int?>(null)

    // ---------------- 설정(앱 전체, 쉽게 바꿀 수 있는 제안값) ----------------
    var artifactMode by mutableStateOf(ArtifactMode.JOB_SERIAL); private set
    var bmTolM by mutableStateOf(BmCheck.DEFAULT_TOL_M); private set
    val featureTypes = mutableStateListOf<String>().apply { addAll(FeatureNaming.DEFAULT_TYPES) }
    /** 새 유구를 가번호(가-3)로 시작 */
    var provisional by mutableStateOf(true); private set
    /** X2 볼륨 아래 키 = 측점(기본 끔) */
    var volumeKey by mutableStateOf(false); private set
    var drawScale by mutableStateOf(DrawingScale.DEFAULT); private set

    // 지도에 보이기(레이어 시트)
    var showArea by mutableStateOf(true); var showGrid by mutableStateOf(true); var showTrench by mutableStateOf(true)
    var showFeature by mutableStateOf(true); var showArtifactNo by mutableStateOf(true); var showLevelH by mutableStateOf(false)
    var showSection by mutableStateOf(true); var showBm by mutableStateOf(true)

    private fun loadGlobals() {
        artifactMode = ArtifactMode.fromId(prefs.get("dig_artifact_mode"))
        bmTolM = prefs.get("dig_bm_tol")?.toDoubleOrNull()?.takeIf { it in 0.005..0.5 } ?: BmCheck.DEFAULT_TOL_M
        featureTypes.clear(); featureTypes.addAll(FeatureNaming.decodeTypes(prefs.get("dig_feature_types")))
        provisional = prefs.get("dig_provisional") != "0"
        volumeKey = prefs.get("dig_volume_key") == "1"
        drawScale = prefs.get("dig_scale")?.toIntOrNull()?.takeIf { it in DrawingScale.CHOICES } ?: DrawingScale.DEFAULT
        prefs.get("dig_layers")?.let { s -> val b = s.map { it == '1' }; if (b.size >= 8) { showArea = b[0]; showGrid = b[1]; showTrench = b[2]; showFeature = b[3]; showArtifactNo = b[4]; showLevelH = b[5]; showSection = b[6]; showBm = b[7] }; if (b.size >= 9) showGcp = b[8] }
    }
    fun saveLayers() { prefs.put("dig_layers", listOf(showArea, showGrid, showTrench, showFeature, showArtifactNo, showLevelH, showSection, showBm, showGcp).joinToString("") { if (it) "1" else "0" }) }
    fun changeArtifactMode(m: ArtifactMode) { artifactMode = m; prefs.put("dig_artifact_mode", m.id); c.ui.ok("유물 번호: ${m.labelKo}") }
    fun changeBmTol(m: Double) { bmTolM = m.coerceIn(0.005, 0.5); prefs.put("dig_bm_tol", String.format(Locale.US, "%.3f", bmTolM)); c.ui.ok("기준점 확인 허용: ${String.format(Locale.US, "%.0f", bmTolM * 100)} cm") }
    fun changeProvisional(v: Boolean) { provisional = v; prefs.put("dig_provisional", if (v) "1" else "0"); c.ui.ok(if (v) "새 유구는 가번호(가-1)로 시작" else "새 유구는 번호(1호 수혈)로 시작") }
    fun changeVolumeKey(v: Boolean) { volumeKey = v; prefs.put("dig_volume_key", if (v) "1" else "0"); c.ui.ok(if (v) "볼륨 아래 키로 측점 · 화면이 켜져 있을 때만" else "볼륨 키 측점 끔") }
    fun changeScale(d: Int) { if (d in DrawingScale.CHOICES) { drawScale = d; prefs.put("dig_scale", d.toString()) } }
    fun setFeatureTypes(l: List<String>) {
        val clean = l.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { FeatureNaming.DEFAULT_TYPES }
        featureTypes.clear(); featureTypes.addAll(clean); prefs.put("dig_feature_types", FeatureNaming.encodeTypes(clean))
    }

    // ---------------- 작업별 기억 ----------------
    private fun key(k: String) = "dig_${k}_${c.projectName}"
    /** 작업을 열거나 바꾼 뒤: 고르던 종류·유구 되살리기 */
    fun restoreSelection() {
        crsUnlocked = false; stakeTarget = null; bmResult = null; closeArmed = false
        val raw = prefs.get(key("sel")) ?: return
        val f = raw.split('|')
        DigKind.fromId(f.getOrNull(0))?.let { applyKind(it) }
        featureId = f.getOrNull(1)?.toLongOrNull()
        LevelKind.fromId(f.getOrNull(2))?.let { level = it }
        f.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { bmName = it }
        AprilTagNaming.parse(f.getOrNull(4))?.let { tagId = it }
    }
    private fun saveSelection() { prefs.put(key("sel"), listOf(kind?.id ?: "", featureId?.toString() ?: "", level.id, bmName, tagId.toString()).joinToString("|")) }

    val poleConfirmedToday: Boolean get() = prefs.get(key("pole_day")) == today() + "|" + String.format(Locale.US, "%.1f", c.antenna.inputValueM) + "|" + c.antennaModelId
    fun confirmPole() { prefs.put(key("pole_day"), today() + "|" + String.format(Locale.US, "%.1f", c.antenna.inputValueM) + "|" + c.antennaModelId); c.ui.ok("폴 높이 ${String.format(Locale.US, "%.1f", c.antenna.inputValueM)} m 확인 · 오늘") }
    /** 오늘 기준점 확인: null = 아직, true = 통과, false = 차이 큼 */
    val bmToday: Boolean? get() = prefs.get(key("bm_day"))?.split('|')?.takeIf { it.getOrNull(0) == today() }?.getOrNull(1)?.let { it == "1" }

    // ---------------- 고르기 ----------------
    private fun applyKind(k: DigKind) {
        if (k.positionType !in c.types) c.setTypesList(c.types + k.positionType)
        val i = c.types.indexOf(k.positionType)
        if (i >= 0) c.typeIndex = i
    }

    /** 「무엇을 잴까요?」에서 고름. 같은 유구·레벨을 유지하면서 종류만 바꿀 수도 있다 */
    fun select(k: DigKind, feature: Long? = featureId, lv: LevelKind? = null) {
        val prevKind = kind; val prevFeature = featureId
        applyKind(k)
        val keepsFeature = k == DigKind.FEATURE || k == DigKind.ARTIFACT || k == DigKind.LEVEL || k == DigKind.GCP
        if (keepsFeature) featureId = feature?.takeIf { id -> c.arch.outlines.any { it.id == id } }
        if (k == DigKind.GCP && (prevKind != DigKind.GCP || prevFeature != featureId)) tagId = suggestTag(featureId)
        if (lv != null) level = lv
        closeArmed = false
        if (k == DigKind.STAKE) pickStake() else stakeTarget = null
        val ch = DigChoice(k, if (keepsFeature) featureId else null, if (k == DigKind.LEVEL) level else null)
        recent.remove(ch); recent.add(0, ch); while (recent.size > 3) recent.removeAt(recent.lastIndex)
        saveSelection()
    }
    fun chooseFeature(id: Long?) { featureId = id; closeArmed = false; if (kind == DigKind.GCP) tagId = suggestTag(id); saveSelection() }

    // ---------------- 0.1.36 사진 기준점(AprilTag) ----------------
    /** 이 유구(null = 유구 밖)에서 이미 잰 표적 번호 */
    fun tagsIn(fid: Long?): List<Int> = c.points.filter { it.digKind == DigKind.GCP.id && it.featureId == fid }.mapNotNull { AprilTagNaming.parse(it.digTag) }
    fun suggestTag(fid: Long?): Int = AprilTagNaming.suggest(tagsIn(fid), c.points.filter { it.digKind == DigKind.GCP.id }.mapNotNull { AprilTagNaming.parse(it.digTag) })
    /** −/+ 단추 */
    fun stepTag(delta: Int) { tagId = AprilTagNaming.clamp(tagId + delta); saveSelection() }
    fun setTag(id: Int) { tagId = AprilTagNaming.clamp(id); saveSelection() }
    /** 0.1.37 지도 단추 번호(1·2·3·4) */
    val quickTags: List<Int> get() = QUICK_TAGS
    /** 1–4 단추를 보이는가: 유구를 골랐거나 사진 기준점 모드 */
    val quickTagsVisible: Boolean get() = kind == DigKind.GCP || selectedFeature != null
    /** 단추 이름 아래 작은 글자: 이 유구 이름(유구 밖) */
    fun quickTagFeature(): String = featureName(featureId) ?: "유구 밖"

    /** 고른 번호를 이 유구에서 이미 쟀는가(다시 재면 내보내기는 마지막 값) */
    val tagAlreadyMeasured: Boolean get() = tagId in tagsIn(featureId)
    fun chooseLevel(lv: LevelKind) { level = lv; saveSelection() }
    /** 사용자 위치 종류(발굴 8종이 아닌 것) */
    fun selectCustomType(name: String) { val i = c.types.indexOf(name); if (i >= 0) { c.typeIndex = i; stakeTarget = null; saveSelection() } }

    // ---------------- 유구 ----------------
    fun featureOutlines(): List<Outline> = c.arch.outlines.filter { it.kind == "feature" || it.kind == "" }
    fun outlinesOf(kindId: String): List<Outline> = c.arch.outlines.filter { it.kind == kindId }
    fun featureName(id: Long?): String? = id?.let { i -> c.arch.outlines.firstOrNull { it.id == i }?.label }
    val selectedFeature: Outline? get() = featureId?.let { id -> c.arch.outlines.firstOrNull { it.id == id } }
    /** 다음에 「새 유구」를 누르면 붙을 이름 */
    fun nextFeatureName(type: String = featureTypes.firstOrNull() ?: "수혈", prov: Boolean = provisional): String =
        FeatureNaming.newName(featureOutlines().map { it.label }, type, prov)

    /** 새 유구 시작(이름 자동). 저장소가 없으면 null */
    suspend fun newFeature(type: String = featureTypes.firstOrNull() ?: "수혈", prov: Boolean = provisional): Long? {
        val name = nextFeatureName(type, prov)
        val o = c.arch.startFeature(name, "feature", if (prov) "" else type) ?: return null
        featureId = o.id; closeArmed = false
        if (kind == DigKind.GCP) tagId = suggestTag(o.id)
        saveSelection()
        return o.id
    }

    /** 유구 이름 바꾸기. 점(P-번호·좌표)은 그대로. 문제가 있으면 문구 */
    fun renameFeature(id: Long, newName: String, type: String): String? {
        val o = c.arch.outlines.firstOrNull { it.id == id } ?: return "유구를 찾지 못했어요"
        val pr = FeatureNaming.renameProblem(newName, featureOutlines().filter { it.id != id }.map { it.label })
        if (pr != null) return pr
        val n = newName.trim()
        if (n == o.label && type == o.featureType) return null
        c.arch.renameOutline(id, n, type)
        val pts = c.points.count { it.featureId == id || it.rowId in o.pointRowIds }
        c.ui.ok("${o.label} → $n · 점 ${pts}개 번호는 그대로")
        renameFor = null
        return null
    }

    // ---------------- 저장 직전: 발굴 속성 붙이기 ----------------
    private fun shapes(): List<FeatureShape> = featureOutlines().map { FeatureShape(it.id, it.label, it.closed, c.arch.verticesOf(it)) }

    /** 측점 결과에 종류·유구·유물 번호·레벨·꼬리표를 붙인다(위치 종류 글자로 종류를 정함) */
    fun decorate(p: SavedPoint): SavedPoint {
        val ne = NE(p.northing, p.easting)
        quickTag?.let { q ->
            return p.copy(type = DigKind.GCP.positionType, digKind = DigKind.GCP.id, featureId = selectedFeature?.id ?: DigGeometry.assign(ne, shapes(), null).featureId, digTag = q.toString())
        }
        val k = DigKind.fromPositionType(p.type) ?: return p.copy(digKind = "")
        return when (k) {
            DigKind.FEATURE -> p.copy(digKind = k.id, featureId = selectedFeature?.takeIf { !it.finished }?.id)
            DigKind.ARTIFACT -> {
                val a = DigGeometry.assign(ne, shapes(), featureId)
                val no = ArtifactNumbering.next(c.points.filter { it.artifactNo != null }.map { ArtifactNo(it.featureId, it.artifactNo!!) }, a.featureId, artifactMode)
                p.copy(digKind = k.id, featureId = a.featureId, artifactNo = no)
            }
            DigKind.LEVEL -> p.copy(digKind = k.id, featureId = DigGeometry.assign(ne, shapes(), featureId).featureId, levelKind = level.id)
            DigKind.SECTION -> p.copy(digKind = k.id, digTag = SectionLabels.next(c.points.filter { it.digKind == DigKind.SECTION.id }.map { it.digTag }))
            DigKind.BM -> p.copy(digKind = k.id, digTag = bmName)
            DigKind.STAKE -> p.copy(digKind = k.id, digTag = stakeTarget?.name ?: "")
            DigKind.TRENCH, DigKind.AREA -> p.copy(digKind = k.id)
            // 표적은 유구 둘레(경계 밖)에 놓으므로 고른 유구가 먼저, 없으면 닫힌 유구 안 자동, 그것도 없으면 유구 밖
            DigKind.GCP -> p.copy(digKind = k.id, featureId = selectedFeature?.id ?: DigGeometry.assign(ne, shapes(), null).featureId, digTag = tagId.toString())
        }
    }

    /** 디스크에 저장된 뒤: 윤곽 꼭짓점 잇기, 기준점 확인, 말뚝 다음 목표 */
    suspend fun afterSaved(saved: SavedPoint): SavedPoint {
        val k = DigKind.resolve(saved.digKind, saved.type) ?: return saved
        justClosed = null
        var out = saved
        when (k) {
            DigKind.FEATURE -> {
                var fid = saved.featureId
                if (fid == null) {
                    val sel = selectedFeature
                    if (sel != null && sel.finished) c.ui.toast("${sel.label}은(는) 면을 닫았어요 · 새 유구로 시작해요")
                    fid = newFeature()
                    if (fid != null) out = saved.copy(featureId = fid)
                }
                if (fid != null) { c.arch.attachVertexTo(fid, saved.rowId); c.store?.updateDig(saved.rowId, fid, null, "", "") }
            }
            DigKind.TRENCH, DigKind.AREA -> {
                val kid = k.id
                var o = outlinesOf(kid).lastOrNull { !it.finished }
                if (o == null) o = c.arch.startFeature(if (k == DigKind.TRENCH) "Tr.${outlinesOf(kid).size + 1}" else if (outlinesOf(kid).isEmpty()) "조사구역" else "조사구역 ${outlinesOf(kid).size + 1}", kid, "")
                if (o != null) { c.arch.attachVertexTo(o.id, saved.rowId); out = saved.copy(featureId = o.id); c.store?.updateDig(saved.rowId, o.id, null, "", "") }
            }
            DigKind.BM -> evaluateBm(saved)
            DigKind.STAKE -> pickStake()
            DigKind.GCP -> {
                val id = AprilTagNaming.parse(saved.digTag)
                // 1–4 단추로 잰 점은 −/+ 번호를 건드리지 않음
                if (id != null && quickTag == null) { tagId = AprilTagNaming.nextAfter(id, tagsIn(saved.featureId)); saveSelection() }
                quickTag = null
            }
            else -> {}
        }
        return out
    }

    private fun evaluateBm(p: SavedPoint) {
        val known = c.points.firstOrNull { it.rowId != p.rowId && it.name != p.name && it.digKind == DigKind.BM.id && it.digTag == p.digTag }
        if (known == null) {
            prefs.put(key("bm_day"), today() + "|1|ref")
            bmResult = null
            c.message = "${p.digTag} 기준값으로 저장했어요 · 다음부터 이 값과 비교해요"
            return
        }
        val r = BmCheck.evaluate(NEH(known.northing, known.easting, known.groundMslM ?: known.groundHeightM), NEH(p.northing, p.easting, p.groundMslM ?: p.groundHeightM), bmTolM)
        prefs.put(key("bm_day"), today() + "|" + (if (r.pass) "1" else "0") + "|" + BmCheck.deltaText(r))
        bmResult = p.digTag to r
        if (r.pass) c.ui.ok(BmCheck.verdictText(p.digTag, r)) else c.ui.fail(BmCheck.verdictText(p.digTag, r))
    }

    // ---------------- 면 닫기 · 되돌리기 ----------------
    /** 닫으려는 유구(지금 고른, 아직 열린 유구) */
    val closable: Outline? get() = selectedFeature?.takeIf { !it.finished && it.pointRowIds.size >= 3 }
    fun closePreview(): String? {
        val o = closable ?: return null
        val s = kr.archsurvey.arch.OutlineMath.summarize(c.arch.verticesOf(o), true)
        return "${s.vertices}점 · ${String.format(Locale.US, "%.2f", s.areaSqm ?: 0.0)}㎡ · 둘레 ${String.format(Locale.US, "%.2f", s.perimeterM)} m"
    }
    /** 「면 닫기」 단추: 한 번 = 미리보기, 측점 단추(면 닫기 확인) = 닫기 */
    fun armClose() {
        if (closeArmed) { closeArmed = false; return }
        val o = selectedFeature
        if (o == null || o.finished) { c.message = "닫을 유구가 없어요 · 유구 경계를 먼저 재세요"; return }
        if (o.pointRowIds.size < 3) { c.message = "면으로 닫으려면 경계점이 3개 이상 필요해요 (지금 ${o.pointRowIds.size}개)"; return }
        closeArmed = true
    }
    fun confirmClose(): Boolean {
        val o = closable ?: run { closeArmed = false; return false }
        closeArmed = false
        val ok = c.arch.finishOutlineId(o.id, true)
        if (ok) { justClosed = o.id; c.ui.ok(c.arch.lastSummary ?: "${o.label} 면을 닫았어요") }
        return ok
    }
    /** 되돌리기가 「면 닫기 취소」인가 */
    val undoIsReopen: Boolean get() = justClosed != null
    fun undoClose() {
        val id = justClosed ?: return
        justClosed = null
        c.arch.reopenOutline(id)
        featureId = id
        c.ui.toast("${featureName(id) ?: "유구"} 면 닫기를 되돌렸어요 · 이어서 잴 수 있어요")
    }
    /** 점을 되돌렸을 때 */
    fun onPointUndone() { justClosed = null; bmResult = null }

    // ---------------- 말뚝 찾아가기 ----------------
    fun stakeNodes(): List<StakeNodes.Node> = c.arch.activeGrid?.let { StakeNodes.list(it) } ?: emptyList()
    fun pickStake() {
        val done = c.points.filter { it.digKind == DigKind.STAKE.id }.map { it.digTag }.toSet()
        stakeTarget = StakeNodes.nearestOpen(stakeNodes(), done, c.currentNE())
    }
    fun stepStake(delta: Int) {
        val n = stakeNodes(); if (n.isEmpty()) return
        val i = n.indexOfFirst { it.name == stakeTarget?.name }.coerceAtLeast(0)
        stakeTarget = n[(i + delta).mod(n.size)]
    }
    fun stakeGuide(): StakeGuide.G? { val t = stakeTarget ?: return null; val cur = c.currentNE() ?: return null; return StakeGuide.of(cur, t.ne) }

    // ---------------- 화면 글자 ----------------
    /** 「지금 재는 것」 굵은 줄: 「유구 경계 · 2호 수혈」 */
    fun nowLine(): Pair<String, String?> {
        val k = kind ?: return c.typeName to null
        return when (k) {
            DigKind.FEATURE -> k.labelKo to (selectedFeature?.takeIf { !it.finished }?.label ?: "새 유구 ${nextFeatureName()}")
            DigKind.ARTIFACT -> k.labelKo to ((featureName(featureId)?.let { "$it · " } ?: "") + "다음 ${nextArtifactNo()}")
            DigKind.LEVEL -> "${k.labelKo} · ${level.labelKo}" to featureName(featureId)
            DigKind.SECTION -> k.labelKo to SectionLabels.next(c.points.filter { it.digKind == DigKind.SECTION.id }.map { it.digTag })
            DigKind.BM -> k.labelKo to bmName
            DigKind.STAKE -> k.labelKo to (stakeTarget?.name ?: "그리드 없음")
            DigKind.TRENCH -> k.labelKo to (outlinesOf("trench").lastOrNull { !it.finished }?.label ?: "Tr.${outlinesOf("trench").size + 1}")
            DigKind.AREA -> k.labelKo to (outlinesOf("area").lastOrNull { !it.finished }?.label ?: "조사구역")
            DigKind.GCP -> "${k.labelKo} · #$tagId" to (featureName(featureId) ?: "유구 고르기")
        }
    }
    fun nextArtifactNo(): Int = ArtifactNumbering.next(c.points.filter { it.artifactNo != null }.map { ArtifactNo(it.featureId, it.artifactNo!!) }, featureId, artifactMode)

    /** 측점 단추 아랫줄 */
    fun measureSub(): String {
        val k = kind ?: return c.nextName
        return when (k) {
            DigKind.FEATURE -> selectedFeature?.takeIf { !it.finished }?.let { "${it.label} · ${it.pointRowIds.size + 1}번째 점" } ?: "${nextFeatureName()} 첫 점"
            DigKind.ARTIFACT -> "유물 ${nextArtifactNo()}"
            DigKind.LEVEL -> level.labelKo + (featureName(featureId)?.let { " · $it" } ?: "")
            DigKind.SECTION -> SectionLabels.next(c.points.filter { it.digKind == DigKind.SECTION.id }.map { it.digTag }) + " 끝점"
            DigKind.BM -> "$bmName 확인"
            DigKind.STAKE -> stakeTarget?.let { "${it.name} · ${String.format(Locale.US, "%.0f", StakeGuide.ARRIVE_M * 100)} cm 안이면" } ?: c.nextName
            DigKind.GCP -> AprilTagNaming.name(tagId) + (featureName(featureId)?.let { " · $it" } ?: " · 유구 밖") + (if (tagAlreadyMeasured) " · 다시 재기" else "")
            else -> c.nextName
        }
    }

    /** 점 하나의 「무엇」 한 줄: 「유물 13 · 2호 수혈 안(자동) · B-3 칸」 */
    fun whatLine(p: SavedPoint): String {
        val k = DigKind.resolve(p.digKind, p.type)
        val parts = ArrayList<String>()
        when (k) {
            DigKind.ARTIFACT -> parts += "유물 ${p.artifactNo ?: "—"}"
            DigKind.LEVEL -> parts += "레벨 · " + (LevelKind.fromId(p.levelKind)?.labelKo ?: "검출면")
            DigKind.SECTION -> parts += "토층 ${p.digTag}"
            DigKind.BM -> parts += "기준점 ${p.digTag}"
            DigKind.STAKE -> parts += "말뚝 ${p.digTag}"
            DigKind.GCP -> parts += AprilTagNaming.parse(p.digTag)?.let { AprilTagNaming.name(it) } ?: "사진 기준점"
            null -> parts += p.type
            else -> parts += k.labelKo
        }
        featureName(p.featureId)?.let { n ->
            val inside = (k == DigKind.ARTIFACT || k == DigKind.LEVEL) && featureOutlines().any { it.id == p.featureId && it.closed && DigGeometry.inside(NE(p.northing, p.easting), c.arch.verticesOf(it)) }
            parts += if (inside) "$n 안(자동)" else n
        }
        if (p.cellName.isNotBlank()) parts += "${p.cellName} 칸"
        if (k == DigKind.FEATURE) c.arch.outlines.firstOrNull { it.id == p.featureId }?.let { o -> val i = o.pointRowIds.indexOf(p.rowId); if (i >= 0) parts += "${i + 1}번째 점" }
        return parts.joinToString(" · ")
    }

    /** 검출면 기준 높이(이 유구) */
    fun detectionRef(fid: Long?): Double? {
        if (fid == null) return null
        val pts = c.points.filter { it.featureId == fid }
        val det = pts.filter { it.digKind == DigKind.LEVEL.id && it.levelKind == LevelKind.DETECT.id }.mapNotNull { it.groundMslM ?: it.groundHeightM }
        val o = c.arch.outlines.firstOrNull { it.id == fid }
        val bnd = c.points.filter { it.rowId in (o?.pointRowIds ?: emptyList()) }.mapNotNull { it.groundMslM ?: it.groundHeightM }
        return DigDepth.detectionRef(det, bnd)
    }

    /** 잰 직후 한 장 */
    fun ledger(p: SavedPoint): SavedLedger {
        val k = DigKind.resolve(p.digKind, p.type)
        val h = p.groundMslM ?: p.groundHeightM
        val title = if (k == DigKind.ARTIFACT && p.artifactNo != null) "유물 ${p.artifactNo}"
            else if (k == DigKind.GCP) AprilTagNaming.parse(p.digTag)?.let { "#$it" } ?: p.name else p.name
        val cells = ArrayList<LedgerCell>()
        cells += LedgerCell("해발", LedgerText.m3(h), "m")
        if (k == DigKind.ARTIFACT || k == DigKind.LEVEL) {
            val rel = DigDepth.relative(detectionRef(p.featureId), h)
            if (rel != null) cells += LedgerCell(if (k == DigKind.LEVEL) "깊이" else "검출면에서", if (k == DigKind.LEVEL) LedgerText.m3(-rel).removePrefix("-") else LedgerText.signed2(rel), "m")
            if (k == DigKind.LEVEL && p.levelKind == LevelKind.FLOOR.id) {
                val floors = c.points.filter { it.featureId == p.featureId && it.digKind == DigKind.LEVEL.id && it.levelKind == LevelKind.FLOOR.id }.mapNotNull { it.groundMslM ?: it.groundHeightM }
                DigDepth.mean(floors)?.let { cells += LedgerCell("바닥 평균", LedgerText.m3(it), "m") }
            }
        }
        cells += LedgerCell("수평 · 수직", "${LedgerText.cm1(p.sigmaHM)}·${LedgerText.cm1(p.sigmaU)}", "cm")
        if (cells.size < 4) cells += LedgerCell("폴", String.format(Locale.US, "%.1f", p.antenna.inputM), "m")
        val chip = when (kr.archsurvey.ui.PointCaution.displayStatus(p)) { SurveyStatus.FIXED -> "고정"; SurveyStatus.CAUTION -> "주의"; SurveyStatus.UNAVAILABLE -> "불가" }
        return SavedLedger(title, chip, (if (title != p.name) "${p.name} · " else "") + whatLine(p), cells.take(4))
    }

    /** 측량한 것 › 유구 카드 */
    fun featureCards(): List<FeatureCard> = featureOutlines().map { o ->
        val members = c.points.filter { it.featureId == o.id || it.rowId in o.pointRowIds }
        val verts = c.arch.verticesOf(o)
        val sum = if (verts.size >= 2) kr.archsurvey.arch.OutlineMath.summarize(verts, o.closed) else null
        val ref = detectionRef(o.id)
        val floors = members.filter { it.digKind == DigKind.LEVEL.id && it.levelKind == LevelKind.FLOOR.id }.mapNotNull { it.groundMslM ?: it.groundHeightM }
        val floorMean = DigDepth.mean(floors)
        FeatureCard(o.id, o.label, o.featureType, o.kind, !o.finished, o.closed, o.pointRowIds.size,
            members.mapNotNull { it.artifactNo }.sorted(), members.count { it.digKind == DigKind.LEVEL.id },
            sum?.areaSqm?.takeIf { o.closed }, sum?.perimeterM, DigDepth.depthBelow(ref, floorMean), floorMean,
            members.count { kr.archsurvey.ui.PointCaution.displayStatus(it) == SurveyStatus.FIXED }, members.size, FeatureNaming.history(o.history),
            members.filter { it.digKind == DigKind.GCP.id }.mapNotNull { AprilTagNaming.parse(it.digTag) }.distinct().sorted())
    }

    /** 오늘 준비 5단계 */
    fun prepSteps(ntripNowMs: Long = c.nowMs()): List<PrepStep> {
        val cc = c.connection
        val ntripOn = cc?.form?.enabled == true
        val nt = cc?.ntrip?.value
        val receiving = nt?.let { it.state == kr.archsurvey.ntrip.NtripState.RECEIVING && it.freshness(ntripNowMs) != kr.archsurvey.engine.RtcmFreshness.LOST } ?: false
        val model = if (c.antennaModelId == "TRIMBLE_R10") "R10" else "SP60"
        val receiverLive = !c.gv.noReceiver && c.gv.link.let { it == kr.archsurvey.device.LinkState.STREAMING || it == kr.archsurvey.device.LinkState.STALE }
        return TodayPrep.steps(PrepInput(
            receiverLive = receiverLive, receiverLabel = model, ntripEnabled = ntripOn, ntripReceiving = receiving, ntripLabel = "VRS",
            poleM = c.antenna.inputValueM, modelShort = model, poleConfirmedToday = poleConfirmedToday,
            epsg = c.zone.epsg, heightLabel = c.geoid.shortKo, crsReady = true,
            bmName = bmName, bmCheckedToday = bmToday != null, bmPassedToday = bmToday, bmKnown = c.points.any { it.digKind == DigKind.BM.id && it.digTag == bmName },
        ))
    }

    // ---------------- 지도 기호 ----------------
    fun outlineVisible(kind: String): Boolean = when (kind) { "trench" -> showTrench; "area" -> showArea; else -> showFeature }

    /** 발굴 도면 기호로 점 표시. 꺼 둔 레이어의 점은 빼고, 유구 경계점은 윤곽이 그린다 */
    fun mapMarkers(pts: List<SavedPoint>, at: (SavedPoint) -> kr.archsurvey.map.LocalXY): List<kr.archsurvey.map.MapMarker> = pts.mapNotNull { p ->
        val flagged = p.cautionFlag && kr.archsurvey.ui.PointCaution.displayStatus(p) != SurveyStatus.FIXED
        val k = DigKind.resolve(p.digKind, p.type)
        val inOutline = p.rowId > 0 && c.arch.outlines.any { p.rowId in it.pointRowIds }
        when (k) {
            DigKind.ARTIFACT -> if (!showArtifactNo && !showFeature) null else kr.archsurvey.map.MapMarker(p.name, at(p), flagged, kr.archsurvey.map.MapSym.ARTIFACT, p.artifactNo?.toString().takeIf { showArtifactNo })
            DigKind.LEVEL -> kr.archsurvey.map.MapMarker(p.name, at(p), flagged, kr.archsurvey.map.MapSym.LEVEL, if (showLevelH) (p.groundMslM ?: p.groundHeightM)?.let { String.format(Locale.US, "%.2f", it) } else null)
            DigKind.BM -> if (!showBm) null else kr.archsurvey.map.MapMarker(p.name, at(p), flagged, kr.archsurvey.map.MapSym.BM, p.digTag.ifBlank { p.name })
            DigKind.STAKE -> if (!showGrid) null else kr.archsurvey.map.MapMarker(p.name, at(p), flagged, kr.archsurvey.map.MapSym.STAKE, p.digTag.substringBefore(' ').ifBlank { null })
            DigKind.GCP -> if (!showGcp) null else kr.archsurvey.map.MapMarker(p.name, at(p), flagged, kr.archsurvey.map.MapSym.TAG, AprilTagNaming.parse(p.digTag)?.let { "#$it" })
            DigKind.SECTION -> if (!showSection) null else kr.archsurvey.map.MapMarker(p.name, at(p), flagged, kr.archsurvey.map.MapSym.SECTION, p.digTag.ifBlank { null })
            DigKind.FEATURE, DigKind.TRENCH, DigKind.AREA -> if (inOutline) { if (outlineVisible(when (k) { DigKind.TRENCH -> "trench"; DigKind.AREA -> "area"; else -> "feature" })) kr.archsurvey.map.MapMarker(p.name, at(p), flagged, kr.archsurvey.map.MapSym.VERTEX, null) else null }
                else kr.archsurvey.map.MapMarker(p.name, at(p), flagged)
            null -> kr.archsurvey.map.MapMarker(p.name, at(p), flagged)
        }
    }

    /** 토층 단면선(양 끝이 다 있는 것만) */
    fun sectionLines(at: (SavedPoint) -> kr.archsurvey.map.LocalXY): List<kr.archsurvey.map.MapSection> {
        val sec = c.points.filter { DigKind.resolve(it.digKind, it.type) == DigKind.SECTION && it.digTag.isNotBlank() }
        return SectionLabels.completeLines(sec.map { it.digTag }).map { line ->
            kr.archsurvey.map.MapSection(SectionLabels.lineName(line) + " 토층", at(sec.last { it.digTag == line }), at(sec.last { it.digTag == line + SectionLabels.PRIME }))
        }
    }

    /** X12 원인 한 줄(고정이 아닐 때만). [ntripNowMs] = NTRIP 상태와 같은 시계 */
    fun causeLine(ntripNowMs: Long): String? {
        val g = c.gv
        val cc = c.connection
        val nt = cc?.ntrip?.value
        val enabled = cc?.form?.enabled == true
        return CauseLine.of(CauseInput(
            noReceiver = g.noReceiver, fixed = g.displayStatus == SurveyStatus.FIXED, floatOrWorse = g.displayStatus != SurveyStatus.FIXED,
            ntripEnabled = enabled && nt != null, ntripReceiving = nt?.state == kr.archsurvey.ntrip.NtripState.RECEIVING,
            ntripRetrying = nt?.state == kr.archsurvey.ntrip.NtripState.RECONNECTING || nt?.state == kr.archsurvey.ntrip.NtripState.CONNECTING,
            rtcmAgeSec = nt?.rtcmAgeMs(ntripNowMs)?.let { it / 1000.0 }, correctionAgeSec = g.correctionAgeSec, satellites = g.satellites,
        ))
    }

    /** 오늘 잰 점 수 */
    fun todayCount(): Int { val d = today(); return c.points.count { DayKey.of(it.measuredAtMs) == d } }

    /** X11: 점이 있으면 좌표계·지역·지오이드 잠금 */
    val crsLocked: Boolean get() = CrsLock.locked(c.points.size, crsUnlocked)
}

/** 0.1.37 지도 위 사진 기준점 단추 */
val QUICK_TAGS = listOf(1, 2, 3, 4)
