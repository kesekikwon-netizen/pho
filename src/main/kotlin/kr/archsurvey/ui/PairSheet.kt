package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.bt.FoundDevice
import kr.archsurvey.bt.PairStep
import kr.archsurvey.bt.PairText

/** 0.1.40 「주변 수신기 찾기」 큰 단추(장비 연결 화면). 사진 기준점 앱과 같은 파일 */
@Composable
internal fun PairFindButton(cc: ConnectionController) {
    val t = T
    val p = cc.pair ?: return
    MBtn(Modifier.fillMaxWidth().height(64.dp).testTag("pair_find"), MKind.PRIMARY, 18.dp, onClick = { p.openSheet() }) {
        Text("주변 수신기 찾기 · 눌러서 바로 연결", style = Ty.label.copy(fontSize = 17.sp), color = t.onPrimary, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** 찾기 → 목록(등록된 것 위) → 누름 = 등록(PIN 자동)+연결. 진행 단계·실패 안내·다시 */
@Composable
internal fun PairSheet(p: PairController) {
    val t = T
    @Suppress("UNUSED_VARIABLE") val tick = p.tick
    Box(Modifier.fillMaxSize().background(t.scrim).testTag("pair_scrim")) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 680.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(t.card)
                .padding(16.dp).verticalScroll(rememberScrollState()).testTag("pair_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("주변 수신기", style = Ty.title, color = t.ink, modifier = Modifier.weight(1f))
                MBtn(Modifier.height(48.dp).testTag("pair_close"), MKind.PLAIN, 14.dp, onClick = { p.close() }) { Text("닫기", style = Ty.label, color = t.ink, modifier = Modifier.padding(horizontal = 14.dp)) }
            }
            if (p.explainPermission) {
                Text("수신기를 찾고 등록하려면 폰의 「주변 기기」 권한이 필요해요. 위치를 모으지 않아요.", style = Ty.body, color = t.ink, modifier = Modifier.testTag("pair_perm_text"))
                MBtn(Modifier.fillMaxWidth().height(56.dp).testTag("pair_perm_allow"), MKind.PRIMARY, 16.dp, onClick = { p.allowPermission() }) { Text("허용하기", style = Ty.label, color = t.onPrimary) }
            }
            val m = p.machine
            if (m != null && p.step != PairStep.IDLE) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.raised).padding(14.dp).testTag("pair_progress"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(m.device.name, style = Ty.label.copy(fontSize = 16.sp), color = t.ink)
                    Text(PairText.step(p.step, m), style = Ty.body, color = if (p.step == PairStep.FAILED) t.bad else t.ink, modifier = Modifier.testTag("pair_step"))
                }
            }
            PairText.fix(p.fix, m?.device?.name ?: "")?.let { (title, body) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.bad.copy(alpha = 0.10f)).padding(14.dp).testTag("pair_fix"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = Ty.label.copy(fontSize = 16.sp), color = t.bad, modifier = Modifier.testTag("pair_fix_title"))
                    Text(body, style = Ty.small, color = t.ink)
                    MBtn(Modifier.fillMaxWidth().height(56.dp).testTag("pair_retry"), MKind.PRIMARY, 16.dp, onClick = { if (m != null) p.retry() else p.scan() }) { Text("다시", style = Ty.label, color = t.onPrimary) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (p.scanning) "찾는 중… (수신기를 켜 두세요)" else "목록 ${p.devices.size}개", style = Ty.small, color = t.inkSub, modifier = Modifier.weight(1f).testTag("pair_scan_state"))
                if (!p.scanning) MBtn(Modifier.height(48.dp).testTag("pair_rescan"), MKind.PLAIN, 14.dp, onClick = { p.scan() }) { Text("다시 찾기", style = Ty.label, color = t.ink, modifier = Modifier.padding(horizontal = 12.dp)) }
            }
            for (d in p.devices) DeviceRow(p, d)
            if (p.devices.isEmpty() && !p.scanning) Text("수신기가 안 보이면: 전원을 껐다 켜고 1분 안에 「다시 찾기」. R10 은 웹 화면 Bluetooth › Discoverable 이 켜져 있어야 보여요.", style = Ty.small, color = t.inkSub)
            FoldRow("고급 · 수신기 PIN ${p.pin}", p.advancedOpen, "pair_adv") { p.advancedOpen = !p.advancedOpen }
            if (p.advancedOpen) {
                Text("PIN 이 요구되면 이 값을 자동으로 넣고, 안 되면 0000(R10 공장값)도 넣어 봐요.", style = Ty.small, color = t.inkSub)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in listOf("1234", "0000")) Choice(v, p.pin == v, Modifier.weight(1f).testTag("pair_pin_$v")) { p.changePin(v) }
                }
                Field("다른 PIN(숫자)", p.pin, "pair_pin_input", kb = androidx.compose.ui.text.input.KeyboardType.NumberPassword) { p.changePin(it) }
            }
        }
    }
}

@Composable
private fun DeviceRow(p: PairController, d: FoundDevice) {
    val t = T
    val busy = p.machine?.device?.address.equals(d.address, true) && (p.step == PairStep.BONDING || p.step == PairStep.WAIT_USER || p.step == PairStep.CONNECTING || p.step == PairStep.CONNECTING_UNBONDED)
    MBtn(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("pair_dev_${d.address}"), if (busy) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = { p.tap(d) }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = Ty.label.copy(fontSize = 16.sp), color = if (d.isReceiver) t.ink else t.inkSub, maxLines = 1)
                Text(listOfNotNull(d.model?.displayName, if (d.bonded) "등록됨" else "새 기기", d.rssi?.let { "신호 $it" }).joinToString(" · "), style = Ty.small, color = t.inkSub, maxLines = 1)
            }
            Text(if (d.bonded) "연결" else "등록+연결", style = Ty.label, color = t.ink)
        }
    }
}
