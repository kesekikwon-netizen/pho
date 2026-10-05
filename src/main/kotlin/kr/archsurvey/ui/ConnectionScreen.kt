package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.LinkState
import kr.archsurvey.ntrip.NtripPresets
import kr.archsurvey.ntrip.NtripState

/** 지도 화면 위에 얹는 연결 UI: 오류 알림 줄(항상) + 전체 화면 연결 설정(열었을 때). */
@Composable
fun ConnectionHost(c: SurveyController, cc: ConnectionController) {
    val link by cc.link.collectAsState()
    val ntrip by cc.ntrip.collectAsState()
    val cards = ErrorCards.from(link, ntrip, cc.form.enabled)
    if (!cc.open && cards.isNotEmpty()) AlertStrip(cards.first(), onClick = { cc.openScreen() })
    if (cc.open) ConnectionScreen(cc)
}

@Composable
private fun AlertStrip(card: ErrorCard, onClick: () -> Unit) {
    val t = T
    Box(Modifier.fillMaxWidth().padding(top = (SurveyDims.STATUS_BAND_DP + 6).dp, start = 72.dp, end = (SurveyDims.MIN_TOUCH_DP + 8).dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.bg.copy(alpha = 0.96f))
                .border(1.dp, t.bad.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp).testTag("alert_strip"),
        ) {
            Text(card.title, style = Ty.label, color = t.ink, maxLines = 2)
            Text(if (card.autoRetry) "자동으로 다시 시도해요 · 눌러서 자세히 (${card.code})" else "눌러서 해결 방법 보기 (${card.code})", style = Ty.small, color = t.inkSub)
        }
    }
}

@Composable
fun ConnectionScreen(cc: ConnectionController) {
    val t = T
    val link by cc.link.collectAsState()
    val ntrip by cc.ntrip.collectAsState()
    val cards = ErrorCards.from(link, ntrip, cc.form.enabled)
    val p = cc.profile
    Column(Modifier.fillMaxSize().background(t.bg).testTag("connection_screen")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MBtn(Modifier.size(48.dp).testTag("conn_back"), MKind.PLAIN, 20.dp, onClick = { cc.close() }) { Box(Modifier.rotate(-90f)) { GlyphIcon(Glyph.CHEVRON_UP, t.accent, 20.dp) } }
            Text("수신기 연결 · VRS", style = Ty.title.copy(fontSize = 20.sp), color = t.ink, modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 맨 위: 지금 상태 한 줄(색·글자) — 오류 카드는 아래에 둔다
            val live = link.state == LinkState.STREAMING
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(if (live) t.fixed.copy(alpha = 0.14f) else t.card).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlyphIcon(if (live) Glyph.CHECK else Glyph.BT, if (live) t.fixed else t.inkSub, 24.dp)
                Column(Modifier.weight(1f)) {
                    Text(linkText(link.state) + (if (link.attempt > 0) " · 시도 ${link.attempt}회" else ""), style = Ty.label, color = t.ink, modifier = Modifier.testTag("conn_link_state"))
                    if (cc.form.enabled) Text("보정: " + ntripText(ntrip.state), style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("conn_ntrip_state"))
                    if (link.message.isNotBlank()) Text(link.message, style = Ty.small, color = t.inkSub)
                }
            }
            cc.notice?.let { ErrorCardView(it, "conn_notice") }
            for (card in cards) ErrorCardView(card, "conn_card_${card.code}")

            // 1. 수신기: 모델 + 폰에 등록된 기기
            Card {
                Text("수신기", style = Ty.label, color = t.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in DeviceProfiles.all) Choice(d.displayName, d.id == p.id, Modifier.weight(1f).testTag("conn_model_${d.id}")) { cc.selectProfile(d) }
                }
                if (cc.bonded.isEmpty()) {
                    Text("등록된 기기가 없어요. 먼저 폰의 블루투스 설정에서 수신기를 등록하세요.", style = Ty.small, color = t.inkSub)
                    for ((i, st) in p.pairingSteps.withIndex()) Text("${i + 1}. $st", style = Ty.small, color = t.inkSub)
                }
                for ((name, addr) in cc.bonded) {
                    val on = addr == cc.address
                    val isLive = on && (link.state == LinkState.STREAMING || link.state == LinkState.CONNECTED_NO_DATA)
                    MBtn(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("conn_bonded_$addr"), if (on) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = { cc.selectAddress(addr) }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, maxLines = 1)
                                Text(addr, style = Ty.small, color = t.inkSub, maxLines = 1)
                            }
                            if (isLive) Row(
                                Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(t.fixed.copy(alpha = 0.14f)).border(1.5.dp, t.fixed, RoundedCornerShape(16.dp)).padding(start = 8.dp, end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) { GlyphIcon(Glyph.CHECK, t.fixed, 16.dp); Text("연결됨", style = Ty.label.copy(fontSize = 12.sp), color = t.ink) }
                            else Text(if (on) "선택됨" else "선택", style = Ty.label, color = t.accentText)
                        }
                    }
                }
                Btn("목록 새로고침", Modifier.fillMaxWidth().testTag("conn_refresh")) { cc.refresh() }
                FoldRow("처음 연결 도움말", cc.showHelp, "conn_help_toggle") { cc.showHelp = !cc.showHelp }
                if (cc.showHelp) {
                    Btn("블루투스 설정 열기", Modifier.fillMaxWidth().testTag("conn_bt_settings")) { cc.openBluetoothSettings() }
                    Text(p.pinNote, style = Ty.small, color = t.inkSub)
                    Text(p.portNote, style = Ty.small, color = t.inkSub)
                }
            }

            // 2. 연결 전 확인: 모두 체크했으면 한 줄로 접는다
            val allChecked = cc.checks.isNotEmpty() && cc.checks.all { it }
            if (allChecked && !cc.showPre) {
                FoldRow("연결 전 확인 ✓ 완료", false, "conn_pre_toggle") { cc.showPre = true }
            } else Card {
                Text("연결 전 확인", style = Ty.label, color = t.ink)
                for ((i, txt) in p.preConnectChecks.withIndex()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { cc.setCheck(i, !cc.checks.getOrElse(i) { false }) }.testTag("conn_check_$i"), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(cc.checks.getOrElse(i) { false }, { cc.setCheck(i, it) })
                        Text(txt, style = Ty.body, color = t.ink)
                    }
                }
                FoldRow(p.nmeaSetupTitle, cc.showNmeaSteps, "conn_nmea_toggle") { cc.showNmeaSteps = !cc.showNmeaSteps }
                if (cc.showNmeaSteps) for ((i, st) in p.nmeaSetupSteps.withIndex()) Text("${i + 1}. $st", style = Ty.small, color = t.inkSub)
            }

            // 3. VRS: 아이디·비밀번호만 앞에, 서버 설정은 접음
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("VRS(NTRIP) 보정", style = Ty.label, color = t.ink, modifier = Modifier.weight(1f))
                    Switch(cc.form.enabled, { cc.updateForm(cc.form.copy(enabled = it)) }, modifier = Modifier.testTag("conn_ntrip_switch"))
                }
                if (cc.form.enabled) {
                    Field("아이디 (통합회원 ID)", cc.form.user, "conn_user") { cc.updateForm(cc.form.copy(user = it)) }
                    Field("비밀번호", cc.form.password, "conn_pw", KeyboardType.Password, hide = true) { cc.updateForm(cc.form.copy(password = it)) }
                    Text("국토지리정보원 VRS는 2025년 8월부터 ‘측위보정정보 통합회원 ID’(지각변동감시시스템에서 등록)를 쓴다고 알려져 있어요. 공식 공지 원문은 확인하지 못했어요.", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("conn_id_help"))
                    FoldRow("서버 설정 · ${cc.form.host}:${cc.form.port} · ${cc.form.mount}", cc.showServer, "conn_server_toggle") { cc.showServer = !cc.showServer }
                    if (cc.showServer) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn("VRS 기본값", Modifier.weight(1f)) { val v = NtripPresets.VRS; cc.updateForm(cc.form.copy(host = v.host, port = v.port.toString(), mount = v.mountPoint)) }
                            Btn("FKP 기본값", Modifier.weight(1f)) { val v = NtripPresets.FKP; cc.updateForm(cc.form.copy(host = v.host, port = v.port.toString(), mount = v.mountPoint)) }
                        }
                        Field("서버 주소", cc.form.host, "conn_host") { cc.updateForm(cc.form.copy(host = it)) }
                        Field("포트", cc.form.port, "conn_port", KeyboardType.Number) { cc.updateForm(cc.form.copy(port = it.filter { ch -> ch.isDigit() }.take(5))) }
                        Field("마운트 포인트", cc.form.mount, "conn_mount") { cc.updateForm(cc.form.copy(mount = it)) }
                        Text("서버·마운트 기본값은 국토지리정보원 공개 안내(2022-05) 기준이에요. 현행 정책은 첫 접속 때 확인이 필요해요.", style = Ty.small, color = t.inkSub)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        run {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = cc.connectRequested && link.state != LinkState.IDLE
                if (running) Btn("연결 종료", Modifier.weight(1f).height(56.dp).testTag("conn_disconnect")) { cc.disconnect() }
                Box(
                    Modifier.weight(2f).height(56.dp).clip(RoundedCornerShape(16.dp)).glossy(t.primary).clickable { cc.connect() }.testTag("conn_connect"),
                    contentAlignment = Alignment.Center,
                ) { Text(if (running) "다시 연결" else "연결", style = Ty.button.copy(fontSize = 18.sp), color = t.onPrimary) }
            }
        }
    }
}

private fun linkText(s: LinkState) = when (s) {
    LinkState.IDLE -> "연결 안 됨"
    LinkState.CONNECTING -> "수신기에 연결하는 중"
    LinkState.CONNECTED_NO_DATA -> "연결됨 · 위치 데이터를 기다리는 중"
    LinkState.STREAMING -> "연결됨 · 위치 수신 중"
    LinkState.STALE -> "연결됨 · 데이터가 잠시 끊김"
    LinkState.RECONNECTING -> "다시 연결하는 중"
    LinkState.NEEDS_USER -> "확인이 필요해요"
}

private fun ntripText(s: NtripState) = when (s) {
    NtripState.IDLE -> "대기"
    NtripState.CONNECTING -> "서버에 접속하는 중"
    NtripState.CONNECTED -> "접속됨 · 데이터를 기다리는 중"
    NtripState.RECEIVING -> "보정 데이터 수신 중"
    NtripState.RECONNECTING -> "다시 접속하는 중"
    NtripState.AUTH_FAILED -> "인증 실패"
    NtripState.ERROR -> "오류"
}

/** 목업 s31/s8 카드: 밝은 면 + 얇은 선 + 둥근 모서리 18dp */
@Composable
internal fun Card(content: @Composable ColumnScope.() -> Unit) {
    val t = T
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(t.raised).border(1.dp, t.line, shape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** 목업의 알약 선택 버튼: 고르면 연한 테라코타 면 + 굵은 테두리 + 체크 */
@Composable
internal fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = T
    MBtn(modifier.heightIn(min = 48.dp), if (selected) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = onClick) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selected) GlyphIcon(Glyph.CHECK, t.accentText, 14.dp)
            Text(label, style = Ty.label, color = t.ink)
        }
    }
}

@Composable
internal fun Btn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = T
    MBtn(modifier.heightIn(min = 48.dp), MKind.PLAIN, 16.dp, onClick = onClick) {
        Text(label, style = Ty.label, color = t.ink, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** 목업의 입력 줄: 48dp, 왼쪽 작은 이름 + 오른쪽 값 */
@Composable
internal fun Field(label: String, value: String, tag: String, kb: KeyboardType = KeyboardType.Text, hide: Boolean = false, onChange: (String) -> Unit) {
    val t = T
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(t.raised).border(1.dp, t.outline, shape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, modifier = Modifier.widthIn(min = 72.dp, max = 110.dp))
        androidx.compose.foundation.text.BasicTextField(
            value, onChange, singleLine = true, modifier = Modifier.weight(1f).padding(vertical = 12.dp).testTag(tag),
            textStyle = Ty.body.copy(color = t.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
            keyboardOptions = KeyboardOptions(keyboardType = kb),
            visualTransformation = if (hide) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        )
    }
}

@Composable
fun ErrorCardView(card: ErrorCard, tag: String) {
    val t = T
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.bad.copy(alpha = 0.10f)).border(1.dp, t.bad.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(16.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(card.title, style = Ty.label, color = t.ink)
        Text(card.action, style = Ty.body, color = t.ink)
        Text(if (card.code == "-") "" else "오류 ${card.code}" + if (card.autoRetry) " · 자동 재시도 중" else "", style = Ty.small, color = t.inkSub)
    }
}
