package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 저장 알림(터치를 가로채지 않음)·확인 대화상자·선택 창. 화면 맨 위(모든 화면 위)에 그린다 */
@Composable
fun FeedbackOverlays(ui: FeedbackHub) {
    ui.choice?.let { ChoiceSheet(ui, it) }
    ui.confirm?.let { ConfirmDialog(ui, it) }
    ToastView(ui)
}

@Composable
private fun ToastView(ui: FeedbackHub) {
    val t = T
    val msg = ui.toast ?: return
    LaunchedEffect(ui.toastSeq) { delay(ui.toastMs); ui.hideToast() }
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 88.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(t.ink).padding(horizontal = 16.dp, vertical = 12.dp).testTag("toast"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlyphIcon(Glyph.CHECK, t.bg, 20.dp)
            Text(msg, style = Ty.body, color = t.bg, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("toast_text"))
        }
    }
}

@Composable
private fun ConfirmDialog(ui: FeedbackHub, r: ConfirmRequest) {
    val t = T
    Box(Modifier.fillMaxSize().background(t.scrim).clickable { ui.answer(false) }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.line, RoundedCornerShape(20.dp))
                .clickable(enabled = false) {}.padding(20.dp).testTag("confirm_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(r.title, style = Ty.title, color = t.ink, modifier = Modifier.testTag("confirm_title"))
            if (r.body.isNotBlank()) Text(r.body, style = Ty.body, color = t.inkSub)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MBtn(Modifier.weight(1f).height(56.dp).testTag("confirm_cancel"), MKind.PLAIN, 16.dp, onClick = { ui.answer(false) }) { Text(r.cancelLabel, style = Ty.label, color = t.ink) }
                MBtn(Modifier.weight(1f).height(56.dp).testTag("confirm_ok"), MKind.PRIMARY, 16.dp, onClick = { ui.answer(true) }) { Text(r.okLabel, style = Ty.label, color = t.onPrimary) }
            }
        }
    }
}

@Composable
private fun ChoiceSheet(ui: FeedbackHub, r: ChoiceRequest) {
    val t = T
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim).clickable { ui.dismissChoice() })
        val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp).clip(shape).background(t.bg).border(1.dp, t.line, shape)
                .padding(horizontal = 16.dp, vertical = 20.dp).testTag("choice_sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(r.title, style = Ty.title, color = t.ink)
            r.note?.let { Text(it, style = Ty.small, color = t.inkSub) }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, o) in r.options.withIndex()) {
                    val on = i == r.selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(if (on) t.primaryCont else t.bg)
                            .border(if (on) 1.5.dp else 1.dp, if (on) t.accent else t.line, RoundedCornerShape(16.dp)).clickable { ui.pick(i) }
                            .padding(horizontal = 16.dp).testTag("${r.tagPrefix}_$i"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, if (on) t.accent else t.outline, CircleShape), contentAlignment = Alignment.Center) {
                            if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(t.accent))
                        }
                        Text(o, style = Ty.body, color = t.ink, modifier = Modifier.weight(1f))
                    }
                }
            }
            MBtn(Modifier.fillMaxWidth().height(56.dp).testTag("choice_close"), MKind.PLAIN, 16.dp, onClick = { ui.dismissChoice() }) { Text("닫기", style = Ty.label, color = t.ink) }
        }
    }
}

enum class StepState { DONE, ACTIVE, WAIT, FAIL, SKIP }
data class StepView(val label: String, val state: StepState, val detail: String? = null)

/** 경과 시간(초). 1초마다 다시 그린다 */
@Composable
fun rememberElapsedSec(startMs: Long, now: () -> Long = System::currentTimeMillis): Long {
    var v by remember(startMs) { mutableLongStateOf(((now() - startMs) / 1000).coerceAtLeast(0)) }
    LaunchedEffect(startMs) { while (true) { delay(1000); v = ((now() - startMs) / 1000).coerceAtLeast(0) } }
    return v
}

/** 진행 창: 제목 · 단계 목록(✓ 완료 / ● 진행 / ○ 대기 / ✕ 실패) · 경과 시간 · 아래 버튼들 */
@Composable
fun ProgressWindow(
    title: String, steps: List<StepView>, elapsedSec: Long, tag: String,
    note: String? = null, progress: Float? = null, buttons: @Composable RowScope.() -> Unit,
) {
    val t = T
    // 뒤 화면 터치를 막되(진행 중 실수 방지) 의미 트리는 합치지 않는다
    Box(Modifier.fillMaxSize().background(t.scrim).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.line, RoundedCornerShape(20.dp)).padding(20.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Ty.title, color = t.ink, modifier = Modifier.weight(1f).testTag("${tag}_title"))
                Text("${elapsedSec}초", style = Ty.coord.copy(fontSize = 16.sp), color = t.inkSub, modifier = Modifier.testTag("${tag}_elapsed"))
            }
            if (progress != null) {
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(t.card).border(1.dp, t.line, RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(t.accent))
                }
            }
            for ((i, s) in steps.withIndex()) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("${tag}_step_$i"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val (mark, col) = when (s.state) {
                        StepState.DONE -> "✓" to t.fixed; StepState.ACTIVE -> "●" to t.accent; StepState.WAIT -> "○" to t.inkSub
                        StepState.FAIL -> "✕" to t.bad; StepState.SKIP -> "–" to t.inkSub
                    }
                    Text(mark, style = Ty.label.copy(fontSize = 18.sp), color = col, modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.label + when (s.state) { StepState.ACTIVE -> " · 진행 중"; StepState.SKIP -> " · 건너뜀"; StepState.FAIL -> " · 실패"; else -> "" },
                            style = Ty.body, color = if (s.state == StepState.WAIT || s.state == StepState.SKIP) t.inkSub else t.ink)
                        s.detail?.let { Text(it, style = Ty.small, color = t.inkSub) }
                    }
                }
            }
            note?.let { Text(it, style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("${tag}_note")) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = buttons)
        }
    }
}
