package kr.archsurvey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.map.*
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.12: V월드 URL·지오이드(EGM96)·작업별 설정 저장/복원·폴 높이 10 cm. JVM 시험(실기기·실제 V월드 키 아님) */
class Feature0112Test {
    private val db by lazy { AntennaDb.parse(File("src/main/assets/antenna_db.json").readText()) }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // ---------- 지오이드 ----------
    @Test fun egm96MatchesPyprojOracleWithin1mm() {
        val rows = File("src/test/resources/geoid/egm96_oracle.csv").readLines().drop(1).filter { it.isNotBlank() }
        assertTrue(rows.size >= 40)
        for (r in rows) {
            val (la, lo, n) = r.split(',').map { it.toDouble() }
            assertEquals("($la,$lo)", n, Egm96Korea.undulation(la, lo)!!, 0.001)
        }
    }
    @Test fun egm96OutsideKoreaIsNull() {
        assertNull(Egm96Korea.undulation(45.0, 127.0)); assertNull(Egm96Korea.undulation(37.0, 140.0)); assertNull(Egm96Korea.undulation(Double.NaN, 127.0))
    }
    @Test fun orthoHeightPerModel() {
        val n = Egm96Korea.undulation(37.5, 127.0)!!
        assertEquals(50.0, GeoidHeights.apcOrtho(GeoidModel.RECEIVER, 37.5, 127.0, 50.0, 25.0).heightM!!, 1e-9)
        assertEquals(75.0, GeoidHeights.apcOrtho(GeoidModel.NONE, 37.5, 127.0, 50.0, 25.0).heightM!!, 1e-9)
        val e = GeoidHeights.apcOrtho(GeoidModel.EGM96, 37.5, 127.0, 50.0, 25.0)
        assertEquals(GeoidModel.EGM96, e.usedModel); assertEquals(75.0 - n, e.heightM!!, 1e-9); assertNull(e.note)
        // 지오이드 분리가 없으면 EGM96 을 못 쓴다 → 수신기 해발고 + 이유
        val miss = GeoidHeights.apcOrtho(GeoidModel.EGM96, 37.5, 127.0, 50.0, null)
        assertEquals(GeoidModel.RECEIVER, miss.usedModel); assertEquals(50.0, miss.heightM!!, 1e-9); assertNotNull(miss.note)
    }
    @Test fun measuredPointRecordsGeoidModelAndHeight() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val c = SurveyController(f, db, scope(), epochsPerPoint = 3)
        c.geoid = GeoidModel.EGM96
        repeat(2) { f.emitNext() }; c.onMeasure(); repeat(6) { f.emitNext() }
        val p = c.points.single()
        assertEquals("EGM96", p.heightModel)
        val n = Egm96Korea.undulation(p.lat, p.lon)!!
        assertEquals(p.apcMslM!! + p.geoidSepM!! - n - p.antenna.apcHeightM, p.groundMslM!!, 1e-6)
    }

    // ---------- 폴 높이 10 cm ----------
    @Test fun poleStepsAre10cm() {
        assertEquals(2.1, AntennaMath.stepPole(2.0, 10), 1e-9)
        assertEquals(1.9, AntennaMath.stepPole(2.0, -10), 1e-9)
        assertEquals(2.1, AntennaMath.stepPole(2.0, 1), 1e-9) // 10 의 배수가 아니면 한 칸
        assertEquals(2.3, AntennaMath.stepPole(2.0, 30), 1e-9)
        assertEquals(2.0, AntennaMath.stepPole(2.03, 0), 1e-9) // 지금 값을 0.1 m 로 맞춤
        assertEquals(0.0, AntennaMath.stepPole(0.0, -10), 1e-9)
        assertEquals(5.0, AntennaMath.stepPole(4.95, 10), 1e-9)
        assertEquals(1.8, AntennaMath.snapPole(1.83), 1e-9); assertEquals(1.9, AntennaMath.snapPole(1.87), 1e-9)
        val c = SurveyController(FakeGnssSource(FakeScenarios.allFixed()), db, scope())
        repeat(3) { c.stepAntennaCm(AntennaMath.POLE_STEP_CM) }
        assertEquals(2.3, c.antenna.inputValueM, 1e-9); assertEquals(AntennaSource.USER, c.antenna.source)
        c.setPoleHeight(1.77); assertEquals(1.8, c.antenna.inputValueM, 1e-9)
    }

    // ---------- V월드 ----------
    @Test fun vworldUrlsAndRedaction() {
        val key = "ABCD1234-TEST-KEY"
        val u = VWorldUrls.cadastral(key, "http://localhost", 17, 111757, 50743)
        assertTrue(u.startsWith("https://api.vworld.kr/req/wms?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0"))
        assertTrue(u.contains("LAYERS=lp_pa_cbnd_bubun,lp_pa_cbnd_bonbun&STYLES=lp_pa_cbnd_bubun,lp_pa_cbnd_bonbun"))
        assertTrue(u.contains("CRS=EPSG:900913")); assertTrue(u.contains("TRANSPARENT=TRUE")); assertTrue(u.contains("DOMAIN=http%3A%2F%2Flocalhost"))
        assertTrue(VWorldUrls.cadastral(key, "", 17, 1, 1, lineOnly = true).contains("STYLES=lp_pa_cbnd_bubun_line,lp_pa_cbnd_bonbun_line"))
        assertFalse(VWorldUrls.cadastral(key, "", 17, 1, 1).contains("DOMAIN="))
        assertEquals("https://api.vworld.kr/req/wmts/1.0.0/$key/Satellite/18/3/5.jpeg", VWorldUrls.satellite(key, 18, 5, 3))
        assertEquals("https://api.vworld.kr/req/wmts/1.0.0/$key/Base/18/3/5.png", VWorldUrls.base(key, 18, 5, 3))
        assertFalse(VWorldUrls.redact(u, key).contains(key)); assertTrue(VWorldUrls.redact(u, "").contains("KEY=****"))
        assertFalse(VWorldAccess(key, "d").toString().contains(key))
    }
    @Test fun layersNeedKeyAndFollowBase() {
        assertTrue(MapLayers.resolve(MapBase.CADASTRAL, VWorldAccess("", "")).isEmpty())
        assertTrue(MapLayers.resolve(MapBase.NONE, VWorldAccess("k12345678", "")).isEmpty())
        assertEquals(listOf("base", "cad"), MapLayers.resolve(MapBase.CADASTRAL, VWorldAccess("k12345678", "")).map { it.id })
        assertEquals(listOf("sat", "cad"), MapLayers.resolve(MapBase.SATELLITE_CADASTRAL, VWorldAccess("k12345678", "")).map { it.id })
        assertEquals(listOf("sat"), MapLayers.resolve(MapBase.SATELLITE, VWorldAccess("k12345678", "")).map { it.id })
        assertNotNull(MapLayers.fallbackMessage(MapBase.CADASTRAL, "")); assertNull(MapLayers.fallbackMessage(MapBase.CADASTRAL, "k"))
    }
    @Test fun tileZoomFromScale() {
        assertEquals(19, MapLayers.zoomFor(33.0, 37.5))  // 12 dp/m × 2.75 → 19 로 묶음
        assertEquals(17, MapLayers.zoomFor(1.4, 37.5))
        assertEquals(6, MapLayers.zoomFor(0.0, 37.5))
        // 서울 시청 z=16 타일 번호(OSM 슬리피 타일과 같은 수식)
        assertEquals(55883, TileMath.tileIndex(TileMath.lonToX(126.9780, 16)))
        assertEquals(25378, TileMath.tileIndex(TileMath.latToY(37.5665, 16)))
    }
    @Test fun controllerTilesOnlyWithKeyAndBase() {
        val c = SurveyController(FakeGnssSource(FakeScenarios.allFixed()), db, scope())
        c.mapBase = MapBase.CADASTRAL
        assertTrue(c.mapTiles().isEmpty()); assertNotNull(c.mapNotice())
        c.mapKeyProvider = { VWorldAccess("k12345678", "") }
        assertEquals(2, c.mapTiles().size); assertNull(c.mapNotice())
    }
    @Test fun vworldSettingsSaveKeepsKeyOutOfState() {
        val p = MemoryMapKeyPlatform()
        val v = VWorldSettingsState(p, scope(), Dispatchers.Unconfined)
        v.keyInput = "short"; assertTrue(v.save().contains("이상"))
        assertEquals("", p.key)
        v.keyInput = "ABCDEFGH-1234-5678"; v.domainInput = " http://localhost "; v.lineOnly = true
        v.save()
        assertEquals("ABCDEFGH-1234-5678", p.key); assertEquals("http://localhost", p.domain); assertTrue(p.lineOnly)
        assertEquals("", v.keyInput); assertTrue(v.hasKey)
        v.keyInput = ""; v.save(); assertEquals("ABCDEFGH-1234-5678", p.key) // 빈 칸이면 기존 키 유지
        v.clearKey(); assertEquals("", p.key); assertFalse(v.hasKey)
    }

    // ---------- 작업별 설정 ----------
    @Test fun jobConfigRoundTrip() {
        val cfg = JobConfig(5187, GeoidModel.EGM96, MapBase.SATELLITE_CADASTRAL, "SPECTRA_SP60", 1.8, "bottom_of_receiver_arp", 5, true)
        assertEquals(cfg, JobConfig.decode(cfg.encode()))
        assertNull(JobConfig.decode("")); assertNull(JobConfig.decode(null))
        val junk = JobConfig.decode("epsg=9999;geoid=X;map=?;pole=2.04;epochs=99;zzz=1")!!
        assertEquals(5186, junk.epsg); assertEquals(GeoidModel.RECEIVER, junk.geoid); assertEquals(MapBase.NONE, junk.mapBase); assertEquals(2.0, junk.poleM, 1e-9); assertEquals(30, junk.epochs)
        assertFalse(cfg.encode().contains("key", ignoreCase = true))
    }
    @Test fun zoneAuto() {
        assertEquals(KoreanTmZone.WEST, ZoneAuto.fromLon(125.5)); assertEquals(KoreanTmZone.CENTRAL, ZoneAuto.fromLon(127.0))
        assertEquals(KoreanTmZone.EAST, ZoneAuto.fromLon(129.2)); assertEquals(KoreanTmZone.EAST_SEA, ZoneAuto.fromLon(130.9)); assertNull(ZoneAuto.fromLon(null))
    }

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
    }

    @Test fun newJobAppliesAllSettingsAtOnceAndSwitchingRestoresEach() {
        val c = SurveyController(FakeGnssSource(FakeScenarios.allFixed()), db, scope())
        val cfgs = MemoryJobConfigPlatform(); val jobs = Jobs()
        c.settingsState = SettingsState(c, Sett())
        val jc = JobController(c, jobs, scope(), cfgs); c.jobs = jc
        c.onJobConfigChanged = { cfgs.save(jobs.current, it) }
        c.stepAntennaCm(-10) // 작업1: 1.9 m
        assertEquals(1.9, cfgs.load("작업1")!!.poleM, 1e-9)
        // 새 작업: 이름 + 지역 5187 + 배경맵 + Geoid 를 한 번에
        jc.openNew(); jc.newName = "동부-1"; jc.formZone = KoreanTmZone.EAST; jc.formMap = MapBase.CADASTRAL; jc.formGeoid = GeoidModel.EGM96
        jc.create()
        assertEquals("동부-1", c.projectName); assertEquals(5187, c.zone.epsg); assertEquals(MapBase.CADASTRAL, c.mapBase); assertEquals(GeoidModel.EGM96, c.geoid)
        assertEquals(5187, cfgs.load("동부-1")!!.epsg)
        // 직전 작업으로 돌아가면 그 작업 설정이 복원
        jc.open("작업1")
        assertEquals(5186, c.zone.epsg); assertEquals(MapBase.NONE, c.mapBase); assertEquals(GeoidModel.RECEIVER, c.geoid); assertEquals(1.9, c.antenna.inputValueM, 1e-9)
        jc.open("동부-1"); assertEquals(5187, c.zone.epsg); assertEquals(GeoidModel.EGM96, c.geoid)
        // 앱 재시작 흉내: 새 컨트롤러가 저장된 설정을 적용
        val c2 = SurveyController(FakeGnssSource(FakeScenarios.allFixed()), db, scope())
        c2.applyJobConfig(cfgs.load(jobs.current)!!)
        assertEquals(5187, c2.zone.epsg); assertEquals(MapBase.CADASTRAL, c2.mapBase); assertEquals(GeoidModel.EGM96, c2.geoid)
    }

    @Test fun zoneChangeBlockedWhenPointsExist() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val c = SurveyController(f, db, scope(), epochsPerPoint = 3)
        val st = SettingsState(c, Sett()); c.settingsState = st
        repeat(2) { f.emitNext() }; c.onMeasure(); repeat(6) { f.emitNext() }
        assertEquals(1, c.points.size)
        assertFalse(st.setZone(KoreanTmZone.EAST)); assertEquals(5186, c.zone.epsg)
        assertNotNull(c.zoneChangeProblem(KoreanTmZone.EAST)); assertNull(c.zoneChangeProblem(KoreanTmZone.CENTRAL))
        val jc = JobController(c, Jobs(), scope()); jc.formZone = KoreanTmZone.EAST; jc.applyInfo()
        assertNotNull(jc.error); assertEquals(5186, c.zone.epsg)
    }
}
