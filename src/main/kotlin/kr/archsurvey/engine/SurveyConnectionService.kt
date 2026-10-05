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
import kr.archsurvey.MainActivity
import kr.archsurvey.R
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as App
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }

        ensureChannel()
        // 5초 안에 startForeground. API 29+ 는 type 인자, 34+ 는 type 필수(manifest 에도 선언).
        val n = buildNotification("측량기 연결 준비 중", "")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(NOTI_ID, n)
        } catch (e: Exception) {
            // 권한 없음/백그라운드 시작 제한 등: 서비스 없이도 엔진은 앱 프로세스에서 동작하도록 시도
            stopSelf(); return START_NOT_STICKY
        }
        acquireWake()

        val s = app.settings
        val profile = DeviceProfiles.byId(intent?.getStringExtra(EXTRA_DEVICE) ?: s.deviceId)
        val addr = intent?.getStringExtra(EXTRA_ADDR) ?: profile?.let { s.btAddressFor(it.id) }
        if (profile == null || addr == null || !BtCompat.hasConnectPermission(this)) { shutdown(); return START_NOT_STICKY }

        try { app.engine.start(profile, addr, s.loadNtrip(), s.ntripEnabled) }
        catch (e: Exception) { kr.archsurvey.diag.CrashLog.record("연결 시작", e); shutdown(); return START_NOT_STICKY }
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
            this, 1, Intent(this, SurveyConnectionService::class.java).setAction(ACTION_STOP),
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

    private fun shutdown() {
        (application as App).engine.stop()
        obs?.cancel(); wakeRenew?.cancel()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
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

        fun start(ctx: Context, deviceId: String, address: String) {
            val i = Intent(ctx, SurveyConnectionService::class.java).putExtra(EXTRA_DEVICE, deviceId).putExtra(EXTRA_ADDR, address)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SurveyConnectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
