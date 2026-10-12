package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.dig.DigKind
import kr.archsurvey.dig.LevelKind
import java.util.Locale
import kotlinx.coroutines.launch

/** 0.1.35 시트들: 무엇을 잴까요 · 지도에 보이기 · 지금 상태 · 메뉴 · 유구 이름 바꾸기 */

@Composable
internal fun DigSheet(title: String, tag: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val t = T
    androidx.activity.compose.BackHandler { onClose() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim.copy(alpha = 0.35f)).hClickable { onClose() })
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 640.dp).clip(shape).background(t.bg).border(1.dp, t.outline, shape)
                .verticalScroll(rememberScrollState()).padding(16.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Ty.serifHead, color = t.ink, modifier = Modifier.weight(1f))
                MBtn(Modifier.size(48.dp).testTag("${tag}_close"), MKind.PLAIN, 24.dp, onClick = onClose) { GlyphIcon(Glyph.CLOSE, t.ink, 18.dp) }
            }
            content()
        }
    }
}

@Composable
private fun SectionTitle(s: String) { Text(s, style = Ty.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = T.accentText) }

@Composable
private fun Tile(title: String, sub: String?, on: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val t = T
    Column(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(if (on) t.primaryCont else t.raised)
            .border(if (on) 2.dp else 1.dp, if (on) t.primary else t.outline, RoundedCornerShape(14.dp)).hClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 「무엇을 잴까요?」: 최근 3개 · 종류 8 타일 · 유구 선택/새 가번호 · 레벨 종류 · 기준점 이름 · 사용자 종류 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DigPickerSheet(c: SurveyController) {
    val d = c.dig
    val close = { d.pickerOpen = false }
    val scope = rememberCoroutineScope()
    DigSheet("무엇을 잴까요?", "dig_picker", close) {
        if (d.recent.isNotEmpty()) {
            SectionTitle("최근 · 한 번에 바꾸기")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.recent.take(3).forEachIndexed { i, ch ->
                    val name = when (ch.kind) { DigKind.LEVEL -> "레벨 · ${ch.level?.labelKo ?: ""}"; else -> ch.kind.labelKo }
                    Tile(name, d.featureName(ch.featureId), false, "recent_$i", Modifier.weight(1f)) { d.select(ch.kind, ch.featureId, ch.level); close() }
                }
            }
        }
        SectionTitle("종류")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
            for (k in DigKind.entries) Tile(k.labelKo, k.subKo, d.kind == k, "kind_${k.id}", Modifier.weight(1f)) {
                d.select(k); if (k != DigKind.FEATURE && k != DigKind.ARTIFACT && k != DigKind.LEVEL && k != DigKind.BM) close()
            }
        }
        val k = d.kind
        if (k == DigKind.FEATURE || k == DigKind.ARTIFACT || k == DigKind.LEVEL) {
            SectionTitle(if (k == DigKind.FEATURE) "어느 유구를 잴까요" else "유구 (안에 있으면 자동)")
            val list = d.featureOutlines().sortedByDescending { !it.finished }
            if (k != DigKind.FEATURE) Tile("자동 · 닫힌 유구 안이면 그 유구", null, d.featureId == null, "feature_auto", Modifier.fillMaxWidth()) { d.chooseFeature(null); close() }
            for (o in list.take(12)) {
                val sub = (if (o.finished) (if (o.closed) "면 닫힘" else "선") else "재는 중") + " · 경계점 ${o.pointRowIds.size}"
                Tile(o.label, sub, d.featureId == o.id, "feature_${o.id}", Modifier.fillMaxWidth()) { d.chooseFeature(o.id); close() }
            }
            if (k == DigKind.FEATURE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("새 유구 ${d.nextFeatureName()}", if (d.provisional) "가번호 · 나중에 이름 바꾸기" else "번호 자동", false, "feature_new", Modifier.weight(1f)) {
                        scope.launch { if (d.newFeature() == null) d.chooseFeature(null); close() }
                    }
                }
            }
        }
        if (k == DigKind.LEVEL) {
            SectionTitle("레벨 종류")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (lv in LevelKind.entries) Tile(lv.labelKo, null, d.level == lv, "level_${lv.id}", Modifier.weight(1f)) { d.chooseLevel(lv); close() }
            }
        }
        if (k == DigKind.BM) {
            SectionTitle("기준점 이름 (처음 잰 값 = 기준, 다음부터 확인)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in listOf("BM-1", "BM-2", "BM-3")) Tile(n, if (c.points.any { it.digKind == "bm" && it.digTag == n }) "기준값 있음" else "처음", d.bmName == n, "bm_$n", Modifier.weight(1f)) { d.bmName = n; d.select(DigKind.BM); close() }
            }
        }
        val custom = c.types.filter { DigKind.fromPositionType(it) == null }
        if (custom.isNotEmpty()) {
            SectionTitle("그 밖의 종류")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                custom.forEach { n -> Tile(n, null, d.kind == null && c.typeName == n, "type_custom_$n", Modifier) { d.selectCustomType(n); close() } }
            }
        }
    }
}

/** 지도에 보이기: 배경(위성+지적 등) + 발굴 레이어 켜고 끄기 + 올린 도면 */
@Composable
internal fun DigLayersSheet(c: SurveyController) {
    val d = c.dig
    val t = T
    val close = { d.saveLayers(); d.layersOpen = false }
    DigSheet("지도에 보이기", "dig_layers", close) {
        SectionTitle("배경 (V월드)")
        for (b in kr.archsurvey.map.MapBase.entries) Tile(b.labelKo, null, c.mapBase == b, "base_${b.id}", Modifier.fillMaxWidth()) { c.changeMapBase(b) }
        SectionTitle("발굴 도면")
        val rows = listOf(
            Triple("조사구역", d.showArea) { v: Boolean -> d.showArea = v }, Triple("그리드 · 말뚝", d.showGrid) { v: Boolean -> d.showGrid = v },
            Triple("트렌치", d.showTrench) { v: Boolean -> d.showTrench = v }, Triple("유구", d.showFeature) { v: Boolean -> d.showFeature = v },
            Triple("유물 번호", d.showArtifactNo) { v: Boolean -> d.showArtifactNo = v }, Triple("레벨 해발", d.showLevelH) { v: Boolean -> d.showLevelH = v },
            Triple("토층 단면선", d.showSection) { v: Boolean -> d.showSection = v }, Triple("기준점", d.showBm) { v: Boolean -> d.showBm = v },
        )
        for ((i, r) in rows.withIndex()) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(t.raised).hClickable { r.third(!r.second) }.padding(horizontal = 14.dp).testTag("layer_$i"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.first, style = Ty.label.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.weight(1f))
                Text(if (r.second) "보임" else "숨김", style = Ty.label, color = if (r.second) t.fixed else t.inkSub)
            }
        }
        c.bg?.let { b -> Tile("올린 도면 관리", "투명도 · 정렬", false, "layer_bg", Modifier.fillMaxWidth()) { close(); b.openManage() } }
    }
}

/** 지금 상태: 신호등 3칸(수신기·보정·고정) + 정밀도·위성·나이 + 원인 + 연결/VRS 단추 */
@Composable
internal fun DigStatusSheet(c: SurveyController) {
    val t = T
    rememberTick()
    val close = { c.dig.statusOpen = false }
    DigSheet("지금 상태", "dig_status", close) {
        val steps = c.dig.prepSteps(ntripNow()).take(2)
        val fixOk = c.gv.displayStatus == kr.archsurvey.core.SurveyStatus.FIXED
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, s) in steps.withIndex()) Light(s.title, s.sub, s.state == kr.archsurvey.dig.StepState.DONE, "light_$i", Modifier.weight(1f))
            Light("고정(FIX)", c.gv.quality.labelKo, fixOk, "light_2", Modifier.weight(1f))
        }
        c.dig.causeLine(ntripNow())?.let { Text(it, style = Ty.label.copy(fontSize = 15.sp), color = t.ink, modifier = Modifier.testTag("status_cause")) }
        val g = c.gv
        val rows = listOf(
            "수평 · 수직 정밀도" to ((g.status.sigmaHM?.let { String.format(Locale.US, "%.1f", it * 100) } ?: "—") + " · " + (g.sigmaVM?.let { String.format(Locale.US, "%.1f", it * 100) } ?: "—") + " cm"),
            "위성" to (g.satellites.toString() + (g.satsInView?.let { " / $it" } ?: "")),
            "보정 나이" to (g.correctionAgeSec?.let { String.format(Locale.US, "%.0f초", it) } ?: "—"),
            "좌표계 · 높이" to "EPSG:${c.zone.epsg} · ${c.geoid.shortKo}",
            "자세히" to HeaderModel.metrics(g),
        )
        for ((k, v) in rows) Row(Modifier.fillMaxWidth()) {
            Text(k, style = Ty.small.copy(fontSize = 13.sp), color = t.inkSub, modifier = Modifier.width(130.dp))
            Text(v, style = Ty.coord.copy(fontSize = 14.sp), color = t.ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MBtn(Modifier.weight(1f).height(52.dp).testTag("status_receiver"), MKind.PLAIN, 14.dp, onClick = { close(); c.connection?.openScreen() }) { Text("수신기 연결", style = Ty.label, color = t.ink) }
            MBtn(Modifier.weight(1f).height(52.dp).testTag("status_vrs"), MKind.PLAIN, 14.dp, onClick = { close(); c.connection?.openVrs() }) { Text("VRS 보정", style = Ty.label, color = t.ink) }
            MBtn(Modifier.weight(1f).height(52.dp).testTag("status_more"), MKind.PLAIN, 14.dp, onClick = { close(); c.gnssInfoOpen = true }) { Text("GNSS 자세히", style = Ty.label, color = t.ink) }
        }
    }
}

@Composable
private fun Light(title: String, sub: String, ok: Boolean, tag: String, modifier: Modifier) {
    val t = T
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(t.raised).border(1.5.dp, if (ok) t.fixed else t.caution, RoundedCornerShape(12.dp)).padding(10.dp).testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) t.fixed else t.caution))
            Text(" $title", style = Ty.label.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = t.ink, maxLines = 1)
        }
        Text(sub, style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, maxLines = 2)
    }
}

/** 유구 이름 바꾸기: 점(P-번호·좌표)은 그대로, 옛 이름은 기록 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RenameFeatureSheet(c: SurveyController, id: Long) {
    val t = T
    val d = c.dig
    val o = c.arch.outlines.firstOrNull { it.id == id } ?: run { d.renameFor = null; return }
    var type by remember(id) { mutableStateOf(o.featureType.ifBlank { d.featureTypes.firstOrNull() ?: "수혈" }) }
    var name by remember(id) { mutableStateOf(if (o.label.startsWith("가-")) kr.archsurvey.dig.FeatureNaming.newName(d.featureOutlines().filter { it.id != id }.map { it.label }, type, false) else o.label) }
    var err by remember(id) { mutableStateOf<String?>(null) }
    val pts = c.points.count { it.featureId == id || it.rowId in o.pointRowIds }
    DigSheet("유구 이름 바꾸기", "rename_sheet", { d.renameFor = null }) {
        Text("${o.label} → 새 이름 · 점 ${pts}개의 번호(P-…)와 좌표는 그대로예요", style = Ty.label, color = t.ink)
        SectionTitle("유구 종류")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ty in d.featureTypes) Tile(ty, null, type == ty, "rename_type_$ty", Modifier) {
                type = ty; name = kr.archsurvey.dig.FeatureNaming.newName(d.featureOutlines().filter { it.id != id }.map { it.label }, ty, false)
            }
        }
        // 0.1.39 한 손가락: 번호를 큰 숫자 고르기로(이름 = 「N호 종류」). 키보드는 「직접 입력」에서만
        var typing by remember(id) { mutableStateOf(false) }
        val no = Regex("^(\\d{1,3})호").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        SectionTitle("번호")
        BigNumberPicker(no, 1..999, "rename_no", "호") { n -> name = kr.archsurvey.dig.FeatureNaming.name(n, type); err = null }
        Text("새 이름 · $name", style = Ty.label, color = t.ink, modifier = Modifier.testTag("rename_preview"))
        TypeFallbackRow(typing, "rename_manual") { typing = !typing }
        if (typing) Field("새 이름 (예: 3호 주거지)", name, "rename_name") { name = it; err = null }
        err?.let { Text(it, style = Ty.label, color = t.bad, modifier = Modifier.testTag("rename_error")) }
        val hist = kr.archsurvey.dig.FeatureNaming.history(o.history)
        if (hist.size > 1) Text("이전 이름: " + hist.dropLast(1).joinToString(" → "), style = Ty.small, color = t.inkSub)
        MBtn(Modifier.fillMaxWidth().height(56.dp).testTag("rename_save"), MKind.PRIMARY, 16.dp, onClick = { err = d.renameFeature(id, name, type) }) { Text("이름 바꾸기", style = Ty.label, color = t.onPrimary) }
    }
}

/** 시트 모음(지도 화면 위) */
@Composable
internal fun DigOverlays(c: SurveyController) {
    val d = c.dig
    if (d.pickerOpen) DigPickerSheet(c)
    if (d.layersOpen) DigLayersSheet(c)
    if (d.statusOpen) DigStatusSheet(c)
    d.renameFor?.let { RenameFeatureSheet(c, it) }
}
