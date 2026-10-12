package kr.archsurvey.map

/** 0.1.14 목업 22 「위치 모양 설정」: 현재 위치·측점·찾아갈 목표의 모양·크기, 부가 정보(오차 원/방향), 점 이름 배경색 */
enum class CurrentShape(val labelKo: String) { RING("원형 표적"), DOT("점"), ARROW("화살표") }
enum class PointShape(val labelKo: String) { DOT("원"), SQUARE("사각형"), TRIANGLE("삼각형") }
enum class TargetShape(val labelKo: String) { FLAG("깃발"), CROSS("십자") }
enum class MarkerExtra(val labelKo: String) { ERROR("오차"), HEADING("방향") }
enum class LabelBg(val labelKo: String) { BLACK("검정색"), WHITE("흰색") }

data class MarkerStyle(
    val current: CurrentShape = CurrentShape.RING, val currentSize: Int = 3,
    val point: PointShape = PointShape.DOT, val pointSize: Int = 3,
    val target: TargetShape = TargetShape.FLAG, val targetSize: Int = 3,
    val extra: MarkerExtra = MarkerExtra.ERROR,
    val labelBg: LabelBg = LabelBg.WHITE,
) {
    /** 크기 1~5 → 배율(3 = 1.0) */
    fun scale(size: Int): Float = 0.6f + 0.2f * size.coerceIn(1, 5)

    fun encode(): String = listOf(current.name, currentSize, point.name, pointSize, target.name, targetSize, extra.name, labelBg.name).joinToString(",")

    companion object {
        val SIZES = (1..5).toList()
        fun decode(s: String?): MarkerStyle {
            val p = s?.split(',') ?: return MarkerStyle()
            if (p.size < 8) return MarkerStyle()
            return runCatching {
                MarkerStyle(
                    CurrentShape.valueOf(p[0]), p[1].toInt().coerceIn(1, 5), PointShape.valueOf(p[2]), p[3].toInt().coerceIn(1, 5),
                    TargetShape.valueOf(p[4]), p[5].toInt().coerceIn(1, 5), MarkerExtra.valueOf(p[6]), LabelBg.valueOf(p[7]),
                )
            }.getOrDefault(MarkerStyle())
        }
    }
}
