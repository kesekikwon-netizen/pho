package kr.archsurvey.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kr.archsurvey.core.AntennaConfig
import kr.archsurvey.core.AntennaSource
import kr.archsurvey.core.HeightUnit
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.ntrip.NtripConfig

/**
 * 앱 설정. 선택 측량기·프로젝트·측점 옵션은 SharedPreferences,
 * NTRIP 비밀번호는 API 23+ 에서만 EncryptedSharedPreferences 에 저장한다.
 * API 21~22 는 암호화 저장소를 쓸 수 없어 비밀번호를 저장하지 않는다(매 실행 입력).
 */
class SettingsStore(private val ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var secure: SharedPreferences? = null

    val canStorePassword: Boolean get() = Build.VERSION.SDK_INT >= 23

    private fun securePrefs(): SharedPreferences? {
        if (!canStorePassword) return null
        secure?.let { return it }
        return try {
            val key = androidx.security.crypto.MasterKey.Builder(ctx)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                ctx, "secure_settings", key,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { secure = it }
        } catch (e: Exception) { null }
    }

    var deviceId: String?
        get() = sp.getString("device_id", null)
        set(v) = sp.edit().putString("device_id", v).apply()

    fun btAddressFor(deviceId: String): String? = sp.getString("bt_addr_$deviceId", null)
    fun setBtAddress(deviceId: String, addr: String?) = sp.edit().putString("bt_addr_$deviceId", addr).apply()

    fun btStrategy(address: String): String? = sp.getString("bt_strategy_$address", null)
    fun setBtStrategy(address: String, strategy: String) = sp.edit().putString("bt_strategy_$address", strategy).apply()

    var project: String
        get() = sp.getString("project", "발굴조사-1") ?: "발굴조사-1"
        set(v) = sp.edit().putString("project", v.trim().ifEmpty { "발굴조사-1" }).apply()
    var idPrefix: String
        get() = sp.getString("id_prefix", "P-") ?: "P-"
        set(v) = sp.edit().putString("id_prefix", v).apply()
    var epochs: Int
        get() = sp.getInt("epochs", 5)
        set(v) = sp.edit().putInt("epochs", v.coerceIn(1, 60)).apply()
    var requireFixed: Boolean
        get() = sp.getBoolean("require_fixed", false)
        set(v) = sp.edit().putBoolean("require_fixed", v).apply()
    /** 안테나 높이는 기기(R10/SP60)별로 저장. 내부 값은 미터. 저장된 게 없으면 null */
    fun loadAntenna(deviceId: String): AntennaConfig? {
        val k = "ant_$deviceId"
        if (!sp.contains("${k}_val")) return null
        return AntennaConfig(
            inputValueM = sp.getFloat("${k}_val", 2f).toDouble(),
            refPointId = sp.getString("${k}_ref", "") ?: "",
            tapeOn = sp.getBoolean("${k}_tape", false),
            receiverApplied = sp.getBoolean("${k}_rx", false),
            source = runCatching { AntennaSource.valueOf(sp.getString("${k}_src", "DEFAULT")!!) }.getOrDefault(AntennaSource.DEFAULT),
            extraPartsM = sp.getFloat("${k}_extra", 0f).toDouble(),
            baseExtension = sp.getBoolean("${k}_bext", false),
        )
    }

    fun saveAntenna(deviceId: String, c: AntennaConfig) {
        val k = "ant_$deviceId"
        sp.edit().putFloat("${k}_val", c.inputValueM.toFloat()).putString("${k}_ref", c.refPointId)
            .putBoolean("${k}_tape", c.tapeOn).putBoolean("${k}_rx", c.receiverApplied).putString("${k}_src", c.source.name)
            .putFloat("${k}_extra", c.extraPartsM.toFloat()).putBoolean("${k}_bext", c.baseExtension).apply()
    }

    var heightUnit: HeightUnit
        get() = runCatching { HeightUnit.valueOf(sp.getString("height_unit", "M")!!) }.getOrDefault(HeightUnit.M)
        set(v) = sp.edit().putString("height_unit", v.name).apply()

    // 배경 지도 (V-World). 키는 사용자가 입력, 코드에 내장하지 않음
    var mapKey: String
        get() = securePrefs()?.getString("vworld_key", "") ?: sp.getString("vworld_key_mem", "") ?: ""
        set(v) { if (securePrefs() != null) securePrefs()!!.edit().putString("vworld_key", v.trim()).apply() else sp.edit().putString("vworld_key_mem", v.trim()).apply() }
    var mapDomain: String
        get() = sp.getString("vworld_domain", "") ?: ""
        set(v) = sp.edit().putString("vworld_domain", v.trim()).apply()
    /** SATELLITE | CADASTRAL | NONE (NONE = 점만 표시하는 평면도) */
    var mapBase: String
        get() = sp.getString("map_base", "SATELLITE") ?: "SATELLITE"
        set(v) = sp.edit().putString("map_base", v).apply()
    var cadastralOverlay: Boolean
        get() = sp.getBoolean("map_cad_overlay", false)
        set(v) = sp.edit().putBoolean("map_cad_overlay", v).apply()
    var tmZone: KoreanTmZone
        get() = KoreanTmZone.fromEpsg(sp.getInt("tm_epsg", 5186))
        set(v) = sp.edit().putInt("tm_epsg", v.epsg).apply()
    var soundOn: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()
    var vibrationOn: Boolean
        get() = sp.getBoolean("vibration", true)
        set(v) = sp.edit().putBoolean("vibration", v).apply()
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen", true)
        set(v) = sp.edit().putBoolean("keep_screen", v).apply()
    /** true = 가짜 신호(화면 시험). 기본 켬: 수신기를 연결하기 전까지는 가짜임을 화면에 표시한다 */
    var fakeSignal: Boolean
        get() = sp.getBoolean("fake_signal", true)
        set(v) = sp.edit().putBoolean("fake_signal", v).apply()
    var ntripEnabled: Boolean
        get() = sp.getBoolean("ntrip_enabled", true)
        set(v) = sp.edit().putBoolean("ntrip_enabled", v).apply()
    var code: String
        get() = sp.getString("code", "") ?: ""
        set(v) = sp.edit().putString("code", v).apply()

    // 카메라 촬영 중 프로세스 사망 대비: 촬영 전에 대상 기록
    var pendingPhoto: String?
        get() = sp.getString("pending_photo", null)
        set(v) = sp.edit().putString("pending_photo", v).apply()

    fun loadNtrip(): NtripConfig {
        val d = NtripConfig()
        return NtripConfig(
            host = sp.getString("ntrip_host", d.host) ?: d.host,
            port = sp.getInt("ntrip_port", d.port),
            mountPoint = sp.getString("ntrip_mount", d.mountPoint) ?: d.mountPoint,
            username = sp.getString("ntrip_user", "") ?: "",
            password = securePrefs()?.getString("ntrip_pw", "") ?: memPassword,
            useV2 = sp.getBoolean("ntrip_v2", false),
        )
    }

    private var memPassword: String = ""

    fun saveNtrip(c: NtripConfig) {
        sp.edit().putString("ntrip_host", c.host.trim()).putInt("ntrip_port", c.port)
            .putString("ntrip_mount", c.mountPoint.trim()).putString("ntrip_user", c.username.trim())
            .putBoolean("ntrip_v2", c.useV2).apply()
        memPassword = c.password
        securePrefs()?.edit()?.putString("ntrip_pw", c.password)?.apply()
    }
}
