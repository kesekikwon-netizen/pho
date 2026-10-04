package kr.archsurvey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** 자체 Canvas 지도 (WP-3). 배경은 가짜 필지 격자 — 실제 지도가 아니다. */
class CanvasMapSurface : MapSurface {
    @Composable
    override fun Draw(state: MapViewState, modifier: Modifier) {
        val t = T
        val tm = rememberTextMeasurer()
        val tone = listOf(Color(0xFFEFEAD8), Color(0xFFE3EBD3), Color(0xFFF1DEDA))
        val toneDark = listOf(Color(0xFF34322B), Color(0xFF2E3829), Color(0xFF3B2E2C))
        val dark = t.bg.red < 0.5f
        Canvas(modifier.fillMaxSize().testTag("map_canvas")) {
            val ppm = (state.dpPerM * density).toFloat()
            val cx = size.width / 2f; val cy = size.height / 2f
            fun sx(x: Double) = cx + ((x - state.center.x) * ppm).toFloat()
            fun sy(y: Double) = cy - ((y - state.center.y) * ppm).toFloat()
            drawRect(if (dark) Color(0xFF26251F) else Color(0xFFF4F1E6), size = size)
            for (p in FakeBackdrop.plots) {
                val tl = Offset(sx(p.x0), sy(p.y1)); val br = Offset(sx(p.x1), sy(p.y0))
                drawRect((if (dark) toneDark else tone)[p.tone % 3], tl, Size(br.x - tl.x, br.y - tl.y))
                drawRect(t.line, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(1f * density))
            }
            drawGrid(state, ppm, ::sx, ::sy, t.outline.copy(alpha = 0.25f))
            // 조사 구역 경계
            val b = FakeBackdrop.siteBounds
            val btl = Offset(sx(b.x0), sy(b.y1)); val bbr = Offset(sx(b.x1), sy(b.y0))
            drawRect(t.accent, btl, Size(bbr.x - btl.x, bbr.y - btl.y),
                style = Stroke(2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 6f * density))))
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
                    TextStyle(fontSize = 11.sp, color = t.ink, fontWeight = FontWeight.Normal))
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
