package kr.archsurvey.engine

import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.ui.SettingsPlatform

class AndroidSettingsPlatform(private val s: SettingsStore, private val onKeepScreenChanged: (Boolean) -> Unit) : SettingsPlatform {
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
