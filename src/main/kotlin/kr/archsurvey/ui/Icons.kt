package kr.archsurvey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.archsurvey.core.SurveyStatus

/** 외부 아이콘 라이브러리 없이 Canvas 로 그리는 작은 라인 아이콘 */
@Composable
fun StatusIcon(status: SurveyStatus, color: Color, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val sw = w * 0.09f
        when (status) {
            SurveyStatus.FIXED -> {
                drawCircle(color, w * 0.46f, style = Stroke(sw))
                val p = Path().apply { moveTo(w * 0.28f, w * 0.52f); lineTo(w * 0.44f, w * 0.68f); lineTo(w * 0.74f, w * 0.34f) }
                drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round))
            }
            SurveyStatus.CAUTION -> {
                val p = Path().apply { moveTo(w * 0.5f, w * 0.1f); lineTo(w * 0.92f, w * 0.86f); lineTo(w * 0.08f, w * 0.86f); close() }
                drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round))
                drawLine(color, Offset(w * 0.5f, w * 0.38f), Offset(w * 0.5f, w * 0.6f), sw, StrokeCap.Round)
                drawCircle(color, sw * 0.7f, Offset(w * 0.5f, w * 0.74f))
            }
            SurveyStatus.UNAVAILABLE -> {
                drawCircle(color, w * 0.46f, style = Stroke(sw))
                drawLine(color, Offset(w * 0.3f, w * 0.3f), Offset(w * 0.7f, w * 0.7f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.7f, w * 0.3f), Offset(w * 0.3f, w * 0.7f), sw, StrokeCap.Round)
            }
        }
    }
}

@Composable
fun GearIcon(color: Color, size: Dp = 26.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val c = Offset(w / 2, w / 2); val sw = w * 0.09f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val s = Offset(c.x + (Math.cos(a) * w * 0.34).toFloat(), c.y + (Math.sin(a) * w * 0.34).toFloat())
            val e = Offset(c.x + (Math.cos(a) * w * 0.47).toFloat(), c.y + (Math.sin(a) * w * 0.47).toFloat())
            drawLine(color, s, e, sw * 1.4f, StrokeCap.Round)
        }
        drawCircle(color, w * 0.30f, c, style = Stroke(sw))
        drawCircle(color, w * 0.10f, c, style = Stroke(sw))
    }
}

enum class Glyph { PLUS, MINUS, LOCATE, UNDO, CHEVRON_UP, CHEVRON_DOWN, MIC, CLOSE, CAMERA, RULER, LAYERS, LIST, CHECK, EXTENT, BT, PIN, SAT, BATT, FOLDER, SHARE, INFO, CALC, HOME,
    /** 0.1.11 목업 화면용 */
    MENU, BACK, CHEVRON_RIGHT, SEARCH, POLYLINE, POLYGON, GRID, POLE, WIFI, GLOBE,
    /** 0.1.43 시안(10-12) */
    SLIDERS, EXPORT, FACE, ARTIFACT, LEVEL, SECTION, TRENCH, STAKE, BM, AREA }

@Composable
fun GlyphIcon(g: Glyph, color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val sw = w * 0.09f; val cap = StrokeCap.Round
        when (g) {
            Glyph.PLUS -> { drawLine(color, Offset(w * .2f, w * .5f), Offset(w * .8f, w * .5f), sw, cap); drawLine(color, Offset(w * .5f, w * .2f), Offset(w * .5f, w * .8f), sw, cap) }
            Glyph.MINUS -> drawLine(color, Offset(w * .2f, w * .5f), Offset(w * .8f, w * .5f), sw, cap)
            Glyph.LOCATE -> {
                drawCircle(color, w * .22f, style = Stroke(sw)); drawCircle(color, w * .07f)
                drawLine(color, Offset(w * .5f, w * .04f), Offset(w * .5f, w * .2f), sw, cap); drawLine(color, Offset(w * .5f, w * .8f), Offset(w * .5f, w * .96f), sw, cap)
                drawLine(color, Offset(w * .04f, w * .5f), Offset(w * .2f, w * .5f), sw, cap); drawLine(color, Offset(w * .8f, w * .5f), Offset(w * .96f, w * .5f), sw, cap)
            }
            Glyph.UNDO -> {
                val p = Path().apply { moveTo(w * .3f, w * .3f); lineTo(w * .15f, w * .45f); lineTo(w * .3f, w * .6f) }
                drawPath(p, color, style = Stroke(sw, cap = cap))
                val q = Path().apply { moveTo(w * .15f, w * .45f); lineTo(w * .6f, w * .45f); cubicTo(w * .9f, w * .45f, w * .9f, w * .85f, w * .6f, w * .85f); lineTo(w * .4f, w * .85f) }
                drawPath(q, color, style = Stroke(sw, cap = cap))
            }
            Glyph.CHEVRON_UP -> drawPath(Path().apply { moveTo(w * .2f, w * .62f); lineTo(w * .5f, w * .34f); lineTo(w * .8f, w * .62f) }, color, style = Stroke(sw, cap = cap))
            Glyph.CHEVRON_DOWN -> drawPath(Path().apply { moveTo(w * .2f, w * .38f); lineTo(w * .5f, w * .66f); lineTo(w * .8f, w * .38f) }, color, style = Stroke(sw, cap = cap))
            Glyph.MIC -> {
                drawRoundRect(color, Offset(w * .38f, w * .1f), androidx.compose.ui.geometry.Size(w * .24f, w * .44f), androidx.compose.ui.geometry.CornerRadius(w * .12f), style = Stroke(sw))
                drawPath(Path().apply { moveTo(w * .24f, w * .46f); cubicTo(w * .24f, w * .78f, w * .76f, w * .78f, w * .76f, w * .46f) }, color, style = Stroke(sw, cap = cap))
                drawLine(color, Offset(w * .5f, w * .72f), Offset(w * .5f, w * .9f), sw, cap)
            }
            Glyph.CLOSE -> { drawLine(color, Offset(w * .25f, w * .25f), Offset(w * .75f, w * .75f), sw, cap); drawLine(color, Offset(w * .75f, w * .25f), Offset(w * .25f, w * .75f), sw, cap) }
            Glyph.CAMERA -> {
                drawRoundRect(color, Offset(w * .1f, w * .28f), androidx.compose.ui.geometry.Size(w * .8f, w * .55f), androidx.compose.ui.geometry.CornerRadius(w * .08f), style = Stroke(sw))
                drawCircle(color, w * .15f, Offset(w * .5f, w * .56f), style = Stroke(sw))
            }
            Glyph.LAYERS -> {
                val d = Path().apply { moveTo(w * .5f, w * .14f); lineTo(w * .86f, w * .34f); lineTo(w * .5f, w * .54f); lineTo(w * .14f, w * .34f); close() }
                drawPath(d, color, style = Stroke(sw, cap = cap))
                drawPath(Path().apply { moveTo(w * .14f, w * .52f); lineTo(w * .5f, w * .72f); lineTo(w * .86f, w * .52f) }, color, style = Stroke(sw, cap = cap))
                drawPath(Path().apply { moveTo(w * .14f, w * .7f); lineTo(w * .5f, w * .9f); lineTo(w * .86f, w * .7f) }, color, style = Stroke(sw, cap = cap))
            }
            Glyph.LIST -> { for (i in 0..2) { val y = w * (.26f + .24f * i); drawCircle(color, sw * .6f, Offset(w * .2f, y)); drawLine(color, Offset(w * .36f, y), Offset(w * .86f, y), sw, cap) } }
            Glyph.CHECK -> drawPath(Path().apply { moveTo(w * .2f, w * .52f); lineTo(w * .42f, w * .74f); lineTo(w * .8f, w * .28f) }, color, style = Stroke(sw, cap = cap))
            Glyph.EXTENT -> {
                for ((cx, cy) in listOf(.18f to .18f, .82f to .18f, .18f to .82f, .82f to .82f)) {
                    val dx = if (cx < .5f) 1f else -1f; val dy = if (cy < .5f) 1f else -1f
                    drawLine(color, Offset(w * cx, w * cy), Offset(w * (cx + .22f * dx), w * cy), sw, cap); drawLine(color, Offset(w * cx, w * cy), Offset(w * cx, w * (cy + .22f * dy)), sw, cap)
                }
            }
            Glyph.BT -> drawPath(Path().apply { moveTo(w * .28f, w * .32f); lineTo(w * .72f, w * .68f); lineTo(w * .5f, w * .88f); lineTo(w * .5f, w * .12f); lineTo(w * .72f, w * .32f); lineTo(w * .28f, w * .68f) }, color, style = Stroke(sw, cap = cap))
            Glyph.PIN -> { drawPath(Path().apply { moveTo(w * .5f, w * .9f); cubicTo(w * .15f, w * .55f, w * .2f, w * .1f, w * .5f, w * .1f); cubicTo(w * .8f, w * .1f, w * .85f, w * .55f, w * .5f, w * .9f) }, color, style = Stroke(sw, cap = cap)); drawCircle(color, w * .1f, Offset(w * .5f, w * .4f), style = Stroke(sw)) }
            Glyph.SAT -> { drawLine(color, Offset(w * .3f, w * .7f), Offset(w * .7f, w * .3f), sw * 2.2f, cap); drawLine(color, Offset(w * .12f, w * .88f), Offset(w * .3f, w * .7f), sw, cap); drawLine(color, Offset(w * .6f, w * .9f), Offset(w * .9f, w * .6f), sw, cap) }
            Glyph.BATT -> { drawRoundRect(color, Offset(w * .3f, w * .16f), androidx.compose.ui.geometry.Size(w * .4f, w * .72f), androidx.compose.ui.geometry.CornerRadius(w * .06f), style = Stroke(sw)); drawLine(color, Offset(w * .42f, w * .08f), Offset(w * .58f, w * .08f), sw, cap) }
            Glyph.FOLDER -> drawPath(Path().apply { moveTo(w * .1f, w * .78f); lineTo(w * .1f, w * .22f); lineTo(w * .4f, w * .22f); lineTo(w * .5f, w * .34f); lineTo(w * .9f, w * .34f); lineTo(w * .9f, w * .78f); close() }, color, style = Stroke(sw, cap = cap))
            Glyph.SHARE -> { drawPath(Path().apply { moveTo(w * .3f, w * .42f); lineTo(w * .2f, w * .42f); lineTo(w * .2f, w * .88f); lineTo(w * .8f, w * .88f); lineTo(w * .8f, w * .42f); lineTo(w * .7f, w * .42f) }, color, style = Stroke(sw, cap = cap)); drawLine(color, Offset(w * .5f, w * .12f), Offset(w * .5f, w * .62f), sw, cap); drawPath(Path().apply { moveTo(w * .34f, w * .28f); lineTo(w * .5f, w * .12f); lineTo(w * .66f, w * .28f) }, color, style = Stroke(sw, cap = cap)) }
            Glyph.INFO -> { drawCircle(color, w * .4f, Offset(w * .5f, w * .5f), style = Stroke(sw)); drawLine(color, Offset(w * .5f, w * .46f), Offset(w * .5f, w * .7f), sw, cap); drawCircle(color, sw * .6f, Offset(w * .5f, w * .3f)) }
            Glyph.CALC -> { drawRoundRect(color, Offset(w * .2f, w * .1f), androidx.compose.ui.geometry.Size(w * .6f, w * .8f), androidx.compose.ui.geometry.CornerRadius(w * .08f), style = Stroke(sw)); drawLine(color, Offset(w * .3f, w * .28f), Offset(w * .7f, w * .28f), sw, cap); for (r in 0..1) for (q in 0..2) drawCircle(color, sw * .5f, Offset(w * (.34f + .16f * q), w * (.5f + .18f * r))) }
            Glyph.HOME -> drawPath(Path().apply { moveTo(w * .12f, w * .5f); lineTo(w * .5f, w * .16f); lineTo(w * .88f, w * .5f); moveTo(w * .22f, w * .44f); lineTo(w * .22f, w * .86f); lineTo(w * .78f, w * .86f); lineTo(w * .78f, w * .44f) }, color, style = Stroke(sw, cap = cap))
            Glyph.MENU -> { for (i in 0..2) { val y = w * (.28f + .22f * i); drawLine(color, Offset(w * .16f, y), Offset(w * .84f, y), sw, cap) } }
            Glyph.BACK -> drawPath(Path().apply { moveTo(w * .62f, w * .2f); lineTo(w * .32f, w * .5f); lineTo(w * .62f, w * .8f) }, color, style = Stroke(sw, cap = cap))
            Glyph.CHEVRON_RIGHT -> drawPath(Path().apply { moveTo(w * .38f, w * .2f); lineTo(w * .68f, w * .5f); lineTo(w * .38f, w * .8f) }, color, style = Stroke(sw, cap = cap))
            Glyph.SEARCH -> { drawCircle(color, w * .26f, Offset(w * .44f, w * .44f), style = Stroke(sw)); drawLine(color, Offset(w * .63f, w * .63f), Offset(w * .86f, w * .86f), sw, cap) }
            Glyph.POLYLINE -> {
                val pts = listOf(Offset(w * .12f, w * .78f), Offset(w * .36f, w * .34f), Offset(w * .62f, w * .62f), Offset(w * .88f, w * .22f))
                for (i in 0 until pts.size - 1) drawLine(color, pts[i], pts[i + 1], sw, cap)
                for (q in pts) drawCircle(color, w * .07f, q)
            }
            Glyph.POLYGON -> {
                val pts = listOf(Offset(w * .2f, w * .3f), Offset(w * .74f, w * .16f), Offset(w * .86f, w * .66f), Offset(w * .3f, w * .84f))
                drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); for (q in pts.drop(1)) lineTo(q.x, q.y); close() }, color, style = Stroke(sw, cap = cap))
                for (q in pts) drawCircle(color, w * .07f, q)
            }
            Glyph.GRID -> { for (i in 0..3) { val v = w * (.14f + .24f * i); drawLine(color, Offset(v, w * .14f), Offset(v, w * .86f), sw * .8f, cap); drawLine(color, Offset(w * .14f, v), Offset(w * .86f, v), sw * .8f, cap) } }
            Glyph.POLE -> {
                drawLine(color, Offset(w * .5f, w * .28f), Offset(w * .5f, w * .92f), sw, cap)
                drawPath(Path().apply { moveTo(w * .26f, w * .28f); cubicTo(w * .26f, w * .06f, w * .74f, w * .06f, w * .74f, w * .28f); close() }, color, style = Stroke(sw, cap = cap))
                drawLine(color, Offset(w * .3f, w * .92f), Offset(w * .7f, w * .92f), sw, cap)
            }
            Glyph.WIFI -> {
                for ((i, r) in listOf(.38f, .26f, .14f).withIndex()) drawArc(color, 225f, 90f, false, Offset(w * .5f - w * r, w * .78f - w * r), androidx.compose.ui.geometry.Size(w * r * 2, w * r * 2), style = Stroke(sw, cap = cap))
                drawCircle(color, sw * .8f, Offset(w * .5f, w * .78f))
            }
            Glyph.GLOBE -> {
                drawCircle(color, w * .38f, Offset(w * .5f, w * .5f), style = Stroke(sw))
                drawOval(color, Offset(w * .32f, w * .12f), androidx.compose.ui.geometry.Size(w * .36f, w * .76f), style = Stroke(sw))
                drawLine(color, Offset(w * .12f, w * .5f), Offset(w * .88f, w * .5f), sw, cap)
            }
            Glyph.SLIDERS -> {
                drawLine(color, Offset(w * .14f, w * .34f), Offset(w * .86f, w * .34f), sw, cap); drawLine(color, Offset(w * .14f, w * .66f), Offset(w * .86f, w * .66f), sw, cap)
                drawCircle(color, w * .09f, Offset(w * .62f, w * .34f)); drawCircle(color, w * .09f, Offset(w * .36f, w * .66f))
            }
            Glyph.EXPORT -> {
                drawLine(color, Offset(w * .18f, w * .16f), Offset(w * .18f, w * .84f), sw, cap)
                drawLine(color, Offset(w * .34f, w * .5f), Offset(w * .84f, w * .5f), sw, cap)
                drawPath(Path().apply { moveTo(w * .64f, w * .3f); lineTo(w * .84f, w * .5f); lineTo(w * .64f, w * .7f) }, color, style = Stroke(sw, cap = cap))
            }
            Glyph.FACE -> drawPath(Path().apply { moveTo(w * .3f, w * .22f); lineTo(w * .84f, w * .3f); lineTo(w * .7f, w * .78f); lineTo(w * .16f, w * .7f); close() }, color, style = Stroke(sw, cap = cap))
            Glyph.ARTIFACT -> { drawCircle(color, w * .36f, Offset(w * .5f, w * .5f), style = Stroke(sw * .8f)); drawLine(color, Offset(w * .5f, w * .32f), Offset(w * .5f, w * .68f), sw, cap); drawLine(color, Offset(w * .42f, w * .38f), Offset(w * .5f, w * .32f), sw, cap) }
            Glyph.LEVEL -> { drawLine(color, Offset(w * .1f, w * .55f), Offset(w * .56f, w * .55f), sw, cap); drawLine(color, Offset(w * .33f, w * .32f), Offset(w * .33f, w * .78f), sw, cap); drawCircle(color, w * .05f, Offset(w * .78f, w * .4f)) }
            Glyph.SECTION -> { drawLine(color, Offset(w * .1f, w * .5f), Offset(w * .9f, w * .5f), sw, cap); drawLine(color, Offset(w * .1f, w * .34f), Offset(w * .1f, w * .66f), sw, cap); drawLine(color, Offset(w * .9f, w * .34f), Offset(w * .9f, w * .66f), sw, cap) }
            Glyph.TRENCH -> drawRect(color, Offset(w * .1f, w * .3f), androidx.compose.ui.geometry.Size(w * .8f, w * .4f), style = Stroke(sw * .8f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w * .1f, w * .06f))))
            Glyph.STAKE -> { drawLine(color, Offset(w * .5f, w * .16f), Offset(w * .5f, w * .84f), sw * 1.2f, cap); drawLine(color, Offset(w * .16f, w * .5f), Offset(w * .84f, w * .5f), sw * 1.2f, cap) }
            Glyph.BM -> { val tri = Path().apply { moveTo(w * .5f, w * .14f); lineTo(w * .9f, w * .84f); lineTo(w * .1f, w * .84f); close() }; drawPath(tri, Color(0xFFC9A227)); drawPath(tri, color, style = Stroke(sw * .8f)) }
            Glyph.AREA -> drawRect(color, Offset(w * .1f, w * .28f), androidx.compose.ui.geometry.Size(w * .8f, w * .44f), style = Stroke(sw * .8f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w * .16f, w * .06f, w * .03f, w * .06f))))
            Glyph.RULER -> { drawLine(color, Offset(w * .5f, w * .1f), Offset(w * .5f, w * .9f), sw, cap); drawLine(color, Offset(w * .3f, w * .1f), Offset(w * .7f, w * .1f), sw, cap); drawLine(color, Offset(w * .3f, w * .9f), Offset(w * .7f, w * .9f), sw, cap) }
        }
    }
}
