package kr.archsurvey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.map.*
import kotlin.math.floor

/** 발굴 구역 색(6가지): 구역 번호 순서대로. 지도 그리드·외곽·구역 목록이 같은 색을 쓴다 */
val ArchColors = listOf(Color(0xFFC6613F), Color(0xFF1B6BB0), Color(0xFF3F7D4E), Color(0xFF8A5A9E), Color(0xFFB07A12), Color(0xFF2B7F86))

/** 자체 Canvas 지도 (WP-3). 배경 지도 없이 5·10 m 격자와 점·현재 위치만 그린다. */
class CanvasMapSurface(private val showBackdrop: Boolean = true) : MapSurface {
    @Composable
    override fun Draw(state: MapViewState, modifier: Modifier) {
        val t = T
        val tm = rememberTextMeasurer()
        val dark = t.bg.red < 0.5f
        Canvas(modifier.fillMaxSize().clipToBounds().testTag("map_canvas")) {
            val ppm = (state.dpPerM * density).toFloat()
            val cx = size.width / 2f; val cy = size.height / 2f
            fun sx(x: Double) = cx + ((x - state.center.x) * ppm).toFloat()
            fun sy(y: Double) = cy - ((y - state.center.y) * ppm).toFloat()
            if (showBackdrop) drawRect(t.mapBg, size = size)
            // 업로드 배경(도면): 투명도는 레이어 슬라이더 값. 윤곽 + 옅은 면 + 격자 느낌의 대각선
            for (bgl in state.backgrounds) {
                if (bgl.polygon.size < 3) continue
                val path = Path()
                bgl.polygon.forEachIndexed { i, q -> if (i == 0) path.moveTo(sx(q.x), sy(q.y)) else path.lineTo(sx(q.x), sy(q.y)) }
                path.close()
                val a = bgl.opacity.toFloat().coerceIn(0f, 1f)
                drawPath(path, t.accent.copy(alpha = 0.35f * a))
                clipPath(path) {
                    val xs = bgl.polygon.map { sx(it.x) }; val ys = bgl.polygon.map { sy(it.y) }
                    var gx = xs.min() - (ys.max() - ys.min())
                    while (gx < xs.max()) { drawLine(t.accent.copy(alpha = 0.5f * a), Offset(gx, ys.max()), Offset(gx + (ys.max() - ys.min()), ys.min()), 1f * density); gx += 14f * density }
                }
                drawPath(path, (if (bgl.preview) t.ink else t.accent).copy(alpha = (0.35f + 0.65f * a).coerceAtMost(1f)),
                    style = Stroke(if (bgl.preview) 2f * density else 1.5f * density,
                        pathEffect = if (bgl.preview) PathEffect.dashPathEffect(floatArrayOf(8f * density, 5f * density)) else null))
            }
            drawGrid(state, ppm, ::sx, ::sy, t.outline.copy(alpha = 0.25f))
            // 고고학: 그리드선·칸 이름·현재 칸 강조
            val areaCol = ArchColors[state.gridColorIdx % ArchColors.size]
            for ((a, b) in state.gridLines) drawLine(areaCol.copy(alpha = 0.55f), Offset(sx(a.x), sy(a.y)), Offset(sx(b.x), sy(b.y)), 1.2f * density)
            if (state.cellHighlight.size >= 3) {
                val path = Path()
                state.cellHighlight.forEachIndexed { i, q -> if (i == 0) path.moveTo(sx(q.x), sy(q.y)) else path.lineTo(sx(q.x), sy(q.y)) }
                path.close()
                drawPath(path, areaCol.copy(alpha = 0.16f)); drawPath(path, areaCol, style = Stroke(2.2f * density))
            }
            for (lb in state.gridLabels) {
                val r = tm.measure(lb.text, TextStyle(fontSize = 12.sp, color = areaCol, fontWeight = FontWeight.Normal))
                drawText(tm, lb.text, Offset(sx(lb.at.x) - r.size.width / 2f, sy(lb.at.y) - r.size.height / 2f), TextStyle(fontSize = 12.sp, color = areaCol, fontWeight = FontWeight.Normal))
            }
            // 유구 외곽: 선/면 + 꼭짓점 번호
            for (o in state.outlines) {
                if (o.pts.isEmpty()) continue
                val col = ArchColors[o.colorIdx % ArchColors.size]
                val path = Path()
                o.pts.forEachIndexed { i, q -> if (i == 0) path.moveTo(sx(q.x), sy(q.y)) else path.lineTo(sx(q.x), sy(q.y)) }
                if (o.closed && o.pts.size >= 3) { path.close(); drawPath(path, col.copy(alpha = 0.22f)) }
                drawPath(path, col, style = Stroke(2.5f * density, pathEffect = if (o.active) PathEffect.dashPathEffect(floatArrayOf(10f * density, 6f * density)) else null))
                o.pts.forEachIndexed { i, q ->
                    drawCircle(col, 4.5f * density, Offset(sx(q.x), sy(q.y)))
                    drawText(tm, "${i + 1}", Offset(sx(q.x) + 6f * density, sy(q.y) + 2f * density), TextStyle(fontSize = 12.sp, color = col, fontWeight = FontWeight.Normal))
                }
                if (o.pts.size >= 2) drawText(tm, o.label, Offset(sx(o.pts[0].x) - 4f * density, sy(o.pts[0].y) - 24f * density), TextStyle(fontSize = 12.sp, color = col, fontWeight = FontWeight.Normal))
            }
            // 이동 꼬리
            if (state.trail.size >= 2) {
                val path = Path()
                state.trail.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p.x), sy(p.y)) else path.lineTo(sx(p.x), sy(p.y)) }
                drawPath(path, t.fixed.copy(alpha = 0.45f), style = Stroke(2.5f * density))
            }
            // 저장된 점
            var prev: Offset? = null
            for (m in state.points) {
                val o = Offset(sx(m.at.x), sy(m.at.y))
                prev?.let { drawLine(t.ink, it, o, 1.5f * density) }
                prev = o
            }
            for (m in state.points) {
                val o = Offset(sx(m.at.x), sy(m.at.y))
                drawCircle(if (m.flagged) t.caution else t.fixed, 7f * density, o)
                drawCircle(t.bg, 7f * density, o, style = Stroke(2f * density))
                drawText(tm, m.name, Offset(o.x + 10f * density, o.y - 22f * density),
                    TextStyle(fontSize = 12.sp, color = t.ink, fontWeight = FontWeight.Normal))
            }
            // 현재 위치 (정확도 원 + 방향 삼각형)
            state.current?.let { c ->
                val o = Offset(sx(c.at.x), sy(c.at.y))
                val col = when (c.status) { SurveyStatus.FIXED -> t.fixed; SurveyStatus.CAUTION -> t.caution; SurveyStatus.UNAVAILABLE -> t.bad }
                val rAcc = (c.accuracyM * ppm).toFloat().coerceIn(12f * density, 120f * density)
                drawCircle(col.copy(alpha = 0.18f), rAcc, o)
                drawCircle(col.copy(alpha = 0.55f), rAcc, o, style = Stroke(1.5f * density))
                drawCircle(t.bg, 11f * density, o)
                drawCircle(col, 8f * density, o)
                c.headingDeg?.let { h ->
                    rotate(h.toFloat(), pivot = o) {
                        val tri = Path().apply {
                            moveTo(o.x, o.y - 15f * density); lineTo(o.x - 5f * density, o.y - 6f * density); lineTo(o.x + 5f * density, o.y - 6f * density); close()
                        }
                        drawPath(tri, t.ink)
                    }
                }
            }
        }
    }

    private fun DrawScope.drawGrid(s: MapViewState, ppm: Float, sx: (Double) -> Float, sy: (Double) -> Float, color: Color) {
        val step = when { s.dpPerM >= 20 -> 1.0; s.dpPerM >= 6 -> 5.0; else -> 10.0 }
        val halfW = size.width / 2f / ppm; val halfH = size.height / 2f / ppm
        var gx = floor((s.center.x - halfW) / step) * step
        while (gx <= s.center.x + halfW) { drawLine(color, Offset(sx(gx), 0f), Offset(sx(gx), size.height), 1f); gx += step }
        var gy = floor((s.center.y - halfH) / step) * step
        while (gy <= s.center.y + halfH) { drawLine(color, Offset(0f, sy(gy)), Offset(size.width, sy(gy)), 1f); gy += step }
    }
}
