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

    /** 암호화 저장소 읽기·쓰기. 기기별 Keystore 문제로 예외가 나도 앱을 끄지 않고 기록만 남긴다(0.1.9) */
    private fun secureGet(key: String): String? = try { securePrefs()?.getString(key, "") } catch (e: Exception) { kr.archsurvey.diag.CrashLog.record("암호화 저장소 읽기", e); null }
    private fun securePut(key: String, v: String): Boolean = try { securePrefs()?.edit()?.putString(key, v)?.apply() != null } catch (e: Exception) { kr.archsurvey.diag.CrashLog.record("암호화 저장소 쓰기", e); false }

    var deviceId: String?
        get() = sp.getString("device_id", null)
        set(v) = sp.edit().putString("device_id", v).apply()

    fun btAddressFor(deviceId: String): String? = sp.getString("bt_addr_$deviceId", null)
    fun setBtAddress(deviceId: String, addr: String?) = sp.edit().putString("bt_addr_$deviceId", addr).apply()

    fun preChecked(deviceId: String): Boolean = sp.getBoolean("pre_ok_$deviceId", false)
    fun setPreChecked(deviceId: String, v: Boolean) = sp.edit().putBoolean("pre_ok_$deviceId", v).apply()

    fun btStrategy(address: String): String? = sp.getString("bt_strategy_$address", null)
    /** strategy=null 이면 저장을 지운다(BT-130 무음 채널로 판명된 방식 제거) */
    fun setBtStrategy(address: String, strategy: String?) {
        val ed = sp.edit()
        if (strategy == null) ed.remove("bt_strategy_$address") else ed.putString("bt_strategy_$address", strategy)
        ed.apply()
    }
    fun clearBtStrategy(address: String) = setBtStrategy(address, null)

    var project: String
        get() = sp.getString("project", "작업1") ?: "작업1"
        set(v) = sp.edit().putString("project", v.trim().ifEmpty { "작업1" }).apply()
    /** 작업을 한 번이라도 열었거나 만든 적이 있으면 true(새 설치 = false) */
    val hasProject: Boolean get() = sp.contains("project")
    /** 만들어 두었지만 아직 점이 없는 작업 이름들(점이 생기면 DB 에서도 찾을 수 있다) */
    var extraProjects: Set<String>
        get() = sp.getStringSet("extra_projects", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("extra_projects", v).apply()
    /** 오른쪽(true)/왼쪽 폴 띠 */
    var poleRight: Boolean
        get() = sp.getBoolean("pole_right", true)
        set(v) = sp.edit().putBoolean("pole_right", v).apply()
    var idPrefix: String
        get() = sp.getString("id_prefix", "P-") ?: "P-"
        set(v) = sp.edit().putString("id_prefix", v).apply()
    /**
     * @deprecated 0.1.13: [epochsPerPoint] 와 같은 키로 통일. 옛 키 "epochs" 는 한 번 읽어 옮긴다.
     * 작업별 측정 횟수의 진실원은 JobConfig.epochs → 저장 시 epochsPerPoint 미러.
     */
    var epochs: Int
        get() = epochsPerPoint
        set(v) { epochsPerPoint = v }
    /** 0.1.26 엄격 품질 게이트: true면 FIX(RTK 고정)만 측점 저장. 기본 false=주의도 경고 후 저장 */
    var requireFixed: Boolean
        get() = sp.getBoolean("require_fixed", false)
        set(v) = sp.edit().putBoolean("require_fixed", v).apply()
    /** @deprecated 0.1.13: 메인 미사용. 폴·모델은 JobConfig. 읽지 말 것. */
    @Deprecated("JobConfig.poleM / antennaModelId 사용", level = DeprecationLevel.WARNING)
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

    // 배경 지도 (V-World). 제품 기본 키(VWorldDefaults) + 사용자 오버라이드(암호화 Pref)
    /** Pref/secure 에 실제로 저장된 키(폴백·내장 상수 제외). 시드·진단용 */
    fun rawStoredMapKey(): String = (
        secureGet("vworld_key")?.takeIf { it.isNotBlank() }
            ?: sp.getString("vworld_key_mem", "")?.takeIf { it.isNotBlank() }
            ?: ""
    ).trim()

    /**
     * 저장이 비어 있으면 제품 기본 키를 secure(또는 mem) Pref 에 심는다.
     * 폴백만으로는 UI 가 "빈 칸"처럼 보이고, Pref 초기화 후 복구가 약해진다.
     */
    fun ensureBuiltinMapKey() {
        if (rawStoredMapKey().isBlank()) {
            mapKey = kr.archsurvey.map.VWorldDefaults.BUILTIN_API_KEY
        }
    }

    var mapKey: String
        get() {
            val stored = rawStoredMapKey()
            return stored.ifBlank { kr.archsurvey.map.VWorldDefaults.BUILTIN_API_KEY }
        }
        set(v) { if (!securePut("vworld_key", v.trim())) sp.edit().putString("vworld_key_mem", v.trim()).apply() }
    var mapDomain: String
        get() {
            // 빈 값 유지(DOMAIN 미전송). DEFAULT 문자열은 WMS 에서 생략하도록 VWorldUrls 가 처리.
            return (sp.getString("vworld_domain", "") ?: "").trim()
        }
        set(v) = sp.edit().putString("vworld_domain", v.trim()).apply()
    /**
     * 배경맵 id 미러(SATELLITE|CADASTRAL|NONE…). 진실원은 작업 JobConfig.mapBase.
     * 0.1.13: JobConfig 저장 때 같이 맞춰, 옛 코드가 읽어도 화면과 같게.
     */
    /** 0.1.14 위치 모양(목업 22). [kr.archsurvey.map.MarkerStyle.encode] 문자열 */
    var markerStyle: String
        get() = sp.getString("marker_style", "") ?: ""
        set(v) = sp.edit().putString("marker_style", v).apply()
    var mapBase: String
        get() = sp.getString("map_base", "SAT_CAD") ?: "SAT_CAD"
        set(v) = sp.edit().putString("map_base", v).apply()
    /** 0.1.12 지적 스타일: false = 면+필지번호(기본, 다른 GIS와 동일). true = *_line(선만) */
    var mapLineOnly: Boolean
        get() = sp.getBoolean("vworld_line_only", false) // 0.1.24: 면 채우기 기본
        set(v) = sp.edit().putBoolean("vworld_line_only", v).apply()
    val hasMapKey: Boolean get() = mapKey.isNotBlank()
    /** 0.1.12 작업별 설정(좌표계·지오이드·배경맵·안테나·측정 횟수) 한 줄. 키 값은 들어가지 않는다 */
    fun jobConfigRaw(project: String): String? = sp.getString("job_cfg_$project", null)
    fun setJobConfigRaw(project: String, v: String?) { if (v == null) sp.edit().remove("job_cfg_$project").apply() else sp.edit().putString("job_cfg_$project", v).apply() }
    /** @deprecated 0.1.13: MapBase.SATELLITE_CADASTRAL 사용. 메인 미읽기 */
    @Deprecated("MapBase 사용", level = DeprecationLevel.WARNING)
    var cadastralOverlay: Boolean
        get() = sp.getBoolean("map_cad_overlay", false)
        set(v) = sp.edit().putBoolean("map_cad_overlay", v).apply()
    /** 좌표계 미러. 진실원은 작업 JobConfig.epsg (0.1.13) */
    var tmZone: KoreanTmZone
        get() = KoreanTmZone.fromEpsg(sp.getInt("tm_epsg", 5186))
        set(v) = sp.edit().putInt("tm_epsg", v.epsg).apply()
    var soundOn: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()
    /** 알림·버튼 탭 햅틱. SharedPreferences 기본 true(ON). */
    var vibrationOn: Boolean
        get() = sp.getBoolean("vibration", true)
        set(v) = sp.edit().putBoolean("vibration", v).apply()
    /** 전자 기포(폰 센서 폴 기울기): 켜짐, 허용 각도(°), 넘을 때 동작, 영점("x,y,z" 또는 없음) */
    var tiltEnabled: Boolean
        get() = sp.getBoolean("tilt_on", true)
        set(v) = sp.edit().putBoolean("tilt_on", v).apply()
    var tiltLimitDeg: Double
        get() = sp.getFloat("tilt_limit", 2.0f).toDouble()
        set(v) = sp.edit().putFloat("tilt_limit", v.toFloat()).apply()
    var tiltPolicy: String
        get() = sp.getString("tilt_policy", "warn") ?: "warn"
        set(v) = sp.edit().putString("tilt_policy", v).apply()
    var tiltReference: String?
        get() = sp.getString("tilt_ref", null)
        set(v) = sp.edit().putString("tilt_ref", v).apply()
    /** 한 점 측정(에폭) 횟수. 0.1.9 기본 3. 작업 JobConfig 의 미러(현재 열린 작업 값) */
    var epochsPerPoint: Int
        get() {
            if (sp.contains("epochs_per_point")) return sp.getInt("epochs_per_point", 3)
            // 구버전 키 이전
            if (sp.contains("epochs")) {
                val old = sp.getInt("epochs", 3).coerceIn(1, 30)
                sp.edit().putInt("epochs_per_point", old).remove("epochs").apply()
                return old
            }
            return 3
        }
        set(v) = sp.edit().putInt("epochs_per_point", v.coerceIn(1, 30)).remove("epochs").apply()
    /** 마지막으로 연결에 성공한 시각(ms). 0 = 아직 없음. 자동 연결은 이 값이 있을 때만 */
    var lastConnectOkAt: Long
        get() = sp.getLong("conn_ok_at", 0L)
        set(v) = sp.edit().putLong("conn_ok_at", v).apply()
    var autoConnect: Boolean
        get() = sp.getBoolean("auto_connect", true)
        set(v) = sp.edit().putBoolean("auto_connect", v).apply()
    /** 0.1.29 T19: 사용자가 보정 서비스·서버·마운트를 직접 바꾼 적 있음(기기 기본값을 넣지 않음) */
    var correctionUserSet: Boolean
        get() = sp.getBoolean("corr_user_set", false)
        set(v) = sp.edit().putBoolean("corr_user_set", v).apply()
    /** 서버(주소)별 마지막 성공 아이디·포트·마운트. 비밀번호는 암호화 저장소에만 */
    fun hostProfile(host: String): Triple<String, Int, String>? {
        val k = host.trim().lowercase(); val u = sp.getString("hp_user_$k", null) ?: return null
        return Triple(u, sp.getInt("hp_port_$k", 2101), sp.getString("hp_mount_$k", "") ?: "")
    }
    fun hostPassword(host: String): String = secureGet("hp_pw_${host.trim().lowercase()}") ?: hostPwMem[host.trim().lowercase()] ?: ""
    private val hostPwMem = HashMap<String, String>()
    fun saveHostProfile(c: NtripConfig) {
        val k = c.host.trim().lowercase()
        sp.edit().putString("hp_user_$k", c.username.trim()).putInt("hp_port_$k", c.port).putString("hp_mount_$k", c.mountPoint.trim()).apply()
        hostPwMem[k] = c.password
        securePut("hp_pw_$k", c.password)
    }
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen", true)
        set(v) = sp.edit().putBoolean("keep_screen", v).apply()
    /** 0.1.28 T14 음성 「저장」 측점(기본 끔). 켤 때 마이크 권한을 묻는다 */
    var voiceSaveOn: Boolean
        get() = sp.getBoolean("voice_save_on", false)
        set(v) = sp.edit().putBoolean("voice_save_on", v).apply()
    /** 0.1.28 온라인(표준) 인식기 사용 동의 — 말소리가 인식 서비스로 보내질 수 있음 */
    var voiceOnlineOk: Boolean
        get() = sp.getBoolean("voice_online_ok", false)
        set(v) = sp.edit().putBoolean("voice_online_ok", v).apply()
    /** 0.1.11 VRS 「GGA 전송」(기본 켬) */
    var ntripSendGga: Boolean
        get() = sp.getBoolean("ntrip_send_gga", true)
        set(v) = sp.edit().putBoolean("ntrip_send_gga", v).apply()
    /** 0.1.11 VRS 「자동 재접속」(기본 켬) */
    var ntripAutoRetry: Boolean
        get() = sp.getBoolean("ntrip_auto_retry", true)
        set(v) = sp.edit().putBoolean("ntrip_auto_retry", v).apply()
    var ntripEnabled: Boolean
        get() = sp.getBoolean("ntrip_enabled", true)
        set(v) = sp.edit().putBoolean("ntrip_enabled", v).apply()
    /** 위치 종류 목록(줄바꿈으로 구분). 비어 있으면 기본 목록 */
    var positionTypes: List<String>
        get() = kr.archsurvey.arch.ArchTypes.decode(sp.getString("position_types", null))
        set(v) = sp.edit().putString("position_types", kr.archsurvey.arch.ArchTypes.encode(v)).apply()
    /** 작업별로 마지막에 쓰던 발굴 구역·그리드 (없으면 null) */
    fun activeArch(project: String): Pair<Long?, Long?> =
        sp.getLong("area_$project", -1L).takeIf { it >= 0 } to sp.getLong("grid_$project", -1L).takeIf { it >= 0 }
    fun setActiveArch(project: String, area: Long?, grid: Long?) =
        sp.edit().putLong("area_$project", area ?: -1L).putLong("grid_$project", grid ?: -1L).apply()
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
            password = secureGet("ntrip_pw")?.takeIf { it.isNotEmpty() || memPassword.isEmpty() } ?: memPassword,
            useV2 = sp.getBoolean("ntrip_v2", false),
        )
    }

    private var memPassword: String = ""

    fun saveNtrip(c: NtripConfig) {
        sp.edit().putString("ntrip_host", c.host.trim()).putInt("ntrip_port", c.port)
            .putString("ntrip_mount", c.mountPoint.trim()).putString("ntrip_user", c.username.trim())
            .putBoolean("ntrip_v2", c.useV2).apply()
        memPassword = c.password
        securePut("ntrip_pw", c.password)
    }

    // —— 0.1.15 Google Drive 백업·측점 업로드 (토큰은 Google Sign-In 이 보관, 여기엔 계정·폴더 id 만)
    var driveAutoBackup: Boolean
        get() = sp.getBoolean("drive_auto_backup", false)
        set(v) = sp.edit().putBoolean("drive_auto_backup", v).apply()
    var driveAutoUploadPoints: Boolean
        get() = sp.getBoolean("drive_auto_upload", false)
        set(v) = sp.edit().putBoolean("drive_auto_upload", v).apply()
    /** 표시용 이메일. 비밀이 아님. 비어 있으면 미연결 */
    var driveAccountEmail: String
        get() = sp.getString("drive_email", "") ?: ""
        set(v) = sp.edit().putString("drive_email", v.trim()).apply()
    var driveFolderId: String
        get() = sp.getString("drive_folder_id", "") ?: ""
        set(v) = sp.edit().putString("drive_folder_id", v.trim()).apply()
    var driveLastBackupAt: Long
        get() = sp.getLong("drive_last_backup", 0L)
        set(v) = sp.edit().putLong("drive_last_backup", v).apply()
    var driveLastUploadAt: Long
        get() = sp.getLong("drive_last_upload", 0L)
        set(v) = sp.edit().putLong("drive_last_upload", v).apply()
    fun drivePointsFileId(project: String): String? = sp.getString("drive_pts_${project.trim()}", null)?.takeIf { it.isNotBlank() }
    fun setDrivePointsFileId(project: String, id: String?) {
        val k = "drive_pts_${project.trim()}"
        if (id.isNullOrBlank()) sp.edit().remove(k).apply() else sp.edit().putString(k, id).apply()
    }
    fun clearDriveIds() {
        val ed = sp.edit().remove("drive_folder_id").remove("drive_email")
        sp.all.keys.filter { it.startsWith("drive_pts_") }.forEach { ed.remove(it) }
        ed.apply()
    }
}
