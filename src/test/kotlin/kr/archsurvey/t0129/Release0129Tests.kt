package kr.archsurvey.t0129

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.MountInfo
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripPresets
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.29 T17(G-VRS RTS3 선택 항목) · T17-2(SP60 펌웨어 안내) · T19(기기별 보정·폴 기본값) */
private class FP(
    var saved: SavedConnection = SavedConnection(null, null, NtripForm()),
    var success: Boolean = false,
    var userSetStored: Boolean = false,
    var gga: Boolean = true,
    val profiles: MutableMap<String, NtripForm> = HashMap(),
    var fetched: List<MountInfo>? = null,
) : ConnectionPlatform {
    override val link = MutableStateFlow(LinkStatus())
    override val ntrip = MutableStateFlow(NtripStatus())
    val saves = ArrayList<SavedConnection>()
    var appliedGga: Boolean? = null
    // 0.1.33-r10: 기기 이름은 저장된 종류와 맞춘다(이름이 R10 인 기기를 SP60 으로 연결하면 이제 종류를 R10 으로 맞춤)
    override fun bondedDevices() = listOf((if (saved.deviceId == DeviceProfiles.SP60_ID) "SP60" else "R10") to "AA:BB")
    override fun hasBluetoothPermission() = true
    override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
    override fun bluetoothEnabled() = true
    override fun openBluetoothSettings() {}
    override fun openAppSettings() {}
    var started = 0
    var autoOn = false
    override var autoConnect: Boolean
        get() = autoOn
        set(v) { autoOn = v }
    override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) { started++ }
    override fun stopReal() {}
    override fun loadSaved() = saved
    override fun save(s: SavedConnection) { saves += s }
    override fun hadSuccess() = success
    override fun hostProfile(host: String) = profiles[host.trim().lowercase()]
    override fun ntripOptions() = gga to true
    override fun applyNtripOptions(sendGga: Boolean, autoRetry: Boolean) { appliedGga = sendGga; gga = sendGga }
    override suspend fun fetchMounts(cfg: NtripConfig): List<MountInfo> = fetched ?: throw IllegalStateException("없음")
    override var correctionUserSet: Boolean
        get() = userSetStored
        set(v) { userSetStored = v }
}

private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
private fun cc(p: FP) = ConnectionController(p, scope())
private fun pickService(c: ConnectionController, id: String) {
    c.askService(); val i = KnownMounts.services.indexOfFirst { it.id == id }; assertTrue(i >= 0); c.ui.pick(i)
}
private fun mi(name: String, fmt: String = "RTCM 3.2", nmea: Boolean = true) = MountInfo(name, name, fmt, "GPS", nmea)
private val gvrsSaved = SavedConnection(DeviceProfiles.R10_ID, "AA:BB", NtripForm(host = Gvrs.HOST, mount = Gvrs.MOUNT, user = "id1", password = "ngii"), true)

class GvrsT17Tests {
    @Test fun rts3IsOptionalListedBeforeCustom_neverDefault() {
        val ids = KnownMounts.services.map { it.id }
        assertEquals(listOf("rts1", "rts2", "fkp", "gvrs", "custom"), ids)
        val g = KnownMounts.services.first { it.id == "gvrs" }
        assertEquals("RTS3.ngii.go.kr", g.host); assertEquals(2101, g.port); assertEquals("GVRS", g.mount)
        assertFalse("앱 기본 폼은 RTS3 아님", Gvrs.isHost(NtripForm().host))
        assertFalse(Gvrs.isHost(NtripPresets.VRS.host))
        for (d in listOf(DeviceCorrectionDefaults.R10, DeviceCorrectionDefaults.SP60)) assertFalse("기기 기본값도 RTS3 아님", Gvrs.isHost(d.host))
        assertFalse("기본 폼에 ngii 없음", NtripForm().password == Gvrs.GUIDE_PASSWORD)
        // 새로 설치(R10) → RTS1
        val c = cc(FP()); assertEquals("rts1", c.currentService().id)
    }

    @Test fun currentServiceMapsById_notListIndex() {
        val c = cc(FP(SavedConnection(DeviceProfiles.R10_ID, null, NtripForm(user = "u", password = "p"))))
        fun at(host: String, mount: String) = c.also { it.updateForm(it.form.copy(host = host, mount = mount)) }.currentService().id
        assertEquals("rts1", at("RTS1.ngii.go.kr", "VRS-RTCM31"))
        assertEquals("rts2", at("rts2.ngii.go.kr", "VRS-RTCM32"))
        assertEquals("fkp", at("RTS2.ngii.go.kr", "FKP-RTCM31"))
        assertEquals("gvrs", at("RTS3.ngii.go.kr", "GVRS"))
        assertEquals("gvrs", at("rts3.ngii.go.kr", "Seoul-Gangseo-Gaehwa"))
        assertEquals("custom", at("caster.example.com", "X"))
    }

    @Test fun knownMountsForRts3OnlyGvrs_noInventedNames() {
        val l = KnownMounts.forHost("rts3.ngii.go.kr")
        assertEquals(listOf("GVRS"), l.map { it.mountPoint })
        assertTrue(l.single().needsNmea)
        assertEquals(listOf("GVRS"), KnownMounts.forHost(" RTS3.ngii.go.kr ").map { it.mountPoint })
    }

    @Test fun selectingRts3_keepsId_prefillsNgii_gvrsMount_ggaOn_notice() {
        val p = FP(SavedConnection(DeviceProfiles.R10_ID, "AA:BB", NtripForm(user = "myid", password = "secret")), gga = false)
        val c = cc(p)
        assertFalse(c.sendGga); assertNull(c.gvrsNotice())
        pickService(c, "gvrs")
        assertEquals("RTS3.ngii.go.kr", c.form.host); assertEquals("2101", c.form.port); assertEquals("GVRS", c.form.mount)
        assertEquals("아이디 그대로", "myid", c.form.user)
        assertEquals("비밀번호 ngii 미리 채움", "ngii", c.form.password)
        assertTrue("GGA 자동 켬", c.sendGga); assertEquals(true, p.appliedGga)
        assertEquals("시범 서비스 · 공공측량 성과에는 사용 불가", c.gvrsNotice())
        // 비밀번호는 수정 가능
        c.updateForm(c.form.copy(password = "other")); assertEquals("other", c.form.password)
    }

    @Test fun leavingRts3_restoresPreviousPassword_noLeakToOtherServers() {
        val c = cc(FP(SavedConnection(DeviceProfiles.R10_ID, "AA:BB", NtripForm(user = "myid", password = "secret"))))
        pickService(c, "gvrs"); assertEquals("ngii", c.form.password)
        pickService(c, "rts1")
        assertEquals("ngii 가 RTS1 로 새지 않음", "secret", c.form.password)
        assertNull("안내는 RTS3 일 때만", c.gvrsNotice())
        // 사용자가 RTS3 비밀번호를 바꿨으면 그 값을 그대로 둔다(덮지 않음)
        pickService(c, "gvrs"); c.updateForm(c.form.copy(password = "mine")); pickService(c, "rts2")
        assertEquals("mine", c.form.password)
    }

    @Test fun rts3WithHostProfile_usesSavedPasswordAndMount() {
        val p = FP(SavedConnection(DeviceProfiles.R10_ID, "AA:BB", NtripForm(user = "myid", password = "secret")))
        p.profiles["rts3.ngii.go.kr"] = NtripForm(host = Gvrs.HOST, port = "2101", mount = "Yechon-Gaepo", user = "myid", password = "pw3")
        val c = cc(p); pickService(c, "gvrs")
        assertEquals("pw3", c.form.password); assertEquals("Yechon-Gaepo", c.form.mount)
    }

    @Test fun gvrsGgaOff_warnsKeepsOn_andBlocksSaveConnectAuto() {
        val p = FP(gvrsSaved, gga = true, success = true)
        val c = cc(p)
        c.changeSendGga(false)
        assertTrue("GVRS 는 GGA 끌 수 없음", c.sendGga)
        assertEquals(Gvrs.GGA_REQUIRED, c.ui.toast)
        // 저장된 옵션이 꺼져 있던 경우(이전 버전) → 문제로 보고 막는다
        val p2 = FP(gvrsSaved, gga = false, success = true); val c2 = cc(p2)
        assertFalse(c2.sendGga)
        assertTrue(c2.connectProblems().contains(Gvrs.GGA_REQUIRED))
        c2.saveOnly(); assertTrue(c2.ui.toast!!.contains(Gvrs.GGA_REQUIRED))
        c2.connect(); assertNotNull(c2.notice); assertTrue(c2.notice!!.action.contains(Gvrs.GGA_REQUIRED)); assertEquals(0, p2.started)
        // 0.1.32(의도된 변경): 자동 연결은 수신기만 하고 이번엔 보정을 끈다(GVRS 를 GGA 없이 접속하지 않음 · 예전엔 수신기 연결 자체를 조용히 건너뜀)
        val p3 = FP(gvrsSaved, gga = false, success = true).also { it.autoOn = true }; val c3 = cc(p3)
        assertTrue(c3.autoConnectOnLaunch()); assertEquals(1, p3.started); assertTrue(c3.ui.toast!!.contains(Gvrs.GGA_REQUIRED))
        // 다시 켜면 연결 진행
        c2.changeSendGga(true); assertTrue(c2.connectProblems().isEmpty())
        c2.connect(); assertEquals(1, p2.started)
    }

    @Test fun regionMount_ggaOptional_pickGvrsTurnsGgaOn() {
        val p = FP(SavedConnection(DeviceProfiles.R10_ID, "AA:BB", NtripForm(host = Gvrs.HOST, mount = "Seoul-Gangseo-Gaehwa", user = "id1", password = "ngii"), true), gga = true)
        val c = cc(p)
        c.changeSendGga(false); assertFalse("지역명 마운트는 GGA 선택", c.sendGga)
        assertTrue(c.connectProblems().isEmpty())
        c.pickMount("GVRS"); assertTrue("GVRS 고르면 GGA 켬", c.sendGga)
    }

    @Test fun mountNotesForRts3() {
        val c = cc(FP(gvrsSaved))
        assertEquals(Gvrs.GVRS_NOTE, c.mountGoodNote(mi("GVRS", "RTCM 3.2")))
        assertTrue(c.mountGoodNote(mi("GVRS", "RTCM 3.2"))!!.contains("GGA"))
        assertEquals(Gvrs.REGION_NOTE, c.mountGoodNote(mi("Seoul-Gangseo-Gaehwa", "RTCM 3.2")))
        assertEquals(Gvrs.REGION_NOTE, c.mountGoodNote(mi("Yechon-Gaepo", "unknown")))
        // RTS1 에서는 기존 문구 그대로(지역 문구 없음)
        val c1 = cc(FP(SavedConnection(DeviceProfiles.R10_ID, "AA:BB", NtripForm(user = "u", password = "p"))))
        assertNotEquals(Gvrs.REGION_NOTE, c1.mountGoodNote(mi("VRS-RTCM32")))
    }

    @Test fun sp60OnRts3ShowsRtcmLine_r10DoesNot() {
        val c = cc(FP(gvrsSaved.copy(deviceId = DeviceProfiles.SP60_ID)))
        assertNotNull(c.gvrsSp60Line()); assertTrue(c.gvrsSp60Line()!!.contains("3.2"))
        assertNull(cc(FP(gvrsSaved)).gvrsSp60Line())
    }

    @Test fun authHints_rts3Only_noAutoRetry() {
        val a401 = NtripStatus(NtripState.AUTH_FAILED, "401 Unauthorized")
        val a403 = NtripStatus(NtripState.AUTH_FAILED, "403 Forbidden")
        val g401 = ErrorCards.ntripCard(a401, "RTS3.ngii.go.kr")!!
        assertTrue(g401.action.contains("RTS1·RTS2 가입 ID인지, 비밀번호 ngii인지 확인")); assertFalse(g401.autoRetry)
        val g403 = ErrorCards.ntripCard(a403, "rts3.ngii.go.kr")!!
        assertTrue(g403.action.contains(Gvrs.AUTH_HINT)); assertFalse(g403.autoRetry); assertEquals("NT-403", g403.code)
        for (h in listOf("", "RTS1.ngii.go.kr", "RTS2.ngii.go.kr")) {
            // 0.1.32(의도된 변경): RTS1·2 401 도 국토지리정보원 공식 FAQ(비밀번호 ngii)를 알린다. G-VRS 전용 문구(RTS1·RTS2 가입 ID인지…)는 여전히 RTS3 만
            assertTrue(ErrorCards.ntripCard(a401, h)!!.action.contains("공식 FAQ"))
            assertFalse(ErrorCards.ntripCard(a401, h)!!.action.contains("RTS1·RTS2 가입 ID인지"))
            assertFalse(ErrorCards.ntripCard(a403, h)!!.action.contains("ngii"))
        }
        assertEquals(ErrorCards.ntripCard(a401), ErrorCards.ntripCard(a401, "RTS1.ngii.go.kr"))
        for (card in listOf(g401, g403)) assertFalse("「시범 신청」 문구 없음", (card.title + card.action).contains("신청"))
        val viaFrom = ErrorCards.from(LinkStatus(), a401, true, "RTS3.ngii.go.kr").single()
        assertTrue(viaFrom.action.contains(Gvrs.AUTH_HINT))
        assertTrue(cc(FP(gvrsSaved)).let { it.cards(); true })
    }
}

class Sp60FirmwareT17Tests {
    @Test fun noticeOnlyBelow11mmOnSp60() {
        assertNotNull(Sp60Firmware.notice(DeviceProfiles.SP60, 0.0109))
        assertTrue(Sp60Firmware.notice(DeviceProfiles.SP60, 0.0109)!!.startsWith("SP60 펌웨어 5.26 이상 필요"))
        assertNull(Sp60Firmware.notice(DeviceProfiles.SP60, 0.011))
        assertNull(Sp60Firmware.notice(DeviceProfiles.SP60, 0.02))
        assertNull(Sp60Firmware.notice(DeviceProfiles.R10, 0.0109))
    }
    @Test fun defaultThreshold20mmNeverTriggers_andNeverBlocks() {
        val p = FP(SavedConnection(DeviceProfiles.SP60_ID, "AA:BB", NtripForm(user = "u", password = "p"), true))
        val c = cc(p)
        assertEquals(0.02, c.sigmaThresholdM, 0.0); assertNull(c.firmwareNotice())
        c.sigmaThresholdM = 0.010
        assertNotNull(c.firmwareNotice())
        c.connect(); assertEquals("안내만 — 연결은 막지 않음", 1, p.started)
    }
}

class DeviceDefaultsT19Tests {
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    @Test fun freshInstallR10_getsRts1Rtcm34_persisted() {
        val p = FP(); val c = cc(p)
        assertEquals("RTS1.ngii.go.kr", c.form.host); assertEquals("VRS-RTCM34", c.form.mount)
        assertEquals("VRS-RTCM34", p.saves.last().ntrip.mount)
        assertEquals("VRS-RTCM34", c.defaultMountApplied)
    }

    @Test fun firstPickSp60_getsRts2Rtcm32_andBackToR10() {
        val p = FP(); val c = cc(p)
        c.selectProfile(DeviceProfiles.SP60)
        assertEquals("RTS2.ngii.go.kr", c.form.host); assertEquals("VRS-RTCM32", c.form.mount)
        assertEquals("rts2", c.currentService().id)
        c.selectProfile(DeviceProfiles.R10)
        assertEquals("RTS1.ngii.go.kr", c.form.host); assertEquals("VRS-RTCM34", c.form.mount)
        assertFalse("기본값 적용은 사용자 설정이 아님", p.userSetStored)
    }

    @Test fun userValuesAreNeverOverridden() {
        // 1) 서비스를 직접 고름
        val p = FP(); val c = cc(p)
        pickService(c, "rts2"); assertTrue(p.userSetStored)
        c.selectProfile(DeviceProfiles.SP60); c.selectProfile(DeviceProfiles.R10)
        assertEquals("RTS2.ngii.go.kr", c.form.host); assertEquals("VRS-RTCM32", c.form.mount)
        // 2) 마운트를 직접 고름(같은 서버여도)
        val p2 = FP(); val c2 = cc(p2); c2.pickMount("VRS-RTCM31")
        c2.selectProfile(DeviceProfiles.SP60)
        assertEquals("RTS1.ngii.go.kr", c2.form.host); assertEquals("VRS-RTCM31", c2.form.mount)
        // 3) 저장된 「사용자 설정」 표시
        val p3 = FP(SavedConnection(DeviceProfiles.R10_ID, null, NtripForm()), userSetStored = true); val c3 = cc(p3)
        c3.selectProfile(DeviceProfiles.SP60); assertEquals("VRS-RTCM31", c3.form.mount)
        // 4) 연결 성공 이력
        val c4 = cc(FP(SavedConnection(DeviceProfiles.R10_ID, null, NtripForm()), success = true))
        c4.selectProfile(DeviceProfiles.SP60); assertEquals("VRS-RTCM31", c4.form.mount)
        // 5) 직접 입력한 다른 서버(이전 버전에서 바꾼 값)
        val c5 = cc(FP(SavedConnection(DeviceProfiles.R10_ID, null, NtripForm(host = "caster.example.com", mount = "M1"))))
        c5.selectProfile(DeviceProfiles.SP60); assertEquals("caster.example.com", c5.form.host); assertEquals("M1", c5.form.mount)
        // 6) RTS3 를 고른 사용자 → 기기 바꿔도 RTS3 유지
        val c6 = cc(FP(gvrsSaved)); c6.selectProfile(DeviceProfiles.SP60); assertEquals(Gvrs.HOST, c6.form.host)
    }

    @Test fun existingInstallWithDeviceChosen_notChangedAtLaunch() {
        val c = cc(FP(SavedConnection(DeviceProfiles.SP60_ID, "AA:BB", NtripForm(user = "u", password = "p"))))
        assertEquals("VRS-RTCM31", c.form.mount)
    }

    @Test fun idOrPasswordEditsAloneDoNotCountAsUserSet() {
        val p = FP(); val c = cc(p)
        c.updateForm(c.form.copy(user = "u")); c.updateForm(c.form.copy(password = "p"))
        assertFalse(p.userSetStored)
        c.selectProfile(DeviceProfiles.SP60); assertEquals("VRS-RTCM32", c.form.mount)
        assertEquals("u", c.form.user); assertEquals("p", c.form.password)
        c.updateForm(c.form.copy(port = "2102")); assertTrue("포트 변경은 사용자 설정", p.userSetStored)
    }

    @Test fun defaultMountMissingFromLiveList_isReported_notInvented() {
        val p = FP(fetched = listOf(mi("VRS-RTCM31", "RTCM 3.1"), mi("VRS-RTCM32", "RTCM 3.2"))); val c = cc(p)
        c.openMountPicker()
        assertEquals(MountLoad.OK, c.mountLoad)
        assertTrue(c.defaultMountMissing); assertTrue(c.ui.toast!!.contains("VRS-RTCM34"))
        assertEquals("이름을 바꾸지 않음(직접 고르게)", "VRS-RTCM34", c.form.mount)
        val p2 = FP(fetched = listOf(mi("VRS-RTCM34", "RTCM 3.4"))); val c2 = cc(p2); c2.openMountPicker()
        assertFalse(c2.defaultMountMissing)
    }

    private fun sc() = SurveyController(FakeGnssSource(FakeScenarios.allFixed()), db, scope())

    @Test fun poleDefaultsPerDevice() {
        assertEquals(1.6, DevicePoleDefaults.forModel("TRIMBLE_R10")!!, 0.0)
        assertEquals(1.8, DevicePoleDefaults.forModel("SPECTRA_SP60")!!, 0.0)
        assertNull(DevicePoleDefaults.forModel("OTHER"))
        assertEquals("SPECTRA_SP60", AntennaDb.antennaIdForProfile(DeviceProfiles.SP60_ID))
        val c = sc()
        c.setAntennaModel("TRIMBLE_R10"); assertEquals(1.6, c.antenna.inputValueM, 1e-9); assertEquals(AntennaSource.DEFAULT, c.antenna.source)
        c.setAntennaModel("SPECTRA_SP60"); assertEquals(1.8, c.antenna.inputValueM, 1e-9); assertEquals("bottom_of_receiver_arp", c.antenna.refPointId)
        assertEquals(AntennaSource.DEFAULT, c.antenna.source)
        assertTrue(AntennaMath.CHIPS_M.contains(1.6))
    }

    @Test fun userPoleKeptAcrossDeviceSwitch() {
        val c = sc(); val saved = ArrayList<JobConfig>(); c.onJobConfigChanged = { saved += it }
        c.setPoleHeight(2.0); assertEquals(AntennaSource.USER, c.antenna.source)
        c.setAntennaModel("SPECTRA_SP60")
        assertEquals("사용자 값 유지", 2.0, c.antenna.inputValueM, 1e-9); assertEquals(AntennaSource.USER, c.antenna.source)
        assertTrue(saved.last().poleUserSet)
        c.setAntennaModel("TRIMBLE_R10"); assertEquals(2.0, c.antenna.inputValueM, 1e-9)
    }

    // ---------------- UX-0129-1: 기존 작업은 저장된 폴 높이 그대로 ----------------

    private class Jobs : JobPlatform {
        override var current = "작업1"; val names = linkedSetOf("작업1")
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

    /** 앱(SurveyViewModel)처럼 배선: 연결 화면 기기 선택 → 안테나 모델 + 폴 안내 문구 */
    private class App(profile: String = DeviceProfiles.R10_ID, jobs: Map<String, JobConfig> = emptyMap(), db: Map<String, kr.archsurvey.core.AntennaModel>) {
        val c = SurveyController(FakeGnssSource(FakeScenarios.allFixed()), db, scope())
        val cfgs = MemoryJobConfigPlatform().also { m -> jobs.forEach { (k, v) -> m.save(k, v) } }
        val jp = Jobs().also { it.names += jobs.keys }
        val cc = ConnectionController(FP(SavedConnection(profile, "AA:BB", NtripForm(user = "u", password = "p"), true)), scope())
        val jc: JobController
        init {
            cc.ui = c.ui; c.connection = cc
            cc.onProfileChanged = { p -> AntennaDb.antennaIdForProfile(p.id)?.let { c.setAntennaModel(it) } }
            AntennaDb.antennaIdForProfile(cc.profile.id)?.let { c.setAntennaModel(it) } // 시작 시(안내 없음)
            c.settingsState = SettingsState(c, Sett())
            jc = JobController(c, jp, scope(), cfgs); c.jobs = jc
            cfgs.load(jp.current)?.let { c.applyJobConfig(it) } ?: cfgs.save(jp.current, c.jobConfig()) // 직전 작업 복원
            c.onJobConfigChanged = { cfgs.save(jp.current, it) }
        }
    }

    @Test fun existingJobUntouched2m_staysOnOpen() {
        // 앱 시작: 직전 작업(손대지 않은 2.0 m) — R10 이어도 1.6 으로 바뀌지 않음
        val a = App(jobs = mapOf("작업1" to JobConfig(antennaModelId = "TRIMBLE_R10", poleM = 2.0, poleUserSet = false)), db = db)
        assertEquals(2.0, a.c.antenna.inputValueM, 1e-9); assertEquals(AntennaSource.DEFAULT, a.c.antenna.source)
        assertEquals(2.0, a.cfgs.load("작업1")!!.poleM, 1e-9)
        // SP60 작업(2.0)을 열어도 그대로
        a.cfgs.save("옛작업", JobConfig(antennaModelId = "SPECTRA_SP60", poleM = 2.0, poleUserSet = false)); a.jp.names += "옛작업"
        a.jc.open("옛작업")
        assertEquals(2.0, a.c.antenna.inputValueM, 1e-9)
        a.jc.open("작업1"); assertEquals(2.0, a.c.antenna.inputValueM, 1e-9)
        // 직접 applyJobConfig 도 같음
        val c = sc(); c.setAntennaModel("SPECTRA_SP60")
        c.applyJobConfig(JobConfig(antennaModelId = "SPECTRA_SP60", poleM = 2.0, poleUserSet = false))
        assertEquals(2.0, c.antenna.inputValueM, 1e-9)
        c.applyJobConfig(JobConfig(antennaModelId = "SPECTRA_SP60", poleM = 2.0, poleUserSet = true))
        assertEquals(AntennaSource.USER, c.antenna.source)
        c.applyJobConfig(JobConfig(antennaModelId = "TRIMBLE_R10", poleM = 2.0, poleUserSet = false))
        assertEquals(2.0, c.antenna.inputValueM, 1e-9); assertEquals("USER 표시가 따라오지 않음", AntennaSource.DEFAULT, c.antenna.source)
    }

    @Test fun deviceSwitchUntouched_changesPoleAndToasts() {
        // 새로 설치(저장된 작업 없음) R10 → 1.6, SP60 으로 바꾸면 1.8 + 안내
        val a = App(db = db)
        assertEquals(1.6, a.c.antenna.inputValueM, 1e-9)
        a.cc.selectProfile(DeviceProfiles.SP60)
        assertEquals(1.8, a.c.antenna.inputValueM, 1e-9)
        assertTrue(a.c.ui.toast!!, a.c.ui.toast!!.startsWith("폴 높이 1.6 → 1.8 m(SP60 기본)"))
        assertEquals(1.8, a.cfgs.load("작업1")!!.poleM, 1e-9)
        a.cc.selectProfile(DeviceProfiles.R10)
        assertTrue(a.c.ui.toast!!.startsWith("폴 높이 1.8 → 1.6 m(R10 기본)"))
        // 기존 작업(손대지 않은 2.0)에서 기기를 바꾸면 그때만 바뀌고 안내
        val b = App(jobs = mapOf("작업1" to JobConfig(antennaModelId = "TRIMBLE_R10", poleM = 2.0)), db = db)
        assertEquals(2.0, b.c.antenna.inputValueM, 1e-9)
        b.cc.selectProfile(DeviceProfiles.SP60)
        assertEquals(1.8, b.c.antenna.inputValueM, 1e-9)
        assertTrue(b.c.ui.toast!!.startsWith("폴 높이 2.0 → 1.8 m(SP60 기본)"))
        assertEquals("폴 높이 1.6 → 1.8 m(SP60 기본)", DevicePoleDefaults.changeNotice(1.6, 1.8, "SPECTRA_SP60"))
    }

    @Test fun newJobGetsDeviceDefault_oldJobKeepsValue() {
        val a = App(jobs = mapOf("작업1" to JobConfig(antennaModelId = "TRIMBLE_R10", poleM = 2.0)), db = db)
        assertEquals(2.0, a.c.antenna.inputValueM, 1e-9)
        a.jc.openNew(); a.jc.newName = "새작업"; a.jc.create()
        assertEquals("새 작업 = R10 기본", 1.6, a.c.antenna.inputValueM, 1e-9)
        assertEquals(1.6, a.cfgs.load("새작업")!!.poleM, 1e-9)
        a.jc.open("작업1"); assertEquals("옛 작업은 2.0 그대로", 2.0, a.c.antenna.inputValueM, 1e-9)
        // SP60 에서 새 작업
        a.cc.selectProfile(DeviceProfiles.SP60)
        a.jc.openNew(); a.jc.newName = "새작업2"; a.jc.create()
        assertEquals(1.8, a.cfgs.load("새작업2")!!.poleM, 1e-9)
        // 사용자가 정한 높이는 새 작업에도 그대로(기존 동작)
        a.c.setPoleHeight(2.1)
        a.jc.openNew(); a.jc.newName = "새작업3"; a.jc.create()
        assertEquals(2.1, a.cfgs.load("새작업3")!!.poleM, 1e-9); assertTrue(a.cfgs.load("새작업3")!!.poleUserSet)
    }

    @Test fun userSetPoleUnchangedOnSwitch_noToast() {
        val a = App(db = db)
        a.c.setPoleHeight(2.0); a.c.ui.hideToast()
        a.cc.selectProfile(DeviceProfiles.SP60)
        assertEquals(2.0, a.c.antenna.inputValueM, 1e-9); assertEquals(AntennaSource.USER, a.c.antenna.source)
        assertFalse("폴 안내 없음", a.c.ui.toast.orEmpty().contains("폴 높이"))
        // 사용자 값 작업을 열고 바꿔도 같음
        val b = App(jobs = mapOf("작업1" to JobConfig(antennaModelId = "TRIMBLE_R10", poleM = 2.0, poleUserSet = true)), db = db)
        b.cc.selectProfile(DeviceProfiles.SP60)
        assertEquals(2.0, b.c.antenna.inputValueM, 1e-9); assertFalse(b.c.ui.toast.orEmpty().contains("폴 높이"))
    }

    @Test fun heightDefaultsUnchanged_receiverNotEgm96() {
        assertEquals(GeoidModel.RECEIVER, sc().geoid)
        assertEquals(GeoidModel.RECEIVER, JobConfig().geoid)
    }
}
