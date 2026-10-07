package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kr.archsurvey.arch.ArchTypes
import kr.archsurvey.arch.CellKind
import kr.archsurvey.arch.DuplicateCheck
import kr.archsurvey.arch.GridMath
import kr.archsurvey.core.*
import kr.archsurvey.device.GnssSource
import kr.archsurvey.map.*
import kotlin.math.atan2
import kotlin.math.hypot

data class GnssView(
    val status: StatusResult = StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.NOT_CONNECTED, null),
    val quality: FixQuality = FixQuality.NONE,
    val satellites: Int = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    /** 수직 정확도 σ(m). GST 가 없으면 null */
    val sigmaVM: Double? = null,
    /** 수평 위치 정밀도 감소율. 없으면 null */
    val hdop: Double? = null,
    /** 보이는 위성 수(GSV). 없으면 null */
    val satsInView: Int? = null,
    /** GGA 안테나 고도(MSL, m). 없으면 null */
    val altM: Double? = null,
    /** 마지막 GGA 받은 시각(수신 시계 ms). 0 = 없음. 위성 수가 오래됐는지 판단 */
    val ggaAtMs: Long = 0L,
    /** GSA 의 PDOP. 없으면 null (0.1.11 상단 머리글) */
    val pdop: Double? = null,
    /** GGA 보정 연령(초, 받은 순간 값). 없으면 null */
    val correctionAgeSec: Double? = null,
    /** 0.1.11 GNSS 정보: VDOP(GSA)·지오이드 분리(GGA, 타원체고 = 고도 + 분리)·기준국 ID */
    val vdop: Double? = null,
    val geoidSepM: Double? = null,
    val baseId: String? = null,
    /** 0.1.11 SkyPlot: GSV 위성 상세(받지 못하면 빈 목록) */
    val sats: List<kr.archsurvey.core.SatInfo> = emptyList(),
    /** 0.1.30 T24: 수신기 링크 상태(머리글 「수신기 없음」·「수신기 끊김」·「연결 중」 구분용) */
    val link: kr.archsurvey.device.LinkState = kr.archsurvey.device.LinkState.IDLE,
    /** 0.1.30 T23-c: 엄격 모드(표시만 — 엄격 + σ 없음은 막힘이라 고정 색으로 보이지 않게) */
    val strictMode: Boolean = false,
) {
    /** 0.1.30 T24: 수신기 없음(미연결) 또는 끊김 — 측점 불가, 좌표·정밀도 「—」 */
    val noReceiver: Boolean get() = status.reason == StatusReason.NOT_CONNECTED || status.reason == StatusReason.LINK_DOWN
    /**
     * 0.1.30 T23-c: **표시 전용** 상태. RTK 고정 + 보정 연령 기준 안(= 판정 NO_SIGMA)이면 σ 가 없어도 ‘측량 가능’ 색으로 보인다.
     * 저장 판정은 계속 [status](NO_SIGMA = 주의)로 한다 — 엄격 모드는 막고, 기본 모드는 주의 표시로 저장(규칙 그대로).
     */
    val displayStatus: SurveyStatus get() = if (sigmaMissingOnFix && !strictMode) SurveyStatus.FIXED else status.status
    /** 고정·보정 양호인데 GST(σ)만 없음 → H/V 옆 「σ 없음」 */
    val sigmaMissingOnFix: Boolean get() = status.status == SurveyStatus.CAUTION && status.reason == StatusReason.NO_SIGMA
    /** 타원체고(m) = 정표고(MSL) + 지오이드 분리. 둘 중 하나라도 없으면 null */
    val ellipsoidalM: Double? get() = if (altM != null && geoidSepM != null) altM + geoidSepM else null
}

/** 화면이 보는 측점. 저장소([PointStore])가 있으면 디스크에도 남는다. */
data class SavedPoint(
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val northing: Double,
    val easting: Double,
    val crsEpsg: Int,
    /** 지면 높이(타원체고) = 수신기 타원체고 − 안테나 위상중심 높이. 계산 불가면 null */
    val groundHeightM: Double?,
    val sigmaHM: Double?,
    val status: SurveyStatus,
    val cautionFlag: Boolean,
    val epochs: Int,
    val antenna: AntennaRecord,
    val fixQuality: Int = 0,
    val satellites: Int = 0,
    val measuredAtMs: Long = 0L,
    val rowId: Long = 0L,
    val memo: String = "",
    /** 3-D 좌표·표고: 안테나 위상중심(APC) 기준 값과 지오이드. 지면값은 APC 값 − 안테나 높이 */
    val apcEllipsoidalM: Double? = null,
    val apcMslM: Double? = null,
    val geoidSepM: Double? = null,
    val groundMslM: Double? = null,
    /** 0.1.12: groundMslM 을 낸 지오이드 모델 id (RECEIVER/EGM96/NONE). 옛 점은 "" */
    val heightModel: String = "",
    val hdop: Double? = null,
    val sigmaE: Double? = null,
    val sigmaN: Double? = null,
    val sigmaU: Double? = null,
    val correctionAgeSec: Double? = null,
    val baseStationId: String? = null,
    /** 평균 측정 메타(0.1.7): 평균 시작·종료 벽시계(ms), 보정 연령 최대·최소(초), 고정·Float 표본 수. 옛 점은 null/0 = 기록 없음 */
    val avgStartedAtMs: Long? = null,
    val avgEndedAtMs: Long? = null,
    val maxCorrectionAgeSec: Double? = null,
    val minCorrectionAgeSec: Double? = null,
    val fixedEpochs: Int = 0,
    val floatEpochs: Int = 0,
    /** 측정하는 동안 폰 센서로 잰 폴 기울기(°) 평균·최대(0.1.8). 센서 없음·꺼짐·옛 점은 null. 좌표 보정에는 쓰지 않음 */
    val tiltDeg: Double? = null,
    val tiltMaxDeg: Double? = null,
    /** 평균에 쓴 각 측정값(0.1.9). 지면 높이 기준. 옛 점은 비어 있음 */
    val samples: List<EpochSample> = emptyList(),
    /** 고고학: 발굴 구역·그리드·칸 이름(예: "A-3", "A-3 / B-3 경계 근처", "그리드 밖")·칸 안 위치(m) */
    val areaId: Long? = null,
    val gridId: Long? = null,
    val cellName: String = "",
    val cellU: Double? = null,
    val cellV: Double? = null,
)

enum class DupChoice { RESURVEY, NEW_POINT, DISCARD }

/** 중복 점 경고: 방금 잰 값 [fresh] 가 기존 점 [existing] 에서 [distM] m 안에 있다 */
/** 평균에 쓴 한 번의 측정값(TM 북·동, 지면 높이) */
data class EpochSample(val n: Double, val e: Double, val h: Double?) {
    companion object {
        fun encode(l: List<EpochSample>): String? = if (l.isEmpty()) null else l.joinToString(";") { "%.4f,%.4f,%s".format(java.util.Locale.US, it.n, it.e, it.h?.let { h -> "%.4f".format(java.util.Locale.US, h) } ?: "") }
        fun decode(s: String?): List<EpochSample> = s?.split(";")?.mapNotNull { r ->
            val f = r.split(","); val n = f.getOrNull(0)?.toDoubleOrNull(); val e = f.getOrNull(1)?.toDoubleOrNull()
            if (n == null || e == null) null else EpochSample(n, e, f.getOrNull(2)?.toDoubleOrNull())
        } ?: emptyList()
    }
}

data class DupPrompt(val fresh: SavedPoint, val existing: SavedPoint, val distM: Double, val worst: SurveyStatus)

/** 측점 저장소. Android 의존 없음(시험용 가짜 구현 가능). 되돌리기는 숨김(소프트 삭제)이라 번호를 다시 쓰지 않는다. */
interface PointStore {
    suspend fun loadAll(): List<SavedPoint>
    /** 지금까지 쓴 가장 큰 번호(숨긴 점 포함). 없으면 0 */
    suspend fun maxSeq(): Int
    suspend fun save(p: SavedPoint): Long
    suspend fun hideLast()
    /** 위치 종류·메모 수정 (좌표는 바꾸지 않는다) */
    suspend fun updateMeta(rowId: Long, type: String, memo: String) {}
    /** 재측량: 점 번호·종류·메모·사진은 두고 측정값만 새 값으로 바꾸며, 이전 측정값은 이력으로 남긴다 */
    suspend fun resurvey(rowId: Long, updated: SavedPoint) {}
    /** 방금 한 재측량을 되돌린다: 가장 최근 이력을 지우고 [old] 값으로 복원 */
    suspend fun undoResurvey(rowId: Long, old: SavedPoint) {}
}

/**
 * 측량 화면의 상태·동작. [GnssSource] 하나만 본다(가짜/실제 교체 가능).
 * Android 의존 없음 → JVM 단위 테스트 가능.
 */
class SurveyController(
    val source: GnssSource,
    val antennaDb: Map<String, AntennaModel>,
    private val scope: CoroutineScope,
    projection: LocalProjection = LocalProjection(36.5, 127.0),
    /** true = 처음 받은 실제 위치(또는 마지막 점)를 지도 그림의 기준으로 삼는다 */
    private val anchorOnFirstFix: Boolean = false,
    zone: KoreanTmZone = KoreanTmZone.CENTRAL,
    /** 한 점에 평균하는 측정(에폭) 횟수 기본값. 설정에서 바꾼다(0.1.9 기본 3) */
    epochsPerPoint: Int = 3,
    private val store: PointStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 한 점의 측정 횟수(1~30). 설정 화면에서 바꾸면 다음 측점부터 */
    var epochs by mutableIntStateOf(epochsPerPoint.coerceIn(1, 30))
    /** 저장 알림·확인·선택 창 */
    val ui = FeedbackHub()
    /** 지금 측정에서 모은 횟수(진행 창 ‘2/3’) */
    var measureCount by mutableIntStateOf(0); private set
    var measureStartedAtMs by mutableStateOf(0L); private set

    /** 화면 작업에서 예외가 나 앱 대신 기록했을 때: 측정 중이면 대기로 되돌리고 안내 */
    fun recoverFromError() {
        if (phase == Phase.ACQUIRING) { acquireJob?.cancel(); phase = Phase.IDLE; progress = 0f }
        message = "오류가 생겨 작업을 멈췄어요. 설정 > 오류 기록에서 내용을 볼 수 있어요"
    }

    /** 지도 그림용 기준 위치. 측량 좌표 계산에는 쓰지 않는다 */
    var projection by mutableStateOf(projection)
    private var anchored = false
    /** 투영 원점(EPSG:5185~5188). 이미 저장한 점은 저장 당시 값을 유지한다 */
    var zone by mutableStateOf(zone)
    /** 0.1.12 작업별 지오이드(표고) 모델. 새 점부터 적용 */
    var geoid by mutableStateOf(GeoidModel.RECEIVER)
    /** 0.1.12 작업별 배경맵(V월드). NONE = 점만 */
    var mapBase by mutableStateOf(kr.archsurvey.map.MapBase.SATELLITE_CADASTRAL)
    /** V월드 키·도메인·지적 스타일(선만) — 설정 저장소(비면 제품 기본 키). 키 원문은 로그·화면에 내지 않는다 */
    var mapKeyProvider: () -> kr.archsurvey.map.VWorldAccess = { kr.archsurvey.map.VWorldAccess("", "") }
    /** 작업 설정(좌표계·지오이드·배경맵·안테나·측정 횟수)이 바뀌면 부른다 → 앱은 지금 작업 이름으로 저장 */
    var onJobConfigChanged: ((JobConfig) -> Unit)? = null
    fun jobConfig() = JobConfig(zone.epsg, geoid, mapBase, antennaModelId, antenna.inputValueM, antenna.refPointId, epochs, poleUserSet = antenna.source == AntennaSource.USER)
    fun jobConfigChanged() { onJobConfigChanged?.invoke(jobConfig()) }
    /** 저장된 작업 설정을 화면 상태에 적용(작업 열기·앱 시작). 저장 콜백은 부르지 않는다 */
    fun applyJobConfig(cfg: JobConfig) {
        clearTrail() // 0.1.30 T26: 작업이 바뀌면 이전 꼬리를 남기지 않음
        zone = KoreanTmZone.fromEpsg(cfg.epsg); geoid = cfg.geoid; mapBase = cfg.mapBase
        if (antennaDb.containsKey(cfg.antennaModelId)) antennaModelId = cfg.antennaModelId
        val m = antennaModel
        val ref = if (m.refPoints.any { it.id == cfg.refPointId }) cfg.refPointId else m.defaultRefPoint
        antenna = antenna.copy(inputValueM = AntennaMath.snapPole(cfg.poleM), refPointId = ref, tapeOn = false,
            source = if (cfg.poleUserSet) AntennaSource.USER else if (antenna.source == AntennaSource.USER) AntennaSource.DEFAULT else antenna.source)
        epochs = cfg.epochs.coerceIn(1, 30)
        // 0.1.29 UX-0129-1: 기존 작업은 저장된 폴 높이 그대로(2.0 m 포함). 기기 기본값은 새로 설치·새 작업·기기 바꿀 때만
    }
    /**
     * 0.1.29 T19 기기별 폴 높이 기본값(SP60 1.8 m · R10 1.6 m). 사용자가 정한 값(USER)·수신기 보고값(RECEIVER)은 그대로.
     * 저장은 지금처럼 작업별, 출처는 DEFAULT 유지. 바뀌었으면 true
     */
    private fun applyDevicePoleDefault(): Boolean {
        if (antenna.source != AntennaSource.DEFAULT) return false
        val d = kr.archsurvey.core.DevicePoleDefaults.forModel(antennaModelId) ?: return false
        if (kotlin.math.abs(antenna.inputValueM - d) < 1e-9) return false
        antenna = antenna.copy(inputValueM = d)
        return true
    }
    /** 이 작업에 다른 좌표계로 저장된 점이 있으면 이유 문구(바꾸지 않음). SmartTopo 는 전 점을 변환하지만 여기선 원본 보존을 위해 막는다 */
    fun zoneChangeProblem(z: KoreanTmZone): String? {
        val other = points.count { it.crsEpsg != z.epsg }
        return if (other > 0) "이 작업에 EPSG:${points.first().crsEpsg} 로 저장된 점이 ${other}개 있어 좌표계를 바꿀 수 없어요. 새 작업을 만들어 고르세요." else null
    }
    fun changeMapBase(b: kr.archsurvey.map.MapBase) { mapBase = b; jobConfigChanged() }
    fun changeGeoid(g: GeoidModel) { geoid = g; jobConfigChanged() }

    /** 위치 종류 목록(편집 가능, 이름 8자 이하). 기본 = 고고학 3종(유구 경계점·유물 출토 위치·그리드 모서리) + 기준점·레벨점·기타 */
    val types = mutableStateListOf<String>().apply { addAll(kr.archsurvey.arch.ArchTypes.seed) }
    /** 종류 목록이 바뀌면 저장 */
    var onTypesChanged: ((List<String>) -> Unit)? = null
    /** 고고학: 발굴 구역·그리드·외곽 */
    var arch: kr.archsurvey.arch.ArchController = kr.archsurvey.arch.ArchController(this, null, scope)
    /** 중복 점 경고 시트 */
    var dupPrompt by mutableStateOf<DupPrompt?>(null)
    /** 전자 기포(폰 센서). null = 쓰지 않음(시험 기본) */
    var tilt: TiltController? = null
    /** 기울기 확인 창: 측점 누를 때의 각도(°). null = 닫힘 */
    var tiltPrompt by mutableStateOf<Double?>(null)
    private var tiltBypass = false
    /** 0.1.26 주의(CAUTION) 확인 후 재진입 시 경고 창 생략 */
    private var qualityBypass = false
    /** 재측량 대기 중인 점: 다음 측점이 이 점의 새 값이 된다 */
    var resurveyTarget by mutableStateOf<SavedPoint?>(null)
    private var resultName: String? = null
    private var resurveyUndo: SavedPoint? = null

    fun setTypesList(list: List<String>) {
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { kr.archsurvey.arch.ArchTypes.seed }
        val keep = types.getOrNull(typeIndex)
        types.clear(); types.addAll(clean)
        typeIndex = keep?.let { clean.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        onTypesChanged?.invoke(clean)
    }
    /** 새 종류 추가. 문제가 있으면 문구를 돌려준다 */
    fun addType(name: String): String? { val pr = kr.archsurvey.arch.ArchTypes.problem(name, types); if (pr == null) setTypesList(types + name.trim()); return pr }
    fun renameType(old: String, name: String): String? {
        if (old == name.trim()) return null
        val pr = kr.archsurvey.arch.ArchTypes.problem(name, types - old); if (pr != null) return pr
        setTypesList(types.map { if (it == old) name.trim() else it }); return null
    }
    fun removeType(name: String): String? { if (types.size <= 1) return "종류는 1개 이상 있어야 해요"; setTypesList(types - name); return null }
    fun moveType(name: String, delta: Int) {
        val i = types.indexOf(name); val j = i + delta
        if (i < 0 || j < 0 || j >= types.size) return
        val l = types.toMutableList(); l.removeAt(i); l.add(j, name); setTypesList(l)
    }

    /** 수신기 연결 화면 상태 (없으면 연결 UI 숨김) */
    var connection: ConnectionController? = null
        set(v) { field = v; v?.ui = ui } // 0.1.14: 연결 알림도 같은 알림 창(완료/오류 햅틱)
    /** 내보내기 화면 상태. 0.1.14: 끝 멘트를 완료/오류 알림으로 */
    var exporter: ExportController? = null
        set(v) { field = v; v?.onResult = { ok, m -> ui.result(ok, m) } }
    /** 백업·복구 화면 상태 */
    var backup: BackupController? = null
        set(v) { field = v; v?.onResult = { ok, m -> ui.result(ok, m) } }
    /** 0.1.15 Google Drive 자동 백업·측점 업로드 */
    var drive: DriveSyncController? = null
        set(v) { field = v; v?.onResult = { ok, m -> ui.result(ok, m) } }
    /** 배경(도면) 레이어. 앱에서는 SurveyViewModel 이 연결한다 */
    var bg: kr.archsurvey.ui.bg.BackgroundController? = null
    /** 점 목록·찾아가기·계산기·설정·작업 관리 화면 */
    val hub = ToolsHub(this)
    /** 측점 결과 알림(진동·소리). true=저장됨, false=실패 */
    var feedback: ((Boolean) -> Unit)? = null

    // ---------------- 0.1.28 T14 음성 「저장」 ----------------
    /** 마이크 중재자 1개: 음성 명령 듣기 ↔ 음성 메모 녹음 배타 */
    val mic = kr.archsurvey.voicecmd.MicArbiter()
    /** 음성 명령(없으면 기능 없음 — 시험 기본) */
    var voice: kr.archsurvey.voicecmd.VoiceCommandController? = null
    /** 음성 「저장」·「예」를 알아들은 순간 햅틱·소리(설정 따름) */
    var voiceHeard: (() -> Unit)? = null
    /** 저장(새 점·재측량)마다 1 증가 — 음성 흐름이 결과를 안다 */
    var saveSerial by mutableIntStateOf(0); private set
    private var lastSavedSpeech: String? = null
    /** onMeasure 가 띄운 주의(CAUTION) 확인 — 다른 확인 창(되돌리기 등)과 구분 */
    private var qualityConfirm: ConfirmRequest? = null
    /** 기울기 확인 창마다 새 열쇠(같은 창인지 비교) */
    private var tiltToken: Any = Any()
    /** 이 기울기 확인 창이 주의 확인 승인 뒤에 떴는가 */
    private var tiltAfterCautionOk = false

    /** 음성 명령이 보는 측량 화면. 저장은 측량 버튼과 같은 경로([onMeasure])만 쓴다 */
    val voiceHost: kr.archsurvey.voicecmd.VoiceHost = object : kr.archsurvey.voicecmd.VoiceHost {
        override fun screenReady(): Boolean {
            val conf = ui.confirm
            return visibleRoute() == "survey" && playingVoiceId == null && ui.choice == null &&
                (conf == null || conf === qualityConfirm) && !setupOpen &&
                // UX-0128-1: 「배경맵 설정」 시트·연결 진행 창이 지도를 덮으면 듣지 않음(시트 뒤 저장 방지)
                (bg?.screen ?: kr.archsurvey.ui.bg.BgScreen.NONE) == kr.archsurvey.ui.bg.BgScreen.NONE &&
                !(connection?.showProgress == true && connection?.connectRequested == true)
        }
        override fun linkUp(): Boolean {
            val r = gv.status.reason
            return r != StatusReason.NOT_CONNECTED && r != StatusReason.LINK_DOWN
        }
        override fun busy(): Boolean = phase == Phase.ACQUIRING || phase == Phase.RECORDING
        override fun requestSave() { if (phase == Phase.RESULT || phase == Phase.RESULT_EXPANDED) nextPoint() else onMeasure() }
        override fun prompt(): kr.archsurvey.voicecmd.VoicePrompt? {
            val conf = ui.confirm
            if (conf != null && conf === qualityConfirm)
                return kr.archsurvey.voicecmd.VoicePrompt(kr.archsurvey.voicecmd.VoicePrompt.Kind.CAUTION, conf, if (gv.status.reason == StatusReason.NO_SIGMA) SigmaText.VOICE_PROMPT else "주의 상태예요. ${gv.status.reason.textKo}. 저장할까요?")
            val ta = tiltPrompt
            if (ta != null)
                return kr.archsurvey.voicecmd.VoicePrompt(kr.archsurvey.voicecmd.VoicePrompt.Kind.TILT, tiltToken, "기울기 ${String.format(java.util.Locale.US, "%.1f", ta)}도예요. 저장할까요?")
            val d = dupPrompt
            if (d != null)
                return kr.archsurvey.voicecmd.VoicePrompt(kr.archsurvey.voicecmd.VoicePrompt.Kind.DUP, d, "같은 자리에 ${d.existing.name} 점이 있어요. 새 점으로 저장할까요?")
            return null
        }
        override fun answer(p: kr.archsurvey.voicecmd.VoicePrompt, yes: Boolean): Boolean = when (p.kind) {
            kr.archsurvey.voicecmd.VoicePrompt.Kind.CAUTION -> if (ui.confirm != null && ui.confirm === p.token) { ui.answer(yes); true } else false
            kr.archsurvey.voicecmd.VoicePrompt.Kind.TILT -> if (tiltPrompt != null && tiltToken === p.token) { answerTilt(yes); true } else false
            kr.archsurvey.voicecmd.VoicePrompt.Kind.DUP -> if (dupPrompt != null && dupPrompt === p.token) { resolveDup(if (yes) DupChoice.NEW_POINT else DupChoice.DISCARD); true } else false
        }
        override fun saveSerial(): Int = saveSerial
        override fun lastSavedText(): String? = lastSavedSpeech
        override fun lastMessage(): String? = message
        override fun notice(text: String, ok: Boolean) = ui.result(ok, text)
        override fun ask(title: String, body: String, okLabel: String, onOk: () -> Unit, onCancel: () -> Unit) = ui.ask(ConfirmRequest(title, body, okLabel) { onOk() })
        override fun heardFeedback() { voiceHeard?.invoke() }
    }

    /** 사진·음성 메모 (없으면 화면 시안 동작) */
    var media: MediaPlatform? = null
    val photos = mutableStateListOf<PhotoItem>()
    val voices = mutableStateListOf<VoiceItem>()
    var playingVoiceId by mutableStateOf<Long?>(null)

    /** 지금 열려 있는 작업 이름(표시용) */
    var projectName by mutableStateOf("작업1")
    /** 설정 화면 상태 (없으면 설정 화면 숨김) */
    var settingsState: SettingsState? = null
    /** 작업 관리 상태 */
    var jobs: JobController? = null
    /** 0.1.12 V월드 키·도메인 설정 (없으면 메모리 기본값 — 시험) */
    var vworld: VWorldSettingsState = VWorldSettingsState(MemoryMapKeyPlatform(), scope).also { it.onResult = { ok, m -> ui.result(ok, m) } }
        set(v) { field = v; v.onResult = { ok, m -> ui.result(ok, m) } }

    /** 저장소에서 점을 다시 읽는다 (작업을 바꾼 뒤) */
    private fun anchorTo(lat: Double, lon: Double) { projection = LocalProjection(lat, lon); anchored = true; clearTrail() }
    private fun toLocalNow(lat: Double, lon: Double) = projection.toLocal(lat, lon)

    private fun anchorToLastPoint() {
        if (anchorOnFirstFix && !anchored) points.lastOrNull()?.let { projection = LocalProjection(it.lat, it.lon); anchored = true }
    }

    fun reloadFromStore() {
        val st = store ?: return
        acquireJob?.cancel(); phase = Phase.IDLE; progress = 0f
        photos.clear(); voices.clear()
        dupPrompt = null; resurveyTarget = null; resultName = null; resurveyUndo = null
        arch.reload()
        scope.launch {
            runCatching { val m = st.maxSeq(); val all = st.loadAll(); seq = m; points.clear(); points.addAll(all); anchorToLastPoint() }
                .onFailure { message = "저장된 점을 불러오지 못했어요" }
        }
    }

    var gv by mutableStateOf(GnssView())

    // ---------------- 0.1.30 T24 폰 위치: 지도 점 표시 전용 ----------------
    /**
     * 폰 내장 위치(지도 「폰 위치(참고)」 점·따라가기 전용). [source](수신기)와 완전히 분리되어
     * gv·측점 취득·평균·주의 승인·저장·내보내기 어디에도 들어가지 않는다.
     */
    var phoneFix by mutableStateOf<kr.archsurvey.device.PhoneFix?>(null); private set
    /** 폰 위치가 이보다 오래되면 지도에서 뺀다 */
    var phoneFixMaxAgeMs: Long = 30_000L
    fun onPhoneLocation(f: kr.archsurvey.device.PhoneFix?) {
        phoneFix = f
        // 지도 그림 기준(앵커)만 — 측량 좌표 계산에는 쓰지 않는다(수신기·점이 없을 때 배경맵을 내 근처로)
        if (f != null && anchorOnFirstFix && !anchored) anchorTo(f.lat, f.lon)
    }
    /** 지도에 폰 점을 그릴지: 수신기 위치가 없을 때만, 그리고 신선할 때만 */
    fun phoneDotVisible(): Boolean {
        val f = phoneFix ?: return false
        val receiverPos = gv.lat != null && gv.lon != null && !gv.noReceiver
        return !receiverPos && source.clockMs() - f.atMs <= phoneFixMaxAgeMs
    }

    // ---------------- 0.1.30 T23 정밀도(GST) 안내: 연결당 한 번 ----------------
    private val precisionGate = PrecisionNoticeGate()
    /** 지금 수신기 모델 id(연결 화면 프로필). 시험에서 바꿀 수 있다 */
    var deviceIdProvider: () -> String? = { connection?.profile?.id }
    /** 이번 연결에 낸 정밀도 안내(없으면 null) — 시험·진단용 */
    var precisionNotice by mutableStateOf<String?>(null); private set
    private fun showPrecisionNotice(text: String) { precisionNotice = text; ui.toast(text) }
    /** Engine 의 SP60 GST/GSA 자동 요청 결과 */
    fun onPrecisionResult(r: kr.archsurvey.bt.PrecisionResult?) {
        if (r == null) return
        if (precisionRetryPending) {
            precisionRetryPending = false
            if (r.gstMissing) showPrecisionNotice(PrecisionGuide.SP60_FAIL) else { precisionNotice = PrecisionGuide.RETRY_OK; ui.ok(PrecisionGuide.RETRY_OK) }
            return
        }
        precisionGate.onActivation(deviceIdProvider(), r)?.let { showPrecisionNotice(it) }
    }
    /** 0.1.30 T23-b: [자동 켜기 다시] — Engine.retryPrecision(SP60 받는 중일 때만 true). 시험에서 바꿀 수 있다 */
    var precisionRetry: (() -> Boolean)? = null
    private var precisionRetryPending = false
    /** 사용자가 누른 재시도: 한 순서만 요청. 품질 규칙·모드·저장은 건드리지 않는다 */
    fun retryPrecision() {
        val sent = precisionRetry?.invoke() == true
        precisionRetryPending = sent
        if (sent) ui.toast(PrecisionGuide.RETRY_SENT) else ui.fail(PrecisionGuide.RETRY_UNAVAILABLE)
    }
    /** 엄격 모드 + GST 없음(NO_SIGMA) 막힘: 「수신기 정밀도 출력 꺼짐」 + 해결 경로(재시도·기기별 안내·설정). 저장·모드 변경 없음 */
    private fun blockNoSigmaStrict() {
        message = "측점 불가 — ${PrecisionGuide.PRECISION_OFF}"
        feedback?.invoke(false)
        if (ui.choice != null || ui.confirm != null) return
        val dev = deviceIdProvider()
        val opts = buildList {
            if (dev == kr.archsurvey.device.DeviceProfiles.SP60_ID && precisionRetry != null) add(PrecisionGuide.RETRY_LABEL)
            add(PrecisionGuide.SETTINGS_LABEL)
        }
        ui.choose(ChoiceRequest(PrecisionGuide.PRECISION_OFF, opts, -1, "precision_off", PrecisionGuide.strictBlockedNote(dev)) { i ->
            when (opts[i]) {
                PrecisionGuide.RETRY_LABEL -> retryPrecision()
                PrecisionGuide.SETTINGS_LABEL -> hub.open(ToolScreen.SETTINGS)
            }
        })
    }
    /** 마지막 GGA 이후 지난 초. 없으면 null (상태 띠가 1초마다 다시 부른다) */
    fun nowMs(): Long = clock()
    fun ggaAgeSec(): Long? = gv.ggaAtMs.takeIf { it > 0L }?.let { ((source.clockMs() - it) / 1000).coerceAtLeast(0) }
    var phase by mutableStateOf(Phase.IDLE)
    var progress by mutableFloatStateOf(0f)
    var message by mutableStateOf<String?>(null)
    var typeIndex by mutableIntStateOf(0)
    var poleOnRight by mutableStateOf(true)
    var drawerOpen by mutableStateOf(false)
    /** 0.1.11 홈(메인 메뉴). 앱 시작 때 MainActivity 가 켠다. 기본 false(시험·기존 화면은 바로 지도) */
    var homeOpen by mutableStateOf(false)
    /** 0.1.11 환경설정 목록 화면(목업 03) */
    var settingsMenuOpen by mutableStateOf(false)
    /** 0.1.14 목업 22 위치 모양 설정 창 */
    var markerStyleOpen by mutableStateOf(false)
    /** 0.1.14 위치 모양. 바꾸면 [onMarkerStyleChanged] 로 저장 */
    var markerStyle by mutableStateOf(kr.archsurvey.map.MarkerStyle())
    var onMarkerStyleChanged: ((kr.archsurvey.map.MarkerStyle) -> Unit)? = null
    fun applyMarkerStyle(s: kr.archsurvey.map.MarkerStyle) { markerStyle = s; onMarkerStyleChanged?.invoke(s); markerStyleOpen = false; ui.ok("위치 모양을 적용했어요") }
    /** 0.1.14 목업 20 점 길게 누르기 메뉴 */
    var pointMenuFor by mutableStateOf<SavedPoint?>(null)
    /** 0.1.14 목업 18 사진 확인(촬영 직후 1/4 · 취소/다시 촬영/확정) */
    var photoReviewOpen by mutableStateOf(false)
    /** 0.1.14 목업 14 데이터 백업/복구 창 */
    var backupQuickOpen by mutableStateOf(false)
    /** 0.1.11 GNSS 정보 창(목업 05) — 상단 상태 알약을 누르면 열린다 */
    var gnssInfoOpen by mutableStateOf(false)
    /** 0.1.11 SkyPlot(목업 06). GSV 위성 상세가 있을 때만 점을 그린다 */
    var skyPlotOpen by mutableStateOf(false)
    var antennaSheetOpen by mutableStateOf(false)
    /** 측량 설정(s15) 시트: 측점 버튼을 길게 누르면 열린다 */
    var setupOpen by mutableStateOf(false)
    /** 다음 측점에 붙일 비고(한 번 쓰면 비움) */
    var pendingMemo by mutableStateOf("")
    var quickSheetOpen by mutableStateOf(false)
    var voiceSeconds by mutableIntStateOf(0)
    var dpPerM by mutableStateOf(12.0)

    companion object {
        /** 지도 배율(dp/m). 0.1.22: 핀치 축소·확대 클램프 완화(기존 2…80 → 0.25…320) */
        const val DP_PER_M_MIN = 0.25
        const val DP_PER_M_MAX = 320.0
        /** 0.1.30 T24 UX리뷰 확정 문구 */
        const val NO_RECEIVER_MSG = "수신기가 연결되지 않아 측점할 수 없어요"
        const val RECEIVER_LOST_MSG = "수신기 끊김 · 저장하지 않았어요"
        const val PHONE_DOT_LABEL = "폰 위치(참고)"
        /** 측정 중 이 이유로 ‘불가’가 되면 「수신기 끊김」 */
        val RECEIVER_LOST = setOf(StatusReason.LINK_DOWN, StatusReason.NOT_CONNECTED, StatusReason.STALE, StatusReason.NO_DATA)
    }
    var follow by mutableStateOf(true)
    private var panCenter by mutableStateOf(LocalXY(0.0, 0.0))

    var antennaModelId by mutableStateOf("TRIMBLE_R10")
    var antenna by mutableStateOf(AntennaResolver.initial(antennaDb.getValue("TRIMBLE_R10")))

    val points = mutableStateListOf<SavedPoint>()
    private val trail = mutableStateListOf<LocalXY>()
    // ---- 0.1.30 T26 이동 꼬리: RTK 고정 위치만 · 점프(1초 안 3 m 넘게)·비고정 뒤에는 선을 끊음 · 재연결/작업 바뀜이면 비움 ----
    private val trailBreaks = HashSet<Int>()
    private var trailLastAtMs = 0L
    private var trailBreakNext = false
    /** 1초에 이보다 많이 움직이면 점프(선을 잇지 않음) */
    var trailJumpM = 3.0
    private fun clearTrail() { trail.clear(); trailBreaks.clear(); trailBreakNext = false; trailLastAtMs = 0L }
    private fun addTrail(p: LocalXY, atMs: Long) {
        val last = trail.lastOrNull()
        val dtSec = ((atMs - trailLastAtMs).coerceAtLeast(0)) / 1000.0
        val jump = last != null && hypot(p.x - last.x, p.y - last.y) > trailJumpM * maxOf(1.0, dtSec)
        if (last != null && (jump || trailBreakNext)) trailBreaks.add(trail.size)
        trail.add(p); trailLastAtMs = atMs; trailBreakNext = false
        while (trail.size > 80) {
            trail.removeAt(0)
            val shifted = trailBreaks.map { it - 1 }.filter { it > 0 }
            trailBreaks.clear(); trailBreaks.addAll(shifted)
        }
    }
    private var lastGgaStamp = -1L
    private var acquireJob: Job? = null
    private var voiceJob: Job? = null

    val antennaModel: AntennaModel get() = antennaDb.getValue(antennaModelId)
    val antennaResult: AntennaResult? get() = AntennaMath.compute(antenna, antennaModel)
    /** 점 번호 카운터. 되돌려도 줄이지 않는다(번호 재사용 금지). */
    private var seq by mutableIntStateOf(0)
    val nextName: String get() = "P-%04d".format(seq + 1)
    val typeName: String get() = types.getOrNull(typeIndex) ?: types.firstOrNull() ?: "기타"
    val lastPoint: SavedPoint? get() = resultName?.let { n -> points.firstOrNull { it.name == n } } ?: points.lastOrNull()

    init {
        store?.let { st ->
            scope.launch {
                runCatching {
                    seq = st.maxSeq()
                    points.addAll(st.loadAll())
                    anchorToLastPoint()
                }.onFailure { message = "저장된 점을 불러오지 못했어요" }
            }
        }
        scope.launch {
            combine(source.gnss, source.link) { g, l -> g to l }.collect { (g, l) ->
                val nowMs = source.clockMs()
                val st = StatusJudge.judge(g, l.state, nowMs)
                val gga = g.gga
                // 0.1.33: 5초 안에 받은 GST(σV)·GSA(PDOP/VDOP)만 보여 준다(σH 는 StatusJudge 가 같은 기준으로 고름)
                val gst = g.freshGst(nowMs); val gsa = g.freshGsa(nowMs)
                // 연결이 없으면 마지막 위치도 지도에 남기지 않는다
                val shown = st.reason != StatusReason.NOT_CONNECTED && st.reason != StatusReason.LINK_DOWN
                gv = GnssView(st, g.quality, if (shown) gga?.satellites ?: 0 else 0, if (shown) gga?.lat else null, if (shown) gga?.lon else null,
                    if (shown) gst?.sigmaAlt else null, if (shown) gga?.hdop else null, if (shown) g.satsInView else null, if (shown) gga?.altitudeMsl else null, if (shown && gga != null) g.lastGgaAtMs else 0L,
                    pdop = if (shown) gsa?.pdop else null, correctionAgeSec = if (shown) gga?.correctionAgeSec else null,
                    vdop = if (shown) gsa?.vdop else null, geoidSepM = if (shown) gga?.geoidSeparation else null,
                    baseId = if (shown) gga?.baseStationId else null, sats = if (shown) g.satsDetail else emptyList(),
                    link = l.state, strictMode = settingsState?.requireFixed == true)
                precisionGate.onUpdate(deviceIdProvider(), l.state, g.seenTypes, source.clockMs())?.let { showPrecisionNotice(it) }
                // 0.1.30 T26: 끊김·재연결(받는 중이 아님)이면 꼬리를 비운다
                val live = l.state == kr.archsurvey.device.LinkState.STREAMING || l.state == kr.archsurvey.device.LinkState.STALE
                if (!live && trail.isNotEmpty()) clearTrail()
                if (shown && gga != null && gga.hasPosition && g.lastGgaAtMs != lastGgaStamp) {
                    lastGgaStamp = g.lastGgaAtMs
                    if (anchorOnFirstFix && !anchored) anchorTo(gga.lat!!, gga.lon!!)
                    if (gga.quality == FixQuality.RTK_FIXED) addTrail(toLocalNow(gga.lat!!, gga.lon!!), g.lastGgaAtMs)
                    else trailBreakNext = true // 비고정 위치는 꼬리에 넣지 않고, 다음 고정 위치에서 새 선
                }
            }
        }
    }

    // ---------------- 측점 ----------------

    /** 결과 띠를 보이는 시간(ms). 띠가 사라져도 측점 버튼은 막히지 않고, 사진·음성·되돌리기는 계속 쓸 수 있다 */
    var resultStripMs: Long = 4000L
    var resultStripShown by mutableStateOf(false)
        private set
    /** 새 결과마다 올라가는 번호. 화면(LaunchedEffect)이 이 번호로 숨김 시간을 다시 잰다 — 화면과 함께 사라지는 타이머라 따로 도는 스레드가 없다 */
    var resultStripSeq by mutableIntStateOf(0)
        private set
    private fun showResultStrip() { resultStripShown = true; resultStripSeq++ }
    fun hideResultStrip() { resultStripShown = false }

    /** 기울기 확인 창의 답. true = 그래도 측점 */
    fun answerTilt(go: Boolean) {
        tiltPrompt = null
        // 0.1.28: 주의 확인을 이미 승인하고 넘어온 기울기 확인이면 주의 확인을 다시 묻지 않는다
        // (예전: 주의 → 기울기 → 주의 → 기울기 … 끝없이 반복되던 버그. 상태는 매번 새로 판정 — 불가면 그대로 막힘)
        val cautionOk = tiltAfterCautionOk; tiltAfterCautionOk = false
        if (!go) { message = "측점하지 않았어요. 폴을 세운 뒤 다시 누르세요."; return }
        tiltBypass = true; qualityBypass = cautionOk
        try { onMeasure() } finally { tiltBypass = false; qualityBypass = false }
    }

    /** 수신기 없이 측점을 누름(버튼·음성 「저장」·이어폰 키 같은 경로) */
    private fun blockNoReceiver() {
        message = NO_RECEIVER_MSG
        feedback?.invoke(false)
        if (ui.confirm == null) ui.ask(ConfirmRequest(NO_RECEIVER_MSG, "블루투스로 R10 / SP60 을 연결한 뒤 측점하세요.", "연결") {
            connection?.openScreen() ?: run { message = "연결 화면을 열 수 없어요" }
        })
    }

    fun onMeasure() {
        if (phase == Phase.ACQUIRING || phase == Phase.RECORDING) return
        resultStripShown = false
        val st = gv.status
        // 0.1.30 T24: 수신기 없음/끊김 → 측점 안 함(폰 위치로 대신하지 않음). 안내 + [연결] + 실패음 1번
        if (gv.noReceiver) { blockNoReceiver(); return }
        val strict = settingsState?.requireFixed == true
        when (StatusJudge.savePolicy(st.status, strict)) {
            SavePolicy.BLOCK -> {
                if (strict && st.reason == StatusReason.NO_SIGMA) { blockNoSigmaStrict(); return }
                message = if (strict && st.status == SurveyStatus.CAUTION)
                    "엄격 모드에서는 FIX(고정)만 측점할 수 있어요 — ${st.reason.textKo}"
                else
                    "지금은 측점할 수 없어요 — ${st.reason.textKo}"
                feedback?.invoke(false)
                return
            }
            SavePolicy.ALLOW_WITH_WARNING -> if (!qualityBypass) {
                val hint = if (st.reason == StatusReason.NO_SIGMA) PrecisionGuide.noSigmaHint(deviceIdProvider())?.let { " ($it)" } ?: "" else ""
                val noSigma = st.reason == StatusReason.NO_SIGMA
                val req = ConfirmRequest(
                    if (noSigma) SigmaText.CONFIRM_TITLE else "주의 상태에서 측점할까요?",
                    if (noSigma) SigmaText.CONFIRM_BODY + (hint.takeIf { it.isNotEmpty() } ?: "") else "${st.reason.textKo}$hint · 저장은 되지만 주의 표시가 기록돼요.",
                    if (noSigma) SigmaText.CONFIRM_OK else "그래도 측점",
                ) {
                    qualityConfirm = null
                    qualityBypass = true
                    try { onMeasure() } finally { qualityBypass = false }
                }
                qualityConfirm = req
                ui.ask(req)
                return
            }
            SavePolicy.ALLOW -> {}
        }
        val tc = tilt
        if (tc != null && tc.isOver && !tiltBypass) {
            val ang = tc.currentAngle() ?: 0.0
            when (tc.policy) {
                TiltPolicy.BLOCK -> { message = "폴이 ${tc.f1(ang)}° 기울었어요 (허용 ${tc.f1(tc.limitDeg)}°). 폴을 세운 뒤 다시 측점하세요."; feedback?.invoke(false); return }
                TiltPolicy.CONFIRM -> { tiltToken = Any(); tiltAfterCautionOk = qualityBypass; tiltPrompt = ang; return }
                TiltPolicy.WARN -> {}
            }
        }
        tiltPrompt = null
        val cfg = antenna
        val rec = AntennaRecord.of(antennaModel, cfg)
        if (rec == null) { message = "안테나 높이를 계산할 수 없어요. 높이 값을 확인하세요."; return }
        message = null; dupPrompt = null; arch.lastSummary = null
        phase = Phase.ACQUIRING; progress = 0f
        val typeAtStart = typeName; val nameAtStart = nextName
        val strictAcquire = strict
        acquireJob?.cancel()
        acquireJob = scope.launch {
            val avg = PointAverager(epochs)
            measureCount = 0; measureStartedAtMs = clock()
            var worst = st.status
            var aborted: StatusReason? = null
            var avgStart: Long? = null; var avgEnd: Long? = null
            val tilts = ArrayList<Double>()
            combine(source.gnss, source.link) { g, l -> g to l }.first { (g, l) ->
                val r = StatusJudge.judge(g, l.state, source.clockMs())
                if (r.status == SurveyStatus.UNAVAILABLE || (strictAcquire && r.status == SurveyStatus.CAUTION)) {
                    aborted = r.reason; return@first true
                }
                if (r.status == SurveyStatus.CAUTION) worst = SurveyStatus.CAUTION
                g.gga?.let { if (avg.add(it, g.freshGst(source.clockMs()))) { measureCount = avg.count; val now = clock(); if (avgStart == null) avgStart = now; avgEnd = now; tilt?.currentAngle()?.let { a -> tilts.add(a) } } }
                progress = avg.progress
                avg.done
            }
            val ar = aborted
            if (ar != null) {
                phase = Phase.IDLE
                progress = 0f
                // 0.1.30 T24: 수신기가 끊기거나 멈추면 그 자리에서 취득 취소 · 아무것도 저장하지 않음(폰 위치로 이어 가지 않음)
                if (ar in RECEIVER_LOST) {
                    message = RECEIVER_LOST_MSG
                    ui.fail(RECEIVER_LOST_MSG)
                    feedback?.invoke(false)
                    return@launch
                }
                message = if (strictAcquire)
                    "엄격 모드에서는 FIX만 허용돼요 — ${ar.textKo}. 저장하지 않았어요."
                else
                    "신호가 ‘불가’가 되어 취득을 멈췄어요 (${ar.textKo}). 저장하지 않았어요."
                feedback?.invoke(false)
                return@launch
            }
            val p = avg.result()
            if (p == null) { phase = Phase.IDLE; message = "위치를 얻지 못했어요"; return@launch }
            val tm = TmProjection.forward(p.lat, p.lon, zone)
            val ground = p.ellipsoidalHeight?.let { it - rec.apcHeightM }
            // 0.1.12: 작업의 지오이드 설정으로 표고를 낸다(기본 = 수신기 해발고 그대로)
            val ortho = GeoidHeights.apcOrtho(geoid, p.lat, p.lon, p.altitudeMsl, p.geoidSeparation)
            val groundMsl = ortho.heightM?.let { it - rec.apcHeightM }
            ortho.note?.let { ui.toast(it) }
            val cell = arch.locate(NE(tm.northing, tm.easting), p.sigmaH)
            val cellName = when (cell.kind) { CellKind.INSIDE, CellKind.NEAR_BOUNDARY, CellKind.OUTSIDE -> cell.label; CellKind.NO_GRID -> "" }
            val sp = SavedPoint(nameAtStart, typeAtStart, p.lat, p.lon, tm.northing, tm.easting, zone.epsg, ground, p.sigmaH,
                worst, worst == SurveyStatus.CAUTION, p.epochs, rec, gv.quality.code, gv.satellites, clock(),
                apcEllipsoidalM = p.ellipsoidalHeight, apcMslM = p.altitudeMsl, geoidSepM = p.geoidSeparation, groundMslM = groundMsl, heightModel = ortho.usedModel.id,
                hdop = p.hdop, sigmaE = p.sigmaE, sigmaN = p.sigmaN, sigmaU = p.sigmaU, correctionAgeSec = p.correctionAgeSec, baseStationId = p.baseStationId,
                avgStartedAtMs = avgStart, avgEndedAtMs = avgEnd, maxCorrectionAgeSec = p.maxCorrectionAgeSec, minCorrectionAgeSec = p.minCorrectionAgeSec,
                fixedEpochs = p.fixedEpochs, floatEpochs = p.floatEpochs,
                tiltDeg = if (tilts.isEmpty()) null else tilts.average(), tiltMaxDeg = tilts.maxOrNull(),
                samples = p.positions.map { (la, lo, h) -> val q = TmProjection.forward(la, lo, zone); EpochSample(q.northing, q.easting, h?.let { it - rec.apcHeightM }) },
                areaId = arch.activeAreaId, gridId = if (cell.kind == CellKind.NO_GRID) null else arch.activeGrid?.id, cellName = cellName,
                cellU = cell.inCellU, cellV = cell.inCellV)
            val target = resurveyTarget
            if (target != null) { resurveyTarget = null; applyResurvey(target, sp, worst); return@launch }
            val dup = findDuplicate(sp, NE(tm.northing, tm.easting))
            if (dup != null) {
                phase = Phase.IDLE
                dupPrompt = DupPrompt(sp, dup.first, dup.second, worst)
                return@launch
            }
            commitNew(sp, worst)
        }
    }

    /** 같은 구역의 기존 점 중 max(5 cm, 정밀도의 2배) 이내에 있는 가장 가까운 점 */
    private fun findDuplicate(fresh: SavedPoint, ne: NE, ignore: String? = null): Pair<SavedPoint, Double>? {
        val cands = points.filter { it.areaId == fresh.areaId && it.name != ignore }
        val hit = DuplicateCheck.find(ne, fresh.sigmaHM, cands.map { it.name to pointNE(it) }) ?: return null
        return cands.first { it.name == hit.key } to hit.distanceM
    }

    /** 새 점으로 저장 (디스크 + 화면). 외곽 모드면 꼭짓점으로 이어 붙인다 */
    private suspend fun commitNew(sp: SavedPoint, worst: SurveyStatus) {
        resultName = null; resurveyUndo = null
        val named = (if (sp.name == nextName) sp else sp.copy(name = nextName)).let { if (pendingMemo.isNotBlank()) it.copy(memo = pendingMemo.trim()) else it }
        pendingMemo = ""
        points.add(named); seq++
        var saveError: String? = null
        store?.let { st ->
            val idx = points.lastIndex
            // 저장은 즉시 디스크(Room 트랜잭션). 실패하면 화면에 알림
            try { val id = st.save(named); points[idx] = named.copy(rowId = id); if (arch.outlineMode && named.type == ArchTypes.OUTLINE_POINT) arch.attachVertex(id) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; saveError = "디스크 저장에 실패했어요 — 다시 시도하세요" }
        }
        phase = Phase.RESULT; showResultStrip()
        photos.clear(); voices.clear()
        feedback?.invoke(saveError == null)
        val noSigmaSaved = PointCaution.reason(points.last()) == PointCaution.NO_SIGMA_FIXED
        message = saveError ?: when {
            noSigmaSaved -> SigmaText.SAVED_MESSAGE
            worst == SurveyStatus.CAUTION -> "주의 상태에서 취득한 점이에요 (표시가 기록돼요)"
            else -> null
        }
        if (saveError == null) {
            saveSerial++
            lastSavedSpeech = "${points.last().name} 저장됨" + if (noSigmaSaved) ", 시그마 없음" else if (worst == SurveyStatus.CAUTION) ", 주의 표시" else ""
            ui.toast(savedText(points.last()))
            drive?.notifyPointSaved()
        }
    }

    /** 저장 알림 문구: 무엇이 저장됐는지 */
    internal fun savedText(p: SavedPoint): String =
        "${p.name} 저장됨 · ${p.type} · ${p.epochs}회 평균" + (p.sigmaHM?.let { " · ±${String.format(java.util.Locale.US, "%.3f", it)} m" } ?: "") +
            (if (PointCaution.reason(p) == PointCaution.NO_SIGMA_FIXED) SigmaText.SAVED_SUFFIX else "")

    /** 화면의 되돌리기 버튼: 확인 창을 거친다(측정 중 취소는 바로) */
    fun askUndo() {
        if (phase == Phase.ACQUIRING) { cancelAcquire(); return }
        val last = resurveyUndo?.name?.let { "$it 재측량" } ?: points.lastOrNull()?.name ?: run { message = "되돌릴 점이 없어요"; return }
        ui.ask(ConfirmRequest("$last 을(를) 되돌릴까요?", "되돌린 점은 목록과 지도에서 사라져요. 번호는 다시 쓰지 않아요.", "되돌리기") { undo() })
    }

    /** 재측량: 번호·종류·메모·사진은 두고 측정값만 새 값으로. 이전 값은 이력에 남는다 */
    private suspend fun applyResurvey(target: SavedPoint, fresh: SavedPoint, worst: SurveyStatus) {
        val idx = points.indexOfFirst { it.rowId == target.rowId && it.name == target.name }
        if (idx < 0) { phase = Phase.IDLE; message = "재측량할 점을 찾지 못했어요"; return }
        val old = points[idx]
        val upd = fresh.copy(name = old.name, type = old.type, memo = old.memo, rowId = old.rowId)
        var err: String? = null
        store?.let { st -> try { st.resurvey(old.rowId, upd) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; err = "디스크 저장에 실패했어요 — 다시 시도하세요" } }
        if (err != null) { phase = Phase.IDLE; message = err; feedback?.invoke(false); return }
        points[idx] = upd
        arch.bumpRevision(old.rowId)
        resultName = upd.name; resurveyUndo = old
        dupPrompt = null
        phase = Phase.RESULT; showResultStrip()
        refreshMedia()
        feedback?.invoke(true)
        saveSerial++
        lastSavedSpeech = "${upd.name} 재측량 저장됨"
        val moved = Calc.distance(pointNE(old), pointNE(upd))
        ui.toast("${upd.name} 재측량 저장됨 · ${upd.epochs}회 평균")
        message = "${upd.name} 재측량 완료 · 이전 값과 ${"%.3f".format(moved)} m 차이 (이전 값은 이력에 보관)" + if (worst == SurveyStatus.CAUTION) " · 주의 상태" else ""
        drive?.notifyPointSaved()
    }

    /**
     * 0.1.14: 측량(지도) 화면으로 확실히 이동. 도구(hub)만 닫으면 그 밑의 환경설정 목록·서랍·연결 창 등이
     * 남아 측량 화면이 가려진다(0.1.13 「적용」 버그). 위에 덮이는 창을 모두 닫고 홈도 끈다.
     */
    fun goToSurvey() {
        hub.closeAll()
        settingsMenuOpen = false; drawerOpen = false
        gnssInfoOpen = false; skyPlotOpen = false
        antennaSheetOpen = false; setupOpen = false; quickSheetOpen = false
        markerStyleOpen = false; pointMenuFor = null; backupQuickOpen = false
        connection?.let { if (it.mountPickerOpen) it.closeMountPicker(); it.close() }
        exporter?.close(); backup?.close(); bg?.close()
        homeOpen = false
    }

    /** 지금 맨 위에 보이는 화면 이름(시험·진단용). 측량 지도가 그대로 보이면 "survey" */
    fun visibleRoute(): String = when {
        photoReviewOpen -> "photo_review"
        markerStyleOpen -> "marker_style"
        backupQuickOpen -> "backup_quick"
        pointMenuFor != null -> "point_menu"
        skyPlotOpen -> "skyplot"
        gnssInfoOpen -> "gnss_info"
        drawerOpen -> "drawer"
        connection?.open == true -> "connection"
        backup?.open == true -> "backup"
        exporter?.open == true -> "export"
        hub.screen != null -> "tool:" + hub.screen!!.name
        antennaSheetOpen -> "antenna"
        setupOpen -> "setup"
        quickSheetOpen -> "quick"
        settingsMenuOpen -> "settings_menu"
        homeOpen -> "home"
        else -> "survey"
    }

    /** 점 상세의 [재측량]: 다음 측점이 이 점의 새 값이 된다 */
    fun startResurvey(p: SavedPoint) {
        resurveyTarget = p; dupPrompt = null; goToSurvey() // 0.1.14: 홈에서 들어와도 측량 화면으로
        message = "${p.name} 재측량 — 점 위에 서서 측점 버튼을 누르세요"
    }
    fun cancelResurvey() { resurveyTarget = null; message = "재측량을 취소했어요" }

    /** 중복 점 경고 시트의 선택 */
    fun resolveDup(choice: DupChoice) {
        val d = dupPrompt ?: return
        dupPrompt = null
        when (choice) {
            DupChoice.RESURVEY -> scope.launch { applyResurvey(d.existing, d.fresh, d.worst) }
            DupChoice.NEW_POINT -> scope.launch { commitNew(d.fresh, d.worst) }
            DupChoice.DISCARD -> { phase = Phase.IDLE; message = "취소했어요 — 방금 측점은 저장하지 않았어요" }
        }
    }

    // ---------------- 0.1.31 T27 앱 종료 ----------------
    /** MainActivity 가 채운다: 앱 작업을 완전히 닫기(finishAndRemoveTask). 시험에서는 기록만 */
    var onExitApp: (() -> Unit)? = null
    /**
     * 0.1.32 종료 두 단계: 수신기가 연결(중)이면 「기기 종료」 → 예 = 끊기 → 「프로그램 종료」. 연결이 없으면 「프로그램 종료」만.
     * 측정 중이면 먼저 측정만 멈추고(저장 안 함), 중복 점 선택 중이면 고르라고 알린다
     */
    fun requestExit() {
        if (dupPrompt != null) { ui.toast(DUP_BACK_HINT); return }
        if (phase == Phase.ACQUIRING) { cancelAcquire(); return }
        if (ui.confirm != null) return
        if (connection?.linkActive() == true) ui.ask(AppExit.deviceStep { disconnectDevice(); ui.ask(AppExit.appStep { exitApp() }) })
        else ui.ask(AppExit.appStep { exitApp() })
    }
    /** 「기기 종료」 예: 보정·블루투스 끊기 + 연결 서비스(알림) 멈춤. 저장된 수신기 설정은 그대로(다음 실행 자동 연결) */
    fun disconnectDevice() {
        runCatching { connection?.stopForExit() }.onFailure { kr.archsurvey.diag.CrashLog.record("기기 종료 연결 끊기", it) }
    }
    /** 「프로그램 종료」 예: 혹시 남은 연결도 끊고 앱 닫기(음성·기울기는 ViewModel.onCleared 가 정리) */
    fun exitApp() {
        if (connection?.linkActive() == true) disconnectDevice()
        onExitApp?.invoke()
    }

    fun cancelAcquire() {
        if (phase != Phase.ACQUIRING) return
        acquireJob?.cancel(); phase = Phase.IDLE; progress = 0f; message = "취득을 취소했어요"
    }

    fun undo() {
        if (phase == Phase.ACQUIRING) { cancelAcquire(); return }
        if (dupPrompt != null) { resolveDup(DupChoice.DISCARD); return }
        val prev = resurveyUndo
        if (prev != null && (phase == Phase.RESULT || phase == Phase.RESULT_EXPANDED)) {
            val i = points.indexOfFirst { it.rowId == prev.rowId && it.name == prev.name }
            resurveyUndo = null; resultName = null
            if (i >= 0) {
                points[i] = prev
                store?.let { st -> scope.launch { runCatching { st.undoResurvey(prev.rowId, prev) }.onFailure { message = "디스크 반영에 실패했어요" } } }
                arch.dropRevision(prev.rowId)
            }
            phase = Phase.IDLE; photos.clear(); voices.clear()
            message = "${prev.name} 재측량을 되돌렸어요 (이전 값으로 복원)"
            voice?.onPointUndone()
            return
        }
        if (phase == Phase.RECORDING) media?.cancelRecording()
        media?.stopPlayback(); playingVoiceId = null
        stopVoice()
        if (points.isEmpty()) return
        val gone = points.removeAt(points.lastIndex)
        if (gone.rowId > 0) arch.detachVertex(gone.rowId)
        store?.let { st -> scope.launch { runCatching { st.hideLast() }.onFailure { message = "디스크 반영에 실패했어요" } } }
        phase = Phase.IDLE
        photos.clear(); voices.clear()
        message = "${gone.name} 되돌렸어요"
        voice?.onPointUndone()
    }

    /** 결과 패널의 [다음 점]: 결과 화면을 닫고 측점 준비 화면으로 */
    /** 0.1.14 목업 17 「아니요」: 결과 패널을 닫고 대기(측량은 시작하지 않음) */
    fun dismissResult() { if (phase == Phase.RESULT || phase == Phase.RESULT_EXPANDED) phase = Phase.IDLE }
    fun nextPoint() { if (phase == Phase.RESULT || phase == Phase.RESULT_EXPANDED) { phase = Phase.IDLE; onMeasure() } }

    fun toggleExpand() {
        phase = when (phase) { Phase.RESULT -> Phase.RESULT_EXPANDED; Phase.RESULT_EXPANDED -> Phase.RESULT; else -> phase }
        if (phase == Phase.RESULT) showResultStrip() else hideResultStrip()
    }

    // ---------------- 사진 · 음성 메모 (마지막 점에 붙는다) ----------------

    /** 마지막 점의 사진·음성 목록을 디스크에서 다시 읽는다 */
    fun refreshMedia() {
        val m = media ?: return
        val rid = lastPoint?.rowId ?: 0L
        if (rid == 0L) { photos.clear(); voices.clear(); return }
        scope.launch {
            val ph = runCatching { m.photos(rid) }.getOrDefault(emptyList())
            val vo = runCatching { m.voices(rid) }.getOrDefault(emptyList())
            photos.clear(); photos.addAll(ph); voices.clear(); voices.addAll(vo)
        }
    }

    private fun mediaTarget(): SavedPoint? {
        val p = lastPoint
        if (p == null) { message = "먼저 측점을 하세요"; return null }
        if (p.rowId == 0L) { message = "이 점이 디스크에 저장되지 않아 사진·음성을 붙일 수 없어요"; return null }
        return p
    }

    fun addPhotoCamera() {
        val m = media ?: run { message = "사진 기능을 쓸 수 없어요"; return }
        val p = mediaTarget() ?: return
        if (photos.size >= MediaLimits.PHOTOS) { message = "사진은 점당 ${MediaLimits.PHOTOS}장까지예요"; return }
        m.takePhoto(p.name, p.rowId) { ok ->
            refreshMedia()
            // 0.1.14 목업 18: 찍은 뒤 확인 화면(취소 = 지우기 · 다시 촬영 · 확정)
            if (ok) photoReviewOpen = true else { message = "사진이 저장되지 않았어요 (촬영 취소 또는 실패)"; ui.fail("사진이 저장되지 않았어요 (촬영 취소 또는 실패)") }
        }
    }
    /** 사진 확인: 확정 */
    fun confirmPhoto() { photoReviewOpen = false; ui.ok("사진 ${photos.size}/${MediaLimits.PHOTOS} 저장됨") }
    /** 사진 확인: 취소(방금 사진 지우기) */
    fun discardPhoto() { photoReviewOpen = false; photos.lastOrNull()?.let { deletePhoto(it) }; ui.toast("방금 찍은 사진을 지웠어요") }
    /** 사진 확인: 다시 촬영 */
    fun retakePhoto() { photoReviewOpen = false; val last = photos.lastOrNull(); val m = media; val p = mediaTarget()
        if (last != null && m != null && p != null) scope.launch { runCatching { m.deletePhoto(last.id) }; refreshMedia(); addPhotoCamera() } else addPhotoCamera() }

    fun addPhotoPicker() {
        val m = media ?: run { message = "사진 기능을 쓸 수 없어요"; return }
        val p = mediaTarget() ?: return
        val remaining = MediaLimits.PHOTOS - photos.size
        if (remaining <= 0) { message = "사진은 점당 ${MediaLimits.PHOTOS}장까지예요"; return }
        m.pickPhotos(p.name, p.rowId, remaining) { n ->
            refreshMedia()
            if (n > 0) message = "사진 ${n}장을 앱 폴더에 복사했어요 (원본은 그대로)"
        }
    }

    fun deletePhoto(item: PhotoItem) {
        val m = media ?: return
        scope.launch { runCatching { m.deletePhoto(item.id) }.onFailure { message = "사진을 지우지 못했어요" }; refreshMedia() }
    }

    fun deleteVoice(item: VoiceItem) {
        val m = media ?: return
        if (playingVoiceId == item.id) { m.stopPlayback(); playingVoiceId = null }
        scope.launch { runCatching { m.deleteVoice(item.id) }.onFailure { message = "음성을 지우지 못했어요" }; refreshMedia() }
    }

    fun playVoice(item: VoiceItem) {
        val m = media ?: return
        if (playingVoiceId == item.id) { m.stopPlayback(); playingVoiceId = null; return }
        val ok = m.play(item.rel) { playingVoiceId = null }
        if (ok) playingVoiceId = item.id else message = "음성을 재생하지 못했어요"
    }

    /** 녹음 시작/끝. 실제 녹음 기능이 연결되어 있으면 마이크 권한 → 녹음(최대 30초) → 저장 */
    fun toggleVoice() {
        val m = media ?: return toggleVoiceMock()
        if (phase == Phase.RECORDING) { finishVoice(); return }
        if (phase != Phase.RESULT && phase != Phase.RESULT_EXPANDED) return
        val p = mediaTarget() ?: return
        if (voices.size >= MediaLimits.VOICES) { message = "음성 메모는 점당 ${MediaLimits.VOICES}개까지예요"; return }
        if (!m.hasMicPermission()) {
            m.requestMicPermission { ok -> if (ok) toggleVoice() else message = "마이크 권한이 꺼져 있어요 — 설정 > 앱 > 권한 > 마이크 를 허용하세요" }
            return
        }
        m.stopPlayback(); playingVoiceId = null
        // 0.1.28: 마이크 중재자 — 음성 명령 듣기를 먼저 멈추고 넘겨받는다
        if (!mic.tryAcquire(kr.archsurvey.voicecmd.MicOwner.MEMO)) { message = "마이크를 다른 기능이 쓰고 있어요"; return }
        val err = m.startRecording(p.name)
        if (err != null) { mic.release(kr.archsurvey.voicecmd.MicOwner.MEMO); message = err; return }
        phase = Phase.RECORDING; voiceSeconds = 0
        voiceJob = scope.launch { while (voiceSeconds < MediaLimits.VOICE_SECONDS) { delay(1000); voiceSeconds++ }; finishVoice() }
    }

    private fun finishVoice() {
        val m = media ?: return
        voiceJob?.cancel(); voiceJob = null
        if (phase == Phase.RECORDING) { phase = Phase.RESULT; showResultStrip() }
        val rid = lastPoint?.rowId ?: 0L
        val releaseMic = { mic.release(kr.archsurvey.voicecmd.MicOwner.MEMO) }
        scope.launch {
            val v = runCatching { m.finishRecording(rid) }.getOrNull()
            releaseMic()
            if (v == null) message = "녹음이 너무 짧거나 저장에 실패해서 남기지 않았어요" else voices.add(v)
        }
    }

    /** 음성 메모 **화면 시안만**: 실제 녹음은 하지 않는다(Phase 1). 30초 자동 정지 타이머만 흉내. */
    fun toggleVoiceMock() {
        if (phase == Phase.RECORDING) { stopVoice(); return }
        if (phase != Phase.RESULT && phase != Phase.RESULT_EXPANDED) return
        phase = Phase.RECORDING; voiceSeconds = 0
        voiceJob = scope.launch { while (voiceSeconds < 30) { delay(1000); voiceSeconds++ }; stopVoice() }
    }

    private fun stopVoice() {
        voiceJob?.cancel(); voiceJob = null
        mic.release(kr.archsurvey.voicecmd.MicOwner.MEMO)
        if (phase == Phase.RECORDING) { phase = Phase.RESULT; showResultStrip() }
    }

    /** 점 하나의 평면 좌표(현재 원점 기준). 저장 당시 원점이 같으면 저장값을 쓰고, 다르면 경위도에서 다시 계산한다 */
    fun pointNE(p: SavedPoint): kr.archsurvey.core.NE =
        if (p.crsEpsg == zone.epsg) kr.archsurvey.core.NE(p.northing, p.easting)
        else TmProjection.forward(p.lat, p.lon, zone).let { kr.archsurvey.core.NE(it.northing, it.easting) }

    /** 현재 위치의 평면 좌표(위치가 없으면 null) */
    fun currentNE(): kr.archsurvey.core.NE? {
        val la = gv.lat ?: return null; val lo = gv.lon ?: return null
        return TmProjection.forward(la, lo, zone).let { kr.archsurvey.core.NE(it.northing, it.easting) }
    }

    /** 위치 종류·메모 수정 (화면 목록 + 저장소) */
    fun updatePointMeta(name: String, type: String, memo: String) {
        val i = points.indexOfFirst { it.name == name }
        if (i < 0) return
        val old = points[i]
        val upd = old.copy(type = type.trim().ifEmpty { old.type }, memo = memo.trim())
        points[i] = upd
        store?.let { st -> if (upd.rowId > 0) scope.launch { runCatching { st.updateMeta(upd.rowId, upd.type, upd.memo) }.onFailure { message = "디스크 반영에 실패했어요" } } }
    }

    /** 지도를 이 점으로 옮긴다 (따라가기 해제) */
    fun focusOn(p: SavedPoint) { follow = false; panCenter = projection.toLocal(p.lat, p.lon) }

    /** 외곽 이어서 취득 켜기/끄기. 켜면 위치 종류를 ‘유구 경계점’으로 맞춘다 */
    fun toggleOutlineMode() {
        arch.outlineMode = !arch.outlineMode
        if (arch.outlineMode) { val i = types.indexOf(ArchTypes.OUTLINE_POINT); if (i >= 0) typeIndex = i else message = "위치 종류에 ‘유구 경계점’이 없어 외곽을 이을 수 없어요" }
    }
    fun cycleType() { typeIndex = (typeIndex + 1) % types.size }

    /** 0.1.11 하단 도구 「선으로 연결」·「면으로 연결」: 외곽 취득을 켜고 닫힘 여부를 정한다. 같은 쪽을 다시 누르면 끈다 */
    /** 0.1.14 하단 「새 연결 시작」(목업 15): 이어 오던 외곽을 마무리하고 다음 점부터 새 선/면 */
    fun startNewLink() {
        if (!arch.outlineMode) { setLinkMode(closed = false); message = "선 연결을 켰어요 · 다음 점부터 새 연결"; return }
        val had = arch.activeOutlineVertices().isNotEmpty()
        if (had) arch.finishOutline(arch.preferClosed)
        message = if (had) "앞 외곽을 마무리했어요 · 다음 점부터 새 연결" else "다음 점부터 새 연결이에요"
    }

    fun setLinkMode(closed: Boolean) {
        if (arch.outlineMode && arch.preferClosed == closed) { toggleOutlineMode(); return }
        arch.preferClosed = closed
        if (!arch.outlineMode) toggleOutlineMode()
    }

    // ---------------- 지도 ----------------

    fun zoomIn() { dpPerM = (dpPerM * 1.5).coerceAtMost(DP_PER_M_MAX) }
    fun zoomOut() { dpPerM = (dpPerM / 1.5).coerceAtLeast(DP_PER_M_MIN) }

    /** 핀치 줌: factor>1 확대. focusXM/YM = 화면 초점의 지도 좌표 오프셋(m, 중심 기준). 초점이 손가락 아래에 남도록 중심을 보정한다. */
    fun scaleBy(factor: Double, focusXM: Double = 0.0, focusYM: Double = 0.0) {
        if (!factor.isFinite() || factor <= 0.0 || kotlin.math.abs(factor - 1.0) < 1e-9) return
        val old = dpPerM
        val neu = (old * factor).coerceIn(DP_PER_M_MIN, DP_PER_M_MAX)
        val actual = neu / old
        if (kotlin.math.abs(actual - 1.0) < 1e-9) return
        val center = mapState().center
        follow = false
        panCenter = LocalXY(
            center.x + focusXM * (1.0 - 1.0 / actual),
            center.y + focusYM * (1.0 - 1.0 / actual),
        )
        dpPerM = neu
    }

    fun recenter() { follow = true }

    /** 전체 보기: 저장한 점이 모두 보이도록 가운데와 배율을 맞춘다(점이 없으면 현재 위치로) */
    fun fitAll() {
        val ps = points.map { projection.toLocal(it.lat, it.lon) }
        if (ps.isEmpty()) { recenter(); return }
        val w = ps.maxOf { it.x } - ps.minOf { it.x }; val h = ps.maxOf { it.y } - ps.minOf { it.y }
        follow = false
        panCenter = LocalXY((ps.maxOf { it.x } + ps.minOf { it.x }) / 2, (ps.maxOf { it.y } + ps.minOf { it.y }) / 2)
        val span = maxOf(w, h, 5.0)
        dpPerM = (260.0 / span).coerceIn(DP_PER_M_MIN, DP_PER_M_MAX)
    }
    fun panBy(dxM: Double, dyM: Double, currentCenter: LocalXY) { follow = false; panCenter = LocalXY(currentCenter.x + dxM, currentCenter.y + dyM) }

    fun mapState(): MapViewState {
        val cur = if (gv.lat != null && gv.lon != null) projection.toLocal(gv.lat!!, gv.lon!!) else null
        // 0.1.30 T24: 폰 점은 수신기 위치가 없을 때만(표시 전용). 따라가기 = 수신기 우선, 없으면 폰
        val pf = phoneFix?.takeIf { phoneDotVisible() }
        val phoneAt = pf?.let { projection.toLocal(it.lat, it.lon) }
        val center = if (follow) (cur ?: phoneAt ?: LocalXY(20.0, 16.0)) else panCenter
        val head = if (trail.size >= 2 && (trail.size - 1) !in trailBreaks) { // 0.1.30 T26: 점프한 위치로 방향을 내지 않음
            val a = trail[trail.size - 2]; val b = trail.last()
            if (hypot(b.x - a.x, b.y - a.y) > 0.05) Math.toDegrees(atan2(b.x - a.x, b.y - a.y)) else null
        } else null
        val sigma = gv.status.sigmaHM ?: 0.5
        val g = arch.activeGrid
        val ref = TmProjection.forward(projection.lat0, projection.lon0, zone)
        fun loc(ne: NE) = LocalXY(ne.e - ref.easting, ne.n - ref.northing)
        val cellNow = if (g != null) arch.currentCell() else null
        // 0.1.20: 수신기·점 없어도 배경맵 표시(기본 원점 36.5/127). 예전엔 anchored 때만 타일 → 미연결 시 위성 안 보임
        val layers = mapTiles()
        return MapViewState(
            center = center, dpPerM = dpPerM, 
            points = points.map { MapMarker(it.name, projection.toLocal(it.lat, it.lon), it.cautionFlag && PointCaution.displayStatus(it) != SurveyStatus.FIXED) },
            current = cur?.let { CurrentMarker(it, sigma, gv.displayStatus, head) }, // 0.1.30 T23-c 표시 색만
            phone = if (cur == null && pf != null && phoneAt != null) PhoneMarker(phoneAt, pf.accuracyM, PHONE_DOT_LABEL) else null,
            trail = trail.toList(), trailBreaks = trailBreaks.toSet(),
            gridLines = if (g != null && g.cols + g.rows <= 400) GridMath.lines(g).map { loc(it.first) to loc(it.second) } else emptyList(),
            gridLabels = if (g != null && g.cols * g.rows <= 1200 && g.spacingM * dpPerM >= 26) GridMath.cellCenters(g).map { MapText(it.first, loc(it.second)) } else emptyList(),
            cellHighlight = if (g != null && cellNow != null && cellNow.col >= 0) g.cellCorners(cellNow.col, cellNow.row).map { loc(it) } else emptyList(),
            gridColorIdx = arch.activeArea?.colorIdx ?: 0,
            backgrounds = bg?.mapLayers().orEmpty(),
            tiles = layers,
            geo = if (layers.isNotEmpty()) projection else null,
            outlines = arch.outlineViews().map { MapOutline(it.outline.label, it.vertices.map { v -> loc(v) }, it.outline.closed, !it.outline.finished, arch.areas.firstOrNull { a -> a.id == it.outline.areaId }?.colorIdx ?: 0) },
            style = markerStyle,
            target = runCatching { hub.gotoTargetNE()?.let { loc(it) } }.getOrNull(),
        )
    }

    // ---------------- 안테나 높이 ----------------

    /** 기기(안테나 모델)를 맞춘다. 기기를 바꿔 폴 높이가 기본값으로 바뀌었으면 안내 문구(「폴 높이 1.6 → 1.8 m(SP60 기본)」), 아니면 null */
    fun setAntennaModel(id: String): String? {
        val changed = id != antennaModelId
        val oldPole = antenna.inputValueM
        if (changed) {
            antennaModelId = id
            // 0.1.29 T19: 기기를 바꿔도 사용자가 정한 폴 높이(USER)는 그대로 — 기준점만 새 모델 기본으로
            val wasUser = antenna.source == AntennaSource.USER
            antenna = AntennaResolver.proposeDefault(antenna, antennaModel).let { if (wasUser) it.copy(source = AntennaSource.USER) else it }
        }
        val poleDefaulted = applyDevicePoleDefault()
        if (changed || poleDefaulted) jobConfigChanged()
        return if (changed && poleDefaulted) kr.archsurvey.core.DevicePoleDefaults.changeNotice(oldPole, antenna.inputValueM, id) else null
    }
    fun setRefPoint(id: String) { antenna = antenna.copy(refPointId = id); jobConfigChanged() }
    /** 0.1.12: 폴 높이는 10 cm(0.1 m) 단위로만 바꾼다. 지금 값을 0.1 m 에 맞춘 뒤 ±0.1 m */
    fun stepAntennaCm(deltaCm: Int) {
        antenna = AntennaResolver.userEdit(antenna.copy(inputValueM = AntennaMath.stepPole(antenna.inputValueM, deltaCm)))
        jobConfigChanged()
    }
    /** 정해진 높이로 바로(칩·새 작업 창). 0.1 m 로 맞춘다 */
    fun setPoleHeight(m: Double) {
        antenna = AntennaResolver.userEdit(antenna.copy(inputValueM = AntennaMath.snapPole(m.coerceIn(0.0, AntennaMath.MAX_HEIGHT_M))))
        jobConfigChanged()
    }

    // ---------------- 배경맵(V월드) ----------------
    /** 지금 배경맵 선택 → 타일 레이어(아래→위). 키가 없으면 빈 목록(점만) */
    fun mapTiles(): List<kr.archsurvey.map.TileLayerSpec> {
        if (mapBase == kr.archsurvey.map.MapBase.NONE) return emptyList()
        val a = mapKeyProvider()
        return kr.archsurvey.map.MapLayers.resolve(mapBase, a)
    }
    fun mapNotice(): String? = kr.archsurvey.map.MapLayers.fallbackMessage(mapBase, mapKeyProvider().key)
}
