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

enum class Glyph { PLUS, MINUS, LOCATE, UNDO, CHEVRON_UP, CHEVRON_DOWN, MIC, CLOSE, CAMERA, RULER, LAYERS, LIST, CHECK, EXTENT, BT, PIN, SAT, BATT }

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
            Glyph.RULER -> { drawLine(color, Offset(w * .5f, w * .1f), Offset(w * .5f, w * .9f), sw, cap); drawLine(color, Offset(w * .3f, w * .1f), Offset(w * .7f, w * .1f), sw, cap); drawLine(color, Offset(w * .3f, w * .9f), Offset(w * .7f, w * .9f), sw, cap) }
        }
    }
}
