package kr.archsurvey.ui

import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

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
                // 0.1.58 B2: GeoTIFF 실제 그림(네 모서리에 맞춰 사상). 아직 없거나 못 읽으면 기존 윤곽+빗금
                BgImages.version
                val bmp = bgl.imagePath?.takeIf { bgl.imageCorners.size == 4 }?.let { BgImages.get(it) }
                if (bmp != null) {
                    val src = floatArrayOf(0f, 0f, bmp.width.toFloat(), 0f, bmp.width.toFloat(), bmp.height.toFloat(), 0f, bmp.height.toFloat())
                    val dst = FloatArray(8); bgl.imageCorners.forEachIndexed { i, q -> dst[i * 2] = sx(q.x); dst[i * 2 + 1] = sy(q.y) }
                    val m = android.graphics.Matrix()
                    if (m.setPolyToPoly(src, 0, dst, 0, 4)) {
                        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * a).toInt().coerceIn(0, 255) }
                        drawIntoCanvas { it.nativeCanvas.drawBitmap(bmp, m, paint) }
                        drawPath(path, t.accent.copy(alpha = (0.35f + 0.65f * a).coerceAtMost(1f)), style = Stroke(1.5f * density))
                        continue
                    }
                }
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
                val r = tm.measure(lb.text, TextStyle(fontSize = 16.sp, color = areaCol, fontWeight = FontWeight.Normal))
                safeText(tm, lb.text, Offset(sx(lb.at.x) - r.size.width / 2f, sy(lb.at.y) - r.size.height / 2f), TextStyle(fontSize = 16.sp, color = areaCol, fontWeight = FontWeight.Normal))
            }
            // 유구 외곽: 선/면 + 꼭짓점 번호
            for (o in state.outlines) {
                if (o.pts.isEmpty()) continue
                if (o.kind.isNotEmpty()) { drawDigOutline(tm, o, ::sx, ::sy, t); continue }
                val col = ArchColors[o.colorIdx % ArchColors.size]
                val path = Path()
                o.pts.forEachIndexed { i, q -> if (i == 0) path.moveTo(sx(q.x), sy(q.y)) else path.lineTo(sx(q.x), sy(q.y)) }
                if (o.closed && o.pts.size >= 3) { path.close(); drawPath(path, col.copy(alpha = 0.22f)) }
                drawPath(path, col, style = Stroke(2.5f * density, pathEffect = if (o.active) PathEffect.dashPathEffect(floatArrayOf(10f * density, 6f * density)) else null))
                o.pts.forEachIndexed { i, q ->
                    drawCircle(col, 4.5f * density, Offset(sx(q.x), sy(q.y)))
                    safeText(tm, "${i + 1}", Offset(sx(q.x) + 6f * density, sy(q.y) + 2f * density), TextStyle(fontSize = 16.sp, color = col, fontWeight = FontWeight.Normal))
                }
                if (o.pts.size >= 2) safeText(tm, o.label, Offset(sx(o.pts[0].x) - 4f * density, sy(o.pts[0].y) - 24f * density), TextStyle(fontSize = 16.sp, color = col, fontWeight = FontWeight.Normal))
            }
            // 이동 꼬리
            if (state.trail.size >= 2) {
                val path = Path()
                state.trail.forEachIndexed { i, p -> if (i == 0 || i in state.trailBreaks) path.moveTo(sx(p.x), sy(p.y)) else path.lineTo(sx(p.x), sy(p.y)) } // 0.1.30 T26 점프에서 끊음
                drawPath(path, t.fixed.copy(alpha = 0.45f), style = Stroke(2.5f * density))
            }
            // 저장된 점
            // 0.1.35: 점끼리 차례로 잇는 선은 그리지 않는다(발굴 도면은 윤곽만 잇는다)
            for (sct in state.sections) drawSection(tm, sct, ::sx, ::sy, t)
            val st = state.style
            val pr = 7f * density * st.scale(st.pointSize)
            for (m in state.points) {
                val o = Offset(sx(m.at.x), sy(m.at.y))
                if (m.sym != kr.archsurvey.map.MapSym.DOT) { drawDigMarker(tm, m, o, t); continue }
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
                    val lay = tm.measure(m.name, TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal))
                    drawRect(if (dark) t.ink.copy(alpha = 0.85f) else t.bg.copy(alpha = 0.85f), Offset(lt.x - 2f * density, lt.y), androidx.compose.ui.geometry.Size(lay.size.width + 4f * density, lay.size.height.toFloat()))
                    drawText(tm, m.name, lt, TextStyle(fontSize = 16.sp, color = if (dark) t.bg else t.ink, fontWeight = FontWeight.Normal))
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
            // 0.1.35 말뚝 찾아가기 목표: 테라코타 ✚ + 이름 + 지금 위치에서 잇는 점선
            state.stake?.let { sk ->
                val o = Offset(sx(sk.at.x), sy(sk.at.y))
                state.current?.let { cur -> drawLine(t.primary, Offset(sx(cur.at.x), sy(cur.at.y)), o, 2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * density, 6f * density))) }
                drawCircle(t.bg, 12f * density, o); drawCircle(t.primary, 12f * density, o, style = Stroke(2.5f * density))
                drawLine(t.primary, Offset(o.x - 8f * density, o.y), Offset(o.x + 8f * density, o.y), 2.5f * density)
                drawLine(t.primary, Offset(o.x, o.y - 8f * density), Offset(o.x, o.y + 8f * density), 2.5f * density)
                haloText(tm, sk.text, Offset(o.x + 14f * density, o.y - 22f * density), TextStyle(fontSize = 16.sp, color = t.primary, fontWeight = FontWeight.Medium), t)
            }
            // 0.1.30 T24: 폰 위치(참고) — 회색 속 빈 원 + 정확도 원 + 이름표. 수신기 표시(색 채운 모양)와 다르게
            state.phone?.let { ph ->
                val o = Offset(sx(ph.at.x), sy(ph.at.y))
                val grey = t.inkSub
                val rAcc = (ph.accuracyM * ppm).toFloat().coerceIn(14f * density, 140f * density)
                drawCircle(grey.copy(alpha = 0.10f), rAcc, o)
                drawCircle(grey.copy(alpha = 0.55f), rAcc, o, style = Stroke(1.5f * density, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))))
                drawCircle(t.bg, 10f * density, o, style = Stroke(5f * density))
                drawCircle(grey, 8f * density, o, style = Stroke(3f * density))
                val lt = Offset(o.x + 12f * density, o.y + 6f * density)
                if (lt.x >= -200f * density && lt.y >= -40f * density && lt.x < size.width && lt.y < size.height) {
                    val ts = TextStyle(fontSize = 16.sp, color = t.inkSub, fontWeight = FontWeight.Normal)
                    val lay = tm.measure(ph.label, ts)
                    drawRect(t.bg.copy(alpha = 0.85f), Offset(lt.x - 2f * density, lt.y), androidx.compose.ui.geometry.Size(lay.size.width + 4f * density, lay.size.height.toFloat()))
                    drawText(tm, ph.label, lt, ts)
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


// ---------------- 0.1.35 발굴 도면 기호 ----------------

/** 아이보리 테두리 글자(위성 사진 위에서도 읽힘). 화면 밖은 그리지 않는다 */
private fun DrawScope.haloText(tm: androidx.compose.ui.text.TextMeasurer, text: String, at: Offset, style: TextStyle, t: Tokens) {
    if (at.x < -300f * density || at.y < -40f * density || at.x > size.width || at.y > size.height) return
    val lay = tm.measure(text, style)
    drawRect(t.bg.copy(alpha = 0.82f), Offset(at.x - 2f * density, at.y), androidx.compose.ui.geometry.Size(lay.size.width + 4f * density, lay.size.height.toFloat()))
    drawText(lay, topLeft = at)
}

/** 유구(검은 윤곽 + 옅은 채움 + 명조 이름) · 진행 중 유구(테라코타 선 + 꼭짓점) · 트렌치(점선) · 조사구역(굵은 점선) */
private fun DrawScope.drawDigOutline(tm: androidx.compose.ui.text.TextMeasurer, o: kr.archsurvey.map.MapOutline, sx: (Double) -> Float, sy: (Double) -> Float, t: Tokens) {
    val path = Path()
    o.pts.forEachIndexed { i, q -> if (i == 0) path.moveTo(sx(q.x), sy(q.y)) else path.lineTo(sx(q.x), sy(q.y)) }
    val ink = Color(0xFF141413)
    val closed = o.closed && o.pts.size >= 3
    if (closed) path.close()
    val cx = o.pts.sumOf { it.x } / o.pts.size; val cy = o.pts.sumOf { it.y } / o.pts.size
    when (o.kind) {
        "area" -> {
            drawPath(path, ink, style = Stroke(3f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f * density, 7f * density))))
            haloText(tm, o.label, Offset(sx(o.pts[0].x) + 6f * density, sy(o.pts[0].y) + 4f * density), TextStyle(fontSize = 16.sp, color = ink, fontFamily = Fonts.serif), t)
        }
        "trench" -> {
            drawPath(path, ink, style = Stroke(2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f * density, 5f * density))))
            haloText(tm, o.label, Offset(sx(cx), sy(cy)), TextStyle(fontSize = 16.sp, color = ink, fontFamily = Fonts.mono), t)
        }
        else -> if (o.active) {
            drawPath(path, t.primary, style = Stroke(2.5f * density))
            o.pts.forEachIndexed { i, q ->
                val p = Offset(sx(q.x), sy(q.y))
                if (i == o.pts.lastIndex) drawCircle(t.primary, 6.5f * density, p)
                else { drawCircle(Color.White, 5.5f * density, p); drawCircle(t.primary, 5.5f * density, p, style = Stroke(2f * density)) }
            }
            haloText(tm, o.label, Offset(sx(o.pts.last().x) - 20f * density, sy(o.pts.last().y) + 10f * density), TextStyle(fontSize = 16.sp, color = t.primary, fontFamily = Fonts.serif), t)
        } else {
            if (closed) drawPath(path, Color(0xFFF2E8DC).copy(alpha = 0.78f))
            drawPath(path, ink, style = Stroke(2.2f * density))
            val st = TextStyle(fontSize = 16.sp, color = ink, fontFamily = Fonts.serif)
            val w = tm.measure(o.label, st).size.width
            haloText(tm, o.label, Offset(sx(cx) - w / 2f, sy(cy) - 10f * density), st, t)
        }
    }
}

/** 토층 단면선: 선 + 양 끝 눈금 + 「A — A′ 토층」 */
private fun DrawScope.drawSection(tm: androidx.compose.ui.text.TextMeasurer, s: kr.archsurvey.map.MapSection, sx: (Double) -> Float, sy: (Double) -> Float, t: Tokens) {
    val a = Offset(sx(s.a.x), sy(s.a.y)); val b = Offset(sx(s.b.x), sy(s.b.y))
    val ink = Color(0xFF141413)
    drawLine(ink, a, b, 2.2f * density)
    val dx = b.x - a.x; val dy = b.y - a.y; val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
    val nx = -dy / len * 8f * density; val ny = dx / len * 8f * density
    for (e in listOf(a, b)) drawLine(ink, Offset(e.x - nx, e.y - ny), Offset(e.x + nx, e.y + ny), 2.2f * density)
    haloText(tm, s.label, Offset(a.x - 8f * density, a.y + 10f * density), TextStyle(fontSize = 16.sp, color = ink), t)
}

/** 유물 검은 네모+번호 · 레벨 ✕(+해발) · 기준점 △ · 말뚝 ✚ · 토층 끝 · 유구 꼭짓점(작은 점) */
private fun DrawScope.drawDigMarker(tm: androidx.compose.ui.text.TextMeasurer, m: kr.archsurvey.map.MapMarker, o: Offset, t: Tokens) {
    val ink = if (m.flagged) t.cautionInk else Color(0xFF141413)
    val d = density
    when (m.sym) {
        kr.archsurvey.map.MapSym.ARTIFACT -> {
            drawRect(Color.White, Offset(o.x - 5.5f * d, o.y - 5.5f * d), androidx.compose.ui.geometry.Size(11f * d, 11f * d))
            drawRect(ink, Offset(o.x - 4f * d, o.y - 4f * d), androidx.compose.ui.geometry.Size(8f * d, 8f * d))
            m.label?.let { haloText(tm, it, Offset(o.x - 6f * d, o.y + 7f * d), TextStyle(fontSize = 16.sp, color = ink, fontFamily = Fonts.mono), t) }
        }
        kr.archsurvey.map.MapSym.LEVEL -> {
            drawLine(ink, Offset(o.x - 5f * d, o.y - 5f * d), Offset(o.x + 5f * d, o.y + 5f * d), 2f * d)
            drawLine(ink, Offset(o.x - 5f * d, o.y + 5f * d), Offset(o.x + 5f * d, o.y - 5f * d), 2f * d)
            m.label?.let { haloText(tm, it, Offset(o.x + 7f * d, o.y - 8f * d), TextStyle(fontSize = 16.sp, color = ink, fontFamily = Fonts.mono), t) }
        }
        kr.archsurvey.map.MapSym.BM -> {
            val tri = Path().apply { moveTo(o.x, o.y - 9f * d); lineTo(o.x - 8f * d, o.y + 6f * d); lineTo(o.x + 8f * d, o.y + 6f * d); close() }
            drawPath(tri, Color.White); drawPath(tri, ink, style = Stroke(2.2f * d))
            m.label?.let { haloText(tm, it, Offset(o.x + 11f * d, o.y - 9f * d), TextStyle(fontSize = 16.sp, color = ink, fontFamily = Fonts.mono), t) }
        }
        kr.archsurvey.map.MapSym.STAKE -> {
            drawLine(ink, Offset(o.x - 6f * d, o.y), Offset(o.x + 6f * d, o.y), 2.2f * d); drawLine(ink, Offset(o.x, o.y - 6f * d), Offset(o.x, o.y + 6f * d), 2.2f * d)
        }
        kr.archsurvey.map.MapSym.SECTION -> {
            drawCircle(ink, 3f * d, o)
            m.label?.let { haloText(tm, it, Offset(o.x + 6f * d, o.y - 18f * d), TextStyle(fontSize = 16.sp, color = ink), t) }
        }
        kr.archsurvey.map.MapSym.VERTEX, kr.archsurvey.map.MapSym.DOT -> {}
    }
}
