package kr.archsurvey.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kr.archsurvey.core.GnssSnapshot

/**
 * 화면이 보는 단일 [GnssSource]. 설정 토글에 따라 가짜 신호와 실제 수신기를 바꿔 끼운다.
 * 바꾸면 이전 소스의 마지막 값이 남지 않도록 link/gnss 는 새 소스의 값으로 즉시 바뀐다.
 * stop() 은 현재 소스만 멈춘다(바꿔 끼운 이전 소스는 호출자가 책임진다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableGnssSource(scope: CoroutineScope, initial: GnssSource) : GnssSource {
    private val current = MutableStateFlow(initial)

    override val link: StateFlow<LinkStatus> =
        current.flatMapLatest { it.link }.stateIn(scope, SharingStarted.Eagerly, initial.link.value)
    override val gnss: StateFlow<GnssSnapshot> =
        current.flatMapLatest { it.gnss }.stateIn(scope, SharingStarted.Eagerly, initial.gnss.value)

    val delegate: GnssSource get() = current.value

    fun switchTo(src: GnssSource) { current.value = src }

    override fun clockMs(): Long = current.value.clockMs()
    override fun stop() { current.value.stop() }
}
