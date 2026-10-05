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
            // 0.1.12 V월드 배경(위성/일반지도) + 지적선. 타일이 오면 TileImages.version 이 바뀌어 다시 그린다
            if (state.tiles.isNotEmpty() && state.geo != null) { TileImages.version; drawTiles(state, ppm, ::sx, ::sy) }
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
                safeText(tm, lb.text, Offset(sx(lb.at.x) - r.size.width / 2f, sy(lb.at.y) - r.size.height / 2f), TextStyle(fontSize = 12.sp, color = areaCol, fontWeight = FontWeight.Normal))
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
                    safeText(tm, "${i + 1}", Offset(sx(q.x) + 6f * density, sy(q.y) + 2f * density), TextStyle(fontSize = 12.sp, color = col, fontWeight = FontWeight.Normal))
                }
                if (o.pts.size >= 2) safeText(tm, o.label, Offset(sx(o.pts[0].x) - 4f * density, sy(o.pts[0].y) - 24f * density), TextStyle(fontSize = 12.sp, color = col, fontWeight = FontWeight.Normal))
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
            val st = state.style
            val pr = 7f * density * st.scale(st.pointSize)
            for (m in state.points) {
                val o = Offset(sx(m.at.x), sy(m.at.y))
                val pc = if (m.flagged) t.caution else t.fixed
                when (st.point) {
                    kr.archsurvey.map.PointShape.DOT -> { drawCircle(pc, pr, o); drawCircle(t.bg, pr, o, style = Stroke(2f * density)) }
                    kr.archsurvey.map.PointShape.SQUARE -> {
                        drawRect(pc, Offset(o.x - pr, o.y - pr), androidx.compose.ui.geometry.Size(pr * 2, pr * 2))
                        drawRect(t.bg, Offset(o.x - pr, o.y - pr), androidx.compose.ui.geometry.Size(pr * 2, pr * 2), style = Stroke(2f * density))
                    }
                    kr.archsurvey.map.PointShape.TRIANGLE -> {
                        val tri = Path().apply { moveTo(o.x, o.y - pr * 1.2f); lineTo(o.x - pr, o.y + pr * 0.8f); lineTo(o.x + pr, o.y + pr * 0.8f); close() }
                        drawPath(tri, pc); drawPath(tri, t.bg, style = Stroke(2f * density))
                    }
                }
                // 0.1.11: 화면 밖(특히 위쪽 멀리) 점의 이름을 그리면 drawText 제약 높이가 수백만 px 가 되어 앱이 죽는다 → 보이는 범위만 그린다
                val lt = Offset(o.x + 10f * density, o.y - 22f * density)
                if (lt.x >= -200f * density && lt.y >= -40f * density && lt.x < size.width && lt.y < size.height)
                {
                    // 0.1.14 점 이름 배경색(검정/흰색)
                    val dark = st.labelBg == kr.archsurvey.map.LabelBg.BLACK
                    val lay = tm.measure(m.name, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal))
                    drawRect(if (dark) t.ink.copy(alpha = 0.85f) else t.bg.copy(alpha = 0.85f), Offset(lt.x - 2f * density, lt.y), androidx.compose.ui.geometry.Size(lay.size.width + 4f * density, lay.size.height.toFloat()))
                    drawText(tm, m.name, lt, TextStyle(fontSize = 12.sp, color = if (dark) t.bg else t.ink, fontWeight = FontWeight.Normal))
                }
            }
            // 0.1.14 찾아갈 목표(깃발/십자)
            state.target?.let { tg ->
                val o = Offset(sx(tg.x), sy(tg.y)); val k = st.scale(st.targetSize)
                if (st.target == kr.archsurvey.map.TargetShape.FLAG) {
                    drawLine(t.ink, o, Offset(o.x, o.y - 22f * density * k), 2f * density)
                    val f = Path().apply { moveTo(o.x, o.y - 22f * density * k); lineTo(o.x + 14f * density * k, o.y - 17f * density * k); lineTo(o.x, o.y - 12f * density * k); close() }
                    drawPath(f, t.bad)
                } else {
                    drawLine(t.bad, Offset(o.x - 10f * density * k, o.y), Offset(o.x + 10f * density * k, o.y), 2.5f * density)
                    drawLine(t.bad, Offset(o.x, o.y - 10f * density * k), Offset(o.x, o.y + 10f * density * k), 2.5f * density)
                }
            }
            // 현재 위치 (정확도 원 + 방향 삼각형)
            state.current?.let { c ->
                val o = Offset(sx(c.at.x), sy(c.at.y))
                val col = when (c.status) { SurveyStatus.FIXED -> t.fixed; SurveyStatus.CAUTION -> t.caution; SurveyStatus.UNAVAILABLE -> t.bad }
                val k = st.scale(st.currentSize)
                if (st.extra == kr.archsurvey.map.MarkerExtra.ERROR) {
                    val rAcc = (c.accuracyM * ppm).toFloat().coerceIn(12f * density, 120f * density)
                    drawCircle(col.copy(alpha = 0.18f), rAcc, o)
                    drawCircle(col.copy(alpha = 0.55f), rAcc, o, style = Stroke(1.5f * density))
                }
                when (st.current) {
                    kr.archsurvey.map.CurrentShape.RING -> {
                        drawCircle(t.bg, 11f * density * k, o); drawCircle(col, 8f * density * k, o)
                        drawCircle(col, 15f * density * k, o, style = Stroke(1.5f * density))
                    }
                    kr.archsurvey.map.CurrentShape.DOT -> { drawCircle(t.bg, 9f * density * k, o); drawCircle(col, 6.5f * density * k, o) }
                    kr.archsurvey.map.CurrentShape.ARROW -> {
                        rotate((c.headingDeg ?: 0.0).toFloat(), pivot = o) {
                            val a = Path().apply { moveTo(o.x, o.y - 14f * density * k); lineTo(o.x - 9f * density * k, o.y + 10f * density * k); lineTo(o.x, o.y + 5f * density * k); lineTo(o.x + 9f * density * k, o.y + 10f * density * k); close() }
                            drawPath(a, col); drawPath(a, t.bg, style = Stroke(2f * density))
                        }
                    }
                }
                if (st.extra == kr.archsurvey.map.MarkerExtra.HEADING) c.headingDeg?.let { h ->
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

    /** 웹 메르카토르 타일을 작업 평면(등장방형 근사)에 놓는다. 수 km 안에서는 어긋남이 화면 1 px 미만 [가설: 실기기 미확인] */
    private fun DrawScope.drawTiles(s: MapViewState, ppm: Float, sx: (Double) -> Float, sy: (Double) -> Float) {
        val geo = s.geo ?: return
        val halfW = size.width / 2.0 / ppm; val halfH = size.height / 2.0 / ppm
        val corners = listOf(LocalXY(s.center.x - halfW, s.center.y - halfH), LocalXY(s.center.x + halfW, s.center.y + halfH),
            LocalXY(s.center.x - halfW, s.center.y + halfH), LocalXY(s.center.x + halfW, s.center.y - halfH)).map { geo.toLatLon(it) }
        val latMin = corners.minOf { it.first }; val latMax = corners.maxOf { it.first }
        val lonMin = corners.minOf { it.second }; val lonMax = corners.maxOf { it.second }
        val z = MapLayers.zoomFor(ppm.toDouble(), (latMin + latMax) / 2)
        val x0 = TileMath.tileIndex(TileMath.lonToX(lonMin, z)); val x1 = TileMath.tileIndex(TileMath.lonToX(lonMax, z))
        val y0 = TileMath.tileIndex(TileMath.latToY(latMax, z)); val y1 = TileMath.tileIndex(TileMath.latToY(latMin, z))
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > 64) return
        for (layer in s.tiles) {
            if (layer.id == "cad" && z < MapLayers.CADASTRAL_MIN_ZOOM) continue
            for (ty in y0..y1) for (tx in x0..x1) {
                val img = TileImages.get(layer.urlOf(z, tx, ty)) ?: continue
                val tl = geo.toLocal(TileMath.yToLat(ty * 256.0, z), TileMath.xToLon(tx * 256.0, z))
                val br = geo.toLocal(TileMath.yToLat((ty + 1) * 256.0, z), TileMath.xToLon((tx + 1) * 256.0, z))
                val l = kotlin.math.floor(sx(tl.x)).toInt(); val tp = kotlin.math.floor(sy(tl.y)).toInt()
                val r = kotlin.math.ceil(sx(br.x)).toInt(); val b = kotlin.math.ceil(sy(br.y)).toInt()
                if (r <= l || b <= tp) continue
                drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(l, tp), dstSize = androidx.compose.ui.unit.IntSize(r - l, b - tp), alpha = layer.alpha)
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

/** 화면 안(여유 포함)에 있을 때만 글자를 그린다. 멀리 벗어난 좌표로 drawText 를 부르면 제약 크기 초과로 앱이 죽는다(0.1.11 발견) */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.safeText(tm: androidx.compose.ui.text.TextMeasurer, text: String, at: Offset, style: TextStyle) {
    if (at.x >= -400f && at.y >= -100f && at.x < size.width && at.y < size.height) drawText(tm, text, at, style)
}
