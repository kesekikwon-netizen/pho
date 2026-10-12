package kr.archsurvey.gcp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.map.*
import kr.archsurvey.ui.TileImages

/** 지도 위 표적·대상 표시 하나 */
data class MapPin(val lat: Double, val lon: Double, val label: String, val color: Color, val filled: Boolean, val id: Long = 0, val big: Boolean = false)

/**
 * V월드 위성 + 지적선(측량 앱 MapCore·TileImages 그대로) 위에 표적·대상을 그린다.
 * 키가 없거나 타일이 안 오면 회색 바탕 + 10 m 격자로 표적 배치만 보인다.
 * 두 손가락으로 확대·이동, 한 번 누르면 가장 가까운 표시를 [onPick].
 */
@Composable
fun GcpMap(pins: List<MapPin>, me: Pair<Double, Double>?, access: VWorldAccess, modifier: Modifier = Modifier, minSpanM: Double = 20.0, caption: String? = null, onPick: ((MapPin) -> Unit)? = null) {
    val pts = pins.map { it.lat to it.lon } + listOfNotNull(me)
    val fitKey = pins.map { it.id to it.label }.hashCode() to (pins.isEmpty() && me != null)
    var scale by remember(fitKey) { mutableFloatStateOf(1f) }
    var pan by remember(fitKey) { mutableStateOf(Offset.Zero) }
    val tm = rememberTextMeasurer()
    val layers = remember(access) { MapLayers.resolve(MapBase.SATELLITE_CADASTRAL, access) }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFFDCD9CF)).testTag("map")) {
        if (pts.isEmpty()) {
            Text("수신기를 연결하면 내 위치와 표적이 보여요", style = GT.sansL.sz(13.sp), color = G.sub, modifier = Modifier.align(Alignment.Center))
        } else {
            val lat0 = (pts.minOf { it.first } + pts.maxOf { it.first }) / 2
            val lon0 = (pts.minOf { it.second } + pts.maxOf { it.second }) / 2
            val geo = remember(lat0, lon0) { LocalProjection(lat0, lon0) }
            val loc = pts.map { geo.toLocal(it.first, it.second) }
            val span = maxOf(minSpanM, (loc.maxOf { it.x } - loc.minOf { it.x }) * 1.5, (loc.maxOf { it.y } - loc.minOf { it.y }) * 1.5)
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(fitKey) { detectTransformGestures { _, p, z, _ -> scale = (scale * z).coerceIn(0.2f, 20f); pan += p } }
                    .pointerInput(fitKey, pins, onPick) {
                        detectTapGestures { tap ->
                            val cb = onPick ?: return@detectTapGestures
                            val ppm = (minOf(size.width, size.height) / span * scale).toFloat()
                            val best = pins.minByOrNull { p -> val q = geo.toLocal(p.lat, p.lon); val sx = size.width / 2f + pan.x + q.x.toFloat() * ppm; val sy = size.height / 2f + pan.y - q.y.toFloat() * ppm; Math.hypot((sx - tap.x).toDouble(), (sy - tap.y).toDouble()) }
                            if (best != null) cb(best)
                        }
                    }
                    .testTag("map_canvas"),
            ) {
                val ppm = (minOf(size.width, size.height) / span * scale).toFloat()
                fun sx(x: Double) = size.width / 2f + pan.x + x.toFloat() * ppm
                fun sy(y: Double) = size.height / 2f + pan.y - y.toFloat() * ppm
                TileImages.version
                val drew = drawTiles(layers, geo, ppm, pan, ::sx, ::sy)
                if (!drew) drawGrid(ppm, ::sx, ::sy)
                for (p in pins) {
                    val q = geo.toLocal(p.lat, p.lon); val c = Offset(sx(q.x), sy(q.y))
                    val h = (if (p.big) 9.dp else 7.dp).toPx()
                    drawRect(Color.White, c - Offset(h + 2, h + 2), Size(2 * h + 4, 2 * h + 4))
                    if (p.filled) drawRect(p.color, c - Offset(h, h), Size(2 * h, 2 * h))
                    else { drawRect(Color.White, c - Offset(h, h), Size(2 * h, 2 * h)); drawRect(p.color, c - Offset(h, h), Size(2 * h, 2 * h), style = Stroke(2.5.dp.toPx())) }
                    val lay = tm.measure(p.label, GT.monoM.sz(if (p.big) 13.sp else 12.sp).copy(color = Color.White))
                    val tl = Offset(c.x + h + 4.dp.toPx(), c.y - lay.size.height / 2f)
                    drawRect(Color(0xCC141413), tl - Offset(3f, 1f), Size(lay.size.width + 6f, lay.size.height + 2f))
                    drawText(lay, topLeft = tl)
                }
                if (me != null) {
                    val q = geo.toLocal(me.first, me.second); val c = Offset(sx(q.x), sy(q.y))
                    drawCircle(Color.White, 9.dp.toPx(), c); drawCircle(G.fix, 6.5.dp.toPx(), c)
                }
            }
        }
        if (caption != null) Text(caption, style = GT.sans.sz(12.sp), color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA141413)).padding(horizontal = 6.dp, vertical = 2.dp))
        if (access.key.isBlank()) Text("V월드 키 없음 · 설정 › 고급", style = GT.sans.sz(11.sp), color = G.sub, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
}

/** 측량 앱 CanvasMapSurface.drawTiles 와 같은 계산(웹 메르카토르 타일을 평면에 놓기). 그린 타일이 있으면 true */
private fun DrawScope.drawTiles(layers: List<TileLayerSpec>, geo: LocalProjection, ppm: Float, pan: Offset, sx: (Double) -> Float, sy: (Double) -> Float): Boolean {
    if (layers.isEmpty()) return false
    val cx = -pan.x / ppm.toDouble(); val cy = pan.y / ppm.toDouble()
    val halfW = size.width / 2.0 / ppm; val halfH = size.height / 2.0 / ppm
    val corners = listOf(LocalXY(cx - halfW, cy - halfH), LocalXY(cx + halfW, cy + halfH), LocalXY(cx - halfW, cy + halfH), LocalXY(cx + halfW, cy - halfH)).map { geo.toLatLon(it) }
    val latMin = corners.minOf { it.first }; val latMax = corners.maxOf { it.first }
    val lonMin = corners.minOf { it.second }; val lonMax = corners.maxOf { it.second }
    val z = MapLayers.zoomFor(ppm.toDouble(), (latMin + latMax) / 2)
    val x0 = TileMath.tileIndex(TileMath.lonToX(lonMin, z)); val x1 = TileMath.tileIndex(TileMath.lonToX(lonMax, z))
    val y0 = TileMath.tileIndex(TileMath.latToY(latMax, z)); val y1 = TileMath.tileIndex(TileMath.latToY(latMin, z))
    if ((x1 - x0 + 1) * (y1 - y0 + 1) > 64) return false
    var any = false
    for (layer in layers) {
        if (layer.id == "cad" && z < MapLayers.CADASTRAL_MIN_ZOOM) continue
        for (ty in y0..y1) for (tx in x0..x1) {
            val img = TileImages.get(layer.urlOf(z, tx, ty)) ?: continue
            val tl = geo.toLocal(TileMath.yToLat(ty * 256.0, z), TileMath.xToLon(tx * 256.0, z))
            val br = geo.toLocal(TileMath.yToLat((ty + 1) * 256.0, z), TileMath.xToLon((tx + 1) * 256.0, z))
            val l = kotlin.math.floor(sx(tl.x)).toInt(); val tp = kotlin.math.floor(sy(tl.y)).toInt()
            val r = kotlin.math.ceil(sx(br.x)).toInt(); val b = kotlin.math.ceil(sy(br.y)).toInt()
            if (r <= l || b <= tp) continue
            drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset(l, tp), dstSize = IntSize(r - l, b - tp), alpha = layer.alpha)
            any = true
        }
    }
    return any
}

private fun DrawScope.drawGrid(ppm: Float, sx: (Double) -> Float, sy: (Double) -> Float) {
    val step = if (ppm > 8f) 1.0 else if (ppm > 1.5f) 10.0 else 100.0
    val c = Color(0x33141413)
    var x = Math.floor(((0 - sx(0.0)) / ppm) / step) * step
    while (sx(x) <= size.width) { drawLine(c, Offset(sx(x), 0f), Offset(sx(x), size.height), 1f); x += step }
    var y = Math.floor(((sy(0.0) - size.height) / ppm) / step) * step
    while (sy(y) >= 0) { drawLine(c, Offset(0f, sy(y)), Offset(size.width, sy(y)), 1f); y += step }
}
