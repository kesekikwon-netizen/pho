package kr.archsurvey.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kr.archsurvey.core.SurveyStatus
import kotlin.math.cos

/** 작업 원점 기준 평면 좌표(m). x=동, y=북. 지도 그림 전용(측량 좌표 N/E 와 별개). */
data class LocalXY(val x: Double, val y: Double)

/** 원점 부근(수 km 이내)에서 쓰는 단순 등장방형 근사. 화면 표시용이며 측량 계산에는 쓰지 않는다. */
class LocalProjection(val lat0: Double, val lon0: Double) {
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))
    fun toLocal(lat: Double, lon: Double) = LocalXY((lon - lon0) * mPerDegLon, (lat - lat0) * mPerDegLat)
    fun toLatLon(p: LocalXY) = (lat0 + p.y / mPerDegLat) to (lon0 + p.x / mPerDegLon)
}

data class MapMarker(val name: String, val at: LocalXY, val flagged: Boolean = false)

data class CurrentMarker(val at: LocalXY, val accuracyM: Double, val status: SurveyStatus, val headingDeg: Double?)

data class MapViewState(
    val center: LocalXY,
    /** 화면 1 m 당 dp */
    val dpPerM: Double,
    val points: List<MapMarker>,
    val current: CurrentMarker?,
    val trail: List<LocalXY>,
    val backdropLabel: String = "가상 지도 · 디자인용 예시",
)

/** 지도 그리기 구현을 갈아끼우기 위한 경계. WP-3 은 자체 Canvas, 이후 MapLibre(WP-9 스파이크 결과에 따라). */
interface MapSurface {
    @Composable fun Draw(state: MapViewState, modifier: Modifier)
}

/** 가짜 배경(디자인용): 필지처럼 보이는 사각형과 조사 구역 경계. 실제 지도 데이터 아님. */
object FakeBackdrop {
    data class Plot(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val tone: Int)
    val plots = listOf(
        Plot(-60.0, -40.0, -8.0, 22.0, 0), Plot(-8.0, -40.0, 30.0, -12.0, 1), Plot(30.0, -40.0, 90.0, 10.0, 2),
        Plot(-60.0, 22.0, -8.0, 70.0, 1), Plot(-8.0, 38.0, 44.0, 70.0, 0), Plot(44.0, 10.0, 90.0, 70.0, 1),
        Plot(-8.0, -12.0, 14.0, 4.0, 2),
    )
    /** 조사 구역 경계(점선 사각형) */
    val siteBounds = Plot(-6.0, -4.0, 48.0, 36.0, 0)
}
