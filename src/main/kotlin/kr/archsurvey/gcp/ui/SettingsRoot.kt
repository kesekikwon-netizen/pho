package kr.archsurvey.gcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.device.LinkState
import kr.archsurvey.gcp.model.ObjIds
import kr.archsurvey.gcp.model.Pipeline
import kr.archsurvey.gcp.model.TargetChecks
import kr.archsurvey.ui.hClickable

// =============================== ⑤ 설정 ===============================
@Composable
fun SettingsScreen(c: GcpController, env: GcpEnv) {
    c.poleTick
    var keyEdit by remember { mutableStateOf<String?>(null) }
    var diag by remember { mutableStateOf<String?>(null) }
    val link by c.source.link.collectAsState()
    Column(Modifier.fillMaxSize().background(G.bg).testTag("settings")) {
        GHeader({ c.back() }, "settings_header", title = { Text("설정", style = GT.serif.sz(22.sp), color = G.ink, modifier = Modifier.weight(1f)) }) { FixPill(c.source) { c.openReceiver() } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val cc = c.connection
            SetRow("수신기", (cc?.profile?.displayName ?: "R10") + " · " + when (link.state) { LinkState.STREAMING -> "연결됨"; LinkState.IDLE -> "연결 안 됨"; LinkState.NEEDS_USER -> "확인 필요"; else -> "연결 중" }, "set_receiver") { c.openReceiver() }
            SetRow("VRS 계정", cc?.form?.let { if (!it.enabled) "끔" else (it.user.ifBlank { "아이디 없음" } + " · " + it.mount.ifBlank { "마운트 없음" }) } ?: "", "set_vrs") { c.openVrs() }
            Card {
                Text("폴 높이 (${(c.connection?.profile?.displayName ?: "R10").substringAfterLast(' ')})", style = GT.sans.sz(15.sp), color = G.ink)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("지면 → 기본 기준점", style = GT.sansL.sz(12.sp), color = G.sub, modifier = Modifier.weight(1f))
                    Stepper(String.format(java.util.Locale.US, "%.1f m", c.poleM()), "pole", { c.stepPole(-10) }, { c.stepPole(10) })
                }
                Text("기본 R10 1.6 m · SP60 1.8 m. 높이 = 수신기 해발 − (폴 + 안테나 보정)", style = GT.sansL.sz(12.sp), color = G.sub)
            }
            Card {
                Text("평균 횟수", style = GT.sans.sz(15.sp), color = G.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in GcpController.EPOCH_CHOICES) GChip("${n}회", c.prefs.epochs == n, "epochs_$n", Modifier.weight(1f)) { c.setEpochs(n) }
                }
                Text("FIX 가 아니면 저장하지 않아요. 재는 중 FIX 가 풀리면 멈춰요.", style = GT.sansL.sz(12.sp), color = G.sub)
            }
            Card {
                Text("이 폰 작업자 글자", style = GT.sans.sz(15.sp), color = G.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (op in ObjIds.OPERATORS) GChip(op.ifEmpty { "없음" }, c.prefs.operator == op, "op_${op.ifEmpty { "none" }}", Modifier.weight(1f)) { c.setOperator(op) }
                }
                Text("폰 한 대면 「없음」(GN-012). 여러 대가 같은 유적을 재면 폰마다 글자를 달리 (GN-A012 / GN-B003) — ID 가 겹치지 않아요.", style = GT.sansL.sz(12.sp), color = G.sub)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).hClickable { c.advancedOpen = !c.advancedOpen }.padding(horizontal = 4.dp).testTag("advanced_toggle"), verticalAlignment = Alignment.CenterVertically) {
                Text("고급", style = GT.sans.sz(15.sp), color = G.ink, modifier = Modifier.weight(1f))
                Text(if (c.advancedOpen) "접기 ▴" else "펼치기 ▾", style = GT.sans.sz(13.sp), color = G.sub)
            }
            if (c.advancedOpen) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("볼륨 아래 키로 측점", style = GT.sans.sz(15.sp), color = G.ink)
                            Text("측점 화면에서 안 잰 첫 표적을 재요", style = GT.sansL.sz(12.sp), color = G.sub)
                        }
                        Switch(c.prefs.volumeKey, { c.setVolumeKey(it) }, modifier = Modifier.testTag("volume_switch"), colors = SwitchDefaults.colors(checkedTrackColor = G.ink))
                    }
                }
                Card {
                    Text("점검 기준 (고정)", style = GT.sans.sz(15.sp), color = G.ink)
                    Text("표적 ${Pipeline.MIN_TARGETS}개부터 사진 · 다시 잰 값 ${(TargetChecks.MOVED_M * 100).toInt()} cm 넘게 다르면 묻기 · 근처 ${TargetChecks.CLASH_RADIUS_M.toInt()} m 안 같은 번호 경고 · XML 정밀도 0.01", style = GT.sansL.sz(12.sp), color = G.sub)
                }
                val acc = env.mapAccess()
                SetRow("V월드 지도 키", if (acc.key.isBlank()) "없음" else "••••" + acc.key.takeLast(4), "set_vworld") { keyEdit = "" }
                SetRow("진단 기록", "연결 방식 · 오류 기록 보기", "set_diag") { diag = c.connection?.diagnostics().orEmpty().ifBlank { "기록 없음" } }
                SetRow("앱 정보", "사진 기준점 ${env.version} · 수신기 코드 = 측량 앱 0.1.37", "set_about") {}
            }
            GBtn("앱 종료", Modifier.fillMaxWidth(), BtnKind.PLAIN, tag = "exit") { c.requestExit() }
        }
    }
    keyEdit?.let { k ->
        Sheet("V월드 키 바꾸기", "vworld_sheet", onClose = { keyEdit = null }) {
            Text("V월드 오픈API 인증키. 화면에는 끝 4자리만 보여요. 비우고 저장하면 앱 기본 키로 돌아가요.", style = GT.sansL.sz(13.sp), color = G.sub)
            GField("인증키", k, "vworld_key", mono = true) { keyEdit = it.trim() }
            GBtn("저장", Modifier.fillMaxWidth(), tag = "vworld_save") { env.saveMapKey(k); keyEdit = null; c.ui.ok("V월드 키를 저장했어요") }
        }
    }
    diag?.let { d ->
        Sheet("진단 기록", "diag_sheet", onClose = { diag = null }) {
            Text(d, style = GT.mono.sz(11.sp), color = G.ink, modifier = Modifier.testTag("diag_text"))
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(G.card).border(1.dp, G.line, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun SetRow(title: String, value: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp)).background(G.card).border(1.dp, G.line, RoundedCornerShape(14.dp)).hClickable { onClick() }.padding(horizontal = 14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = GT.sans.sz(15.sp), color = G.ink)
            Text(value, style = GT.sansL.sz(12.sp), color = G.sub, maxLines = 2)
        }
        Text("›", style = GT.sans.sz(22.sp), color = G.sub)
    }
}

// =============================== 뿌리 ===============================
@Composable
fun GcpRoot(c: GcpController, env: GcpEnv) {
    androidx.activity.compose.BackHandler { c.back() }
    val g by c.source.gnss.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(kr.archsurvey.ui.LocalFixCode provides g.quality.code) {
        Box(Modifier.fillMaxSize().background(G.bg)) {
            when (c.screen) {
                Screen.SITES -> SitesScreen(c)
                Screen.BOARD -> BoardScreen(c)
                Screen.SITE_MAP -> SiteMapScreen(c, env)
                Screen.MEASURE -> MeasureScreen(c, env)
                Screen.ID_CARD -> IdCardScreen(c)
                Screen.SETTINGS -> SettingsScreen(c, env)
            }
            c.newSite?.let { NewSiteSheet(c, it) }
            c.newObj?.let { NewObjSheet(c, it) }
            c.newSession?.let { NewSessionSheet(c, it) }
            c.slotEdit?.let { SlotEditDialog(c, it) }
            c.renaming?.let { RenameDialog(c, it) }
            if (c.handoffOpen) HandoffSheet(c)
            c.connection?.let { cc -> kr.archsurvey.ui.ConnectionHost(cc, g.quality, g.gga?.satellites ?: 0) }
            kr.archsurvey.ui.FeedbackOverlays(c.ui)
        }
    }
}
