package kr.archsurvey.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 0.1.48(측량) · 0.1.10(사진 기준점) 같은 파일.
 * 화면(ViewModel)이 없이 연결 서비스만 남은 프로세스도 다른 앱이 켜지면 완전히 닫히게 하는 매니페스트 수신기.
 * 화면이 살아 있으면 [AndroidLockBus] → ReceiverLock → ConnectionController.closeForOtherApp 이 처리하므로 여기서는 아무것도 안 한다.
 * 권한 kr.archsurvey.permission.RECEIVER_LOCK(signature) 이 있는 같은 서명 앱만 보낼 수 있다.
 */
class CloseOtherReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getStringExtra("op") != "CLOSE_APP") return
        val from = i.getStringExtra("from") ?: return
        if (from == c.packageName || AndroidLockBus.vmAlive) return
        runCatching { SurveyConnectionService.stopSafely(c) }
        runCatching { (c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(SurveyConnectionService.NOTI_ID) }
        runCatching {
            c.sendBroadcast(Intent(AndroidLockBus.ACTION).setPackage(from)
                .putExtra("op", "CLOSED").putExtra("from", c.packageName).putExtra("label", AndroidLockBus.labelFor(c.packageName)).putExtra("to", from),
                AndroidLockBus.PERMISSION)
        }
        val pending = goAsync()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            pending.finish(); android.os.Process.killProcess(android.os.Process.myPid())
        }, 500L)
    }
}
