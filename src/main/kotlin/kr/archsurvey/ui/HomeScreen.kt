package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.*

/** 목업 s2 홈: 작업 이름·배터리, 수신기 연결 상태, 큰 버튼(측량 / 점 찾아가기 / 작업 관리 / 내보내기), 설정. 표시하는 값은 모두 실제 수신 값이다. */
@Composable
fun HomeScreen(c: SurveyController, batteryPct: Int?) {
    val t = T
    val st = c.gv.status
    val (bg, fg) = statusColors(t, st.status)
    val notConn = st.reason == StatusReason.NOT_CONNECTED
    Row(Modifier.fillMaxSize().background(t.bg).windowInsetsPadding(WindowInsets.systemBars).testTag("home_screen")) {
        if (!c.poleOnRight) PoleStrip(c, batteryPct)
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 16.dp)) {
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(kr.archsurvey.R.drawable.ic_logo_mark_terracotta),
                    contentDescription = "발굴 측량 로고", modifier = Modifier.size(36.dp),
                )
                Text("발굴 측량", style = Ty.title, color = t.ink)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.projectName, style = Ty.title.copy(fontSize = 28.sp, letterSpacing = (-0.5).sp), color = t.ink, maxLines = 1, modifier = Modifier.weight(1f).testTag("home_job"))
                GlyphIcon(Glyph.BATT, t.ink, 18.dp)
                Text(batteryPct?.let { "$it%" } ?: "—", style = Ty.label, color = t.ink)
            }
            Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (c.antennaModelId == "TRIMBLE_R10") "Trimble R10" else "Spectra SP60", style = Ty.label, color = t.inkSub)
                if (notConn) Text("연결 안 됨", style = Ty.label, color = t.bad, modifier = Modifier.testTag("home_conn"))
                else Text("연결됨", style = Ty.label, color = kr.archsurvey.ui.T.fixed, modifier = Modifier.testTag("home_conn"))
            }
            val chipShape = RoundedCornerShape(22.dp)
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().height(44.dp).clip22(chipShape).background(bg.copy(alpha = 0.16f)).border(1.5.dp, bg, chipShape).padding(horizontal = 14.dp).testTag("home_status"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusIcon(st.status, bg, 22.dp)
                Text(if (notConn) "수신기 연결 안 됨" else c.gv.quality.labelKo, style = Ty.label.copy(fontSize = 15.sp), color = t.ink, maxLines = 1, modifier = Modifier.weight(1f))
                if (!notConn) st.sigmaHM?.let { Text("±" + f3(it) + " m", style = Ty.coord.copy(fontSize = 17.6.sp), color = t.ink, maxLines = 1) }
            }
            Text(
                if (notConn) "수신기를 연결하면 위치·정확도가 여기에 보여요" else bandDetail(c) + " · 안테나 " + f3(c.antenna.inputValueM) + " m",
                style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, maxLines = 2, modifier = Modifier.padding(top = 6.dp).testTag("home_detail"),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MBtn(Modifier.weight(1.1f).height(116.dp).testTag("home_survey"), MKind.PRIMARY, 22.dp, onClick = { c.homeOpen = false }) {
                    Box(Modifier.fillMaxSize().padding(12.dp, 14.dp)) {
                        GlyphIcon(Glyph.PIN, t.ink, 30.dp, )
                        Text("측량", style = Ty.button.copy(fontSize = 24.6.sp, letterSpacing = 2.sp), color = t.onPrimary, modifier = Modifier.align(Alignment.BottomStart))
                    }
                }
                HomeCard("점 찾아가기", Glyph.LOCATE, "home_goto", Modifier.weight(1f).height(116.dp)) { c.hub.open(ToolScreen.GOTO) }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeCard("작업 관리", Glyph.LIST, "home_jobs", Modifier.weight(1f).height(100.dp)) { c.hub.open(ToolScreen.JOB_OPEN) }
                HomeCard("내보내기", Glyph.LAYERS, "home_export", Modifier.weight(1f).height(100.dp)) { c.exporter?.openScreen() ?: run { c.message = "내보내기는 이 빌드에 없어요" } }
            }
            MBtn(Modifier.padding(top = 8.dp).fillMaxWidth().height(56.dp).testTag("home_settings"), MKind.PLAIN, 20.dp, onClick = { c.hub.open(ToolScreen.SETTINGS) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    GearIcon(t.accent, 22.dp); Spacer(Modifier.width(12.dp))
                    Text("설정", style = Ty.label.copy(fontSize = 16.7.sp), color = t.ink, modifier = Modifier.weight(1f))
                    GlyphIcon(Glyph.CHEVRON_DOWN, t.inkSub, 20.dp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(rememberAppVersionLabel(), style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("home_version"))
        }
        if (c.poleOnRight) PoleStrip(c, batteryPct)
    }
}

@Composable
private fun HomeCard(label: String, g: Glyph, tag: String, modifier: Modifier, onClick: () -> Unit) {
    MBtn(modifier.testTag(tag), MKind.PLAIN, 22.dp, onClick = onClick) {
        Box(Modifier.fillMaxSize().padding(12.dp, 14.dp)) {
            GlyphIcon(g, T.accent, 28.dp)
            Text(label, style = Ty.label.copy(fontSize = 16.7.sp), color = T.ink, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}

private fun Modifier.clip22(shape: RoundedCornerShape): Modifier = this.then(Modifier.clip(shape))
