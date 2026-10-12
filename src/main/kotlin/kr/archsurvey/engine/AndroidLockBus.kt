package kr.archsurvey.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kr.archsurvey.ui.LockBus
import kr.archsurvey.ui.LockMsg
import kr.archsurvey.ui.LockOp

/**
 * 0.1.42(측량) · 0.1.4(사진 기준점) 같은 파일. 같은 서명 앱끼리만 주고받는 방송으로 수신기 잠금 알림.
 * 보내기·받기 모두 권한 kr.archsurvey.permission.RECEIVER_LOCK(protectionLevel=signature) 필요 → 다른 서명의 앱은 못 보내고 못 받음.
 */
class AndroidLockBus(private val ctx: Context) : LockBus {
    companion object {
        const val ACTION = "kr.archsurvey.action.RECEIVER_LOCK"
        const val PERMISSION = "kr.archsurvey.permission.RECEIVER_LOCK"
        /** 같은 서명 앱(서로에게 직접 보냄 — 암시적 방송 제한을 피하려고 패키지 지정) */
        val PACKAGES = listOf("kr.archsurvey.field", "kr.archsurvey.gcp", "kr.archsurvey.field.debug", "kr.archsurvey.gcp.debug")
    }
    override var onMessage: ((LockMsg) -> Unit)? = null
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val op = runCatching { LockOp.valueOf(i.getStringExtra("op") ?: return) }.getOrNull() ?: return
            val m = LockMsg(op, i.getStringExtra("from") ?: return, i.getStringExtra("label") ?: "다른 앱", i.getStringExtra("mac"), i.getStringExtra("to"))
            runCatching { onMessage?.invoke(m) }.onFailure { kr.archsurvey.diag.CrashLog.record("수신기 잠금 받기", it) }
        }
    }
    init {
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(ACTION), PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
    }
    override fun send(m: LockMsg) {
        for (pkg in PACKAGES) {
            if (pkg == ctx.packageName) continue
            val i = Intent(ACTION).setPackage(pkg)
                .putExtra("op", m.op.name).putExtra("from", m.from).putExtra("label", m.label)
                .putExtra("mac", m.mac).putExtra("to", m.to)
            runCatching { ctx.sendBroadcast(i, PERMISSION) }
        }
    }
    fun release() { runCatching { ctx.unregisterReceiver(rx) } }
}
