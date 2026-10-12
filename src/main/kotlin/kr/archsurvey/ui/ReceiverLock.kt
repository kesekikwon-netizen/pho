package kr.archsurvey.ui

/*
 * 0.1.42(측량) · 0.1.4(사진 기준점) 같은 파일 — 두 앱이 한 수신기(R10 은 SPP 링크 하나만 받음)를 서로 빼앗지 않게.
 * 현장 logcat 13:15: 두 앱이 같은 R10 에 7초마다 번갈아 재연결하며 서로의 소켓을 닫음.
 * 방송(같은 서명만 받는 권한 kr.archsurvey.permission.RECEIVER_LOCK)으로 「잡음/놓음/놓아 주세요/누가 잡았나」를 주고받는다.
 * 잡은 쪽은 20초마다 다시 알림 — 60초 넘게 소식이 없으면(앱이 죽음) 잠금은 없는 것으로 본다.
 */
enum class LockOp { HOLD, RELEASE, ASK_RELEASE, QUERY }

data class LockMsg(val op: LockOp, val from: String, val label: String, val mac: String? = null, val to: String? = null)

/** 앱 사이 방송 통로(안드로이드: AndroidLockBus · 시험: 가짜) */
interface LockBus {
    fun send(m: LockMsg)
    var onMessage: ((LockMsg) -> Unit)?
}

class ReceiverLock(
    private val bus: LockBus,
    val self: String,
    val selfLabel: String,
    private val clock: () -> Long,
    val staleMs: Long = 60_000L,
) {
    data class Holder(val pkg: String, val label: String, val mac: String, val seenAt: Long)
    private val holders = LinkedHashMap<String, Holder>()
    var holding: String? = null; private set
    /** 다른 앱이 「놓아 주세요」 함 → 연결을 끊어야 함 */
    var onReleaseRequested: ((String) -> Unit)? = null
    /** 다른 앱이 같은 수신기를 잡음(내가 잡고 있을 때) */
    var onConflict: ((Holder) -> Unit)? = null
    /** 잠금 상태가 바뀜(화면 새로 그리기·기다리던 연결 이어 가기) */
    var onChanged: (() -> Unit)? = null

    init { bus.onMessage = { handle(it) }; runCatching { bus.send(LockMsg(LockOp.QUERY, self, selfLabel)) } }

    private fun same(a: String?, b: String?) = a != null && b != null && a.equals(b, ignoreCase = true)

    /** [mac] 을 다른 앱이 (살아서) 잡고 있으면 그 앱 */
    fun otherHolder(mac: String?): Holder? {
        val now = clock()
        holders.values.removeAll { now - it.seenAt > staleMs }
        return holders.values.firstOrNull { it.pkg != self && (mac == null || same(it.mac, mac)) }
    }

    fun hold(mac: String) { holding = mac; send(LockMsg(LockOp.HOLD, self, selfLabel, mac)) }
    fun release() { if (holding != null) { holding = null; send(LockMsg(LockOp.RELEASE, self, selfLabel)) } }
    /** 잡고 있는 동안 주기적으로(20초) */
    fun heartbeat() { holding?.let { send(LockMsg(LockOp.HOLD, self, selfLabel, it)) } }
    fun askRelease(h: Holder) = send(LockMsg(LockOp.ASK_RELEASE, self, selfLabel, h.mac, to = h.pkg))

    private fun send(m: LockMsg) { runCatching { bus.send(m) } }

    fun handle(m: LockMsg) {
        if (m.from == self) return
        when (m.op) {
            LockOp.HOLD -> {
                val mac = m.mac ?: return
                val h = Holder(m.from, m.label, mac, clock())
                holders[m.from] = h
                if (same(holding, mac)) onConflict?.invoke(h)
            }
            LockOp.RELEASE -> holders.remove(m.from)
            LockOp.ASK_RELEASE -> if (m.to == self && holding != null) onReleaseRequested?.invoke(m.label)
            LockOp.QUERY -> heartbeat()
        }
        onChanged?.invoke()
    }
}

/** 화면 글 */
object LockText {
    fun busy(label: String) = "다른 앱($label)이 수신기를 쓰는 중"
    const val TAKE = "여기서 쓰기"
    fun handedOver(label: String) = "수신기를 $label 에 넘겼어요 · 연결을 끊었어요"
    const val BG_STOP = "앱이 뒤로 가 있어 자동 연결을 멈췄어요 · 앱을 열면 다시 연결해요"
}
