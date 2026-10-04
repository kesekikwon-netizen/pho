package kr.archsurvey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kr.archsurvey.core.*
import kr.archsurvey.device.DeviceProfiles

private fun shortName(deviceId: String?) = if (deviceId == DeviceProfiles.SP60_ID) "SP60" else "R10"

/** 기준점 문구 — 항상 크게 */
@Composable
fun ReferenceBanner(vm: MainVm, modifier: Modifier = Modifier) {
    val m = vm.antennaModel ?: return
    val ref = m.ref(vm.antenna.refPointId)
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.primaryCont).padding(14.dp)) {
        Text(AntennaMath.instruction(shortName(vm.deviceId), ref), style = Ty.title, color = T.onPrimaryCont)
    }
}

/** 컴팩트 안테나 바: 측량 화면용. 확인/변경이 쉽도록 큰 +/- 와 자주 쓰는 값 칩 */
@Composable
fun AntennaBar(vm: MainVm, onDetail: () -> Unit) {
    val m = vm.antennaModel ?: return
    val cfg = vm.antenna
    val res = vm.antennaResult()
    Card(accent = when (cfg.source) { AntennaSource.USER -> T.primary; AntennaSource.RECEIVER -> T.fixed; else -> T.none }) {
        ReferenceBanner(vm)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", "안테나 높이 1센티 줄이기") { vm.updateAntenna(cfg.copy(inputValueM = AntennaMath.step(cfg.inputValueM, -1))) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(AntennaMath.format(cfg.inputValueM, vm.heightUnit), style = Ty.big, color = T.ink)
                Text("출처: ${cfg.source.labelKo}", style = Ty.small, color = T.inkSub)
            }
            StepButton("+", "안테나 높이 1센티 늘리기") { vm.updateAntenna(cfg.copy(inputValueM = AntennaMath.step(cfg.inputValueM, 1))) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AntennaMath.CHIPS_M.forEach { c ->
                Chip(String.format(java.util.Locale.US, "%.3f", c), kotlin.math.abs(cfg.inputValueM - c) < 0.0005) { vm.updateAntenna(cfg.copy(inputValueM = c)) }
            }
            Spacer(Modifier.weight(1f))
            OutlineBigButton("자세히", onDetail, height = 48.dp)
        }
        if (res != null) Text(AntennaMath.summary(res), style = Ty.small, color = T.inkSub, modifier = Modifier.padding(top = 6.dp))
        else if (!cfg.receiverApplied) Text("높이를 계산할 수 없어요. '자세히'에서 확인하세요.", style = Ty.small, color = T.singleText)
        AntennaMath.mismatchWarning(cfg, m)?.let { StatusBanner(it, Kind.WARN, Modifier.padding(top = 6.dp)) }
        if (AntennaMath.isUnusual(cfg.inputValueM)) StatusBanner("폴 높이가 맞나요? (1.0~3.0 m 밖이에요)", Kind.WARN, Modifier.padding(top = 6.dp))
    }
}

/** 기기별 측정 구간 그림: 폴 끝(지면)부터 기준점까지 빨간 선 */
@Composable
fun ReferenceDiagram(deviceId: String?, refId: String, modifier: Modifier = Modifier) {
    val ink = T.ink; val red = T.single; val sub = T.outline; val fill = T.surfaceVar; val apc = T.fixed
    val sp60 = deviceId == DeviceProfiles.SP60_ID
    Canvas(modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)).background(T.surface)) {
        val w = size.width; val h = size.height
        val cx = w * 0.35f
        val ground = h - 16f
        // 지면 + 폴
        drawLine(sub, Offset(10f, ground), Offset(w - 10f, ground), 4f)
        val poleTop = h * 0.50f
        drawRect(sub, Offset(cx - 6f, poleTop), Size(12f, ground - poleTop))
        // 어댑터(R10 퀵릴리스)
        val qrH = if (!sp60) 14f else 0f
        val mountY = poleTop - qrH
        if (!sp60) drawRoundRect(ink.copy(alpha = 0.55f), Offset(cx - 22f, mountY), Size(44f, qrH), CornerRadius(4f))
        // 수신기 본체
        val bodyW = if (sp60) 120f else 100f
        val bodyH = if (sp60) 38f else 44f
        drawRoundRect(fill, Offset(cx - bodyW / 2, mountY - bodyH), Size(bodyW, bodyH), CornerRadius(18f))
        drawRoundRect(ink, Offset(cx - bodyW / 2, mountY - bodyH), Size(bodyW, bodyH), CornerRadius(18f), style = Stroke(3f))
        // APC 표시(점선)
        val apcY = mountY - (if (sp60) 20f else 24f)
        drawLine(apc, Offset(cx - bodyW / 2 - 14f, apcY), Offset(cx + bodyW / 2 + 14f, apcY), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        // 기준 높이
        val refY = when (refId) {
            "bottom_of_quick_release" -> poleTop
            else -> mountY
        }
        val dx = cx + bodyW / 2 + 36f
        drawLine(red, Offset(dx, ground), Offset(dx, refY), 6f)
        drawLine(red, Offset(dx - 14f, ground), Offset(dx + 14f, ground), 6f)
        drawLine(red, Offset(dx - 14f, refY), Offset(dx + 14f, refY), 6f)
        drawLine(red.copy(alpha = 0.4f), Offset(cx, refY), Offset(dx, refY), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    }
}

@Composable
fun AntennaDialog(vm: MainVm, onClose: () -> Unit) {
    val m = vm.antennaModel ?: run { onClose(); return }
    val cfg = vm.antenna
    var unit by remember { mutableStateOf(vm.heightUnit) }
    var text by remember { mutableStateOf(unitText(cfg.inputValueM, unit)) }
    var err by remember { mutableStateOf<String?>(null) }
    var adv by remember { mutableStateOf(cfg.extraPartsM != 0.0 || cfg.baseExtension || m.ref(cfg.refPointId).slant) }
    var extraText by remember { mutableStateOf(if (cfg.extraPartsM == 0.0) "" else unitText(cfg.extraPartsM, unit)) }
    val ref = m.ref(cfg.refPointId)
    val res = vm.antennaResult()
    val sn = shortName(vm.deviceId)

    fun commit(newM: Double) { vm.updateAntenna(vm.antenna.copy(inputValueM = newM)); text = unitText(newM, unit) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = T.surface,
        title = { Text("폴 높이 확인", style = Ty.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReferenceBanner(vm)
                ReferenceDiagram(vm.deviceId, ref.id)
                Text("빨간 선: 지면(폴 끝)에서 여기까지 재세요. 파란 점선: 안테나 위상중심(앱이 자동으로 더해요).", style = Ty.small, color = T.inkSub)

                // 기준점 선택
                if (m.visibleRefs.size > 1) {
                    Text("어디까지 재셨나요?", style = Ty.bodyB, color = T.ink)
                    m.visibleRefs.forEach { r ->
                        Chip(r.labelKo, r.id == cfg.refPointId) { vm.updateAntenna(vm.antenna.copy(refPointId = r.id, tapeOn = false)) }
                    }
                    if (vm.deviceId == DeviceProfiles.R10_ID) {
                        Text(if (cfg.refPointId == "bottom_of_quick_release") AntennaMath.ADAPTER_NOTE_R10 else AntennaMath.ADAPTER_NOTE_R10_MOUNT, style = Ty.small, color = T.floatText)
                    }
                } else if (vm.deviceId == DeviceProfiles.SP60_ID) {
                    Text(AntennaMath.ADAPTER_NOTE_SP60, style = Ty.small, color = T.floatText)
                }

                // 입력
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeightUnit.entries.forEach { u ->
                        Chip(u.label, unit == u) { unit = u; vm.updateHeightUnit(u); text = unitText(vm.antenna.inputValueM, u) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("−", "1센티 줄이기") { commit(AntennaMath.step(vm.antenna.inputValueM, -1)); err = null }
                    OutlinedTextField(
                        text, { text = it
                            when (val p = AntennaMath.parse(it, unit)) {
                                is AntennaMath.ParseResult.Ok -> { err = null; vm.updateAntenna(vm.antenna.copy(inputValueM = p.meters)) }
                                is AntennaMath.ParseResult.Error -> err = p.message
                            }
                        },
                        label = { Text("폴 높이 (${unit.label})") }, singleLine = true, isError = err != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = Ty.big, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    StepButton("+", "1센티 늘리기") { commit(AntennaMath.step(vm.antenna.inputValueM, 1)); err = null }
                }
                err?.let { Text(it, style = Ty.body, color = T.singleText) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AntennaMath.CHIPS_M.forEach { c -> Chip(String.format(java.util.Locale.US, "%.3f", c), false) { commit(c); err = null } }
                }
                AntennaMath.mismatchWarning(vm.antenna, m)?.let { StatusBanner(it, Kind.WARN) }
                if (AntennaMath.isUnusual(vm.antenna.inputValueM)) StatusBanner("폴 높이가 맞나요? (1.0~3.0 m 밖이에요)", Kind.WARN)

                if (res != null) StatusBanner(AntennaMath.summary(res), Kind.OK)
                else if (!vm.antenna.receiverApplied) StatusBanner("높이를 계산할 수 없어요. 사선 길이를 확인하세요.", Kind.ERROR)
                Text("값 출처: ${vm.antenna.source.labelKo} · 직접 고치면 '직접 입력'이 우선해요.", style = Ty.small, color = T.inkSub)
                Text(AntennaMath.SUM_PARTS_NOTE, style = Ty.small, color = T.inkSub)

                // 수신기 쪽 이중 보정 방지
                StatusBanner(AntennaMath.RECEIVER_SIDE_NOTICE, Kind.INFO)
                LabeledSwitch("수신기에서 이미 반영됨 (앱은 보정 안 함)", vm.antenna.receiverApplied) { vm.updateAntenna(vm.antenna.copy(receiverApplied = it)) }

                // 고급
                OutlineBigButton(if (adv) "고급 옵션 접기 ▲" else "고급 옵션 (사선·연장봉·부품) ▼", { adv = !adv })
                if (adv) {
                    if (vm.deviceId == DeviceProfiles.R10_ID) {
                        LabeledSwitch("베이스 연장봉 15 cm 를 앱이 더하기", vm.antenna.baseExtension) { vm.updateAntenna(vm.antenna.copy(baseExtension = it)) }
                        Text(AntennaMath.BASE_EXT_NOTE, style = Ty.small, color = T.inkSub)
                    }
                    Text("사선으로 쟀을 때 (고급)", style = Ty.bodyB, color = T.ink)
                    Chip("직고 (기본)", !ref.slant) { vm.updateAntenna(vm.antenna.copy(refPointId = m.defaultRefPoint, tapeOn = false)) }
                    m.advancedRefs.filter { it.slant }.forEach { r -> Chip(r.labelKo, r.id == cfg.refPointId) { vm.updateAntenna(vm.antenna.copy(refPointId = r.id)) } }
                    if (ref.slant && ref.tapeCorrection != null)
                        LabeledSwitch("줄자 걸이로 쟀어요 (+${String.format(java.util.Locale.US, "%.1f", ref.tapeCorrection * 100)} cm 자동 보정)", vm.antenna.tapeOn) { vm.updateAntenna(vm.antenna.copy(tapeOn = it)) }
                    if (ref.slant) Text("사선 공식은 줄자로 직고/사선을 각각 재서 일치하는지 현장 검증이 필요해요. [현장확인]", style = Ty.small, color = T.floatText)
                    Text("기타 부품 높이", style = Ty.bodyB, color = T.ink)
                    OutlinedTextField(extraText, {
                        extraText = it
                        val v = if (it.isBlank()) AntennaMath.ParseResult.Ok(0.0) else AntennaMath.parse(it, unit)
                        if (v is AntennaMath.ParseResult.Ok) vm.updateAntenna(vm.antenna.copy(extraPartsM = v.meters))
                    }, label = { Text("부품 길이 합 (${unit.label}, 기본 0)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = Ty.body, modifier = Modifier.fillMaxWidth())
                    Text("지면~측정 위치 사이를 줄자로 재지 않고 부품 길이로 더할 때만 입력하세요. 따로 기록돼요.", style = Ty.small, color = T.inkSub)
                }
                m.adapters.filter { it.heightM != null && it.doNotAddSeparately }.forEach {
                    Text("참고: 어댑터 ${(it.heightM!! * 100).toInt()} cm 는 기준점 오프셋에 이미 포함 (표시용, 계산에 더하지 않음)", style = Ty.small, color = T.inkSub)
                }
            }
        },
        confirmButton = { BigButton("확인", onClose, height = 56.dp) },
    )
}

private fun unitText(m: Double, u: HeightUnit): String =
    if (u == HeightUnit.M) String.format(java.util.Locale.US, "%.3f", m) else String.format(java.util.Locale.US, "%.1f", m * 100)
