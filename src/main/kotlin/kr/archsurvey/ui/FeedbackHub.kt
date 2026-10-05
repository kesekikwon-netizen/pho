package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 확인 대화상자: 되돌릴 수 없거나 조심할 동작 앞에서 */
data class ConfirmRequest(
    val title: String, val body: String, val okLabel: String,
    val cancelLabel: String = "취소", val danger: Boolean = false, val onOk: () -> Unit,
)

/** 선택 창: 목록에서 하나 고르기 */
data class ChoiceRequest(
    val title: String, val options: List<String>, val selected: Int, val tagPrefix: String,
    val note: String? = null, val onPick: (Int) -> Unit,
)

/**
 * 0.1.9 피드백 창 모음(화면 맨 위에 그린다): 저장 알림(토스트), 확인 대화상자, 선택 창.
 * 진행 창(측정·연결·소스테이블·내보내기)은 각 기능의 상태에서 바로 그린다.
 */
class FeedbackHub {
    var toast by mutableStateOf<String?>(null); private set
    /** 새 알림마다 증가 → 화면이 숨김 타이머를 다시 잰다 */
    var toastSeq by mutableIntStateOf(0); private set
    var confirm by mutableStateOf<ConfirmRequest?>(null); private set
    var choice by mutableStateOf<ChoiceRequest?>(null); private set
    /** 알림 표시 시간(ms) */
    var toastMs = 3500L

    fun toast(text: String) { toast = text; toastSeq++ }
    fun hideToast() { toast = null }

    fun ask(r: ConfirmRequest) { confirm = r }
    fun answer(ok: Boolean) { val r = confirm; confirm = null; if (ok) r?.onOk?.invoke() }

    fun choose(r: ChoiceRequest) { choice = r }
    fun choose(title: String, options: List<String>, selected: Int, tagPrefix: String, note: String? = null, onPick: (Int) -> Unit) =
        choose(ChoiceRequest(title, options, selected, tagPrefix, note, onPick))
    fun pick(i: Int) { val r = choice; choice = null; if (r != null && i in r.options.indices) r.onPick(i) }
    fun dismissChoice() { choice = null }
}
