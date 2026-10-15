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

/**
 * 측량기 연결 엔진: 블루투스 NMEA 수신 + NTRIP 보정 중계 + 측점 평균.
 * 서비스(SurveyConnectionService) 수명 동안 소켓을 소유한다(Activity/ViewModel 아님).
 * 모델별 차이는 DeviceProfile 로만 주입한다.
 */
class SurveyEngine(
    private val app: Context, private val settingsStrategy: (String) -> String?, private val saveStrategy: (String, String?) -> Unit,
    /** 0.1.33: R10 자동설정이 확인한 주소별 블루투스 포트 */
    private val r10Port: (String) -> Int? = { null }, private val saveR10Port: (String, Int) -> Unit = { _, _ -> },
) : GnssSource {
    /** 백그라운드 작업의 예외는 앱을 끄지 않고 오류 기록에 남긴다(0.1.9) */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> kr.archsurvey.diag.CrashLog.record("엔진(블루투스·보정) 작업", e) })

    override val link = MutableStateFlow(LinkStatus())
    override val gnss = MutableStateFlow(GnssSnapshot())
    override fun clockMs(): Long = SystemClock.elapsedRealtime()
    val ntrip = MutableStateFlow(NtripStatus())
    val events = MutableSharedFlow<FeedbackEvent>(extraBufferCapacity = 16)
    val measureProgress = MutableStateFlow(0f)
    val profileFlow = MutableStateFlow<DeviceProfile?>(null)
    /** 0.1.30 T23: 이번 연결의 SP60 GST/GSA 자동 요청 결과(없으면 null). 화면 안내·진단용 */
    val precision = MutableStateFlow<PrecisionResult?>(null)
    /** 0.1.32: 이번 연결에서 본 제조사 고유 NMEA 신호로 판단한 수신기 종류 id(`$PASH`=SP60, `$PTNL`=R10). 없으면 null */
    val receiverSignature = MutableStateFlow<String?>(null)
    /** 0.1.33: 이번 연결의 R10 자동설정 결과(켜기·보충 중 마지막). R10 아니면 null */
    val r10Auto = MutableStateFlow<R10AutoResult?>(null)
    /** 0.1.32: 알림 「연결 종료」처럼 화면 밖에서 연결을 멈췄을 때 한 번 */
    val stoppedOutside = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 0.1.30 T23-b: 사용자 [자동 켜기 다시] */
    private val precisionRetry = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    /** SP60 이 받는 중일 때만 보낸다. 보냈으면 true */
    fun retryPrecision(): Boolean {
        if (profileFlow.value?.id != kr.archsurvey.device.DeviceProfiles.SP60_ID) return false
        val st = link.value.state
        if (st != kr.archsurvey.device.LinkState.STREAMING && st != kr.archsurvey.device.LinkState.STALE) return false
        return precisionRetry.trySend(Unit).isSuccess
    }

    private var gnssJob: Job? = null
    @Volatile private var ntripConfig: NtripConfig? = null
    @Volatile private var ntripEnabled = true
    private val rtcmQueue = Channel<ByteArray>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var receiversRegistered = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastGgaRaw: String? = null
    @Volatile private var lastQuality = FixQuality.NONE

    companion object {
        /** 수신기 GGA 가 이보다 오래되면 캐스터에 올리지 않는다(2분) */
        const val GGA_MAX_AGE_MS = 120_000L
    }

    val running: Boolean get() = gnssJob?.isActive == true

    /** 수신기로부터 안테나 높이를 보고받을 수 있으면 여기로 (NMEA 에는 없음; GSOF 등 2단계). null = 보고 없음 */
    val receiverReportedAntennaM = MutableStateFlow<Double?>(null)

    fun start(profile: DeviceProfile, address: String, ntrip: NtripConfig?, ntripEnabled: Boolean) {
        val old = gnssJob
        stop()
        profileFlow.value = profile
        precision.value = null
        receiverSignature.value = null
        r10Auto.value = null
        this.ntripConfig = ntrip
        this.ntripEnabled = ntripEnabled
        while (wake.tryReceive().isSuccess) { } // 이전 세션의 낡은 깨우기 신호 제거
        registerReceivers()
        // 0.1.32: 이전 세션이 완전히 끝난 뒤(소켓 닫힘·finally 의 link=IDLE) 새 세션 시작 — 늦게 도는 이전 finally 가 새 세션 상태를 IDLE 로 덮던 경합 제거
        gnssJob = scope.launch { old?.join(); gnssLoop(profile, address) }
        restartNtrip()
    }

    override fun stop() {
        gnssJob?.cancel(); gnssJob = null
        ntripSupervisor.stop()
        unregisterReceivers()
        link.value = LinkStatus()
        gnss.value = GnssSnapshot()
        lastQuality = FixQuality.NONE
        fixGate.reset()
        while (rtcmQueue.tryReceive().isSuccess) { /* 비움 */ }
    }

    fun updateNtrip(cfg: NtripConfig?, enabled: Boolean) {
        ntripConfig = cfg; ntripEnabled = enabled
        if (running) restartNtrip()
    }

    fun kickReconnect() { wake.trySend(Unit) }

    fun latestGgaForCaster(): String? = freshGgaForCaster()

    // ---------------- 블루투스 ----------------

    /** BT-130 무음으로 판명된 연결 방식 — 같은 주소 치유 때 exclude. STREAMING 되면 비운다 */
    private val silentStrategies: MutableSet<Strategy> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Strategy, Boolean>()) // 0.1.46: KeySetView(API 24+) 대신 — minSdk 21 lint NewApi

    private suspend fun gnssLoop(profile: DeviceProfile, address: String) {
        silentStrategies.clear()
        val sup = LinkSupervisor(
            profile = profile,
            newTransport = {
                val preferred = settingsStrategy(address)?.let { runCatching { Strategy.valueOf(it) }.getOrNull() }
                    ?.takeUnless { it in silentStrategies }
                BtTransport(app, address, preferred, exclude = silentStrategies.toSet(), advertisedFirst = profile.advertisedUuidFirst, insecureFirst = profile.insecureFirst)
            },
            clock = { SystemClock.elapsedRealtime() },
            link = link, gnss = gnss, rtcmQueue = rtcmQueue, wake = wake,
            emit = { events.tryEmit(it) },
            onGga = { lastGgaRaw = it; lastGgaEngineAtMs = SystemClock.elapsedRealtime() },
            onQuality = { onQualityMaybeChanged(it) },
            // GGA 확인된 방식만 저장. 무음 목록도 비운다
            onStrategyUsed = { name ->
                runCatching { Strategy.valueOf(name) }.getOrNull()?.let { silentStrategies.remove(it) }
                saveStrategy(address, name)
            },
            // 소켓 OK·GGA 없음 → 다음 치유에서 이 방식 제외 + 저장된 선호라면 지움
            onSilentStrategy = { name ->
                runCatching { Strategy.valueOf(name) }.getOrNull()?.let { silentStrategies.add(it) }
                if (settingsStrategy(address) == name) saveStrategy(address, null)
            },
            onPrecisionResult = { precision.value = it },
            onSignature = { receiverSignature.value = it },
            precisionBudget = PrecisionBudget.forDevice(address),
            precisionRetry = precisionRetry,
            r10Auto = R10AutoOptions.forProfile(profile, object : R10PortStore {
                override fun port(): Int? = r10Port(address)
                override fun save(port: Int) = saveR10Port(address, port)
            }),
            onR10Auto = { r10Auto.value = it },
        )
        sup.run()
    }

    @Volatile private var lastGgaEngineAtMs = 0L

    private val fixGate = FixSoundGate(scope) { events.tryEmit(it) }

    @androidx.annotation.VisibleForTesting
    internal fun onQualityMaybeChanged(q: FixQuality) {
        if (q == lastQuality) return
        val prev = lastQuality
        lastQuality = q
        // SFX-0119-1: 소리·진동은 FixSoundGate 가 유지 시간으로 거름(화면 품질 표시는 gnss 로 즉시)
        val fixed = q == FixQuality.RTK_FIXED
        if (fixed != (prev == FixQuality.RTK_FIXED)) fixGate.onFixedChanged(fixed)
    }

    // ---------------- NTRIP ----------------

    private val linkStreaming: StateFlow<Boolean> by lazy {
        link.map { it.state == LinkState.STREAMING || it.state == LinkState.STALE }.stateIn(scope, SharingStarted.Eagerly, false)
    }
    private val ntripSupervisor by lazy {
        NtripSupervisor(
            scope = scope, status = ntrip, clock = { SystemClock.elapsedRealtime() },
            linkStreaming = linkStreaming,
            ggaProvider = { freshGgaForCaster() },
            rtcmSink = { rtcmQueue.trySend(it) },
            emit = { events.tryEmit(it) },
            baseIdProvider = { gnss.value.gga?.baseStationId }, // 0.1.58 B6
        )
    }

    /** VRS 서버에는 위치 있는 GGA 만, 그리고 너무 오래된 것(수신기 끊김 2분 초과)은 올리지 않는다 */
    private fun freshGgaForCaster(): String? {
        if (SystemClock.elapsedRealtime() - lastGgaEngineAtMs > GGA_MAX_AGE_MS) return null
        return GgaRelay.forCaster(lastGgaRaw)
    }

    private fun restartNtrip() {
        if (profileFlow.value == null) return
        ntripSupervisor.start(ntripConfig, ntripEnabled, ntripGgaIntervalMs)
    }

    /** 사용자가 "다시 시도"를 눌렀을 때(인증 실패 보류도 푼다) */
    fun retryNtripNow() { if (!ntripSupervisor.active) restartNtrip() else ntripSupervisor.retryNow() }

    /** 0.1.11 VRS 「GGA 전송」·「자동 재접속」 스위치. 접속을 다시 맺지 않고 다음 송신/재시도부터 적용 */
    fun setNtripOptions(sendGga: Boolean, autoRetry: Boolean) {
        ntripSupervisor.sendGga = sendGga
        val was = ntripSupervisor.autoRetry
        ntripSupervisor.autoRetry = autoRetry
        if (autoRetry && !was && running) ntripSupervisor.retryNow()
    }

    /** GGA 업로드 주기(ms). 1~10초로 제한된다. 값을 바꾸면 보정 접속을 다시 맺는다 */
    @Volatile var ntripGgaIntervalMs: Long = 5_000
        private set
    fun setGgaInterval(ms: Long) {
        val v = NtripTiming.clampGgaIntervalMs(ms)
        if (v == ntripGgaIntervalMs) return
        ntripGgaIntervalMs = v
        if (running) restartNtrip()
    }

    /** 소스테이블(마운트 목록) 조회. 설정 화면의 "마운트 고르기"용. 실패 시 [NtripException] 계열 */
    suspend fun fetchMountPoints(cfg: NtripConfig): List<MountInfo> = NtripClient().fetchSourcetable(cfg)

    // ---------------- 측점 평균 ----------------

    /** epochs 개의 서로 다른 GGA 에폭을 평균. 시간 초과/데이터 없음 시 null */
    suspend fun measure(epochs: Int): AveragedPoint? {
        val avg = PointAverager(epochs)
        measureProgress.value = 0f
        val timeout = epochs * 3000L + 8000L
        val r = withTimeoutOrNull(timeout) {
            gnss.first { snap ->
                snap.gga?.let { avg.add(it, snap.freshGst(snap.lastGgaAtMs), snap.lastGgaAtMs) }
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
                        val was = link.value.state
                        link.value = LinkStatus(LinkState.NEEDS_USER, link.value.attempt, link.value.failures, BtError.BT_OFF, "${BtError.BT_OFF.title} (오류 ${BtError.BT_OFF.code})")
                        if (was == LinkState.STREAMING || was == LinkState.STALE) events.tryEmit(FeedbackEvent.LINK_LOST)
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
                override fun onAvailable(network: Network) { ntripSupervisor.networkAvailable() } // 네트워크 전환 시 즉시 재접속
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
