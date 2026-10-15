package kr.archsurvey.bt

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.DeviceProfiles

/** R10 자동설정 시간값. 시험은 짧게 준다 */
data class R10AutoTiming(
    /** 연결 뒤 자연 NMEA 를 기다리는 시간 */
    val naturalWaitMs: Long = 2_000,
    /** ENQ·64h 단일 바이트 응답 대기(ICD: 보통 500 ms 안) */
    val ackWaitMs: Long = 600,
    /** 포트에 켜고 문장이 오는지 보는 시간 */
    val probeWaitMs: Long = 2_500,
    /** 다 켠 뒤 어떤 문장이 오는지 보는 시간(진단용) */
    val settleMs: Long = 2_500,
    /** GGA 는 오는데 GST/GSA 가 이 시간 동안 안 오면 보충 */
    val precisionCheckMs: Long = 5_000,
)

/** 수신기 주소별로 확인된 R10 블루투스 포트(0-based 17–19) 저장소 */
interface R10PortStore {
    fun port(): Int?
    fun save(port: Int)
}

/** LinkSupervisor 에 넘기는 R10 자동설정 묶음. SP60·기타 수신기는 null */
class R10AutoOptions(
    val store: R10PortStore, val timing: R10AutoTiming = R10AutoTiming(), val probeWhenNatural: Boolean = false,
    /** 0.1.41: 양방향 확인(GETSERIAL) + 포트별로 안 되면 17·18·19 한꺼번에 길게 한 번 더 */
    val extended: Boolean = false,
) {
    companion object {
        /** Engine 이 쓰는 선택: R10 만 자동설정. SP60 경로는 그대로 */
        fun forProfile(profile: DeviceProfile, store: R10PortStore): R10AutoOptions? =
            if (profile.id == DeviceProfiles.R10_ID) R10AutoOptions(store, extended = true) else null
    }
}

enum class R10AutoOutcome {
    /** 이미 GGA 가 와서 아무 명령도 안 보냄 */
    NATURAL,
    /** 포트를 찾아 켰고 문장이 옴 */
    PORT_FOUND,
    /** GGA 는 원래 오고, 빠진 GST/GSA 를 보충해 옴 */
    PRECISION_ADDED,
    /** GGA·GST·GSA 가 다 와서 보충 필요 없음 */
    PRECISION_OK,
    /** 0.1.34: 자연 NMEA 인데 GST/GSA 없음 — 64h 를 보내지 않고 기록·안내만 */
    PRECISION_MISSING_NATURAL,
    /** 0.1.57: 4종을 켜니 GGA 가 멈춰 GGA 만 다시 켜서 옴 */
    GGA_ONLY_FALLBACK,
    /** 모든 포트가 NAK(거부) */
    ALL_NAK,
    /** 명령은 보냈지만 어떤 포트에서도 문장이 안 옴 */
    NOT_FOUND,
}

data class R10AutoResult(
    val outcome: R10AutoOutcome,
    val port: Int?,
    val enq: String,
    val tried: List<String>,
    val seenAfter: Set<String>,
    val elapsedMs: Long,
    val log: List<String>,
    /** 0.1.41 GETSERIAL 응답: "RSERIAL 5411457757" · "응답없음" · "-"(안 물어봄) */
    val twoWay: String = "-",
) {
    val gotGga: Boolean get() = outcome == R10AutoOutcome.NATURAL || outcome == R10AutoOutcome.PORT_FOUND || outcome == R10AutoOutcome.GGA_ONLY_FALLBACK
    fun summary(): String = "R10 자동설정 결과=$outcome 포트=${port ?: "-"} ENQ=$enq 시도=${tried.joinToString(", ").ifEmpty { "-" }} " +
        "이후문장=${seenAfter.sorted().joinToString("/").ifEmpty { "-" }} 양방향=$twoWay ${elapsedMs}ms"
}

/**
 * R10 블루투스 포트 출력 자동 켜기(Trimble DCOL 64h). 연결(세션)마다 새로 만든다.
 *
 * 순서(출력 켜기 [enableOutput]): 자연 NMEA 2 s 대기 → ENQ(05, 응답은 기록만) → 저장된 포트 먼저, 없으면 17·18·19 →
 * 각 포트에 GGA 1 Hz 를 켜고 2.5 s 안에 GGA 가 오면 그 포트 확정·저장 → GGA·GST·GSA·GSV 1 Hz.
 * 안 오면 **우리가 켠 그 GGA 만** 끄고 다음 포트. NAK(거부)면 켜진 게 없으므로 끄지 않는다.
 *
 * 보충([addPrecision]): GGA 는 오는데 GST/GSA 가 5 s 안 오면, 빠진 문장 하나로 같은 방식의 포트 탐색 → 찾은 포트에 GST·GSA·GSV.
 * GGA 는 절대 끄지 않는다. 끄는 것은 이번에 우리가 켰는데 문장이 안 온 포트의 그 문장뿐.
 *
 * @param send 쓰기(LinkSupervisor 가 RTCM 프레임 사이에만 쓴다)
 * @param events 수신 스트림의 ACK/NAK/DCOL 프레임
 * @param count 지금까지 받은 문장 종류별 개수(GGA…)
 * @param nextTx 64h 전송 번호(연결을 넘어 1씩 증가, 0–255)
 */
class R10AutoConfig(
    private val send: suspend (bytes: ByteArray, label: String) -> Unit,
    private val events: ReceiveChannel<DcolEvent>,
    private val count: (String) -> Long,
    private val clock: () -> Long,
    private val store: R10PortStore,
    private val timing: R10AutoTiming = R10AutoTiming(),
    private val nextTx: () -> Int,
    private val onStatus: (String) -> Unit = {},
    /**
     * 0.1.34(사용자 SmartTopo 분석 반영): 자연 NMEA 가 이미 오면 DCOL 64h 를 보내지 않는다(기본 false).
     * true 일 때만 0.1.33 의 GST 포트 탐색 보충을 한다(시험·향후 선택용).
     */
    private val probeWhenNatural: Boolean = false,
    private val extended: Boolean = false,
) {
    companion object {
        val FULL_SET = listOf("GGA", "GST", "GSA", "GSV")
        val PRECISION_SET = listOf("GST", "GSA", "GSV")
    }

    private val log = ArrayList<String>()
    private var t0 = 0L
    private fun age() = clock() - t0

    private fun drain() { while (events.tryReceive().isSuccess) { } }

    /** 단일 바이트 응답(ACK/NAK)을 [ms] 동안 기다린다. DCOL 프레임은 기록만 하고 계속 */
    private suspend fun awaitReply(ms: Long): String {
        val end = clock() + ms
        while (true) {
            val left = end - clock()
            if (left <= 0) return "-"
            val e = withTimeoutOrNull(left) { events.receive() } ?: return "-"
            when (e) {
                DcolEvent.Ack -> return "ACK"
                DcolEvent.Nak -> return "NAK"
                is DcolEvent.Frame -> log += "RX $e"
            }
        }
    }

    private suspend fun waitFor(type: String, base: Long, ms: Long): Boolean {
        val end = clock() + ms
        while (clock() < end) { if (count(type) > base) return true; delay(50) }
        return count(type) > base
    }

    private suspend fun app(port: Int, sentences: List<String>, on: Boolean): String {
        drain()
        val pkt = TrimbleDcol.setOutputs(nextTx(), port, sentences, on)
        val label = "64h ${sentences.joinToString("+")} ${if (on) "1Hz" else "끔"} 포트$port"
        send(pkt, label)
        val r = awaitReply(timing.ackWaitMs)
        log += "TX $label [${TrimbleDcol.hex(pkt)}] → $r (${age()}ms)"
        return r
    }

    private fun candidates(): List<Int> {
        val saved = store.port()?.takeIf { it in TrimbleDcol.BT_PORTS }
        return if (saved == null) TrimbleDcol.BT_PORTS else listOf(saved) + TrimbleDcol.BT_PORTS.filter { it != saved }
    }

    private suspend fun enq(): String {
        drain()
        send(TrimbleDcol.enq(), "ENQ")
        val r = awaitReply(timing.ackWaitMs)
        log += "TX ENQ [05] → $r (${age()}ms)"
        return r
    }

    /** 0.1.41 GETSERIAL → RSERIAL 기다림(읽기 전용, 링크가 양방향인지·수신기가 이 세션 명령을 처리하는지) */
    private suspend fun serial(): String {
        drain()
        send(TrimbleDcol.getSerial(), "GETSERIAL")
        val end = clock() + timing.ackWaitMs * 2
        var r = "응답없음"
        while (true) {
            val left = end - clock()
            if (left <= 0) break
            val e = withTimeoutOrNull(left) { events.receive() } ?: break
            if (e is DcolEvent.Frame && e.type == TrimbleDcol.TYPE_RSERIAL) { r = "RSERIAL " + TrimbleDcol.serialText(e.data).ifEmpty { "?" }; break }
            if (e == DcolEvent.Nak) { r = "NAK"; break }
        }
        log += "TX GETSERIAL [${TrimbleDcol.hex(TrimbleDcol.getSerial())}] → $r (${age()}ms)"
        return r
    }

    /** 0.1.41 17·18·19 한꺼번에 GGA 켜고 길게(포트 번호가 맞는데 늦게 나오는 경우·세션 포트를 모를 때) */
    private suspend fun allPortsOnce(tried: MutableList<String>): Boolean {
        val ports = TrimbleDcol.BT_PORTS
        val base = count("GGA")
        drain()
        val pkt = TrimbleDcol.setOutputsPorts(nextTx(), ports, listOf("GGA"), true)
        send(pkt, "64h GGA 1Hz 포트17·18·19")
        val r = awaitReply(timing.ackWaitMs)
        log += "TX 64h GGA 1Hz 포트17·18·19 [${TrimbleDcol.hex(pkt)}] → $r (${age()}ms)"
        if (r == "NAK") { tried += "17-19:NAK"; return false }
        if (waitFor("GGA", base, timing.probeWaitMs * 2)) {
            tried += "17-19:$r→GGA ${age()}ms"
            drain()
            val full = TrimbleDcol.setOutputsPorts(nextTx(), ports, FULL_SET, true)
            send(full, "64h 4종 1Hz 포트17·18·19")
            log += "TX 64h 4종 포트17·18·19 → ${awaitReply(timing.ackWaitMs)}"
            return true
        }
        drain()
        val off = TrimbleDcol.setOutputsPorts(nextTx(), ports, listOf("GGA"), false)
        send(off, "64h GGA 끔 포트17·18·19")
        val ro = awaitReply(timing.ackWaitMs)
        tried += "17-19:$r→무GGA→끔$ro"
        return false
    }

    private suspend fun observe(): Set<String> {
        val base = FULL_SET.associateWith { count(it) }
        delay(timing.settleMs)
        return FULL_SET.filter { count(it) > base.getValue(it) }.toSet()
    }

    /** 포트 하나씩 [probe] 문장을 켜 보고, 온 포트를 돌려준다. 실패한 포트는 그 문장만 끈다 */
    private suspend fun probePorts(probe: String, tried: MutableList<String>): Pair<Int?, Boolean> {
        var allNak = true
        for (p in candidates()) {
            val base = count(probe)
            onStatus("R10 블루투스 포트 $p 에 $probe 켜 보는 중…")
            val r = app(p, listOf(probe), on = true)
            if (r != "NAK") allNak = false
            if (r == "NAK") { tried += "$p:NAK"; continue } // 거부 = 켜진 것 없음 → 끄지 않는다
            if (waitFor(probe, base, timing.probeWaitMs)) {
                tried += "$p:$r→$probe ${age()}ms"
                store.save(p)
                return p to false
            }
            val off = app(p, listOf(probe), on = false)
            tried += "$p:$r→무$probe→끔$off"
        }
        return null to allNak
    }

    /** 출력 켜기(연결 직후). GGA 가 없을 때만 명령을 보낸다 */
    suspend fun enableOutput(): R10AutoResult {
        t0 = clock()
        val tried = ArrayList<String>()
        onStatus("위치(GGA) 기다리는 중…")
        // count 는 이 연결에서 받은 개수(0부터). 하나라도 오면 자연 수신
        if (waitFor("GGA", 0, timing.naturalWaitMs)) {
            log += "자연 NMEA 수신 (${age()}ms) — 명령 안 보냄"
            return R10AutoResult(R10AutoOutcome.NATURAL, null, "-", tried, emptySet(), age(), log)
        }
        onStatus("R10 출력 자동 켜기 시작…")
        val enq = enq()
        if (count("GGA") > 0) {
            log += "ENQ 도중 GGA 수신 — 자연 수신으로 처리"
            return R10AutoResult(R10AutoOutcome.NATURAL, null, enq, tried, emptySet(), age(), log)
        }
        val two = if (extended) { onStatus("R10 이 명령을 받는지 확인 중(일련번호)…"); serial() } else "-"
        val (port, allNak) = probePorts("GGA", tried)
        if (port == null) {
            if (extended && !allNak) {
                onStatus("R10 블루투스 포트 17·18·19 한꺼번에 켜 보는 중…")
                if (allPortsOnce(tried)) {
                    val seen = observe()
                    log += "포트 17·18·19 한꺼번에 켜서 GGA 수신 · 이후 문장 ${seen.sorted()} (${age()}ms)"
                    return R10AutoResult(R10AutoOutcome.PORT_FOUND, null, enq, tried, seen, age(), log, two)
                }
            }
            val out = if (allNak) R10AutoOutcome.ALL_NAK else R10AutoOutcome.NOT_FOUND
            log += "결과 $out 양방향=$two (${age()}ms)"
            return R10AutoResult(out, null, enq, tried, emptySet(), age(), log, two)
        }
        onStatus("R10 포트 $port 확인 · GGA·GST·GSA·GSV 켜는 중…")
        app(port, FULL_SET, on = true)
        var seen = observe()
        log += "확정 포트 $port · 이후 문장 ${seen.sorted()} (${age()}ms)"
        if ("GGA" !in seen) {
            // 0.1.57: 4종을 켠 뒤 GGA 가 멈춤(현장 2026-10-15: 이후 문장 [] · 59 s 동안 6문장) → 위치가 안 와 측량 불가.
            // 보충 3종을 끄고 GGA 만 다시 켠다(위치가 정밀 문장보다 먼저)
            onStatus("R10 위치가 멈춰 GGA 만 다시 켜는 중…")
            app(port, PRECISION_SET, on = false)
            app(port, listOf("GGA"), on = true)
            seen = observe()
            log += "GGA 멈춤 → 보충 끄고 GGA 만 · 이후 문장 ${seen.sorted()} (${age()}ms)"
            if ("GGA" in seen) return R10AutoResult(R10AutoOutcome.GGA_ONLY_FALLBACK, port, enq, tried, seen, age(), log, two)
        }
        return R10AutoResult(R10AutoOutcome.PORT_FOUND, port, enq, tried, seen, age(), log, two)
    }

    /**
     * GGA 가 오는 중에 GST/GSA 가 빠졌으면 보충. [knownPort] 는 이번 연결에서 [enableOutput] 으로 확정한 포트
     * (이미 4종을 켰으므로 다시 탐색하지 않고 기록만).
     */
    suspend fun addPrecision(knownPort: Int?): R10AutoResult {
        t0 = clock()
        val tried = ArrayList<String>()
        val base = listOf("GST", "GSA").associateWith { count(it) }
        val end = clock() + timing.precisionCheckMs
        while (clock() < end && base.any { (k, v) -> count(k) <= v }) delay(100)
        val missing = base.filter { (k, v) -> count(k) <= v }.keys.toList()
        if (missing.isEmpty()) {
            log += "GST·GSA 수신 중 — 보충 필요 없음 (${age()}ms)"
            return R10AutoResult(R10AutoOutcome.PRECISION_OK, knownPort, "-", tried, setOf("GST", "GSA"), age(), log)
        }
        if (knownPort != null) {
            log += "포트 $knownPort 에 이미 4종을 켰는데 ${missing} 없음 — 다시 보내지 않음(수신기 옵션·펌웨어 확인)"
            return R10AutoResult(R10AutoOutcome.NOT_FOUND, knownPort, "-", tried, emptySet(), age(), log)
        }
        if (!probeWhenNatural) {
            log += "자연 NMEA 수신 중 ${missing} 없음 — 64h 보내지 않음(수신기 설정 그대로). 웹 UI 에서 GST·GSA 켜기 안내"
            return R10AutoResult(R10AutoOutcome.PRECISION_MISSING_NATURAL, null, "-", tried, emptySet(), age(), log)
        }
        val probe = missing.first()
        log += "GGA 는 오는데 ${missing} 없음 → $probe 로 포트 탐색"
        val (port, allNak) = probePorts(probe, tried)
        if (port == null) {
            val out = if (allNak) R10AutoOutcome.ALL_NAK else R10AutoOutcome.NOT_FOUND
            log += "결과 $out (${age()}ms)"
            return R10AutoResult(out, null, "-", tried, emptySet(), age(), log)
        }
        app(port, PRECISION_SET, on = true)
        val seen = observe()
        log += "보충 포트 $port · 이후 문장 ${seen.sorted()} (${age()}ms)"
        return R10AutoResult(R10AutoOutcome.PRECISION_ADDED, port, "-", tried, seen, age(), log)
    }
}
