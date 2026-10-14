package kr.archsurvey.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 0.1.50 / 0.1.12 오른손 한 엄지 조작(design-program/04-right-hand.md #6·#9). 두 앱에서 글자 하나 다르지 않게 유지한다.
 * - [releaseTap]: 손을 뗄 때 실행, 누른 채 8dp 넘게 밀리면 취소, 같은 단추 연타 0.8초 막기(장갑·걷는 중 스침 방지).
 * - [oneThumbMap]: 한 손가락 끌기 = 이동, 두 번 탭 후 위아래로 끌기 = 확대·축소(아래 = 확대), 두 번 탭 = 확대,
 *   두 손가락 = 이동·확대(예전과 같음). 왼쪽 끝 24dp 는 시스템 뒤로 제스처 몫이라 받지 않는다.
 */
object ThumbSpec {
    val tapSlop = 8.dp
    const val REPEAT_GUARD_MS = 800L
    val backEdge = 24.dp
    /** 두 번 탭 확대: 끌기 이만큼(dp)마다 2배 */
    const val DRAG_ZOOM_DP_PER_DOUBLE = 160f
}

/** 손을 뗄 때 실행하는 탭(+ 선택: 길게 누름). 접근성 클릭도 같은 막기를 거친다 */
fun Modifier.releaseTap(
    label: String? = null,
    enabled: Boolean = true,
    guardMs: Long = ThumbSpec.REPEAT_GUARD_MS,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val view = LocalView.current; val haptic = LocalTapHaptic.current
    val click = rememberUpdatedState(onClick); val long = rememberUpdatedState(onLongClick)
    val last = remember { longArrayOf(Long.MIN_VALUE / 4) }
    val fire: () -> Unit = {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - last[0] >= guardMs) { last[0] = now; if (haptic) TapHaptics.tap(view); click.value() }
    }
    val fireLong: () -> Unit = { long.value?.let { if (haptic) TapHaptics.long(view); it() } }
    this.semantics {
        role = Role.Button
        if (enabled) {
            onClick(label) { fire(); true }
            if (onLongClick != null) onLongClick { fireLong(); true }
        }
    }.then(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        val slop = ThumbSpec.tapSlop.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            var up = false
            val inTime = withTimeoutOrNull(if (long.value != null) viewConfiguration.longPressTimeoutMillis else Long.MAX_VALUE / 4) {
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) { if (!ch.isConsumed) { ch.consume(); up = true }; break }
                    if (ch.isConsumed || (ch.position - down.position).getDistance() > slop) break
                }
                true
            }
            if (inTime == null) {
                fireLong()
                while (true) { val ev = awaitPointerEvent(); ev.changes.forEach { it.consume() }; if (ev.changes.none { it.pressed }) break }
            } else if (up) fire()
        }
    })
}

/** 한 엄지 지도 제스처. [onPan] 화면 px 이동, [onZoom] 배율과 기준점(px, 지도 왼쪽 위 기준) */
fun Modifier.oneThumbMap(onPan: (Offset) -> Unit, onZoom: (Float, Offset) -> Unit): Modifier = composed {
    val pan = rememberUpdatedState(onPan); val zoom = rememberUpdatedState(onZoom)
    pointerInput(Unit) {
        val edge = ThumbSpec.backEdge.toPx(); val slop = ThumbSpec.tapSlop.toPx()
        val perDouble = ThumbSpec.DRAG_ZOOM_DP_PER_DOUBLE.dp.toPx()
        var lastTapUp = Long.MIN_VALUE / 4; var lastTapPos = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < edge) return@awaitEachGesture // 시스템 뒤로 제스처 몫
            val second = down.uptimeMillis - lastTapUp < viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastTapPos).getDistance() < 48.dp.toPx()
            var moving = false; var multi = false
            while (true) {
                val ev = awaitPointerEvent()
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    multi = true; moving = true
                    val p = ev.calculatePan(); val z = ev.calculateZoom(); val c = ev.calculateCentroid()
                    if (p != Offset.Zero) pan.value(p)
                    if (z != 1f && c != Offset.Unspecified) zoom.value(z, c)
                    ev.changes.forEach { it.consume() }
                    continue
                }
                val ch = pressed.first()
                if (!moving && (ch.position - down.position).getDistance() > slop) moving = true
                if (moving && !multi) {
                    val d = ch.position - ch.previousPosition
                    if (second) { if (d.y != 0f) zoom.value(Math.pow(2.0, (d.y / perDouble).toDouble()).toFloat(), down.position) }
                    else if (d != Offset.Zero) pan.value(d)
                    ch.consume()
                }
            }
            if (!moving) {
                if (second) { zoom.value(1.5f, down.position); lastTapUp = Long.MIN_VALUE / 4 }
                else { lastTapUp = android.os.SystemClock.uptimeMillis(); lastTapPos = down.position }
            } else lastTapUp = Long.MIN_VALUE / 4
        }
    }
}
