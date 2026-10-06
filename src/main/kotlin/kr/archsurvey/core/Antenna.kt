package kr.archsurvey.core

import kotlin.math.sqrt

enum class HeightUnit(val label: String, val toMeters: Double) {
    M("m", 1.0), CM("cm", 0.01);
}

/** 안테나 높이 값의 출처. 우선순위: USER > RECEIVER > DEFAULT */
enum class AntennaSource(val labelKo: String) {
    DEFAULT("기본값"), RECEIVER("수신기에서 읽음"), USER("직접 입력");
}

/** 모델별 기준점. pcoV: 기준점→위상중심(APC) 수직 오프셋, pcoH: 사선 환산용 수평 오프셋 (antenna-height.md §1,§5) */
data class RefPoint(
    val id: String,
    val labelKo: String,
    val pcoV: Double,
    val pcoH: Double,
    val slant: Boolean,
    val tapeCorrection: Double?,
    val hidden: Boolean,
    val confidence: String,
)

/** 마운트 어댑터 등 부품. 표시·경고 전용 — 계산에 가산하지 않는다 (기준점 pcoV 에 이미 포함, antenna-height.md §10) */
data class AntennaAdapter(val id: String, val model: String, val heightM: Double?, val includedInRefPoint: String?, val doNotAddSeparately: Boolean)

data class AntennaModel(
    val dbId: String,
    val label: String,
    val defaultRefPoint: String,
    val refPoints: List<RefPoint>,
    val nmeaCarriesAntennaHeight: Boolean,
    val adapters: List<AntennaAdapter> = emptyList(),
) {
    fun ref(id: String?): RefPoint = refPoints.firstOrNull { it.id == id } ?: refPoints.first { it.id == defaultRefPoint }
    val defaultRef: RefPoint get() = ref(defaultRefPoint)
    /** 일반 사용자에게 보이는 기준점 (hidden 은 고급) */
    val visibleRefs: List<RefPoint> get() = refPoints.filter { !it.hidden && !it.slant }
    val advancedRefs: List<RefPoint> get() = refPoints.filter { it.slant || it.hidden }
}

/**
 * 안테나 높이 설정 (기기별 저장). 내부 값은 모두 미터.
 * @param inputValueM  지면(폴 끝)→기준점 거리. 기준점이 사선형이면 사선 길이
 * @param refPointId   기준점 (모델 DB 의 id)
 * @param tapeOn       사선 줄자 걸이 보정(SP60 높이 표시 +0.073 m) 적용
 * @param receiverApplied 수신기가 이미 안테나 높이를 반영하면 true (이중 적용 금지)
 */
data class AntennaConfig(
    val inputValueM: Double = 2.0,
    val refPointId: String = "",
    val tapeOn: Boolean = false,
    val receiverApplied: Boolean = false,
    val source: AntennaSource = AntennaSource.DEFAULT,
    /** 고급(숨김, 기본 0): 지면~측정 위치 사이를 줄자로 재지 않고 부품 길이로 더할 때만 */
    val extraPartsM: Double = 0.0,
    /** 베이스 연장봉 0.15 m 를 앱이 더함 (이중 입력 방지: 켜면 사용자는 삼각대 높이만 입력) */
    val baseExtension: Boolean = false,
)

/** 계산 결과: 점에 그대로 저장할 값들 */
data class AntennaResult(
    val refPointId: String,
    val method: String,       // vertical | slant
    val inputM: Double,
    val extraPartsM: Double,
    val baseExtM: Double,
    val pcoV: Double,
    val pcoH: Double,
    val verticalToRefM: Double, // 지면→기준점 직고
    val totalToApcM: Double,    // 지면→APC (H_apc)
)

object AntennaMath {
    const val MAX_HEIGHT_M = 5.0
    /** 0.1.29: R10 기본 1.6 m 칩 추가(T19) */
    val CHIPS_M = listOf(1.500, 1.600, 1.800, 2.000)

    sealed interface ParseResult {
        data class Ok(val meters: Double) : ParseResult
        data class Error(val message: String) : ParseResult
    }

    /** 사용자 입력 검증: 숫자, 음수 불가, 범위(0~MAX). 쉼표 소수점 허용. */
    fun parse(text: String, unit: HeightUnit, maxM: Double = MAX_HEIGHT_M): ParseResult {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return ParseResult.Error("높이를 입력하세요")
        val v = t.toDoubleOrNull() ?: return ParseResult.Error("숫자만 입력하세요")
        if (v.isNaN() || v.isInfinite()) return ParseResult.Error("숫자만 입력하세요")
        if (v < 0) return ParseResult.Error("음수는 입력할 수 없어요")
        val m = v * unit.toMeters
        if (m > maxM) return ParseResult.Error("너무 커요 (최대 ${format(maxM, unit)})")
        return ParseResult.Ok(m)
    }

    /** 1.0~3.0 m 밖이면 "폴 높이가 맞나요?" 확인 */
    fun isUnusual(m: Double) = m < 1.0 || m > 3.0

    /** 0.1.12: 폴(안테나) 높이 조정 단위 = 10 cm. 사용자 조정(± 버튼·칩·새 작업 창)은 이 단위로만 */
    const val POLE_STEP_CM = 10

    /** 0.1 m 단위로 맞춤(반올림), 0~MAX */
    fun snapPole(m: Double, maxM: Double = MAX_HEIGHT_M): Double {
        if (m.isNaN() || m.isInfinite()) return 0.0
        return (Math.round(m * 10.0) / 10.0).coerceIn(0.0, maxM)
    }

    /** ± 버튼(0.1.12): 현재 값을 0.1 m 로 맞춘 뒤 10 cm 배수로 증감. deltaCm 이 10 의 배수가 아니면 부호만 보고 한 칸(10 cm) */
    fun stepPole(currentM: Double, deltaCm: Int, maxM: Double = MAX_HEIGHT_M): Double {
        val steps = when { deltaCm == 0 -> 0; deltaCm % POLE_STEP_CM == 0 -> deltaCm / POLE_STEP_CM; deltaCm > 0 -> 1; else -> -1 }
        val base = Math.round(currentM * 10.0)
        return snapPole((base + steps) / 10.0, maxM)
    }

    /** +/- 버튼: cm 단위로 증감, 0~MAX 로 제한 (0.1.11 까지 화면에서 쓰던 1 cm 단위 — 이제 화면에서는 [stepPole]) */
    fun step(currentM: Double, deltaCm: Int, maxM: Double = MAX_HEIGHT_M): Double {
        val cm = Math.round(currentM * 100.0) + deltaCm
        return (cm / 100.0).coerceIn(0.0, maxM)
    }

    fun format(m: Double, unit: HeightUnit): String =
        if (unit == HeightUnit.M) String.format(java.util.Locale.US, "%.3f m", m)
        else String.format(java.util.Locale.US, "%.1f cm", m * 100.0)

    /**
     * H_apc = 직고(H_in) + pcoV
     * 사선: Hv = sqrt((Hs + tape)^2 - pcoH^2);  H_apc = Hv + pcoV
     * 계산 불가(사선 길이 < 수평 오프셋)면 null.
     */
    fun compute(cfg: AntennaConfig, model: AntennaModel): AntennaResult? {
        val r = model.ref(cfg.refPointId)
        // 어댑터(QR 0.05 등)는 기준점 pcoV 에 이미 포함 -> 여기서 가산하지 않는다
        val ext = if (cfg.baseExtension) BASE_EXTENSION_M else 0.0
        val measured = cfg.inputValueM + cfg.extraPartsM + ext
        val vertical: Double = if (!r.slant) measured else {
            val hs = measured + (if (cfg.tapeOn) (r.tapeCorrection ?: 0.0) else 0.0)
            if (hs <= r.pcoH) return null
            sqrt(hs * hs - r.pcoH * r.pcoH)
        }
        return AntennaResult(r.id, if (r.slant) "slant" else "vertical", cfg.inputValueM, cfg.extraPartsM, ext, r.pcoV, r.pcoH, vertical, vertical + r.pcoV)
    }

    /** 지면 높이 = 수신기 고도(APC) − 안테나(APC) 높이. receiverApplied 면 원값 그대로(이중 적용 금지). */
    fun groundHeight(apcHeight: Double?, cfg: AntennaConfig, result: AntennaResult?): Double? {
        if (apcHeight == null) return null
        if (cfg.receiverApplied) return apcHeight
        return result?.let { apcHeight - it.totalToApcM }
    }

    const val BASE_EXTENSION_M = 0.15
    private const val NEAR = 0.015

    /**
     * 입력값-기준점 어긋남 경고 (R10 전용). 경고만 하고 값은 절대 바꾸지 않는다.
     * 2.05 근처인데 '퀵릴리즈 하단' / 2.00 근처인데 '수신기 바닥' 이면 어댑터 5cm 이중/누락 의심.
     */
    fun mismatchWarning(cfg: AntennaConfig, model: AntennaModel): String? {
        if (model.dbId != "TRIMBLE_R10") return null
        val v = cfg.inputValueM
        return when {
            cfg.refPointId == "bottom_of_quick_release" && Math.abs(v - 2.05) <= NEAR ->
                "혹시 어댑터 5 cm를 더하셨나요? 선택한 위치(퀵릴리즈 하단)를 다시 확인해 주세요."
            cfg.refPointId == "bottom_of_antenna_mount" && Math.abs(v - 2.00) <= NEAR ->
                "혹시 어댑터 5 cm를 빼고 재셨나요? 선택한 위치(수신기 바닥)를 다시 확인해 주세요."
            else -> null
        }
    }

    /** 결과 한 줄: "폴 2.000 m + 어댑터·기기 보정 0.199 m = 안테나 위상중심 2.199 m" */
    fun summary(r: AntennaResult): String {
        val pole = r.inputM + r.extraPartsM + r.baseExtM
        val corr = r.totalToApcM - pole
        fun f(x: Double) = String.format(java.util.Locale.US, "%.3f", x)
        return "폴 ${f(pole)} m + 어댑터·기기 보정 ${f(corr)} m = 안테나 위상중심 ${f(r.totalToApcM)} m"
    }

    const val ADAPTER_NOTE_R10 = "어댑터 두께(5 cm)는 앱이 이미 계산해요. 따로 더하지 마세요."
    const val ADAPTER_NOTE_R10_MOUNT = "이때는 어댑터 5 cm가 잰 값에 들어 있어요. 또 더하지 마세요."
    const val ADAPTER_NOTE_SP60 = "SP60은 바닥(ARP)이 기준이에요. 폴에 나사로 직접 끼우므로 어댑터 두께를 더하지 않아요. UHF 모듈을 끼워도 기준점은 그대로예요."
    const val SUM_PARTS_NOTE = "연장봉·폴 조각처럼 지면과 측정 위치 사이에 끼운 부품은 줄자로 직접 재면 따로 더할 필요가 없어요."
    const val BASE_EXT_NOTE = "고정높이 삼각대에 연장봉(15 cm)을 쓰셨나요? 켜면 앱이 15 cm를 더해요. 삼각대 높이만 입력하세요."

    /** 두 모델 사이 같은 폴 높이에서의 APC 높이 차 (안내문용) */
    fun apcDifference(a: AntennaModel, b: AntennaModel): Double = Math.abs(a.defaultRef.pcoV - b.defaultRef.pcoV)

    /** 화면에 크게 표시할 기준점 문구 */
    fun instruction(shortName: String, ref: RefPoint): String = "$shortName · " + when (ref.id) {
        "bottom_of_antenna_mount" -> "마운트 하단부터 재세요"
        "bottom_of_quick_release" -> "퀵릴리즈 하단부터 재세요"
        "bottom_of_receiver_arp" -> "수신기 바닥면부터 재세요"
        "lever_of_r10_extension" -> "연장봉 레버까지 사선으로 재세요"
        "slant_height_mark" -> "높이 표시(후크)까지 사선으로 재세요"
        "bumper_bottom_outer_edge" -> "범퍼 아래 모서리까지 사선으로 재세요"
        "bottom_of_uhf_extension" -> "UHF 연장부 하단부터 재세요"
        else -> ref.labelKo + "부터 재세요"
    }

    const val MODEL_CHANGE_NOTICE =
        "R10(퀵릴리즈 하단 기준)과 SP60(수신기 바닥 기준)은 높이 재는 기준점이 달라 같은 폴 높이여도 안테나 위상중심이 약 13cm 차이납니다. " +
            "앱이 자동 보정하니 선택한 기기의 기준점부터 재 주세요."
    const val RECEIVER_SIDE_NOTICE =
        "수신기(Trimble Access·웹 설정)의 안테나 높이는 0.00(안테나 위상중심)으로 두세요. 이중으로 더해져요."
}

object AntennaResolver {
    /** 모델 기본값 제안: 기본 기준점 + 현재 입력값 유지. 사용자 입력값이 있으면 덮어쓰기 전 확인 필요 */
    fun proposeDefault(cur: AntennaConfig, model: AntennaModel): AntennaConfig =
        cur.copy(refPointId = model.defaultRefPoint, tapeOn = false, source = AntennaSource.DEFAULT)

    fun initial(model: AntennaModel) = AntennaConfig(2.0, model.defaultRefPoint, false, false, AntennaSource.DEFAULT)

    /** 수신기가 높이를 보고했을 때: 사용자가 직접 입력한 값이 있으면 그대로 둔다. */
    fun applyReceiverReport(cur: AntennaConfig, reportedM: Double?): AntennaConfig {
        if (reportedM == null || cur.source == AntennaSource.USER) return cur
        if (AntennaMath.parse(reportedM.toString(), HeightUnit.M) !is AntennaMath.ParseResult.Ok) return cur
        return cur.copy(inputValueM = reportedM, source = AntennaSource.RECEIVER)
    }

    /** 사용자가 값을 바꾸면 출처는 항상 USER */
    fun userEdit(new: AntennaConfig): AntennaConfig = new.copy(source = AntennaSource.USER)

    /** 모델 변경 시 기본 기준점으로 바꾸려면 확인이 필요한가 (사용자가 직접 정한 설정이 있을 때) */
    fun needsOverwriteConfirm(cur: AntennaConfig, proposed: AntennaConfig): Boolean =
        cur.source == AntennaSource.USER && (cur.refPointId != proposed.refPointId || cur.tapeOn != proposed.tapeOn)
}

/**
 * 점에 함께 저장할 안테나 기록 (WP-4): 기준점 id·이름·오프셋·입력값·위상중심(APC) 높이.
 * 나중에 DB 값이 아닌 이 기록으로 재계산·검증할 수 있다.
 */
data class AntennaRecord(
    val modelId: String,
    val refPointId: String,
    val refPointLabel: String,
    val inputM: Double,
    val method: String,
    val pcoV: Double,
    val pcoH: Double,
    val apcHeightM: Double,
) {
    companion object {
        fun of(model: AntennaModel, cfg: AntennaConfig): AntennaRecord? {
            val r = AntennaMath.compute(cfg, model) ?: return null
            return AntennaRecord(model.dbId, r.refPointId, model.ref(r.refPointId).labelKo, cfg.inputValueM, r.method, r.pcoV, r.pcoH, r.totalToApcM)
        }
    }
}

/**
 * 0.1.29 T19 기기별 폴 높이 기본값(사용자 결정 10-06): SP60 1.8 m, R10 1.6 m.
 * 뜻은 지금과 같음 = 지면(폴 끝) → 그 기기의 기본 기준점(R10 퀵릴리즈 하단 · SP60 수신기 바닥) [사용자 확인 중].
 * 사용자가 직접 바꾼 값(AntennaSource.USER)은 절대 덮지 않는다. 입력 방식(vertical/slant)과 무관 — 기본 기준점은 둘 다 수직.
 */
object DevicePoleDefaults {
    const val R10_M = 1.6
    const val SP60_M = 1.8
    /** 안테나 DB 모델 id → 기본 폴 높이(m). 모르는 모델은 null(바꾸지 않음) */
    fun forModel(antennaModelId: String?): Double? = when (antennaModelId) {
        "TRIMBLE_R10" -> R10_M
        "SPECTRA_SP60" -> SP60_M
        else -> null
    }
}
