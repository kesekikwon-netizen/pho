package kr.archsurvey.engine

import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.ui.SettingsPlatform

class AndroidSettingsPlatform(private val s: SettingsStore, private val onKeepScreenChanged: (Boolean) -> Unit, private val ctx: android.content.Context? = null) : SettingsPlatform {
    override var epochs: Int
        get() = s.epochsPerPoint
        set(v) { s.epochsPerPoint = v }
    override fun crashLog(): String = kr.archsurvey.diag.CrashLog.read()
    override fun clearCrashLog() = kr.archsurvey.diag.CrashLog.clear()
    override fun shareCrashLog() {
        val c = ctx ?: return
        val txt = crashLog().ifBlank { "오류 기록 없음" }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "발굴 측량 오류 기록").putExtra(android.content.Intent.EXTRA_TEXT, txt.takeLast(90_000))
        c.startActivity(android.content.Intent.createChooser(send, "오류 기록 공유").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override var zone: KoreanTmZone
        get() = s.tmZone
        set(v) { s.tmZone = v }
    override var poleRight: Boolean
        get() = s.poleRight
        set(v) { s.poleRight = v }
    override var sound: Boolean
        get() = s.soundOn
        set(v) { s.soundOn = v }
    override var vibration: Boolean
        get() = s.vibrationOn
        set(v) { s.vibrationOn = v }
    override var keepScreenOn: Boolean
        get() = s.keepScreenOn
        set(v) { s.keepScreenOn = v; onKeepScreenChanged(v) }
}
