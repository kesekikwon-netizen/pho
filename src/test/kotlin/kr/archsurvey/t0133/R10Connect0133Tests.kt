package kr.archsurvey.t0133

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.bt.BtError
import kr.archsurvey.bt.NmeaActivation
import kr.archsurvey.comm.CommTestUtil.gga
import kr.archsurvey.comm.CommTestUtil.rtcmFrame
import kr.archsurvey.comm.MockSocket
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.device.*
import kr.archsurvey.engine.LinkSupervisor
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 0.1.33-r10 R10 연결 시험용 메모리 플랫폼(t0132 MemPlatform 과 같은 규칙) */
internal class R10Platform(var devices: List<Pair<String, String>>) : ConnectionPlatform {
    override val link = MutableStateFlow(LinkStatus())
    override val ntrip = MutableStateFlow(NtripStatus())
    var deviceId: String? = null
    val addr = HashMap<String, String>()
    val pre = HashMap<String, Boolean>()
    val types = HashMap<String, String>()
    val started = mutableListOf<Pair<String, String>>()
    var globalNtrip = NtripForm(user = "u1", password = "pw")
    override var autoConnect = true
    override var correctionUserSet = false
    override fun bondedDevices() = devices
    override fun hasBluetoothPermission() = true
    override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
    override fun bluetoothEnabled() = true
    override fun openBluetoothSettings() {}
    override fun openAppSettings() {}
    override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {
        deviceId = profile.id; addr[profile.id] = address; started += profile.id to address
        link.value = LinkStatus(LinkState.CONNECTING)
    }
    override fun stopReal() { link.value = LinkStatus() }
    override fun loadSaved() = SavedConnection(deviceId, deviceId?.let { addr[it] }, globalNtrip, deviceId?.let { pre[it] } ?: false)
    override fun save(s: SavedConnection) {
        s.deviceId?.let { id -> deviceId = id; s.address?.let { addr[id] = it }; pre[id] = s.preChecked }
        globalNtrip = s.ntrip
    }
    override fun hadSuccess() = true
    override fun savedFor(deviceId: String): SavedConnection? {
        val a = addr[deviceId] ?: return null
        return SavedConnection(deviceId, a, NtripForm(host = ""), pre[deviceId] ?: false)
    }
    override fun deviceTypeFor(address: String) = types[address]
    override fun markLinkOk(address: String, deviceId: String) { types[address] = deviceId }
}

private fun ui() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
private val R10 = "R10, 5432K12345" to "AA:01"
private val SP60 = "SP60_1234" to "BB:02"

class R10Detect0133Tests {
    /** 0.1.32: SP60 탭에서 R10 을 골라 위치를 받으면 R10 주소가 SP60 으로 기억 → 다음부터 R10 이 SP60 명령·안테나로 연결됨 */
    @Test fun modelNameBeatsPoisonedMemory() {
        val poisoned: (String) -> String? = { if (it == R10.second) DeviceProfiles.SP60_ID else null }
        val d = ReceiverDetect.decide(DeviceProfiles.SP60_ID, R10.second, listOf(R10, SP60), poisoned)
        assertEquals(DeviceProfiles.R10, (d as ReceiverDetect.Decision.UseSaved).target.profile)
        val c = ReceiverDetect.candidates(listOf(R10), poisoned)
        assertEquals(DeviceProfiles.R10, c.single().profile)
        // 이름에 모델 글자가 없으면 기억이 그대로 이긴다(0.1.32 동작 유지)
        val d2 = ReceiverDetect.decide(DeviceProfiles.R10_ID, "DD:04", listOf("Rover1" to "DD:04"), { DeviceProfiles.SP60_ID })
        assertEquals(DeviceProfiles.SP60, (d2 as ReceiverDetect.Decision.UseSaved).target.profile)
    }

    @Test fun realR10NameShapes() {
        for (n in listOf("R10, 5432K12345", "R10-5434K0001", "Trimble R10", "R10 2", "TrimbleR10", "TRMB R10"))
            assertEquals(n, DeviceProfiles.R10, ReceiverDetect.fromName(n))
        assertEquals(DeviceProfiles.R10, ReceiverDetect.fromModelName("R10, 5432K12345"))
        assertNull("시리얼만 있는 이름은 모름 → 사용자가 고른 주소로만", ReceiverDetect.fromName("5432K12345"))
        assertNull("제조사 글자만이면 모델 아님", ReceiverDetect.fromModelName("Trimble 1234"))
    }
}

class R10Select0133Tests {
    /** 예전(0.1.31 이하) 기본 탭 R10 에서 SP60 을 골라 쓴 기록 → 0.1.32 는 R10 탭을 누르면 SP60 주소를 되살려 「R10 연결」이 SP60 으로 감 */
    @Test fun switchingToR10_doesNotRestoreSp60Address() {
        val p = R10Platform(listOf(R10, SP60))
        p.deviceId = DeviceProfiles.SP60_ID; p.addr[DeviceProfiles.SP60_ID] = SP60.second
        p.addr[DeviceProfiles.R10_ID] = SP60.second // 예전 기록
        val c = ConnectionController(p, ui())
        c.selectProfile(DeviceProfiles.R10)
        assertEquals(DeviceProfiles.R10_ID, c.profile.id)
        assertNotEquals("R10 탭에 SP60 주소를 되살리면 안 됨", SP60.second, c.address)
    }

    @Test fun tappingR10UnderSp60Tab_switchesProfile() {
        val p = R10Platform(listOf(R10, SP60))
        p.deviceId = DeviceProfiles.SP60_ID; p.addr[DeviceProfiles.SP60_ID] = SP60.second
        val c = ConnectionController(p, ui())
        c.refresh()
        c.selectAddress(R10.second)
        assertEquals(DeviceProfiles.R10_ID, c.profile.id)
        assertEquals(R10.second, c.address)
        assertEquals("SP60 의 저장 주소는 그대로", SP60.second, p.addr[DeviceProfiles.SP60_ID])
    }

    @Test fun connectWithMismatchedSavedAddress_neverStartsR10AsSp60() {
        val p = R10Platform(listOf(R10, SP60))
        p.deviceId = DeviceProfiles.SP60_ID; p.addr[DeviceProfiles.SP60_ID] = R10.second // 0.1.32 에서 SP60 탭으로 R10 을 연결한 기록
        p.pre[DeviceProfiles.SP60_ID] = true; p.pre[DeviceProfiles.R10_ID] = true
        val c = ConnectionController(p, ui())
        c.updateForm(c.form.copy(enabled = false))
        c.connect()
        assertTrue("시작했다면 R10 종류로: ${p.started}", p.started.none { it.first == DeviceProfiles.SP60_ID && it.second == R10.second })
        assertEquals(DeviceProfiles.R10_ID, c.profile.id)
    }
}

class R10Text0133Tests {
    @Test fun r10NoDataCopy_saysAppCannotEnableNmea_noSp60Items() {
        val r = NmeaActivation.fieldChecklist(DeviceProfiles.R10)
        assertTrue(r, "앱이 명령으로" in r && "웹 UI" in r && "Bluetooth" in r && "Maintain configuration" in r)
        for (bad in listOf("SPace", "도난경보", "시작잠금")) assertFalse("R10 문구에 SP60 항목: $bad", bad in r)
        val h = DeviceProfiles.R10.noDataHint
        for (bad in listOf("SPace", "도난경보", "시작잠금")) assertFalse(bad, bad in h)
        assertTrue("SP60 문구는 그대로", "도난경보" in NmeaActivation.fieldChecklist(DeviceProfiles.SP60))
    }

    @Test fun r10RtcmPacing_matchesFieldProvenSp60() {
        assertTrue(DeviceProfiles.R10.rtcmWriteChunk <= DeviceProfiles.SP60.rtcmWriteChunk)
        assertTrue(DeviceProfiles.R10.rtcmWriteGapMs > 0)
    }
}

/** 0.1.32 는 BT-130 만 기록 → GGA 를 받다 끊긴 R10 세션의 이유가 진단에 없었다 */
class R10SessionEnd0133Tests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private fun prof() = DeviceProfiles.R10.copy(
        staleAfterMs = 300, reconnectAfterMs = 700, noDataHoldMs = 0, nmeaHealAttempts = 0, rtcmWriteGapMs = 0,
        reconnect = ReconnectPolicy(initialDelayMs = 100, maxDelayMs = 400, settleMs = 0, repairHintAfterFailures = 3),
    )

    private class Rig(p: DeviceProfile, scope: CoroutineScope, writeStallMs: Long = 3_000, factory: (Int) -> MockSocket) {
        val link = MutableStateFlow(LinkStatus())
        val rtcm = Channel<ByteArray>(64)
        val sockets = CopyOnWriteArrayList<MockSocket>()
        val ends = CopyOnWriteArrayList<LinkSupervisor.SessionEnd>()
        val job = scope.launch {
            LinkSupervisor(p, { factory(sockets.size).also { sockets.add(it) } }, { System.nanoTime() / 1_000_000 },
                link, MutableStateFlow(GnssSnapshot()), rtcm, Channel(Channel.CONFLATED), {}, {},
                watchdogPeriodMs = 50, writeStallMs = writeStallMs, onSessionEnd = { ends.add(it) }).run()
        }
        fun waitFor(ms: Long = 5000, what: String, c: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (c()) return; Thread.sleep(10) }
            fail("timeout $what link=${link.value} ends=$ends")
        }
    }

    @Test fun streamingThenReceiverDrops_isRecordedWithReason() {
        val r = Rig(prof(), scope) { MockSocket() }
        r.waitFor(what = "socket") { r.sockets.size == 1 }
        r.sockets[0].push(gga())
        r.waitFor(what = "streaming") { r.link.value.state == LinkState.STREAMING }
        r.rtcm.trySend(rtcmFrame(1077, 300))
        r.waitFor(what = "written") { r.sockets[0].written.size() > 0 }
        r.sockets[0].eof()
        r.waitFor(what = "end") { r.ends.isNotEmpty() }
        val e = r.ends[0]
        assertEquals(BtError.SOCKET_CLOSED, e.error)
        assertTrue(e.bytesIn > 0 && e.sentences >= 1 && "GGA" in e.types)
        assertTrue("RTCM 송신 기록: $e", e.rtcmBytesOut >= 300 && e.rtcmFrames == 1L)
        assertTrue(e.summary("trimble_r10").contains("진단 세션 종료 BT-"))
    }

    @Test fun rtcmWriteBlocking_isRecordedAsWriteStalled() {
        val r = Rig(prof(), scope, writeStallMs = 300) { i ->
            MockSocket(writeBehavior = { s, b, o, n -> if (i == 0) Thread.sleep(2_000) else s.written.write(b, o, n) })
        }
        r.waitFor(what = "socket") { r.sockets.size == 1 }
        r.sockets[0].push(gga())
        r.waitFor(what = "streaming") { r.link.value.state == LinkState.STREAMING }
        r.rtcm.trySend(rtcmFrame(1077, 300))
        r.waitFor(ms = 6000, what = "end") { r.ends.isNotEmpty() }
        assertEquals(BtError.WRITE_STALLED, r.ends[0].error)
        assertEquals(1L, r.ends[0].rtcmFrames)
    }

    @Test fun zeroBytes_isRecordedAsNoData() {
        val r = Rig(prof(), scope) { MockSocket() }
        r.waitFor(ms = 6000, what = "end") { r.ends.isNotEmpty() }
        assertEquals(BtError.NO_DATA, r.ends[0].error)
        assertEquals(0L, r.ends[0].bytesIn)
    }
}
