package kr.archsurvey.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 측량 앱 MapChrome.kt 의 MBtn/MKind 만 그대로 옮김(연결 화면이 쓴다) */
internal enum class MKind { PLAIN, SELECTED, PRIMARY }

@Composable
internal fun MBtn(
    modifier: Modifier, kind: MKind = MKind.PLAIN, radius: Dp = 16.dp, alpha: Float = 1f,
    onLongClick: (() -> Unit)? = null,
    solid: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val t = T; val shape = RoundedCornerShape(radius)
    val bgc = solid ?: when (kind) { MKind.PRIMARY -> t.primary; MKind.SELECTED -> t.primaryCont; else -> t.raised }
    val bm = when (kind) {
        MKind.PRIMARY -> Modifier
        MKind.SELECTED -> Modifier.border(1.5.dp, t.accent, shape)
        else -> Modifier.border(1.dp, t.outline, shape)
    }
    Column(
        modifier.alpha(alpha).clip(shape).glossy(bgc).then(bm).hCombinedClickable(onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content,
    )
}

/** 측량 앱 머리글 높이(연결 오류 띠 위치용) — 이 앱은 머리글 56 + 상태 없음 */
object HeaderDims { const val TOP_DP = 56; const val FULL_DP = TOP_DP + 1; const val COMPACT_DP = TOP_DP + 1 }
object StatusLineDims { const val FULL_DP = 0; const val COMPACT_DP = 0 }

/** 연결 단계 표시가 읽는 지금 GGA 품질 코드(측량 앱 LocalSurvey.gv.quality.code 대신) */
val LocalFixCode = androidx.compose.runtime.compositionLocalOf { 0 }

@androidx.compose.runtime.Composable
internal fun rememberTick(active: Boolean = true): Int {
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    if (active) androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    return tick
}

/** 단순 머리글: ← 제목 (측량 앱 AppHeader 의 상태 줄 없이) */
@Composable
fun AppHeader(title: String, onBack: (() -> Unit)?, backTag: String = "header_back", modifier: Modifier = Modifier, trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) {
    val t = T
    Column(modifier.fillMaxWidth().background(t.bg)) {
        Row(Modifier.fillMaxWidth().height(HeaderDims.TOP_DP.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).hClickable(onClickLabel = "뒤로") { onBack() }.testTag(backTag), contentAlignment = Alignment.Center) {
                Text("←", style = Ty.title, color = t.ink)
            } else Spacer(Modifier.width(12.dp))
            Text(title, style = Ty.title, color = t.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
            trailing?.invoke(this)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.5f)))
    }
}
