package kr.archsurvey.t0135

import kr.archsurvey.core.AntennaRecord
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.ui.SavedPoint

object DigFixtures {
    val ant = AntennaRecord(modelId = "TRIMBLE_R10", refPointId = "bottom_of_quick_release", refPointLabel = "퀵릴리즈", inputM = 1.6, method = "vertical", pcoV = 0.199, pcoH = 0.0, apcHeightM = 1.799)
    fun point(name: String, n: Double = 500000.0, e: Double = 200000.0, h: Double? = 50.0, type: String = "유구 경계점", at: Long = 1000L) = SavedPoint(
        name = name, type = type, lat = 37.5, lon = 127.1, northing = n, easting = e, crsEpsg = 5187, groundHeightM = h, sigmaHM = 0.008,
        status = SurveyStatus.FIXED, cautionFlag = false, epochs = 3, antenna = ant, fixQuality = 4, satellites = 20, measuredAtMs = at, groundMslM = h,
    )
}
