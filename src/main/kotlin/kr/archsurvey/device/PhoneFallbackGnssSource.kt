package kr.archsurvey.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kr.archsurvey.core.GnssSnapshot

/**
 * 외부 수신기(BT NMEA)가 연결·수신 중이면 그쪽, 아니면 폰 GPS.
 * IDLE / NEEDS_USER 일 때만 폰으로 넘긴다(연결 시도·수신 대기 중에는 수신기 상태 유지).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneFallbackGnssSource(
    private val external: GnssSource,
    private val phone: PhoneGpsSource,
    scope: CoroutineScope,
) : GnssSource {
    private val active = MutableStateFlow(external)

    init {
        scope.launch {
            external.link.collect { st ->
                if (usePhone(st.state)) {
                    phone.ensureStarted()
                    active.value = phone
                } else {
                    if (active.value === phone) phone.stopListening()
                    active.value = external
                }
            }
        }
        // 시작 시 수신기가 IDLE 이면 바로 폰 시도
        if (usePhone(external.link.value.state)) {
            phone.ensureStarted()
            active.value = phone
        }
    }

    override val link: StateFlow<LinkStatus> =
        active.flatMapLatest { it.link }.stateIn(scope, SharingStarted.Eagerly, active.value.link.value)
    override val gnss: StateFlow<GnssSnapshot> =
        active.flatMapLatest { it.gnss }.stateIn(scope, SharingStarted.Eagerly, active.value.gnss.value)

    val usingPhone: Boolean get() = active.value === phone

    override fun clockMs(): Long = active.value.clockMs()
    override fun stop() {
        phone.stopListening()
        external.stop()
    }

    companion object {
        fun usePhone(s: LinkState): Boolean = s == LinkState.IDLE || s == LinkState.NEEDS_USER
    }
}
