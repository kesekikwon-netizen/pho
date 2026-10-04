package kr.archsurvey.engine

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kr.archsurvey.bt.*
import kr.archsurvey.core.*
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.GnssSource
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.feedback.FeedbackEvent
import kr.archsurvey.ntrip.*
import java.io.IOException

data class NtripStatus(
    val state: NtripState = NtripState.IDLE,
    val message: String = "",
    val rtcmBytes: Long = 0,
    val lastRtcmAtMs: Long = 0,
    val attempt: Int = 0,
)

/**
 * 측량기 연결 엔진: 블루투스 NMEA 수신 + NTRIP 보정 중계 + 측점 평균.
 * 서비스(SurveyConnectionService) 수명 동안 소켓을 소유한다(Activity/ViewModel 아님).
 * 모델별 차이는 DeviceProfile 로만 주입한다.
 */
class SurveyEngine(private val app: Context, private val settingsStrategy: (String) -> String?, private val saveStrategy: (String, String) -> Unit) : GnssSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val link = MutableStateFlow(LinkStatus())
    override val gnss = MutableStateFlow(GnssSnapshot())
    override fun clockMs(): Long = SystemClock.elapsedRealtime()
    val ntrip = MutableStateFlow(NtripStatus())
    val events = MutableSharedFlow<FeedbackEvent>(extraBufferCapacity = 16)
    val measureProgress = MutableStateFlow(0f)
    val profileFlow = MutableStateFlow<DeviceProfile?>(null)

    private var gnssJob: Job? = null
    private var ntripJob: Job? = null
    @Volatile private var ntripConfig: NtripConfig? = null
    @Volatile private var ntripEnabled = true
    private val rtcmQueue = Channel<ByteArray>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val ntripWake = Channel<Unit>(Channel.CONFLATED)
    private var receiversRegistered = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastGgaRaw: String? = null
    @Volatile private var wasConnectedOnce = false
    private var lastQuality = FixQuality.NONE
    private var linkLostAnnounced = false
    private var ntripLostAnnounced = false

    val running: Boolean get() = gnssJob?.isActive == true

    /** 수신기로부터 안테나 높이를 보고받을 수 있으면 여기로 (NMEA 에는 없음; GSOF 등 2단계). null = 보고 없음 */
    val receiverReportedAntennaM = MutableStateFlow<Double?>(null)

    fun start(profile: DeviceProfile, address: String, ntrip: NtripConfig?, ntripEnabled: Boolean) {
        stop()
        profileFlow.value = profile
        this.ntripConfig = ntrip
        this.ntripEnabled = ntripEnabled
        registerReceivers()
        gnssJob = scope.launch { gnssLoop(profile, address) }
        restartNtrip()
    }

    override fun stop() {
        gnssJob?.cancel(); gnssJob = null
        ntripJob?.cancel(); ntripJob = null
        unregisterReceivers()
        link.value = LinkStatus()
        ntrip.value = NtripStatus()
        gnss.value = GnssSnapshot()
        wasConnectedOnce = false; linkLostAnnounced = false; ntripLostAnnounced = false
        lastQuality = FixQuality.NONE
        while (rtcmQueue.tryReceive().isSuccess) { /* 비움 */ }
    }

    fun updateNtrip(cfg: NtripConfig?, enabled: Boolean) {
        ntripConfig = cfg; ntripEnabled = enabled
        if (running) restartNtrip()
    }

    fun kickReconnect() { wake.trySend(Unit) }

    fun latestGgaForCaster(): String? = GgaRelay.forCaster(lastGgaRaw)

    // ---------------- 블루투스 ----------------

    private suspend fun gnssLoop(profile: DeviceProfile, address: String) {
        val policy = profile.reconnect
        var attempt = 0
        var failures = 0
        var driver = NmeaDriver(profile)
        try {
            while (currentCoroutineContext().isActive) {
                attempt++
                val wait = policy.delayBeforeAttempt(attempt)
                if (wait > 0) sleepOrWake(wait)
                setLink(if (attempt == 1) LinkState.CONNECTING else LinkState.RECONNECTING, attempt = attempt, failures = failures,
                    msg = if (attempt == 1) "연결하는 중…" else "다시 연결하는 중… (${attempt}번째)")
                val transport = BtTransport(app, address)
                var err: BtError? = null
                try {
                    withContext(Dispatchers.IO) {
                        transport.connect(settingsStrategy(address)?.let { runCatching { Strategy.valueOf(it) }.getOrNull() })
                    }
                    transport.usedStrategy?.let { saveStrategy(address, it.name) }
                    driver = NmeaDriver(profile).also { it.reset(SystemClock.elapsedRealtime()) }
                    setLink(LinkState.CONNECTED_NO_DATA, attempt, failures, msg = "연결됨 — 데이터를 기다리는 중", strategy = transport.usedStrategy?.name)
                    if (wasConnectedOnce && linkLostAnnounced) { events.tryEmit(FeedbackEvent.LINK_RESTORED); linkLostAnnounced = false }
                    wasConnectedOnce = true
                    while (rtcmQueue.tryReceive().isSuccess) { }
                    runSession(profile, driver, transport, attempt, failures) { gotData -> if (gotData) { failures = 0; attempt = 0 } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    err = (e as? BtConnectException)?.error ?: if (e is SessionLost) e.error else BtError.classify(e)
                } finally {
                    transport.close()
                }
                if (err == null) err = BtError.LINK_LOST
                failures++
                if (wasConnectedOnce && !linkLostAnnounced) { events.tryEmit(FeedbackEvent.LINK_LOST); linkLostAnnounced = true }
                if (err.needsUserAction) {
                    // 권한/블루투스 꺼짐/미등록: 무한 재시도 금지 — 사용자 조치 대기 (wake 신호로 재개)
                    setLink(LinkState.NEEDS_USER, attempt, failures, err, "${err.title} (오류 ${err.code})", false)
                    wake.receive()
                    attempt = 0
                    continue
                }
                setLink(LinkState.RECONNECTING, attempt, failures, err,
                    "${err.title} — 자동으로 다시 연결해요 (오류 ${err.code})", policy.shouldShowRepairHint(failures))
                delay(policy.settleMs.coerceAtLeast(300))
            }
        } finally {
            link.value = LinkStatus()
        }
    }

    private class SessionLost(val error: BtError, msg: String) : IOException(msg)

    /** 연결 수립 후 읽기/워치독/RTCM 쓰기. 끊기면 SessionLost 로 종료 */
    private suspend fun runSession(
        profile: DeviceProfile, driver: NmeaDriver, t: BtTransport, attempt: Int, failures: Int,
        onData: (Boolean) -> Unit,
    ) = coroutineScope {
        var gotData = false
        // 취소 시 블로킹 read 를 깨우기 위해 소켓 close
        launch { try { awaitCancellation() } finally { t.close() } }
        // 읽기
        launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            try {
                while (isActive) {
                    val n = t.input.read(buf)
                    if (n < 0) throw SessionLost(BtError.SOCKET_CLOSED, "EOF")
                    if (n == 0) continue
                    val now = SystemClock.elapsedRealtime()
                    val msgs = driver.onBytes(buf, n, now)
                    if (msgs.isNotEmpty()) {
                        val snap = driver.snapshot
                        snap.gga?.let { if (it.raw.isNotEmpty()) lastGgaRaw = it.raw }
                        onQualityMaybeChanged(snap.quality)
                        gnss.value = snap
                        if (!gotData && snap.gga != null) {
                            gotData = true; onData(true)
                            setLink(LinkState.STREAMING, attempt, 0, msg = "수신 중", strategy = t.usedStrategy?.name)
                        } else if (link.value.state == LinkState.STALE) {
                            setLink(LinkState.STREAMING, attempt, 0, msg = "수신 중", strategy = t.usedStrategy?.name)
                        }
                    }
                }
            } catch (e: SessionLost) { throw e
            } catch (e: IOException) {
                if (!isActive) return@launch
                throw SessionLost(BtError.classify(e).let { if (it == BtError.UNKNOWN) BtError.LINK_LOST else it }, e.message ?: "read")
            }
        }
        // 워치독: 무수신 → STALE → 재연결
        launch {
            while (isActive) {
                delay(1000)
                when (driver.health(SystemClock.elapsedRealtime())) {
                    DataHealth.STALE -> if (link.value.state != LinkState.STALE) {
                        val miss = driver.missingRequired().isNotEmpty() && driver.snapshot.sentencesTotal == 0L
                        setLink(LinkState.STALE, attempt, failures, if (miss) BtError.NO_DATA else BtError.BAD_CHECKSUM.takeIf { driver.snapshot.checksumErrors > 5 },
                            if (miss) profile.noDataHint else "데이터가 잠시 끊겼어요", strategy = t.usedStrategy?.name)
                    }
                    DataHealth.DEAD -> throw SessionLost(
                        if (driver.snapshot.sentencesTotal == 0L) BtError.NO_DATA else BtError.LINK_LOST, "no data")
                    else -> {}
                }
            }
        }
        // 쓰기: RTCM 큐 직렬화, 청크 단위, 정체 감지
        launch(Dispatchers.IO) {
            try {
                for (frame in rtcmQueue) {
                    var off = 0
                    while (off < frame.size) {
                        val n = minOf(profile.rtcmWriteChunk, frame.size - off)
                        val t0 = SystemClock.elapsedRealtime()
                        t.output.write(frame, off, n)
                        t.output.flush()
                        if (SystemClock.elapsedRealtime() - t0 > 3000) throw SessionLost(BtError.WRITE_STALLED, "write stalled")
                        off += n
                        if (profile.rtcmWriteGapMs > 0) delay(profile.rtcmWriteGapMs)
                    }
                }
            } catch (e: SessionLost) { throw e
            } catch (e: IOException) {
                if (isActive) throw SessionLost(BtError.WRITE_FAILED, e.message ?: "write")
            }
        }
        Unit
    }

    private fun onQualityMaybeChanged(q: FixQuality) {
        if (q == lastQuality) return
        val prev = lastQuality
        lastQuality = q
        if (q == FixQuality.RTK_FIXED) events.tryEmit(FeedbackEvent.FIX_ACQUIRED)
        else if (prev == FixQuality.RTK_FIXED) events.tryEmit(FeedbackEvent.FIX_LOST)
    }

    private fun setLink(state: LinkState, attempt: Int = link.value.attempt, failures: Int = link.value.failures,
                        err: BtError? = null, msg: String = "", repair: Boolean = false, strategy: String? = link.value.strategy) {
        link.value = LinkStatus(state, attempt, failures, err, msg, repair, strategy)
    }

    private suspend fun sleepOrWake(ms: Long) { withTimeoutOrNull(ms) { wake.receive() } }

    // ---------------- NTRIP ----------------

    private fun restartNtrip() {
        ntripJob?.cancel()
        val cfg = ntripConfig
        if (!ntripEnabled || cfg == null || !cfg.isUsable) {
            ntrip.value = NtripStatus(NtripState.IDLE, if (!ntripEnabled) "보정 사용 안 함" else "VRS 정보를 입력하세요")
            return
        }
        val profile = profileFlow.value ?: return
        ntripJob = scope.launch {
            var attempt = 0
            val client = NtripClient()
            while (isActive) {
                attempt++
                var bytes = ntrip.value.rtcmBytes
                try {
                    client.run(
                        cfg = cfg, ggaIntervalMs = profile.ggaUploadIntervalMs, noDataTimeoutMs = 10_000,
                        ggaProvider = { latestGgaForCaster() },
                        onState = { st ->
                            ntrip.value = ntrip.value.copy(state = st, message = when (st) {
                                NtripState.CONNECTING -> "VRS 서버에 접속 중…"
                                NtripState.CONNECTED -> "접속됨 — 보정 데이터를 기다리는 중"
                                NtripState.RECEIVING -> "보정 데이터를 받고 있어요"
                                else -> ""
                            }, attempt = attempt)
                            if (st == NtripState.RECEIVING) {
                                attempt = 0
                                if (ntripLostAnnounced) { events.tryEmit(FeedbackEvent.NTRIP_OK); ntripLostAnnounced = false }
                            }
                        },
                        onRtcm = { buf, n ->
                            bytes += n
                            rtcmQueue.trySend(buf.copyOf(n))
                            ntrip.value = ntrip.value.copy(rtcmBytes = bytes, lastRtcmAtMs = SystemClock.elapsedRealtime())
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NtripAuthException) {
                    ntrip.value = NtripStatus(NtripState.AUTH_FAILED, e.message ?: "인증 실패", bytes)
                    events.tryEmit(FeedbackEvent.NTRIP_LOST)
                    ntripWake.receive() // 설정이 바뀔 때까지 재시도 중단
                    continue
                } catch (e: NtripMountException) {
                    ntrip.value = NtripStatus(NtripState.ERROR, e.message ?: "마운트포인트 오류", bytes)
                    ntripWake.receive()
                    continue
                } catch (e: Exception) {
                    if (!ntripLostAnnounced && ntrip.value.lastRtcmAtMs != 0L) { events.tryEmit(FeedbackEvent.NTRIP_LOST); ntripLostAnnounced = true }
                    val d = minOf(15_000L, 1_000L shl (attempt - 1).coerceIn(0, 4))
                    ntrip.value = NtripStatus(NtripState.RECONNECTING, "보정 연결이 끊겼어요. ${d / 1000}초 후 다시 접속 [${e.javaClass.simpleName}] ${e.message ?: ""}", bytes, ntrip.value.lastRtcmAtMs, attempt)
                    withTimeoutOrNull(d) { ntripWake.receive() }
                }
            }
        }
    }

    fun retryNtripNow() { ntripWake.trySend(Unit); if (ntripJob?.isActive != true) restartNtrip() }

    // ---------------- 측점 평균 ----------------

    /** epochs 개의 서로 다른 GGA 에폭을 평균. 시간 초과/데이터 없음 시 null */
    suspend fun measure(epochs: Int): AveragedPoint? {
        val avg = PointAverager(epochs)
        measureProgress.value = 0f
        val timeout = epochs * 3000L + 8000L
        val r = withTimeoutOrNull(timeout) {
            gnss.first { snap ->
                snap.gga?.let { avg.add(it, snap.gst) }
                measureProgress.value = avg.progress
                avg.done
            }
            true
        }
        measureProgress.value = 0f
        return if (r == true) avg.result() else null
    }

    // ---------------- 시스템 신호 ----------------

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (st == BluetoothAdapter.STATE_ON) kickReconnect()
                    if (st == BluetoothAdapter.STATE_OFF) {
                        setLink(LinkState.NEEDS_USER, err = BtError.BT_OFF, msg = "${BtError.BT_OFF.title} (오류 ${BtError.BT_OFF.code})")
                        if (wasConnectedOnce && !linkLostAnnounced) { events.tryEmit(FeedbackEvent.LINK_LOST); linkLostAnnounced = true }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerReceivers() {
        if (receiversRegistered) return
        receiversRegistered = true
        try { app.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) } catch (_: Exception) { }
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { ntripWake.trySend(Unit) } // 네트워크 전환 시 즉시 재접속
            }
            val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(req, cb) // API 21+
            netCallback = cb
        } catch (_: Exception) { }
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        receiversRegistered = false
        try { app.unregisterReceiver(btReceiver) } catch (_: Exception) { }
        try { netCallback?.let { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (_: Exception) { }
        netCallback = null
    }
}
