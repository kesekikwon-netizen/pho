package kr.archsurvey.engine

import kr.archsurvey.App
import kr.archsurvey.bt.BtCompat
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ui.ConnectionPlatform
import kr.archsurvey.ui.NtripForm
import kr.archsurvey.ui.SavedConnection

/** [ConnectionPlatform] 의 Android 구현. 권한 팝업은 Activity 가 [permissionRequester] 로 채워 준다. */
class AndroidConnectionPlatform(private val app: App) : ConnectionPlatform {
    /** (요청할 권한들, 결과 콜백). MainActivity 가 설정 */
    var permissionRequester: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null

    override val link get() = app.engine.link
    override val ntrip get() = app.engine.ntrip

    override fun bondedDevices() = BtCompat.bonded(app).map { it.name to it.address }
    override fun hasBluetoothPermission() = BtCompat.hasConnectPermission(app) && BtCompat.hasScanPermission(app)
    override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) {
        val perms = (BtCompat.runtimePermissions().toList() + listOfNotNull(BtCompat.notificationPermission())).toTypedArray()
        val r = permissionRequester
        if (r == null) onResult(hasBluetoothPermission()) else r(perms) { onResult(hasBluetoothPermission()) }
    }
    override fun bluetoothEnabled() = BtCompat.isEnabled(app)
    override fun openBluetoothSettings() = BtCompat.openBluetoothSettings(app)
    override fun openAppSettings() = BtCompat.openAppSettings(app)

    override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {
        val s = app.settings
        s.deviceId = profile.id; s.setBtAddress(profile.id, address)
        s.saveNtrip(ntrip); s.ntripEnabled = ntripEnabled
        SurveyConnectionService.start(app, profile.id, address)
    }
    override fun stopReal() { SurveyConnectionService.stop(app); app.engine.stop() }

    override fun loadSaved(): SavedConnection {
        val s = app.settings
        val id = s.deviceId
        return SavedConnection(id, id?.let { s.btAddressFor(it) }, NtripForm.from(s.loadNtrip(), s.ntripEnabled), id?.let { s.preChecked(it) } ?: false)
    }
    override fun save(s: SavedConnection) {
        val st = app.settings
        s.deviceId?.let { st.deviceId = it; st.setBtAddress(it, s.address); st.setPreChecked(it, s.preChecked) }
        st.saveNtrip(s.ntrip.toConfig()); st.ntripEnabled = s.ntrip.enabled
    }
}
