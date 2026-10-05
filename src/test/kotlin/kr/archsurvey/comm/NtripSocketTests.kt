package kr.archsurvey.comm

import kotlinx.coroutines.*
import kr.archsurvey.comm.CommTestUtil.gga
import kr.archsurvey.comm.CommTestUtil.rtcmFrame
import kr.archsurvey.core.GgaRelay
import kr.archsurvey.engine.*
import kr.archsurvey.ntrip.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 실제 소켓을 쓰는 목 캐스터. 요청 헤더를 읽고 [script] 가 응답/후속 동작을 한다. */
private class MockCaster(val script: (Socket, String) -> Unit) : AutoCloseable {
    val ss = ServerSocket(0)
    val requests = CopyOnWriteArrayList<String>()
    val accepted = AtomicInteger(0)
    val port get() = ss.localPort
    private val th = Thread {
        try {
            while (!ss.isClosed) {
                val s = ss.accept(); accepted.incrementAndGet()
                Thread {
                    try {
                        val ins = s.getInputStream(); val sb = StringBuilder()
                        while (!sb.endsWith("\r\n\r\n")) { val c = ins.read(); if (c < 0) return@Thread; sb.append(c.toChar()) }
                        requests.add(sb.toString())
                        script(s, sb.toString())
                    } catch (_: Exception) { } finally { runCatching { s.close() } }
                }.also { it.isDaemon = true }.start()
            }
        } catch (_: Exception) { }
    }.also { it.isDaemon = true; it.start() }
    override fun close() { runCatching { ss.close() } }
}

private fun cfg(port: Int, mount: String = "VRS", user: String = "u", v2: Boolean = false) =
    NtripConfig(host = "127.0.0.1", port = port, mountPoint = mount, username = user, password = "p", useV2 = v2)

private suspend fun runOnce(c: NtripClient = NtripClient(), cfg: NtripConfig, timeoutMs: Long = 6000): Throwable? =
    runCatching { withTimeout(timeoutMs) { c.run(cfg, 1000, 5000, { null }, {}, { _, _ -> }) } }.exceptionOrNull()

class NtripSocketTests {
    @Test fun v2MountMissingReturnsSourcetableWith200() = runBlocking {
        MockCaster { s, _ ->
            val body = "STR;X;x;RTCM 3.2;;2;GPS;N;KOR;37;127;0;0;g;none;B;N;1000;\r\nENDSOURCETABLE\r\n"
            s.getOutputStream().write(("HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\nContent-Type: gnss/sourcetable\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray())
            Thread.sleep(300)
        }.use { c ->
            val e = runOnce(cfg = cfg(c.port, "NOPE", v2 = true))
            assertTrue(e.toString(), e is NtripMountException) // 이전에는 "200 OK" 라서 데이터로 착각했다
        }
    }

    @Test fun serverErrorIsNotAuthOrProtocol() = runBlocking {
        MockCaster { s, _ -> s.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\n\r\n".toByteArray()) }.use { c ->
            val e = runOnce(cfg = cfg(c.port))
            assertTrue(e.toString(), e is NtripServerException); assertEquals(503, (e as NtripServerException).httpCode)
            assertEquals(NtripFailureKind.SERVER_ERROR, NtripFailureKind.classify(e))
        }
    }

    @Test fun v1BadPasswordIsAuthFailure() = runBlocking {
        MockCaster { s, _ -> s.getOutputStream().write("ERROR - Bad Password\r\n".toByteArray()) }.use { c ->
            assertTrue(runOnce(cfg = cfg(c.port)) is NtripAuthException)
        }
    }

    @Test fun forbidden403() = runBlocking {
        MockCaster { s, _ -> s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray()) }.use { c ->
            val e = runOnce(cfg = cfg(c.port)) as NtripAuthException
            assertEquals(403, e.httpCode)
        }
    }

    @Test fun silentServerHandshakeTimeout() = runBlocking {
        MockCaster { _, _ -> Thread.sleep(3000) }.use { c ->
            val t0 = System.currentTimeMillis()
            val e = runOnce(NtripClient(handshakeTimeoutMs = 400), cfg(c.port))
            assertTrue(e.toString(), e is NtripTimeoutException)
            assertEquals(NtripTimeoutException.Phase.HANDSHAKE, (e as NtripTimeoutException).phase)
            assertTrue("핸드셰이크 대기가 설정을 따라야 함", System.currentTimeMillis() - t0 < 2500)
        }
    }

    @Test fun connectionRefusedIsNetworkError() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // 닫힌 포트
        val e = runOnce(cfg = cfg(port))
        assertTrue(e.toString(), e is NtripNetworkException)
        assertEquals(NtripFailureKind.NETWORK, NtripFailureKind.classify(e!!))
    }

    @Test fun unknownHostIsDns() = runBlocking {
        val e = runOnce(cfg = NtripConfig(host = "no-such-host.invalid", port = 2101, mountPoint = "V", username = "u"), timeoutMs = 15000)
        assertTrue(e.toString(), e is NtripNetworkException)
        assertEquals(NtripFailureKind.DNS, NtripFailureKind.classify(e!!))
    }

    @Test fun fetchSourcetableParsesAndStopsAtEnd() = runBlocking {
        val tbl = "SOURCETABLE 200 OK\r\nCAS;x;2101\r\nSTR;VRS-RTCM31;Seoul;RTCM 3.1;;2;GPS;NGII;KOR;37.5;127.0;1;1;g;none;B;N;9600;\r\nENDSOURCETABLE\r\n"
        MockCaster { s, _ ->
            val o = s.getOutputStream()
            // ENDSOURCETABLE 가 읽기 경계에 걸리도록 두 번에 나눠 보낸다
            val b = tbl.toByteArray(); val cut = tbl.indexOf("ENDSOURCE") + 5
            o.write(b, 0, cut); o.flush(); Thread.sleep(100); o.write(b, cut, b.size - cut); o.flush(); Thread.sleep(2000)
        }.use { c ->
            val t0 = System.currentTimeMillis()
            val l = NtripClient().fetchSourcetable(cfg(c.port))
            assertEquals(listOf("VRS-RTCM31"), l.map { it.mountPoint })
            assertTrue("ENDSOURCETABLE 에서 바로 끝나야 함", System.currentTimeMillis() - t0 < 1500)
        }
    }

    @Test fun fetchSourcetable401() = runBlocking {
        MockCaster { s, _ -> s.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray()) }.use { c ->
            val r = runCatching { NtripClient().fetchSourcetable(cfg(c.port)) }.exceptionOrNull()
            assertTrue(r.toString(), r is NtripAuthException)
        }
    }

    @Test fun fetchSourcetableSilentServerTimesOutInsteadOfEmptyList() = runBlocking {
        MockCaster { _, _ -> Thread.sleep(10_000) }.use { c ->
            val r = runCatching { withTimeout(20_000) { NtripClient().fetchSourcetable(cfg(c.port)) } }.exceptionOrNull()
            assertTrue(r.toString(), r is NtripTimeoutException) // 이전에는 빈 목록을 "성공"으로 돌려줬다
        }
    }

    @Test fun ggaSentPeriodicallyAndFirstOneImmediately() = runBlocking {
        val times = CopyOnWriteArrayList<Long>(); val lines = CopyOnWriteArrayList<String>()
        MockCaster { s, _ ->
            s.getOutputStream().write("ICY 200 OK\r\n".toByteArray()); s.getOutputStream().flush()
            val ins = s.getInputStream(); val sb = StringBuilder()
            s.soTimeout = 4000
            try { while (true) { val ch = ins.read(); if (ch < 0) break; sb.append(ch.toChar()); if (ch == '\n'.code) { times.add(System.currentTimeMillis()); lines.add(sb.toString()); sb.setLength(0) } } } catch (_: Exception) { }
        }.use { c ->
            val g = GgaRelay.forCaster(gga(q = 1))!!
            val t0 = System.currentTimeMillis()
            runCatching { withTimeout(3300) { NtripClient().run(cfg(c.port), 1000, 20_000, { g }, {}, { _, _ -> }) } }
            assertTrue("GGA 가 3회 이상 와야 함: ${times.size}", times.size >= 3)
            assertTrue("첫 GGA 는 접속 직후", times[0] - t0 < 800)
            assertTrue("주기 약 1초", times[2] - times[0] in 1700..2600)
            assertTrue(lines.all { it == g })
        }
    }

    @Test fun supervisorWithRealClientRelaysOnlyValidFramesAndSendsGga() = runBlocking {
        val f1 = rtcmFrame(1005, 19); val f2 = rtcmFrame(1077, 60)
        val gotGga = CopyOnWriteArrayList<String>()
        MockCaster { s, _ ->
            val o = s.getOutputStream()
            o.write("ICY 200 OK\r\n".toByteArray()); o.write(byteArrayOf(0x00, 0x11, 0x22)) // 앞쪽 쓰레기
            o.write(f1, 0, 10); o.flush(); Thread.sleep(60); o.write(f1, 10, f1.size - 10); o.write(f2); o.flush()
            val ins = s.getInputStream(); val sb = StringBuilder(); s.soTimeout = 3000
            try { while (true) { val ch = ins.read(); if (ch < 0) break; sb.append(ch.toChar()); if (ch == '\n'.code) { gotGga.add(sb.toString()); sb.setLength(0) } } } catch (_: Exception) { }
        }.use { c ->
            val sink = CopyOnWriteArrayList<ByteArray>()
            val st = MutableStateFlow(NtripStatus())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sup = NtripSupervisor(scope, st, { System.nanoTime() / 1_000_000 }, MutableStateFlow(true), { GgaRelay.forCaster(gga(q = 1)) }, { sink.add(it) })
            sup.start(cfg(c.port), true, 1000)
            withTimeout(5000) { while (sink.size < 2) delay(20) }
            assertArrayEquals(f1, sink[0]); assertArrayEquals(f2, sink[1])
            assertEquals(NtripState.RECEIVING, st.value.state)
            assertEquals(2L, st.value.framesOk)
            assertTrue(st.value.lastRtcmAtMs > 0)
            withTimeout(3000) { while (gotGga.isEmpty()) delay(20) }
            assertTrue(gotGga[0].startsWith("\$GPGGA"))
            sup.stop(); scope.cancel()
            assertEquals(NtripState.IDLE, st.value.state)
        }
    }
}
