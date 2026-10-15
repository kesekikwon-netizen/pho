package kr.archsurvey.pg.r10

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.pg.ui.OutlineBtn
import kr.archsurvey.pg.ui.P
import kr.archsurvey.pg.ui.PrimaryBtn
import kr.archsurvey.pg.ui.Txt

/** 0.0.14 R10 창(선택): 상태 한 줄 · 연결 · 레버암 · 폴 높이. R10 없이도 앱은 그대로 */
@Composable
fun R10Sheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val hub = R10Hub.current
    var lever by remember { mutableStateOf(R10Prefs.lever(ctx)) }
    var pole by remember { mutableStateOf(R10Prefs.pole(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    val t = hub?.tagNow()
    val status = when {
        hub == null || !R10Hub.connected() -> "R10 연결 안 됨 · 없어도 촬영·좌표 입력·내보내기는 그대로"
        t == null -> "R10 연결 중 · 위치 기다리는 중"
        t.fixed -> "R10 FIX" + (t.sigmaH?.let { String.format(java.util.Locale.US, " ±%.1f cm", it * 100) } ?: "") + " · 사진에 위치를 넣어요"
        else -> "R10 ${t.quality} · FIX 아님 · 사진은 찍히고 표시만 해요"
    }
    Box(Modifier.fillMaxSize().background(P.bg.copy(alpha = 0.98f)).safeDrawingPadding().padding(16.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("R10 (선택)", bold = true, size = 22.sp)
            Txt(status, bold = true, color = if (t?.fixed == true) P.fix else P.sub)
            PrimaryBtn(if (R10Hub.connected()) "연결 화면" else "R10 연결", Modifier.fillMaxWidth()) {
                val h = R10Hub.get(ctx); h.cc.open = true; onClose()
            }
            Txt("렌즈 → 안테나 받침 (폰 기준, cm) · 기본 0", size = 15.sp, color = P.sub)
            Txt("cm 정확도는 폰을 폴에 고정해야 나와요(손으로 대면 몇 cm 흔들림). 빨리 걷지 말고 멈춰 찍으면 좋아요.", size = 14.sp, color = P.float)
            @Composable fun num(label: String, v: Double, set: (Double) -> Unit) {
                var s by remember { mutableStateOf(if (v == 0.0) "" else v.toString()) }
                OutlinedTextField(s, { s = it; it.replace(',', '.').toDoubleOrNull()?.takeIf { d -> kotlin.math.abs(d) <= 500 }?.let(set) ?: if (it.isBlank()) set(0.0) else Unit },
                    Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            @Composable fun cm(label: String, vM: Double, set: (Double) -> Unit) = num(label, vM * 100) { set(it / 100) }
            cm("위(+) / 아래(−)", lever.up) { lever = lever.copy(up = it); R10Prefs.setLever(ctx, lever) }
            cm("앞(+, 카메라 쪽) / 뒤(−)", lever.fwd) { lever = lever.copy(fwd = it); R10Prefs.setLever(ctx, lever) }
            cm("오른쪽(+) / 왼쪽(−)", lever.right) { lever = lever.copy(right = it); R10Prefs.setLever(ctx, lever) }
            var apc by remember { mutableStateOf(R10Prefs.apc(ctx)) }
            cm("R10 위상중심 상수(받침 → APC, 따로)", apc) { apc = it; R10Prefs.setApc(ctx, it) }
            Txt("표적 재기 · 폴 높이(m)", size = 15.sp, color = P.sub)
            num("폴 높이", pole) { if (it > 0) { pole = it; R10Prefs.setPole(ctx, it) } }
            OutlineBtn("닫기", Modifier.fillMaxWidth()) { onClose() }
        }
    }
}
