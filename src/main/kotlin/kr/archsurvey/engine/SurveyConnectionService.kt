package kr.archsurvey.engine

import kr.archsurvey.device.LinkState
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kr.archsurvey.App
import kr.archsurvey.pg.MainActivity
import kr.archsurvey.pg.R
import kr.archsurvey.bt.BtCompat
import kr.archsurvey.core.FixQuality
import kr.archsurvey.device.DeviceProfiles

/**
 * 포그라운드 서비스: BT 소켓/NTRIP/NMEA 수명을 화면과 분리한다.
 * 시작은 반드시 사용자가 앱을 보는 중에(버튼) 한다 (Android 12+ 백그라운드 시작 제한).
 */
class SurveyConnectionService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> kr.archsurvey.diag.CrashLog.record("연결 서비스 알림", e) })
    private var obs: Job? = null
    private var wakeRenew: Job? = null

    override fun onBind(i: Intent?): IBinder? = null

    /** 0.1.44: 이 서비스 인스턴스가 포그라운드로 올라갔는지 */
    @Volatile internal var promoted = false
    /** 시험용: startForeground 성공 횟수 */
    internal var promoteCount = 0

    override fun onCreate() {
        super.onCreate()
        running = true
        // 0.1.44: startForegroundService 뒤 5초 안 startForeground 필수 → 생성 즉시 올린다(어떤 분기·잠금·종료 확인보다 먼저)
        promote()
    }

    /** 알림 채널 + startForeground(connectedDevice). 실패해도 예외를 밖으로 내지 않는다 */
    private fun promote(): Boolean {
        return try {
            ensureChannel()
            val n = buildNotification("측량기 연결 준비 중", "")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTI_ID, n)
            promoted = true; promoteCount++; true
        } catch (e: Exception) {
            // 권한 없음(FOREGROUND_SERVICE_CONNECTED_DEVICE/BT)·ForegroundServiceStartNotAllowedException 등
            kr.archsurvey.diag.CrashLog.record("연결 서비스 시작(포그라운드 불가 — 화면이 켜진 동안만 연결)", e)
            false
        } finally { pendingStart = false }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 0.1.44: 매 명령마다 맨 먼저 startForeground — 정지 명령이어도 먼저 올린 뒤 내린다(대기 중인 startForegroundService 몫까지 채움)
        val ok = promote()
        val app = application as App
        if (intent?.action == ACTION_STOP) { shutdown(startId); return START_NOT_STICKY }
        // 0.1.32: 알림 「연결 종료」 — 화면의 연결 상태도 풀어 준다(예전엔 「연결 중」으로 남음)
        if (intent?.action == ACTION_STOP_FROM_NOTIFICATION) { shutdown(startId); app.engine.stoppedOutside.tryEmit(Unit); return START_NOT_STICKY }

        if (!ok) {
            // 서비스 없이 엔진만 앱 프로세스에서 돌린다
            runInProcess(app, intent?.getStringExtra(EXTRA_DEVICE), intent?.getStringExtra(EXTRA_ADDR), intent?.let { if (it.hasExtra(EXTRA_NTRIP)) it.getBooleanExtra(EXTRA_NTRIP, true) else null })
            dropForeground(); stopSelf(startId); return START_NOT_STICKY
        }
        acquireWake()

        val s = app.settings
        val profile = DeviceProfiles.byId(intent?.getStringExtra(EXTRA_DEVICE) ?: s.deviceId)
        val addr = intent?.getStringExtra(EXTRA_ADDR) ?: profile?.let { s.btAddressFor(it.id) }
        if (profile == null || addr == null || !BtCompat.hasConnectPermission(this)) {
            shutdown(startId)
            // 0.1.32: 조용히 끝내면 화면은 「연결 중」에 멈춘다 → 사용자 조치 상태로 알림
            val err = if (!BtCompat.hasConnectPermission(this)) kr.archsurvey.bt.BtError.PERMISSION_NEARBY else kr.archsurvey.bt.BtError.NO_DEVICE_SELECTED
            app.engine.link.value = kr.archsurvey.device.LinkStatus(LinkState.NEEDS_USER, error = err, message = "${err.title} (오류 ${err.code})")
            return START_NOT_STICKY
        }
        val ntripOn = intent?.let { if (it.hasExtra(EXTRA_NTRIP)) it.getBooleanExtra(EXTRA_NTRIP, true) else null } ?: s.ntripEnabled

        try { app.engine.start(profile, addr, s.loadNtrip(), ntripOn) }
        catch (e: Exception) { kr.archsurvey.diag.CrashLog.record("연결 시작", e); shutdown(startId); return START_NOT_STICKY }
        obs?.cancel()
        obs = scope.launch {
            // 알림은 제목/문구가 **바뀔 때만** 갱신한다. (이전: 위치가 갱신될 때마다 초당 여러 번 notify → 불필요한 IPC·배터리)
            combine(app.engine.link, app.engine.gnss, app.engine.ntrip) { l, g, nt ->
                val title = when (l.state) {
                    LinkState.STREAMING -> "${profile.displayName} · ${g.quality.labelKo}"
                    LinkState.IDLE -> "측량 준비"
                    LinkState.NEEDS_USER -> "확인이 필요해요"
                    else -> "${profile.displayName} 연결 중"
                }
                val text = "보정: " + when {
                    nt.paused -> "수신기 위치 대기"
                    nt.state == kr.archsurvey.ntrip.NtripState.RECEIVING -> "정상"
                    nt.state == kr.archsurvey.ntrip.NtripState.IDLE -> "사용 안 함"
                    else -> "확인 필요"
                }
                title to text
            }.distinctUntilChanged().collect { (title, text) ->
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTI_ID, buildNotification(title, text))
            }
        }
        // 웨이크락은 시간 제한을 두고 주기적으로 갱신한다(서비스가 비정상 종료돼도 영구히 CPU 를 붙잡지 않도록)
        wakeRenew?.cancel()
        wakeRenew = scope.launch { while (true) { delay(WAKE_RENEW_MS); acquireWake() } }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SurveyConnectionService::class.java).setAction(ACTION_STOP_FROM_NOTIFICATION),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_survey)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 상태만, 좌표 노출 없음
            .setContentIntent(open)
            .addAction(0, "열기", open).addAction(0, "연결 종료", stop)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "측량기 연결", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
    }

    private fun acquireWake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val w = wake ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "survey:connection").apply { setReferenceCounted(false) }.also { wake = it }
        w.acquire(WAKE_TIMEOUT_MS) // 비참조 카운트: 다시 acquire 하면 만료 시각만 연장된다
    }

    private fun dropForeground() {
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
        promoted = false
    }

    /** 0.1.44: stopSelf(startId) — 뒤에 들어온 시작 명령이 있으면 서비스를 죽이지 않는다(그 명령이 startForeground 를 못 하고 끝나는 일 방지) */
    private fun shutdown(startId: Int) {
        (application as App).engine.stop()
        obs?.cancel(); wakeRenew?.cancel()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        dropForeground()
        stopSelf(startId)
    }

    override fun onDestroy() {
        running = false; pendingStart = false
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        obs?.cancel(); wakeRenew?.cancel(); scope.cancel()
        (application as App).engine.stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "survey_connection"
        /** 웨이크락 유효 시간과 갱신 주기(갱신 주기 < 유효 시간) */
        const val WAKE_TIMEOUT_MS = 10 * 60_000L
        const val WAKE_RENEW_MS = 5 * 60_000L
        const val NOTI_ID = 4201
        const val ACTION_STOP = "kr.archsurvey.STOP"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_ADDR = "addr"
        const val EXTRA_NTRIP = "ntrip_on"
        const val ACTION_STOP_FROM_NOTIFICATION = "kr.archsurvey.STOP_NOTI"

        /** 0.1.44: startForegroundService 를 불렀고 아직 서비스가 startForeground 를 못 한 상태 */
        @Volatile @JvmStatic var pendingStart = false
            internal set

        fun start(ctx: Context, deviceId: String, address: String, ntripEnabled: Boolean = true) {
            val i = Intent(ctx, SurveyConnectionService::class.java).putExtra(EXTRA_DEVICE, deviceId).putExtra(EXTRA_ADDR, address).putExtra(EXTRA_NTRIP, ntripEnabled)
            try {
                pendingStart = true
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Android 12+ ForegroundServiceStartNotAllowedException(IllegalStateException)·SecurityException:
                // 앱 크래시 대신 화면이 켜진 동안만 앱 프로세스에서 연결
                pendingStart = false
                kr.archsurvey.diag.CrashLog.record("연결 서비스 시작 거부 — 화면이 켜진 동안만 연결", e)
                (ctx.applicationContext as? App)?.let { runInProcess(it, deviceId, address, ntripEnabled) }
            }
        }

        /** 정지 명령. 서비스가 없으면 새로 띄우지 않는다(stop 하려고 서비스를 깨우지 않음) */
        @Volatile @JvmStatic var running = false
            internal set

        fun stop(ctx: Context) {
            if (!running && !pendingStart) return
            runCatching { ctx.startService(Intent(ctx, SurveyConnectionService::class.java).setAction(ACTION_STOP)) }
                .onFailure { runCatching { ctx.stopService(Intent(ctx, SurveyConnectionService::class.java)) } }
        }

        /**
         * 0.1.44 완전 종료용: 시작 명령이 아직 처리 전이면 stopService 로 끊으면 ForegroundServiceDidNotStartInTimeException →
         * 정지 명령을 줄 세워(시작 명령이 먼저 startForeground 한 뒤) 내린다. 아니면 바로 stopService.
         */
        fun stopSafely(ctx: Context) {
            val i = Intent(ctx, SurveyConnectionService::class.java)
            if (pendingStart) runCatching { ctx.startService(i.setAction(ACTION_STOP)) }.onFailure { runCatching { ctx.stopService(i) } }
            else runCatching { ctx.stopService(i) }
        }

        internal fun runInProcess(app: App, deviceId: String?, address: String?, ntrip: Boolean?) {
            val s0 = app.settings
            val p0 = DeviceProfiles.byId(deviceId ?: s0.deviceId)
            val a0 = address ?: p0?.let { s0.btAddressFor(it.id) }
            if (p0 != null && a0 != null && BtCompat.hasConnectPermission(app)) runCatching {
                app.engine.start(p0, a0, s0.loadNtrip(), ntrip ?: s0.ntripEnabled)
            }.onFailure { kr.archsurvey.diag.CrashLog.record("연결 시작", it) }
        }
    }
}
