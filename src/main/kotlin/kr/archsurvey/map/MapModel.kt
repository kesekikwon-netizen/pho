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

/** 지도에 얹는 업로드 배경(도면) 한 장의 윤곽. 정합이 끝난 것만 넘긴다. [preview] 는 수동 정렬 중 미리보기. */
data class MapBgLayer(val id: String, val label: String, val polygon: List<LocalXY>, val opacity: Double, val preview: Boolean = false)

/** 고고학 외곽(선·면). 꼭짓점은 취득 순서대로 1,2,3… 번호를 붙여 그린다. [active]=아직 이어서 취득 중 */
data class MapOutline(val label: String, val pts: List<LocalXY>, val closed: Boolean, val active: Boolean, val colorIdx: Int)
data class MapText(val text: String, val at: LocalXY)

data class MapViewState(
    val center: LocalXY,
    /** 화면 1 m 당 dp */
    val dpPerM: Double,
    val points: List<MapMarker>,
    val current: CurrentMarker?,
    val trail: List<LocalXY>,

    /** 업로드 배경(아래→위 순서). 기본은 없음 */
    val backgrounds: List<MapBgLayer> = emptyList(),
    /** 활성 그리드의 선(바깥 테두리 포함)·칸 이름(칸 중심)·지금 위치가 속한 칸 윤곽·구역 색 번호 */
    val gridLines: List<Pair<LocalXY, LocalXY>> = emptyList(),
    val gridLabels: List<MapText> = emptyList(),
    val cellHighlight: List<LocalXY> = emptyList(),
    val gridColorIdx: Int = 0,
    val outlines: List<MapOutline> = emptyList(),
    /** 0.1.12 V월드 타일 레이어(아래→위). 비어 있으면 배경 없음 */
    val tiles: List<TileLayerSpec> = emptyList(),
    /** 타일을 놓기 위한 위경도↔지도 평면 변환(작업 원점). tiles 가 있을 때만 필요 */
    val geo: LocalProjection? = null,
)

/** 지도 그리기 구현을 갈아끼우기 위한 경계. WP-3 은 자체 Canvas, 이후 MapLibre(WP-9 스파이크 결과에 따라). */
interface MapSurface {
    @Composable fun Draw(state: MapViewState, modifier: Modifier)
}
