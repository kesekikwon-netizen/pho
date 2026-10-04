package kr.archsurvey.ntrip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * NTRIP 접속 설정. 비밀번호/ID 는 코드에 넣지 않고 사용자가 입력한다.
 * 기본값은 NGII 공개 안내문 기준(2022-05) — [확인 필요: 현행 정책].
 */
data class NtripConfig(
    val host: String = "RTS1.ngii.go.kr",
    val port: Int = 2101,
    val mountPoint: String = "VRS-RTCM31",
    val username: String = "",
    val password: String = "",
    /** true: NTRIP v2 헤더 + HTTP/1.1 (chunked 처리). false: v1 (ICY 200 OK) */
    val useV2: Boolean = false,
) {
    val isUsable: Boolean get() = host.isNotBlank() && port in 1..65535 && mountPoint.isNotBlank() && username.isNotBlank()
}

/** NGII 공개 안내(2022-05) 기준 프리셋. 서버·포트·마운트는 사용자가 수정 가능 [확인 필요: 현행 정책] */
object NtripPresets {
    val VRS = NtripConfig(host = "RTS1.ngii.go.kr", port = 2101, mountPoint = "VRS-RTCM31")
    val FKP = NtripConfig(host = "RTS2.ngii.go.kr", port = 2101, mountPoint = "FKP-RTCM31")
}

sealed class NtripException(msg: String) : IOException(msg)
/** 401/403: 재시도 해도 소용없음 */
class NtripAuthException(val httpCode: Int, msg: String) : NtripException(msg)
class NtripMountException(msg: String) : NtripException(msg)
class NtripProtocolException(msg: String) : NtripException(msg)
class NtripNoDataException(msg: String) : NtripException(msg)

enum class NtripState { IDLE, CONNECTING, CONNECTED, RECEIVING, RECONNECTING, AUTH_FAILED, ERROR }

data class MountInfo(val mountPoint: String, val identifier: String, val format: String, val navSystem: String, val needsNmea: Boolean)

object Sourcetable {
    /** STR;mount;identifier;format;format-details;carrier;nav-system;network;country;lat;lon;nmea;... */
    fun parse(text: String): List<MountInfo> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(';')
        if (f.size >= 12 && f[0] == "STR") MountInfo(f[1], f[2], f[3], f[6], f[11] == "1") else null
    }.toList()
}

/** HTTP/1.1 chunked 본문 디코더 (스트리밍). v2 캐스터 응답용 */
class ChunkedDecoder {
    private enum class S { SIZE, DATA, DATA_CRLF, TRAILER }
    private var state = S.SIZE
    private var remaining = 0
    private val sizeLine = StringBuilder()

    fun decode(buf: ByteArray, len: Int, out: (ByteArray, Int) -> Unit) {
        var i = 0
        while (i < len) {
            when (state) {
                S.SIZE -> {
                    val c = buf[i++].toInt().toChar()
                    if (c == '\n') {
                        val hex = sizeLine.toString().trim().substringBefore(';')
                        sizeLine.setLength(0)
                        if (hex.isEmpty()) continue
                        remaining = hex.toIntOrNull(16) ?: throw NtripProtocolException("잘못된 chunk 크기")
                        state = if (remaining == 0) S.TRAILER else S.DATA
                    } else if (sizeLine.length < 32) sizeLine.append(c)
                }
                S.DATA -> {
                    val n = minOf(remaining, len - i)
                    out(buf.copyOfRange(i, i + n), n)
                    i += n; remaining -= n
                    if (remaining == 0) state = S.DATA_CRLF
                }
                S.DATA_CRLF -> {
                    val c = buf[i++].toInt().toChar()
                    if (c == '\n') state = S.SIZE
                }
                S.TRAILER -> i = len // 끝
            }
        }
    }
}

class NtripClient(private val connectTimeoutMs: Int = 10_000) {

    companion object {
        fun basicAuth(user: String, pass: String): String {
            val raw = "$user:$pass".toByteArray(Charsets.UTF_8)
            return "Basic " + android_free_base64(raw)
        }

        // java.util.Base64 는 API 26+ 이므로 직접 구현 (minSdk 21 호환)
        private val TBL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        fun android_free_base64(data: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i < data.size) {
                val b0 = data[i].toInt() and 0xFF
                val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
                val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
                sb.append(TBL[b0 shr 2])
                sb.append(TBL[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
                sb.append(if (b1 >= 0) TBL[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
                sb.append(if (b2 >= 0) TBL[b2 and 63] else '=')
                i += 3
            }
            return sb.toString()
        }

        fun buildRequest(cfg: NtripConfig, path: String): String {
            val sb = StringBuilder()
            if (cfg.useV2) {
                sb.append("GET $path HTTP/1.1\r\nHost: ${cfg.host}:${cfg.port}\r\nNtrip-Version: Ntrip/2.0\r\n")
            } else {
                sb.append("GET $path HTTP/1.0\r\nHost: ${cfg.host}\r\n")
            }
            sb.append("User-Agent: NTRIP ExcavationSurvey/0.1\r\n")
            if (cfg.username.isNotEmpty()) sb.append("Authorization: ${basicAuth(cfg.username, cfg.password)}\r\n")
            sb.append("Connection: close\r\n\r\n")
            return sb.toString()
        }
    }

    private fun openSocket(cfg: NtripConfig): Socket {
        val s = Socket()
        s.keepAlive = true
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(cfg.host, cfg.port), connectTimeoutMs)
        return s
    }

    private fun readLine(ins: InputStream): String? {
        val bo = ByteArrayOutputStream()
        while (bo.size() < 8192) {
            val b = ins.read()
            if (b < 0) return if (bo.size() == 0) null else bo.toString("ISO-8859-1")
            if (b == '\n'.code) return bo.toString("ISO-8859-1").trimEnd('\r')
            bo.write(b)
        }
        throw NtripProtocolException("응답 헤더가 너무 깁니다")
    }

    /** 소스테이블 조회 (마운트 목록). 호출자는 IO 컨텍스트에서 호출할 것. */
    suspend fun fetchSourcetable(cfg: NtripConfig): List<MountInfo> = withContext(Dispatchers.IO) {
        val sock = openSocket(cfg)
        try {
            sock.soTimeout = 8_000
            sock.getOutputStream().apply { write(buildRequest(cfg, "/").toByteArray()); flush() }
            val ins = sock.getInputStream()
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 15_000
            try {
                while (System.currentTimeMillis() < deadline) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    bo.write(buf, 0, n)
                    if (bo.size() > 2_000_000) break
                    if (bo.toString("ISO-8859-1").contains("ENDSOURCETABLE")) break
                }
            } catch (_: SocketTimeoutException) { }
            val text = bo.toString("ISO-8859-1")
            val first = text.lineSequence().firstOrNull().orEmpty()
            if (first.contains(" 401") || first.contains(" 403")) throw NtripAuthException(if (first.contains("401")) 401 else 403, "인증 실패: $first")
            Sourcetable.parse(text)
        } finally { runCatching { sock.close() } }
    }

    /**
     * 캐스터에 접속해 RTCM 을 수신한다. 정상 종료는 없고 예외/취소로만 끝난다.
     * @param ggaProvider  최신 GGA 원문 ($...*CS\r\n 포함 가능, 없으면 null)
     * @param onRtcm       수신 RTCM 바이트 (청크)
     */
    suspend fun run(
        cfg: NtripConfig,
        ggaIntervalMs: Long,
        noDataTimeoutMs: Long,
        ggaProvider: () -> String?,
        onState: (NtripState) -> Unit,
        onRtcm: (ByteArray, Int) -> Unit,
    ): Nothing = withContext(Dispatchers.IO) {
        onState(NtripState.CONNECTING)
        val sock = openSocket(cfg)
        try {
            coroutineScope {
                // 취소 시 블로킹 read 를 깨우기 위해 소켓을 닫는다
                launch { try { awaitCancellation() } finally { runCatching { sock.close() } } }
                sock.soTimeout = 3_000
                val out = sock.getOutputStream()
                val ins = sock.getInputStream()
                val lock = Any()
                fun send(b: ByteArray) = synchronized(lock) { out.write(b); out.flush() }

                send(buildRequest(cfg, "/" + cfg.mountPoint.trimStart('/')).toByteArray(Charsets.ISO_8859_1))
                val status = readLine(ins) ?: throw NtripProtocolException("서버가 응답 없이 연결을 닫았어요")
                var chunked = false
                when {
                    status.startsWith("ICY 200") -> { /* v1: 헤더 없이 곧바로 데이터 */ }
                    status.startsWith("HTTP/") && status.contains(" 200") -> {
                        while (true) {
                            val h = readLine(ins) ?: throw NtripProtocolException("헤더 중 연결 종료")
                            if (h.isEmpty()) break
                            if (h.startsWith("Transfer-Encoding", true) && h.contains("chunked", true)) chunked = true
                        }
                    }
                    status.startsWith("SOURCETABLE") -> throw NtripMountException("마운트포인트 '${cfg.mountPoint}' 를 찾을 수 없어요 (소스테이블이 반환됨)")
                    status.contains(" 401") -> throw NtripAuthException(401, "아이디/비밀번호가 맞지 않아요 (401)")
                    status.contains(" 403") -> throw NtripAuthException(403, "접속이 거부됐어요 (403). 같은 ID 가 다른 기기에서 쓰이는 중일 수 있어요")
                    status.contains(" 404") -> throw NtripMountException("마운트포인트를 찾을 수 없어요 (404)")
                    else -> throw NtripProtocolException("예상하지 못한 응답: ${status.take(80)}")
                }
                onState(NtripState.CONNECTED)

                // GGA 주기 송신 (VRS 는 필수)
                launch {
                    while (isActive) {
                        val g = ggaProvider()
                        if (g != null) {
                            val line = if (g.endsWith("\n")) g else g + "\r\n"
                            runCatching { send(line.toByteArray(Charsets.ISO_8859_1)) }.onFailure { throw it }
                        }
                        delay(if (g == null) 1_000 else ggaIntervalMs)
                    }
                }
                // 최초 GGA 를 가능한 빨리 올리기 위해 위 루프가 즉시 한 번 실행됨

                val dec = if (chunked) ChunkedDecoder() else null
                val buf = ByteArray(4096)
                var lastData = System.currentTimeMillis()
                var received = false
                while (isActive) {
                    val n = try { ins.read(buf) } catch (e: SocketTimeoutException) { -2 }
                    val now = System.currentTimeMillis()
                    if (n == -1) throw NtripProtocolException("서버가 연결을 닫았어요")
                    if (n > 0) {
                        lastData = now
                        if (!received) { received = true; onState(NtripState.RECEIVING) }
                        if (dec != null) dec.decode(buf, n, onRtcm) else onRtcm(buf, n)
                    } else if (now - lastData > noDataTimeoutMs) {
                        throw NtripNoDataException("보정 데이터가 ${noDataTimeoutMs / 1000}초 동안 오지 않아요")
                    }
                }
                throw CancellationException()
            }
            throw CancellationException()
        } finally { runCatching { sock.close() } }
    }
}
