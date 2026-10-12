package kr.archsurvey.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.archsurvey.feedback.FeedbackEvent

/**
 * 0.1.19 SFX-0119-1: RTK 고정↔유동이 짧게 반복(트렌치 벽·나무 밑)될 때 FIX_ACQUIRED/FIX_LOST 소리·진동 연타 방지.
 * 화면 품질 표시는 엔진 gnss 로 즉시 바뀌고, 소리만 여기서 거른다:
 * 새 상태(고정/비고정)가 [holdMs] 동안 유지되고, 마지막으로 알린 상태와 다를 때만 한 번 emit.
 * → 한 번 고정 획득·오래 잃음은 울리고, 플랩 중에는 울리지 않는다.
 */
class FixSoundGate(
    private val scope: CoroutineScope,
    private val holdMs: Long = HOLD_MS,
    private val emit: (FeedbackEvent) -> Unit,
) {
    companion object { const val HOLD_MS = 3_000L }

    private var announcedFixed = false
    private var gen = 0L
    private var pending: Job? = null

    /** 고정 여부가 바뀔 때마다 호출(같은 값 반복 호출도 무해) */
    @Synchronized fun onFixedChanged(fixed: Boolean) {
        pending?.cancel(); pending = null
        val g = ++gen
        if (fixed == announcedFixed) return // 알린 상태로 되돌아옴 → 무음
        pending = scope.launch { delay(holdMs); commit(g, fixed) }
    }

    @Synchronized private fun commit(g: Long, fixed: Boolean) {
        if (g != gen || fixed == announcedFixed) return
        announcedFixed = fixed
        pending = null
        emit(if (fixed) FeedbackEvent.FIX_ACQUIRED else FeedbackEvent.FIX_LOST)
    }

    /** 세션 종료(엔진 stop): 무음으로 초기화. 다음 세션 첫 고정은 다시 울림 */
    @Synchronized fun reset() {
        pending?.cancel(); pending = null
        gen++
        announcedFixed = false
    }
}
