package kr.archsurvey.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 0.1.31 시작 화면 길이(ms). 끝나면 [SplashScreen] 의 onDone → 직전 작업 화면(앱 동작은 그동안 뒤에서 그대로 시작) */
object SplashSpec {
    const val DURATION_MS = 3_000
    const val TITLE = "발굴 측량"
    const val SUBTITLE = "발굴 현장 GNSS 측량"
}

/** 진행률(0..1)의 [a]..[b] 구간을 0..1 로 */
internal fun splashPhase(p: Float, a: Float, b: Float): Float = ((p - a) / (b - a)).coerceIn(0f, 1f)

/**
 * 0.1.31 시작 모션(직접 그린 원본 — 외부 그림·로고 복사 없음): 아이보리 바탕에
 * ① 발굴 그리드 3×3 가는 선이 그어지고 ② 가운데에서 수신 신호 고리가 퍼진 뒤 ③ 측점 십자와 점이 자리 잡는다.
 * 제목은 천천히 나타나고, 아래 가는 선이 3초 진행을 보여 준다. 뒤로 단추 = 바로 건너뛰기.
 */
@Composable
fun SplashScreen(versionLabel: String, onDone: () -> Unit) {
    val t = T
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        prog.animateTo(1f, tween(SplashSpec.DURATION_MS, easing = LinearEasing))
        onDone()
    }
    androidx.activity.compose.BackHandler { onDone() }
    val p = prog.value
    Box(Modifier.fillMaxSize().background(t.splashBg).semantics { contentDescription = "발굴 측량 시작 중" }.testTag("splash")) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(168.dp).testTag("splash_motion")) {
                val w = size.minDimension; val o = Offset((size.width - w) / 2f, (size.height - w) / 2f)
                val thin = 1.2.dp.toPx(); val c = Offset(size.width / 2f, size.height / 2f)
                // ① 그리드: 바깥 사각형 + 안쪽 선 4개가 차례로 그어진다(0 ~ 0.45)
                val g = splashPhase(p, 0f, 0.45f)
                val lines = listOf(0f, 1f / 3f, 2f / 3f, 1f)
                lines.forEachIndexed { i, f ->
                    val k = splashPhase(g, i * 0.15f, i * 0.15f + 0.55f)
                    if (k > 0f) {
                        drawLine(t.splashLine.copy(alpha = 0.55f), Offset(o.x, o.y + w * f), Offset(o.x + w * k, o.y + w * f), thin, StrokeCap.Round)
                        drawLine(t.splashLine.copy(alpha = 0.55f), Offset(o.x + w * f, o.y), Offset(o.x + w * f, o.y + w * k), thin, StrokeCap.Round)
                    }
                }
                // ② 신호 고리 3개가 가운데에서 퍼지며 옅어진다(0.30 ~ 0.85)
                for (i in 0 until 3) {
                    val r = splashPhase(p, 0.30f + i * 0.12f, 0.62f + i * 0.12f)
                    if (r > 0f && r < 1f) drawCircle(t.splashLine.copy(alpha = (1f - r) * 0.9f), radius = w * 0.08f + w * 0.40f * r, center = c, style = Stroke(thin))
                }
                // ③ 측점 십자 + 점(0.70 ~ 1.0)
                val m = splashPhase(p, 0.70f, 1f)
                if (m > 0f) {
                    val arm = w * 0.11f * m
                    drawLine(t.splashLineDeep, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), thin * 1.4f, StrokeCap.Round)
                    drawLine(t.splashLineDeep, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), thin * 1.4f, StrokeCap.Round)
                    drawCircle(t.splashLineDeep, radius = w * 0.022f * m, center = c)
                    drawCircle(t.splashLineDeep.copy(alpha = m), radius = w * 0.06f, center = c, style = Stroke(thin))
                }
            }
            Spacer(Modifier.height(28.dp))
            val ta = splashPhase(p, 0.15f, 0.55f)
            Text(SplashSpec.TITLE, style = Ty.title.copy(fontSize = 28.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp), color = t.splashInk, modifier = Modifier.alpha(ta).testTag("splash_title"))
            Spacer(Modifier.height(6.dp))
            Text(SplashSpec.SUBTITLE, style = Ty.small.copy(fontSize = 16.sp), color = t.splashInk.copy(alpha = 0.62f), modifier = Modifier.alpha(splashPhase(p, 0.35f, 0.75f)))
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(120.dp).height(2.dp).background(t.splashLine.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(t.splashLineDeep).testTag("splash_progress"))
            }
            Spacer(Modifier.height(12.dp))
            Text(versionLabel, style = Ty.small.copy(fontSize = 16.sp), color = t.splashInk.copy(alpha = 0.55f), modifier = Modifier.testTag("splash_version"))
        }
    }
}
