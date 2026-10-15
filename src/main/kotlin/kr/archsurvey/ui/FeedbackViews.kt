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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/** 저장 알림(터치를 가로채지 않음)·확인 대화상자·선택 창. 화면 맨 위(모든 화면 위)에 그린다 */
@Composable
fun FeedbackOverlays(ui: FeedbackHub) {
    // 0.1.29 T22-1·T22-2: 시스템 뒤로는 맨 위 창부터 — 선택 창이면 창만 닫고(고르지 않음), 확인 창이면 「취소」와 같다.
    // 이 함수는 화면 맨 끝에 그려져 화면 BackHandler(backTarget)보다 나중에 등록 → 우선. 확인 창 처리를 나중에 등록해 둘 다 떠 있으면 확인 창부터.
    // (예전: 창은 그대로 두고 뒤 화면이 홈으로 가거나 연결 화면이 닫혔다)
    androidx.activity.compose.BackHandler(enabled = ui.choice != null) { ui.dismissChoice() }
    androidx.activity.compose.BackHandler(enabled = ui.confirm != null) { ui.answer(false) }
    ui.choice?.let { ChoiceSheet(ui, it) }
    ui.confirm?.let { ConfirmDialog(ui, it) }
    ToastView(ui)
}

@Composable
private fun ToastView(ui: FeedbackHub) {
    val t = T
    val msg = ui.toast ?: return
    val kind = ui.toastKind
    val view = androidx.compose.ui.platform.LocalView.current
    val hap = LocalTapHaptic.current
    // 0.1.14: 완료/오류 멘트에 햅틱 한 번(같은 알림이 다시 그려져도 한 번만). 오류는 조금 더 오래 보인다
    LaunchedEffect(ui.toastSeq) {
        if (hap) when (kind) { ToastKind.OK -> TapHaptics.ok(view); ToastKind.ERROR -> TapHaptics.error(view); else -> {} }
        delay(if (kind == ToastKind.ERROR) ui.toastMs + 2000L else ui.toastMs); ui.hideToast()
    }
    val bgc = if (kind == ToastKind.ERROR) t.bad else t.ink
    val fg = if (kind == ToastKind.ERROR) t.onBad else t.bg
    // 0.1.8: 지도가 화면에 있으면 알림은 지도 아래쪽 안에만(펼침에서 오른쪽 판을 가리지 않음). 없으면 예전 자리(위)
    val map = ToastAnchor.mapBounds
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val dens = androidx.compose.ui.platform.LocalDensity.current
    if (map != null) {
        // 바깥 = 화면 전체(뿌리 기준 원점 재기) · 안 = 지도 경계 안 아래쪽
        Box(Modifier.fillMaxSize().onGloballyPositionedOrigin { origin = it }) {
            Box(with(dens) {
                Modifier.offset((map.left - origin.x).toDp(), (map.top - origin.y).toDp())
                    .size(map.width.toDp(), (map.height.toDp() - ToastAnchor.bottomGap).coerceAtLeast(0.dp))
                    .padding(start = 12.dp, end = ToastAnchor.rightGap).testTag("toast_area_map")
            }, contentAlignment = Alignment.BottomStart) { ToastBody(ui, msg, kind, bgc, fg) }
        }
        return
    }
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 88.dp), contentAlignment = Alignment.TopCenter) { ToastBody(ui, msg, kind, bgc, fg) }
}

@Composable
private fun ToastBody(ui: FeedbackHub, msg: String, kind: ToastKind, bgc: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    run {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(bgc).padding(horizontal = 16.dp, vertical = 12.dp).testTag("toast")
                .semantics { contentDescription = when (kind) { ToastKind.OK -> "완료"; ToastKind.ERROR -> "오류"; else -> "알림" } },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (kind == ToastKind.ERROR) Text("✕", style = Ty.label, color = fg, modifier = Modifier.testTag("toast_error_mark"))
            else GlyphIcon(Glyph.CHECK, fg, 20.dp)
            Text(msg, style = Ty.body, color = fg, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("toast_text"))
        }
    }
}

@Composable
private fun ConfirmDialog(ui: FeedbackHub, r: ConfirmRequest) {
    val t = T
    Box(Modifier.fillMaxSize().background(t.scrim).hClickable { ui.answer(false) }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.line, RoundedCornerShape(20.dp))
                .hClickable(enabled = false) {}.padding(20.dp).testTag("confirm_dialog"),
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
        Box(Modifier.fillMaxSize().background(t.scrim).hClickable { ui.dismissChoice() })
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
                            .border(if (on) 1.5.dp else 1.dp, if (on) t.accent else t.line, RoundedCornerShape(16.dp)).hClickable { ui.pick(i) }
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

/** 0.1.8 알림 자리: 지도 화면이 자기 경계(뿌리 좌표)를 알려 준다. 지도가 없으면 null */
object ToastAnchor {
    var mapBounds by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** 0.1.12 오른손 #4: 알림은 지도 아래 왼쪽(축척 줄 위). 오른쪽 아래 내 위치·확대 단추와 측량 단추를 가리지 않게 오른쪽을 비운다 */
    val bottomGap = 40.dp
    val rightGap = 140.dp
}

private fun Modifier.onGloballyPositionedOrigin(f: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    this.then(Modifier.onGloballyPositioned { f(it.positionInRoot()) })
