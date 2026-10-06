package kr.archsurvey.t0129

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * QA-0129-1 (UX-0129-1 과 같은 원인): 0.1.28 에서 폴 높이를 직접 1.5 m 로 정한 뒤 기기를 SP60 으로 바꾸면
 * 0.1.28 은 출처를 USER → DEFAULT 로 풀어 저장했다(poleM 1.5, poleUser=false, 모델 SPECTRA_SP60).
 * 0.1.29 에서 그 작업을 열면 1.5 m 그대로여야 한다(1.8 로 덮으면 안 됨).
 * 이 파일은 cc1b398(UX-0129-1 수정 전)에도 컴파일된다 — 수정 전 실패 증거용.
 */
class LegacyPoleQa01291Test {
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class Plat(val deviceId: String) : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        override fun bondedDevices() = listOf("SP60" to "AA:BB")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {}
        override fun stopReal() {}
        override fun loadSaved() = SavedConnection(deviceId, "AA:BB", NtripForm(user = "u", password = "p"), true)
        override fun save(s: SavedConnection) {}
    }
    private class Jobs(names: Collection<String>) : JobPlatform {
        override var current = "작업1"; val names = linkedSetOf("작업1").also { it += names }
        override suspend fun list() = names.map { JobInfo(it, 0, 0, 0, null, null) }
        override suspend fun info(name: String) = JobInfo(name, 0, 0, 0, null, null)
        override suspend fun create(name: String) { names += name; current = name }
        override suspend fun open(name: String) { current = name }
        override suspend fun delete(name: String): String { names -= name; return "b.zip" }
    }
    private class Sett : SettingsPlatform {
        override var zone = KoreanTmZone.CENTRAL; override var poleRight = true; override var sound = true; override var vibration = true; override var keepScreenOn = true
        override var epochs = 3
    }

    /** 0.1.28 이 남긴 작업 설정 그대로(저장 형식 문자열로) — 폴 1.5, 사용자 표시 풀림, SP60 */
    private val legacyEncoded: String = JobConfig(antennaModelId = "SPECTRA_SP60", poleM = 1.5, refPointId = "bottom_of_receiver_arp", poleUserSet = false).encode()

    @Test fun qa0129_1_legacyUserPole1_5_resetToDefaultBy0128DeviceSwitch_keeps1_5OnOpen() {
        // 0.1.28 이 남긴 상태: 사용자 1.5 m 지정(USER) 뒤 기기 SP60 으로 바꿈 → proposeDefault 가 출처를 DEFAULT 로 → 저장 = 1.5 · poleUser=false
        // (0.1.28 코드는 이 트리에 없어 저장 형식 문자열로 재현. 저장 형식은 0.1.28 과 같음)
        val stored = JobConfig.decode(legacyEncoded)!!
        assertEquals(1.5, stored.poleM, 1e-9); assertFalse("0.1.28 이 풀어 둔 표시", stored.poleUserSet); assertEquals("SPECTRA_SP60", stored.antennaModelId)

        // 0.1.29 앱 시작(SurveyViewModel 순서): 연결 프로필 SP60 → 안테나 모델 맞춤 → 직전 작업 복원
        val c = SurveyController(FakeGnssSource(FakeScenarios.allFixed()), db, scope())
        val cfgs = MemoryJobConfigPlatform(); cfgs.save("작업1", stored); cfgs.save("다른작업", JobConfig(antennaModelId = "SPECTRA_SP60", poleM = 2.0))
        val jp = Jobs(listOf("다른작업"))
        val cc = ConnectionController(Plat(DeviceProfiles.SP60_ID), scope())
        cc.ui = c.ui; c.connection = cc
        cc.onProfileChanged = { p -> AntennaDb.antennaIdForProfile(p.id)?.let { c.setAntennaModel(it) } }
        AntennaDb.antennaIdForProfile(cc.profile.id)?.let { c.setAntennaModel(it) }
        c.settingsState = SettingsState(c, Sett())
        val jc = JobController(c, jp, scope(), cfgs); c.jobs = jc
        cfgs.load(jp.current)?.let { c.applyJobConfig(it) }
        c.onJobConfigChanged = { cfgs.save(jp.current, it) }
        assertEquals("앱 시작 시 직전 작업 1.5 m 그대로(1.8 아님)", 1.5, c.antenna.inputValueM, 1e-9)

        // 다른 작업 갔다가 다시 열어도 1.5 / 2.0 각자 그대로
        jc.open("다른작업"); assertEquals("손대지 않은 2.0 도 그대로", 2.0, c.antenna.inputValueM, 1e-9)
        jc.open("작업1"); assertEquals(1.5, c.antenna.inputValueM, 1e-9)
        assertEquals("저장값도 덮이지 않음", 1.5, cfgs.load("작업1")!!.poleM, 1e-9)
    }
}
