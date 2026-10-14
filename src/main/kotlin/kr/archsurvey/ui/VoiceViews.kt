package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.voicecmd.RecognizerMode
import kr.archsurvey.voicecmd.VoiceCommandController
import kr.archsurvey.voicecmd.VoiceState

/** 0.1.28 T14 문구 */
object VoiceCopy {
    const val NOTE = "측량 화면에서 「저장」이라고 말하면 측량하기 버튼과 똑같이 측점해요(같은 품질 판정). " +
        "주의·기울기·중복 확인은 음성으로도 하나씩 묻고, 「예」라고 말하거나 화면을 눌러야 저장해요. 10초 안에 답이 없으면 취소해요. " +
        "이어폰 재생 버튼 1번 = 저장 요청(확인 승인에는 못 써요). 측량 화면이 보이고 수신기가 연결돼 있을 때만 들어요. 앱은 소리를 저장하지 않아요."
    fun modeLine(m: RecognizerMode): String = when (m) {
        RecognizerMode.ON_DEVICE -> "기기 내 인식 · 오프라인"
        RecognizerMode.ONLINE -> "온라인 인식 · 말소리가 음성 인식 서비스(인터넷)로 보내질 수 있어요"
        RecognizerMode.NONE -> "쓸 수 없음"
    }
}

/** 설정 화면 카드 */
@Composable
internal fun VoiceSettingsCard(v: VoiceCommandController) {
    val t = T
    Card {
        if (!v.supported) {
            Text("음성 「저장」: 이 폰에서는 음성 인식 서비스를 찾을 수 없어 쓸 수 없어요", style = Ty.body, color = t.inkSub, modifier = Modifier.testTag("set_voice_unsupported"))
            return@Card
        }
        ToggleRow("음성 「저장」으로 측점", v.enabled, "set_voice_save") { v.toggle(it) }
        if (v.enabled) Text("방식: " + VoiceCopy.modeLine(v.mode), style = Ty.label, color = if (v.mode == RecognizerMode.ONLINE) t.bad else t.ink, modifier = Modifier.testTag("set_voice_mode"))
        Text(VoiceCopy.NOTE, style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("set_voice_note"))
    }
}

/** 측량 화면(지도 왼쪽 아래) 듣는 중 표시. 온라인 인식이면 「온라인 인식」을 따로 보인다 */
@Composable
internal fun VoicePill(c: SurveyController) {
    val v = c.voice ?: return
    if (!v.enabled || v.state == VoiceState.OFF) return
    val t = T
    val live = v.state == VoiceState.LISTENING || v.state == VoiceState.CONFIRM
    val online = v.mode == RecognizerMode.ONLINE
    val dot = when { v.issue != null -> t.bad; live -> t.ok; else -> t.inkSub }
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.heightIn(min = 40.dp).background(t.raised.copy(alpha = 0.95f), shape).border(1.5.dp, if (online) t.bad else if (live) t.ok else t.line, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = "음성 저장 " + v.indicatorText() }.testTag("voice_pill"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(12.dp).background(dot, CircleShape).testTag("voice_dot"))
        GlyphIcon(Glyph.MIC, t.ink, 16.dp)
        Column {
            Text(v.indicatorText(), style = Ty.label.copy(fontSize = 16.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("voice_state"))
            Text(
                (if (online) "온라인 인식" else "기기 내 인식") + (v.issue?.let { " · $it" } ?: ""),
                style = Ty.small.copy(fontSize = 16.sp, lineHeight = 21.sp), color = if (online || v.issue != null) t.bad else t.inkSub, maxLines = 1,
                modifier = Modifier.testTag("voice_mode"),
            )
        }
    }
}

/** 음성 확인 대기 중: 화면 맨 위 띠(확인 창 위에 겹치지 않게 위쪽) */
@Composable
internal fun BoxScope.VoiceConfirmBanner(c: SurveyController) {
    val v = c.voice ?: return
    val p = v.asking ?: return
    if (v.state != VoiceState.CONFIRM && v.state != VoiceState.SPEAKING) return
    val t = T
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 12.dp, end = 12.dp).fillMaxWidth()
            .background(t.raised, shape).border(2.dp, t.caution, shape).padding(horizontal = 14.dp, vertical = 10.dp).testTag("voice_confirm_banner"),
    ) {
        Text(p.question, style = Ty.label, color = t.ink, maxLines = 2)
        Text(
            if (v.state == VoiceState.CONFIRM) "「예」라고 말하거나 화면에서 고르세요 · ${v.confirmLeftSec}초 뒤 취소 · 이어폰 버튼 = 취소" else "음성 안내 중…",
            style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("voice_confirm_hint"),
        )
    }
}
