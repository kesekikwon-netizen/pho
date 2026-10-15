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
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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

    /** 0.1.18 보안: 기본 data class toString 은 비밀번호를 그대로 찍는다 → 가린다(로그·예외·진단 공유 대비) */
    override fun toString(): String = "NtripConfig(host=$host, port=$port, mountPoint=$mountPoint, username=${if (username.isEmpty()) "" else "***"}, password=${if (password.isEmpty()) "" else "***"}, useV2=$useV2)"
}

/** NGII 공개 안내(2022-05) 기준 프리셋. 서버·포트·마운트는 사용자가 수정 가능 [확인 필요: 현행 정책] */
object NtripPresets {
    val VRS = NtripConfig(host = "RTS1.ngii.go.kr", port = 2101, mountPoint = "VRS-RTCM31")
    val FKP = NtripConfig(host = "RTS2.ngii.go.kr", port = 2101, mountPoint = "FKP-RTCM31")
}

sealed class NtripException(msg: String) : IOException(msg)
/**
 * 0.1.54: VRS 아이디·비밀번호 정리. 앞뒤 공백과 보이지 않는 문자(zero-width, NBSP, BOM, 전각 공백 등)를 뗀다.
 * 저장·불러오기·보내기 모두 이 함수를 거친다. 가운데 문자는 건드리지 않는다.
 */
object Cred {
    private fun junk(c: Char): Boolean = c.isWhitespace() || Character.isSpaceChar(c) || c == '\u200B' || c == '\u200C' ||
        c == '\u200D' || c == '\u2060' || c == '\uFEFF' || c == '\u00AD' || c == '\u200E' || c == '\u200F' ||
        Character.getType(c) == Character.FORMAT.toInt() || Character.isISOControl(c)
    fun clean(s: String): String = s.trim { junk(it) }
    /** 서버 첫 응답 줄을 화면용으로(제어문자 제거, 80자). 비밀번호는 서버 응답에 없다 */
    fun serverLine(s: String): String = s.filter { !Character.isISOControl(it) }.trim().take(80)
}

/** 401/403: 재시도 해도 소용없음 */
class NtripAuthException(val httpCode: Int, msg: String) : NtripException(msg)
class NtripMountException(msg: String) : NtripException(msg)
class NtripProtocolException(msg: String) : NtripException(msg)
class NtripNoDataException(msg: String) : NtripException(msg)
/** 0.1.58 B6: 수신기 GGA 의 기준국 번호가 바뀐 뒤 보정이 멈춤(SmartTopo 로그 「New base」 → 끊김) */
class NtripBaseChangeException(msg: String) : NtripException(msg)
/** DNS 실패·네트워크 없음·경로 없음·연결 거부 */
class NtripNetworkException(msg: String, cause: Throwable? = null) : NtripException(msg) { init { initCause(cause) } }
/** 접속(connect) 또는 응답 헤더 대기(handshake) 시간 초과 */
class NtripTimeoutException(val phase: Phase, msg: String) : NtripException(msg) { enum class Phase { CONNECT, HANDSHAKE } }
/** 4xx/5xx 중 인증·마운트가 아닌 서버 쪽 오류 */
class NtripServerException(val httpCode: Int, msg: String) : NtripException(msg)
/** 수신 바이트가 RTCM 3 가 아님(오류 페이지·다른 형식). 수신기로 넘기지 않는다 */
class NtripNotRtcmException(msg: String) : NtripException(msg)

enum class NtripState { IDLE, CONNECTING, CONNECTED, RECEIVING, RECONNECTING, AUTH_FAILED, ERROR }

data class MountInfo(
    val mountPoint: String, val identifier: String, val format: String, val navSystem: String, val needsNmea: Boolean,
    val formatDetails: String = "", val lat: Double? = null, val lon: Double? = null,
    /** STR 의 authentication 필드: N(없음) / B(Basic) / D(Digest) */
    val authentication: String = "", val bitrate: Int? = null, val network: String = "",
) {
    val isRtcm3: Boolean get() = format.contains("RTCM 3", true) || format.contains("RTCM3", true)
}

object Sourcetable {
    /**
     * STR;mount;identifier;format;format-details;carrier;nav-system;network;country;lat;lon;nmea;solution;generator;compression;authentication;fee;bitrate;misc
     * 필드가 모자란 줄은 건너뛰고, 위도·경도·비트레이트가 비었거나 숫자가 아니면 null.
     */
    fun parse(text: String): List<MountInfo> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(';')
        if (f.size >= 12 && f[0] == "STR" && f[1].isNotEmpty()) MountInfo(
            mountPoint = f[1], identifier = f[2], format = f[3], navSystem = f[6], needsNmea = f[11] == "1",
            formatDetails = f[4], lat = f[9].toDoubleOrNull(), lon = f[10].toDoubleOrNull(),
            authentication = f.getOrNull(15).orEmpty(), bitrate = f.getOrNull(17)?.toIntOrNull(), network = f[7],
        ) else null
    }.toList()

    /**
     * 마운트포인트 추천 순서. RTCM 3 만 남기고,
     * ① VRS(NMEA 필요) 선호 시 VRS 먼저 ② 위치(lat/lon)를 알면 가까운 순(위치 없는 마운트는 뒤) ③ 비트레이트 낮은 순(데이터 절약).
     * 서버의 STR 정보가 틀릴 수 있으므로 "추천"일 뿐 자동 선택하지 않는다 [현장확인].
     */
    fun recommend(mounts: List<MountInfo>, lat: Double?, lon: Double?, preferVrs: Boolean = true): List<MountInfo> {
        fun dist(m: MountInfo): Double =
            if (lat == null || lon == null || m.lat == null || m.lon == null || (m.lat == 0.0 && m.lon == 0.0)) Double.MAX_VALUE
            else haversineKm(lat, lon, m.lat, m.lon)
        return mounts.filter { it.isRtcm3 }.sortedWith(
            compareBy<MountInfo> { if (preferVrs && it.needsNmea) 0 else if (!preferVrs && !it.needsNmea) 0 else 1 }
                .thenBy { if (it.needsNmea) 0.0 else dist(it) } // VRS 는 거리 무의미
                .thenBy { it.bitrate ?: Int.MAX_VALUE }
                .thenBy { it.mountPoint }
        )
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}

/** GGA 송신 주기 설정 한계: VRS 서버는 너무 잦은 GGA 를 싫어하고(트래픽), 너무 드물면 기준점이 갱신되지 않는다 */
object NtripTiming {
    const val GGA_MIN_MS = 1_000L
    const val GGA_MAX_MS = 10_000L
    fun clampGgaIntervalMs(ms: Long): Long = ms.coerceIn(GGA_MIN_MS, GGA_MAX_MS)
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

class NtripClient(
    private val connectTimeoutMs: Int = 10_000,
    /** 요청 후 응답 첫 줄·헤더가 와야 하는 시간. VRS 서버는 접속 직후 몇 초 걸릴 수 있다 */
    private val handshakeTimeoutMs: Int = 10_000,
) {

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
            kr.archsurvey.diag.CrashLog.registerSecret(Cred.clean(cfg.username)); kr.archsurvey.diag.CrashLog.registerSecret(Cred.clean(cfg.password))
            if (Cred.clean(cfg.username).isNotEmpty()) sb.append("Authorization: ${basicAuth(Cred.clean(cfg.username), Cred.clean(cfg.password))}\r\n")
            sb.append("Connection: close\r\n\r\n")
            return sb.toString()
        }
    }

    private fun openSocket(cfg: NtripConfig): Socket {
        val s = Socket()
        try {
            s.keepAlive = true
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(cfg.host, cfg.port), connectTimeoutMs)
            return s
        } catch (e: SocketTimeoutException) {
            runCatching { s.close() }
            throw NtripTimeoutException(NtripTimeoutException.Phase.CONNECT, "서버(${cfg.host}:${cfg.port})에 ${connectTimeoutMs / 1000}초 안에 접속하지 못했어요")
        } catch (e: UnknownHostException) {
            runCatching { s.close() }
            throw NtripNetworkException("서버 주소(${cfg.host})를 찾지 못했어요. 인터넷 연결이나 주소를 확인하세요", e)
        } catch (e: ConnectException) {
            runCatching { s.close() }
            throw NtripNetworkException("서버에 연결하지 못했어요: ${e.message ?: ""}", e)
        } catch (e: NoRouteToHostException) {
            runCatching { s.close() }
            throw NtripNetworkException("서버까지 경로가 없어요(인터넷 연결 확인)", e)
        } catch (e: java.io.IOException) {
            runCatching { s.close() }
            throw NtripNetworkException("네트워크 오류: ${e.message ?: e.javaClass.simpleName}", e)
        }
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

    /** 소스테이블 조회 (마운트 목록). 인증·네트워크·시간 초과를 구분해 예외로 알린다. */
    suspend fun fetchSourcetable(cfg: NtripConfig): List<MountInfo> = withContext(Dispatchers.IO) {
        val sock = openSocket(cfg)
        try {
            sock.soTimeout = 8_000
            sock.getOutputStream().apply { write(buildRequest(cfg, "/").toByteArray()); flush() }
            val ins = sock.getInputStream()
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 15_000
            var timedOut = false
            val marker = "ENDSOURCETABLE".toByteArray(Charsets.ISO_8859_1)
            var tail = ByteArray(0)
            try {
                while (System.currentTimeMillis() < deadline) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    bo.write(buf, 0, n)
                    if (bo.size() > 2_000_000) break
                    // 전체를 문자열로 바꾸지 않고 꼬리 부분만 검사(이전: 읽을 때마다 전체 변환 O(n²))
                    if (containsMarker(tail, buf, n, marker)) break
                    tail = buf.copyOfRange(maxOf(0, n - (marker.size - 1)), n).let { if (n < marker.size - 1) (tail + it).takeLast(marker.size - 1).toByteArray() else it }
                }
            } catch (_: SocketTimeoutException) { timedOut = true }
            val text = bo.toString("ISO-8859-1")
            val first = text.lineSequence().firstOrNull().orEmpty()
            if (text.isEmpty()) {
                throw if (timedOut) NtripTimeoutException(NtripTimeoutException.Phase.HANDSHAKE, "소스테이블 응답이 없어요(시간 초과)")
                else NtripProtocolException("서버가 응답 없이 연결을 닫았어요")
            }
            when {
                first.contains(" 401") -> throw NtripAuthException(401, "인증 실패 [${Cred.serverLine(first)}]")
                first.contains(" 403") -> throw NtripAuthException(403, "인증 실패 (403) [${Cred.serverLine(first)}]")
                first.startsWith("HTTP/") && !first.contains(" 200") -> throw NtripServerException(statusCode(first), "서버 응답: ${first.take(80)}")
            }
            val list = Sourcetable.parse(text)
            if (list.isEmpty() && timedOut) throw NtripTimeoutException(NtripTimeoutException.Phase.HANDSHAKE, "소스테이블을 다 받지 못했어요(시간 초과)")
            list
        } finally { runCatching { sock.close() } }
    }

    /** [tail]+[buf] 안에 [marker] 가 있으면 true. 호출자는 다음 호출을 위해 꼬리(marker.size-1 바이트)만 유지한다 */
    private fun containsMarker(tail: ByteArray, buf: ByteArray, n: Int, marker: ByteArray): Boolean {
        val all = ByteArray(tail.size + n)
        System.arraycopy(tail, 0, all, 0, tail.size); System.arraycopy(buf, 0, all, tail.size, n)
        outer@ for (i in 0..all.size - marker.size) {
            for (j in marker.indices) if (all[i + j] != marker[j]) continue@outer
            return true
        }
        return false
    }

    private fun statusCode(line: String): Int = Regex("\\b([1-5]\\d\\d)\\b").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0

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
                sock.soTimeout = handshakeTimeoutMs
                val out = sock.getOutputStream()
                val ins = sock.getInputStream()
                val lock = Any()
                fun send(b: ByteArray) = synchronized(lock) { out.write(b); out.flush() }

                send(buildRequest(cfg, "/" + cfg.mountPoint.trimStart('/')).toByteArray(Charsets.ISO_8859_1))
                val status = try { readLine(ins) } catch (e: SocketTimeoutException) {
                    throw NtripTimeoutException(NtripTimeoutException.Phase.HANDSHAKE, "서버가 ${handshakeTimeoutMs / 1000}초 안에 응답하지 않아요")
                } ?: throw NtripProtocolException("서버가 응답 없이 연결을 닫았어요")
                var chunked = false
                when {
                    status.startsWith("ICY 200") -> { /* v1: 헤더 없이 곧바로 데이터 */ }
                    status.startsWith("HTTP/") && status.contains(" 200") -> {
                        var isSourcetable = false
                        while (true) {
                            val h = try { readLine(ins) } catch (e: SocketTimeoutException) {
                                throw NtripTimeoutException(NtripTimeoutException.Phase.HANDSHAKE, "응답 헤더가 끝까지 오지 않아요")
                            } ?: throw NtripProtocolException("헤더 중 연결 종료")
                            if (h.isEmpty()) break
                            if (h.startsWith("Transfer-Encoding", true) && h.contains("chunked", true)) chunked = true
                            // NTRIP v2: 마운트포인트가 없으면 "200 OK + Content-Type: gnss/sourcetable" 로 소스테이블을 준다
                            if (h.startsWith("Content-Type", true) && h.contains("sourcetable", true)) isSourcetable = true
                        }
                        if (isSourcetable) throw NtripMountException("마운트포인트 '${cfg.mountPoint}' 를 찾을 수 없어요 (소스테이블이 반환됨)")
                    }
                    status.startsWith("SOURCETABLE") -> throw NtripMountException("마운트포인트 '${cfg.mountPoint}' 를 찾을 수 없어요 (소스테이블이 반환됨)")
                    status.contains(" 401") -> throw NtripAuthException(401, "아이디/비밀번호가 맞지 않아요 [${Cred.serverLine(status)}]")
                    status.contains(" 403") -> throw NtripAuthException(403, "접속이 거부됐어요 (403). 같은 ID 가 다른 기기에서 쓰이는 중일 수 있어요 [${Cred.serverLine(status)}]")
                    status.contains(" 404") -> throw NtripMountException("마운트포인트를 찾을 수 없어요 (404)")
                    status.startsWith("ERROR", true) && status.contains("password", true) -> throw NtripAuthException(401, "아이디/비밀번호가 맞지 않아요 [${Cred.serverLine(status)}]")
                    status.startsWith("HTTP/") && statusCode(status) in 400..599 -> throw NtripServerException(statusCode(status), "서버 오류 (${status.take(60)})")
                    else -> throw NtripProtocolException("예상하지 못한 응답: ${status.take(80)}")
                }
                sock.soTimeout = 3_000 // 이후부터는 짧은 주기로 깨어나 무수신 시간을 잰다
                onState(NtripState.CONNECTED)

                // GGA 주기 송신 (VRS 는 필수)
                val ggaMs = NtripTiming.clampGgaIntervalMs(ggaIntervalMs)
                launch {
                    while (isActive) {
                        val g = ggaProvider()
                        if (g != null) {
                            val line = if (g.endsWith("\n")) g else g + "\r\n"
                            runCatching { send(line.toByteArray(Charsets.ISO_8859_1)) }.onFailure { throw it }
                        }
                        delay(if (g == null) 1_000 else ggaMs)
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
