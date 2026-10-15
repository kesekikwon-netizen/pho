package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.archsurvey.dig.DigKind

/**
 * 0.1.53 (06-minimal §4) ⋯ 메뉴: 미니멀에서 숨긴 기능 전부를 한 곳에서. 모두 선택(한 번 누름)만.
 * 순서: [미니멀|일반] → 자주(점 목록·사진·메모·면 닫기·레이어·맞춤·확대 단추 보이기) → 작업 → 설정·수신기·전체 메뉴.
 */
@Composable
fun MoreMenu(c: SurveyController) {
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler { Minimal.moreOpen = false }
    fun go(f: () -> Unit) { Minimal.moreOpen = false; f() }
    Box(Modifier.fillMaxSize().background(D.scrim).hClickable { Minimal.moreOpen = false }.testTag("more_scrim")) {
        val sh = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.86f).clip(sh).background(D.bg).border(1.dp, D.hair, sh).hClickable {}
                .verticalScroll(rememberScrollState()).padding(16.dp).testTag("more_menu"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("더보기", style = DTy.h1, color = D.ink, modifier = Modifier.weight(1f))
                ModeChip(D.ink, D.card, D.line)
            }
            MoreHead("자주")
            MoreGrid(listOfNotNull(
                MoreItem(Glyph.LIST, "점 목록", "more_points") { go { c.homeOpen = false; c.hub.open(ToolScreen.POINTS) } },
                MoreItem(Glyph.CAMERA, "사진", "more_photo") { go { if (c.lastPoint != null) c.addPhotoCamera() else c.ui.toast("점을 잰 뒤 사진을 붙일 수 있어요") } },
                MoreItem(Glyph.MIC, if (c.voiceSeconds > 0) "녹음 끝" else "메모", "more_memo") { go { c.toggleVoice() } },
                if (c.dig.kind == DigKind.FEATURE) MoreItem(Glyph.FACE, if (c.dig.closeArmed) "닫기 취소" else "면 닫기", "more_close_area") { go { c.dig.armClose() } } else null,
                MoreItem(Glyph.LAYERS, "레이어", "more_layers") { go { c.homeOpen = false; c.dig.layersOpen = true } },
                MoreItem(Glyph.EXTENT, "맞춤", "more_fit") { go { c.fitAll() } },
                MoreItem(Glyph.PLUS, if (Minimal.zoomButtons) "확대 단추 숨기기" else "확대 단추 보이기", "more_zoom_buttons", on = Minimal.zoomButtons) { Minimal.setZoomButtons(ctx, !Minimal.zoomButtons) },
            ))
            MoreHead("작업")
            MoreGrid(listOf(
                MoreItem(Glyph.FOLDER, "다른 작업", "more_jobs") { go { c.jobs?.openList() ?: c.hub.open(ToolScreen.JOB_OPEN) } },
                MoreItem(Glyph.GRID, "구역·그리드", "more_areas") { go { c.homeOpen = false; c.hub.open(ToolScreen.AREAS) } },
                MoreItem(Glyph.LOCATE, "점 찾아가기", "more_goto") { go { c.hub.open(ToolScreen.GOTO) } },
                MoreItem(Glyph.FACE, "유구별", "more_features") { go { c.homeOpen = false; c.hub.open(ToolScreen.FEATURES) } },
                MoreItem(Glyph.EXPORT, "내보내기", "more_export") { go { c.exporter?.openScreen() } },
            ))
            MoreHead("설정")
            MoreGrid(listOf(
                MoreItem(Glyph.SLIDERS, "설정", "more_settings") { go { c.hub.open(ToolScreen.SETTINGS) } },
                MoreItem(Glyph.BT, "수신기", "more_receiver") { go { c.connection?.openScreen() ?: c.ui.toast("연결 화면을 열 수 없어요") } },
                MoreItem(Glyph.MENU, "전체 메뉴", "more_drawer") { go { c.drawerOpen = true } },
            ))
        }
    }
}

internal class MoreItem(val g: Glyph, val label: String, val tag: String, val on: Boolean = false, val onClick: () -> Unit)

@Composable private fun MoreHead(t: String) { Text(t, style = DTy.h2, color = D.sub, modifier = Modifier.padding(top = 6.dp)) }

@Composable
private fun MoreGrid(items: List<MoreItem>) {
    items.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { m ->
                val sh = RoundedCornerShape(14.dp)
                Column(Modifier.weight(1f).heightIn(min = 72.dp).clip(sh).background(if (m.on) D.sel else D.card).border(if (m.on) 2.dp else 1.5.dp, if (m.on) D.ink else D.line, sh)
                    .hClickable(onClickLabel = m.label, onClick = m.onClick).padding(6.dp).semantics { contentDescription = m.label }.testTag(m.tag),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    GlyphIcon(m.g, D.ink, 22.dp); Spacer(Modifier.height(4.dp))
                    Text(m.label, style = DTy.small.copy(fontWeight = FontWeight.Bold), color = D.ink, maxLines = 1)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
