package kr.archsurvey.engine

import kr.archsurvey.App
import kr.archsurvey.bt.BtCompat
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.ntrip.MountInfo
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
        app.engine.setNtripOptions(s.ntripSendGga, s.ntripAutoRetry)
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

    override suspend fun fetchMounts(cfg: NtripConfig): List<MountInfo> = app.engine.fetchMountPoints(cfg)
    override fun hostProfile(host: String): NtripForm? {
        val s = app.settings
        val (u, port, mount) = s.hostProfile(host) ?: return null
        return NtripForm(host = host.trim(), port = port.toString(), mount = mount, user = u, password = s.hostPassword(host))
    }
    override fun markSuccess(form: NtripForm) {
        val s = app.settings
        s.lastConnectOkAt = System.currentTimeMillis()
        if (form.enabled) s.saveHostProfile(form.toConfig())
    }
    override fun hadSuccess() = app.settings.lastConnectOkAt > 0L
    override fun ntripOptions() = app.settings.ntripSendGga to app.settings.ntripAutoRetry
    override fun applyNtripOptions(sendGga: Boolean, autoRetry: Boolean) {
        app.settings.ntripSendGga = sendGga; app.settings.ntripAutoRetry = autoRetry
        app.engine.setNtripOptions(sendGga, autoRetry)
    }
    override fun diagnostics(): String = diagnosticsFrom(kr.archsurvey.diag.CrashLog.read())
    override fun retryNtrip() = app.engine.retryNtripNow()
    override var autoConnect: Boolean
        get() = app.settings.autoConnect
        set(v) { app.settings.autoConnect = v }
}

/** 오류 기록에서 연결 진단(NMEA 활성화·블루투스·보정) 블록만 최근 4건 골라 짧게 돌려준다 */
internal fun diagnosticsFrom(log: String): String =
    log.split(Regex("(?m)^(?==== )")).filter { b ->
        listOf("NMEA", "블루투스", "보정", "연결", "진단", "BT-130", "치유", "PASH").any { it in b }
    }.takeLast(6).joinToString("\n") { it.trim().lines().take(16).joinToString("\n") }.takeLast(5000)
