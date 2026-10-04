package kr.archsurvey.ui

/** 화면 상태. 지도 영역 높이 계산에만 쓰는 최소 분류. */
enum class Phase { IDLE, ACQUIRING, RESULT, RESULT_EXPANDED, RECORDING }

/** MASTER-PLAN §4 UI 원칙의 치수. 화면 코드와 레이아웃 시험이 같은 상수를 쓴다. 모두 dp. */
object SurveyDims {
    const val MEASURE_BUTTON_DP = 72
    const val MIN_TOUCH_DP = 48
    const val POLE_STRIP_DP = 56
    const val STATUS_BAND_DP = 64
    const val COORD_LINE_DP = 36
    const val INFO_STRIP_DP = 56
    const val EXPANDED_EXTRA_DP = 150
    const val CTRL_PAD_TOP_DP = 12
    const val CTRL_GAP_DP = 8
    const val CTRL_PAD_BOTTOM_DP = 12
    const val SECONDARY_ROW_DP = MIN_TOUCH_DP
    const val CONTROL_AREA_DP = CTRL_PAD_TOP_DP + MEASURE_BUTTON_DP + CTRL_GAP_DP + SECONDARY_ROW_DP + CTRL_PAD_BOTTOM_DP
    const val MIN_MAP_FRACTION = 0.60
    const val MAP_TOOL_GAP_DP = 8
}

/** 세 영역(상태 띠 / 지도 / 컨트롤)이 겹치지 않는 고정 높이 합산. 지도는 남는 높이 전부. */
object SurveyLayout {
    fun infoHeightDp(phase: Phase): Int = when (phase) {
        Phase.IDLE -> SurveyDims.COORD_LINE_DP
        Phase.ACQUIRING, Phase.RESULT, Phase.RECORDING -> SurveyDims.INFO_STRIP_DP
        Phase.RESULT_EXPANDED -> SurveyDims.INFO_STRIP_DP + SurveyDims.EXPANDED_EXTRA_DP
    }
    fun fixedHeightDp(phase: Phase): Int = SurveyDims.STATUS_BAND_DP + infoHeightDp(phase) + SurveyDims.CONTROL_AREA_DP
    fun mapHeightDp(screenHeightDp: Int, phase: Phase): Int = screenHeightDp - fixedHeightDp(phase)
    fun mapFraction(screenHeightDp: Int, phase: Phase): Double = mapHeightDp(screenHeightDp, phase).toDouble() / screenHeightDp
}
