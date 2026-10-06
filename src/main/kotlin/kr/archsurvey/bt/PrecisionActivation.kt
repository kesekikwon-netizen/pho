package kr.archsurvey.bt

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kr.archsurvey.core.NmeaParser
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.1.30 T23: SP60 정밀도 문장(GST·GSA) 자동 요청.
 *
 * 원인(통신GNSS): [NmeaActivation] 은 GGA 가 이미 오면 NATURAL_GGA 로 끝나 GST/GSA 명령을 한 번도 보내지 않는다 →
 * SP60 이 GST/GSA 를 안 내면 고정이어도 σH/σV·PDOP 가 비었다. 그래서 GGA 확인 뒤 **따로** 검사·요청하는 단계.
 *
 * 근거: SP80 ICD Rel 1.2 §3.1.67 NME · §3.2.57 PRT (형제기종). **SP60 적용은 [미확인]**, 수신기에 저장되는지도 [미확인] → 연결마다 다시 검사.
 *
 * 규칙(게이트 통과 조건 + 통신GNSS 보충, 시험으로 고정):
 * - SP60 만. 보낼 수 있는 문자열은 [PrecisionCommands.isAllowed] 세 가지 꼴뿐: `$PASHQ,PRT` · `$PASHS,NME,GST,<p>,ON,1` · `$PASHS,NME,GSA,<p>,ON,1`.
 *   NME,ALL · $PASHS,PRT · RST · INI · OUT · DIF · GGA 관련 · 1초보다 짧은 주기는 만들 수 없다.
 * - 한 번에 하나씩: PRT → (없는 것만) GST → GSA. 다음 명령은 응답(ACK/NAK/PRT) 또는 1.5 s 뒤.
 * - 포트: `$PASHR,PRT,` 로 시작하고 체크섬이 맞는 줄의 둘째 필드. A/B/C/D/I 만 받음. 그 밖·응답 없음·NAK → C 로 대체(로그).
 * - 성공 판정은 ACK 가 아니라 명령 뒤 5 s 안에 GST·GSA 문장이 실제로 들어왔는지(seenTypes). 하나만 오면 부분 성공.
 * - 실패해도 예외를 던지지 않는다(연결·RTCM 흐름 유지). 쓰기는 호출 측이 RTCM 프레임 사이에만 끼워 넣는다.
 * - 한 연결에 한 번. 같은 수신기에 앱 실행 동안 최대 [PrecisionBudget.MAX_RUNS] 번(첫 연결 + 재연결 1번) — 재연결 고리에서 반복 송신 금지.
 */
object PrecisionCommands {
    const val PRT_QUERY = "PASHQ,PRT"
    const val FALLBACK_PORT = 'C'
    /** 통신GNSS 확정: 이 글자만 포트로 믿는다 */
    val ALLOWED_PORTS: Set<Char> = setOf('A', 'B', 'C', 'D', 'I')
    /** 켜는 문장 순서 */
    val SENTENCES = listOf("GST", "GSA")

    fun enable(sentence: String, port: Char): String {
        require(sentence in SENTENCES) { "GST/GSA 만" }
        require(port in ALLOWED_PORTS) { "포트 A/B/C/D/I 만" }
        return "PASHS,NME,$sentence,$port,ON,1"
    }

    private val ENABLE_RE = Regex("""PASHS,NME,(GST|GSA),[ABCDI],ON,1""")
    /** 이 단계가 보낼 수 있는 본문인가(`$`·체크섬 제외) */
    fun isAllowed(body: String): Boolean = body == PRT_QUERY || ENABLE_RE.matches(body)

    /** 송신 줄(체크섬 포함, CRLF) */
    fun line(body: String): String = NmeaParser.build(body)
}

/** `$PASHR,…` 응답 한 줄 해석 */
sealed class PashReply {
    abstract val raw: String
    data class Ack(override val raw: String) : PashReply()
    data class Nak(override val raw: String) : PashReply()
    /** [port] = 둘째 필드 첫 글자. 체크섬이 없거나 틀리면 [checksumOk]=false */
    data class Prt(override val raw: String, val port: Char?, val checksumOk: Boolean) : PashReply()
    data class Other(override val raw: String) : PashReply()

    companion object {
        /** 체크섬이 있으면 검사. 없으면 null */
        fun checksum(line: String): Boolean? {
            val star = line.lastIndexOf('*')
            if (star < 0) return null
            val body = line.substring(1, star)
            val given = line.substring(star + 1).trim().take(2).toIntOrNull(16) ?: return false
            return given == NmeaParser.checksum(body)
        }

        fun parse(line: String): PashReply {
            val s = line.trim()
            val body = s.substringBefore('*')
            val f = body.split(',')
            return when {
                f.size >= 2 && f[0] == "\$PASHR" && f[1] == "ACK" -> Ack(s)
                f.size >= 2 && f[0] == "\$PASHR" && f[1] == "NAK" -> Nak(s)
                f.size >= 3 && f[0] == "\$PASHR" && f[1] == "PRT" -> Prt(s, f[2].trim().singleOrNull(), checksum(s) == true)
                else -> Other(s)
            }
        }
    }
}

/**
 * `$PASHR,` 응답 줄 모으기. 읽기 청크가 줄 중간에서 잘려 와도 CR/LF 까지 모은 뒤 한 줄로 돌려준다.
 * 비 ASCII 바이트(같은 소켓의 바이너리)는 그 줄을 버린다. 스레드 안전하지 않음(읽기 스레드 하나).
 */
class PashrLineAssembler(private val maxLine: Int = 200) {
    private val sb = StringBuilder()
    private var inLine = false

    fun feed(buf: ByteArray, len: Int = buf.size): List<String> {
        var out: ArrayList<String>? = null
        for (i in 0 until len) {
            val b = buf[i].toInt() and 0xFF
            when {
                b == '\r'.code || b == '\n'.code -> {
                    if (inLine && sb.startsWith("\$PASHR,")) (out ?: ArrayList<String>(1).also { out = it }).add(sb.toString())
                    sb.setLength(0); inLine = false
                }
                b == '$'.code -> { sb.setLength(0); sb.append('$'); inLine = true }
                !inLine -> {}
                b in 0x20..0x7E -> if (sb.length < maxLine) sb.append(b.toChar()) else { sb.setLength(0); inLine = false }
                else -> { sb.setLength(0); inLine = false }
            }
        }
        return out ?: emptyList()
    }
}

/** 같은 수신기(주소)에 앱 실행 동안 자동 요청 횟수 상한. 게이트: 연결당 1번 + 재연결 뒤 1번 더 */
class PrecisionBudget(val maxRuns: Int = MAX_RUNS) {
    @Volatile var used = 0; private set
    @Synchronized fun tryAcquire(): Boolean { if (used >= maxRuns) return false; used++; return true }

    companion object {
        const val MAX_RUNS = 2
        private val byDevice = ConcurrentHashMap<String, PrecisionBudget>()
        /** 앱(프로세스) 수명 동안 주소별 하나 */
        fun forDevice(address: String): PrecisionBudget = byDevice.getOrPut(address) { PrecisionBudget() }
    }
}

enum class PrecisionOutcome(val ko: String) {
    /** 명령 없이도 GST·GSA 가 이미 옴 */
    ALREADY_PRESENT("이미 수신"),
    SUCCESS("성공"),
    PARTIAL_GST_ONLY("부분 성공(GST만)"),
    PARTIAL_GSA_ONLY("부분 성공(GSA만)"),
    /** 명령에 NAK(도난경보 중이면 모든 명령이 NAK 일 수 있음) */
    FAILED_NAK("실패(NAK)"),
    /** 응답 없음 또는 명령 뒤 5 s 안에 문장 없음 */
    FAILED_TIMEOUT("실패(무응답·문장 없음)"),
    FAILED_WRITE("실패(쓰기 오류)"),
    /** 이 수신기에 이번 실행 상한을 다 씀 → 보내지 않음 */
    BUDGET_USED("보내지 않음(횟수 상한)"),
}

data class PrecisionResult(
    val outcome: PrecisionOutcome,
    /** 명령에 쓴 포트(보내지 않았으면 null) */
    val port: Char?,
    /** "PRT 응답" / "대체 C(무응답)" 등 */
    val portSource: String,
    val sent: List<String>,
    val replies: List<String>,
    val log: List<String>,
    /** 명령을 다 보낸 뒤 첫 GST/GSA 를 본 시각(clock ms). 못 봤으면 null */
    val firstGstAtMs: Long?,
    val firstGsaAtMs: Long?,
    val seenTypes: Set<String>,
    val atMs: Long,
) {
    /** GST(σ)가 여전히 없음 → 사용자 안내 대상 */
    val gstMissing: Boolean get() = "GST" !in seenTypes
    val failed: Boolean get() = outcome == PrecisionOutcome.FAILED_NAK || outcome == PrecisionOutcome.FAILED_TIMEOUT ||
        outcome == PrecisionOutcome.FAILED_WRITE || outcome == PrecisionOutcome.BUDGET_USED
    fun summary(): String = "정밀도 자동요청 ${outcome.ko} · 포트 ${port ?: "-"}($portSource) · 받은 문장 ${seenTypes.sorted().joinToString("/")}"
}

/**
 * 순서대로 한 번 돌린다. 시간은 [clock](ms)과 코루틴 delay 로 잰다(시험은 가상 시간).
 * [send] 는 본문을 받아 체크섬 줄로 쓰는 함수 — 호출 측이 RTCM 프레임 경계에서만 쓴다. 예외를 던지면 FAILED_WRITE.
 */
class PrecisionActivator(
    private val clock: () -> Long,
    private val naturalWaitMs: Long = 5_000,
    private val replyWaitMs: Long = 1_500,
    private val verifyWaitMs: Long = 5_000,
    private val pollMs: Long = 50,
) {
    suspend fun run(
        send: suspend (String) -> Unit,
        replies: ReceiveChannel<String>,
        seenTypes: () -> Set<String>,
        budget: PrecisionBudget,
    ): PrecisionResult {
        val t0 = clock()
        val sent = ArrayList<String>(); val got = ArrayList<String>(); val log = ArrayList<String>()
        fun has(t: String) = t in seenTypes()
        fun result(o: PrecisionOutcome, port: Char?, src: String, gstAt: Long? = null, gsaAt: Long? = null): PrecisionResult {
            log += "결과 ${o.ko} · 받은 문장 ${seenTypes().sorted()} · ${clock() - t0}ms"
            return PrecisionResult(o, port, src, sent.toList(), got.toList(), log.toList(), gstAt, gsaAt, seenTypes(), clock())
        }
        // 1) GGA 확인 뒤 5 s 자연 대기
        val until = t0 + naturalWaitMs
        while (clock() < until && !(has("GST") && has("GSA"))) delay(pollMs)
        if (has("GST") && has("GSA")) { log += "GST·GSA 이미 수신 — 명령 안 보냄"; return result(PrecisionOutcome.ALREADY_PRESENT, null, "-") }
        val missing = PrecisionCommands.SENTENCES.filter { !has(it) }
        log += "${naturalWaitMs / 1000}초 동안 ${missing.joinToString("·")} 없음"
        if (!budget.tryAcquire()) { log += "이 수신기 자동요청 상한 ${budget.maxRuns}번을 이미 씀 — 보내지 않음"; return result(PrecisionOutcome.BUDGET_USED, null, "-") }
        while (replies.tryReceive().isSuccess) { } // 앞선 응답 버림

        suspend fun sendOne(body: String): Boolean {
            check(PrecisionCommands.isAllowed(body)) { "허용 안 된 명령 $body" }
            sent += body
            log += "TX \$$body"
            return try { send(body); true } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log += "쓰기 실패 ${e.javaClass.simpleName}"; false
            }
        }
        /** 응답 하나(ACK/NAK/PRT)를 기다린다. 다른 줄은 기록만 */
        suspend fun awaitReply(wantPrt: Boolean): PashReply? {
            val end = clock() + replyWaitMs
            while (true) {
                val left = end - clock()
                if (left <= 0) return null
                val line = withTimeoutOrNull(left) { replies.receive() } ?: return null
                got += line; log += "RX $line"
                when (val r = PashReply.parse(line)) {
                    is PashReply.Nak -> return r
                    is PashReply.Ack -> if (!wantPrt) return r
                    is PashReply.Prt -> if (wantPrt) { if (r.checksumOk) return r else log += "PRT 체크섬 불일치 — 무시" }
                    is PashReply.Other -> {}
                }
            }
        }

        // 2) PRT 질의 → 포트
        if (!sendOne(PrecisionCommands.PRT_QUERY)) return result(PrecisionOutcome.FAILED_WRITE, null, "-")
        val prt = awaitReply(wantPrt = true)
        val (port, src) = when {
            prt is PashReply.Prt && prt.port != null && prt.port in PrecisionCommands.ALLOWED_PORTS -> prt.port to "PRT 응답"
            prt is PashReply.Prt -> PrecisionCommands.FALLBACK_PORT to "대체 C(PRT 포트 '${prt.port ?: ""}' 허용 밖)"
            prt is PashReply.Nak -> PrecisionCommands.FALLBACK_PORT to "대체 C(PRT NAK)"
            else -> PrecisionCommands.FALLBACK_PORT to "대체 C(PRT 무응답 ${replyWaitMs}ms)"
        }
        log += "포트 $port · $src"

        // 3) 없는 문장만 하나씩
        var nak = 0; var noReply = 0
        for (s in missing) {
            if (!sendOne(PrecisionCommands.enable(s, port))) return result(PrecisionOutcome.FAILED_WRITE, port, src)
            when (awaitReply(wantPrt = false)) {
                is PashReply.Nak -> { nak++; log += "$s NAK (도난경보·잠금 중이면 모든 명령 NAK)" }
                is PashReply.Ack -> log += "$s ACK(참고용 — 성공은 문장 수신으로 판정)"
                else -> { noReply++; log += "$s 무응답 ${replyWaitMs}ms" }
            }
        }

        // 4) 명령 뒤 5 s 안에 실제 문장
        val cmdEnd = clock()
        var gstAt: Long? = null; var gsaAt: Long? = null
        val vEnd = cmdEnd + verifyWaitMs
        while (true) {
            val now = clock()
            if (gstAt == null && has("GST")) { gstAt = now; log += "명령 뒤 첫 GST ${now - cmdEnd}ms" }
            if (gsaAt == null && has("GSA")) { gsaAt = now; log += "명령 뒤 첫 GSA ${now - cmdEnd}ms" }
            if ((gstAt != null && gsaAt != null) || now >= vEnd) break
            delay(pollMs)
        }
        val gst = has("GST"); val gsa = has("GSA")
        val o = when {
            gst && gsa -> PrecisionOutcome.SUCCESS
            gst -> PrecisionOutcome.PARTIAL_GST_ONLY
            gsa -> PrecisionOutcome.PARTIAL_GSA_ONLY
            nak > 0 -> PrecisionOutcome.FAILED_NAK
            else -> PrecisionOutcome.FAILED_TIMEOUT
        }
        log += "NAK $nak · 무응답 $noReply"
        return result(o, port, src, gstAt, gsaAt)
    }
}
