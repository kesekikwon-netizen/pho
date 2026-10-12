package kr.archsurvey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 0.1.39 현장 지시 「모든 설정·측량 동작은 한 손가락으로 고르기만(키보드 없이)」 공용 부품.
 * 큰 숫자 고르기(−10 · −1 · 값 · +1 · +10, 64dp) · 칩 줄 · 「직접 입력」 접기.
 */
@Composable
internal fun BigNumberPicker(value: Int, range: IntRange, tag: String, suffix: String = "", onChange: (Int) -> Unit) {
    val t = T
    Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        @Composable fun step(label: String, d: Int, tg: String) =
            MBtn(Modifier.height(64.dp).widthIn(min = 52.dp).testTag("${tag}_$tg"), MKind.PLAIN, 16.dp, onClick = { onChange((value + d).coerceIn(range.first, range.last)) }) {
                Text(label, style = Ty.label.copy(fontSize = 17.sp), color = t.ink, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 4.dp))
            }
        step("−10", -10, "m10"); step("−", -1, "m1")
        Row(Modifier.weight(1f).height(64.dp).testTag("${tag}_value"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text("$value", style = Ty.coord.copy(fontSize = 28.sp), color = t.ink, maxLines = 1, softWrap = false)
            if (suffix.isNotEmpty()) Text(suffix, style = Ty.small, color = t.inkSub, maxLines = 1, softWrap = false)
        }
        step("+", 1, "p1"); step("+10", 10, "p10")
    }
}

/** 칩 줄(여러 줄로 감김). 고른 것은 체크 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChipChoices(options: List<T>, selected: T?, tagPrefix: String, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) Choice(label(o), o == selected, Modifier.testTag("${tagPrefix}_${label(o)}")) { onPick(o) }
    }
}

/** 「직접 입력 (키보드)」 접기 줄 — 고르기로 안 될 때만 */
@Composable
internal fun TypeFallbackRow(open: Boolean, tag: String, onToggle: () -> Unit) {
    val t = T
    MBtn(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag), MKind.PLAIN, 14.dp, onClick = onToggle) {
        Text(if (open) "직접 입력 닫기" else "직접 입력 (키보드)", style = Ty.small, color = t.inkSub, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

/** 0.1.39 자주 쓰는 비고(눌러서 넣기·빼기). 긴 글은 음성 메모 또는 「직접 입력」 */
object QuickNotes {
    val ITEMS = listOf("교란", "확인 필요", "다시 재기", "경계 불확실", "기준점 위", "나무 그늘", "물 고임", "출토 위치")
    /** 있으면 빼고 없으면 「 · 」로 덧붙임 */
    fun toggle(memo: String, note: String): String {
        val parts = memo.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        return (if (note in parts) parts - note else parts + note).joinToString(" · ")
    }
    fun has(memo: String, note: String) = note in memo.split("·").map { it.trim() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickNoteChips(memo: String, tagPrefix: String, onChange: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in QuickNotes.ITEMS) Choice(n, QuickNotes.has(memo, n), Modifier.testTag("${tagPrefix}_$n")) { onChange(QuickNotes.toggle(memo, n)) }
    }
}

/** 0.1.39 구역·그리드 이름/간격 칩 */
object OneFingerNames {
    val SPACINGS = listOf(0.5, 1.0, 2.0, 4.0, 5.0, 10.0, 20.0)
    fun m(v: Double) = (if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) + " m"
    fun areaChoices(taken: Set<String>, max: Int = 8): List<String> =
        (('A'..'H').map { it.toString() } + (1..9).map { "${it}지구" }).filter { it !in taken }.take(max)
    fun gridChoices(taken: Set<String>, max: Int = 6): List<String> =
        (1..30).map { "G$it" }.filter { it !in taken }.take(max)
}

/** 0.1.39 유구 종류 켜기/끄기 목록(자주 쓰는 발굴 유구 + 지금 켠 것) */
object OneFingerTypes {
    val COMMON = listOf("주거지", "수혈", "주혈", "구", "토광묘", "석곽묘", "석실묘", "옹관묘", "가마", "건물지", "고상건물지", "우물", "소성유구", "집석", "적석", "경작유구", "구상유구")
    fun catalog(on: List<String>): List<String> = (on + COMMON).distinct()
    /** 마지막 하나는 끄지 않음(유구 종류가 비면 안 됨) */
    fun toggle(on: List<String>, t: String): List<String> = if (t in on) (if (on.size > 1) on - t else on) else on + t
}

/** 칩을 여러 줄로 감싸는 줄(FlowRow 래퍼) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}
