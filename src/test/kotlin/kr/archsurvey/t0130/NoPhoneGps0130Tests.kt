package kr.archsurvey.t0130

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.PointEntity
import kr.archsurvey.device.*
import kr.archsurvey.export.Formats
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** 0.1.30 T24: 폰 GPS 는 측점·저장·내보내기 어느 경로에도 없음. 기존 점은 그대로. */
class NoPhoneGps0130Tests {
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private class MemStore(init: List<SavedPoint> = emptyList()) : PointStore {
        val saved = ArrayList(init)
        override suspend fun loadAll() = saved.toList()
        override suspend fun maxSeq() = saved.size
        override suspend fun save(p: SavedPoint): Long { saved.add(p); return saved.size.toLong() }
        override suspend fun hideLast() {}
    }
    private fun ctl(f: GnssSource, store: MemStore = MemStore()) =
        SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = 5, store = store)

    @Test fun noReceiver_measureBlocked_withConnectConfirm_andFailFeedbackOnce() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val store = MemStore(); val c = ctl(f, store)
        val fb = ArrayList<Boolean>(); c.feedback = { fb += it }
        assertTrue(c.gv.noReceiver)
        c.onMeasure()
        assertEquals(SurveyController.NO_RECEIVER_MSG, c.message)
        assertEquals(listOf(false), fb)
        assertEquals("연결", c.ui.confirm?.okLabel)
        assertEquals(Phase.IDLE, c.phase); assertTrue(store.saved.isEmpty())
    }

    @Test fun phoneFix_neverReachesGvOrPoints_onlyMapDot() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val store = MemStore(); val c = ctl(f, store)
        val before = c.gv
        c.onPhoneLocation(kr.archsurvey.device.PhoneFix(36.5, 127.0, 4.0, f.clockMs()))
        assertEquals("gv 는 폰 위치로 바뀌지 않음", before, c.gv)
        assertNull(c.gv.lat); assertTrue(c.gv.noReceiver)
        assertTrue(c.phoneDotVisible())
        val ms = c.mapState()
        assertNull("수신기 마커 없음", ms.current)
        assertEquals(SurveyController.PHONE_DOT_LABEL, ms.phone?.label)
        c.onMeasure()
        assertEquals(Phase.IDLE, c.phase); assertTrue(store.saved.isEmpty())
        // 수신기 위치가 오면 폰 점은 숨는다
        repeat(3) { f.emitNext() }
        assertFalse(c.gv.noReceiver); assertFalse(c.phoneDotVisible()); assertNull(c.mapState().phone)
    }

    @Test fun receiverLostMidAcquire_nothingSaved_withNotice() {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val store = MemStore(); val c = ctl(f, store)
        val fb = ArrayList<Boolean>(); c.feedback = { fb += it }
        repeat(3) { f.emitNext() }
        c.onMeasure(); assertEquals(Phase.ACQUIRING, c.phase)
        f.emitNext()
        f.link.value = LinkStatus(LinkState.RECONNECTING, message = "끊김")
        assertEquals(Phase.IDLE, c.phase)
        assertEquals(SurveyController.RECEIVER_LOST_MSG, c.message)
        assertEquals(false, fb.last())
        assertTrue(store.saved.isEmpty()); assertEquals(0, c.points.size)
    }

    @Test fun headerWording_noReceiver_and_lost() {
        assertEquals("수신기 없음", StatusReason.NOT_CONNECTED.textKo)
        assertEquals("수신기 끊김", StatusReason.LINK_DOWN.textKo)
        val lost = GnssView(StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.LINK_DOWN, null), FixQuality.RTK_FIXED, 0, null, null)
        assertEquals("수신기 끊김", HeaderModel.pill(lost, null, false, 0).fix)
        assertEquals("수신기 없음", HeaderModel.pill(GnssView(), null, false, 0).fix)
    }

    @Test fun mainSourcesHaveNoPhoneGpsPathOrWording() {
        val main = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertFalse(File("src/main/kotlin/kr/archsurvey/device/PhoneFallbackGnssSource.kt").exists())
        for (f in main) {
            val t = f.readText()
            assertFalse(f.path, t.contains("폰 GPS") || t.contains("폰GPS") || t.contains("fromPhone"))
        }
        val phone = File("src/main/kotlin/kr/archsurvey/device/PhoneGpsSource.kt").readText()
        assertFalse("폰 위치는 GnssSource 가 아님", Regex(""":\s*GnssSource""").containsMatchIn(phone))
    }

    /** 0.1.20~0.1.29 에 폰 GPS 로 저장된 점(품질 1·주의) — 이 행 그대로 099b70e 에서 낸 CSV 와 같아야 함 */
    private fun oldPhonePoint() = PointEntity(
        rowId = 7, project = "작업1", pointId = "P-0003", positionType = "유구 경계점", memo = "폰",
        lat = 36.5, lon = 127.0, ellipsoidalHeight = 52.0, altitudeMsl = 26.0, geoidSeparation = 26.0,
        northing = 400000.0, easting = 200000.0, crsEpsg = 5186, fixQuality = 1, allEpochsFixed = false,
        satellites = 0, hdop = null, sigmaE = 3.5, sigmaN = 3.5, sigmaH = 5.0, sigmaU = 5.0,
        correctionAgeSec = null, baseStationId = null, epochs = 5, antennaHeight = 1.8, device = "수신기", measuredAtUtcMs = 1_760_000_000_000L,
    )

    @Test fun oldPhonePoint_exportUnchanged_andListShowsLowSource() {
        val csv = Formats.pointsCsv(listOf(oldPhonePoint()), emptyMap(), TimeZone.getTimeZone("Asia/Seoul"))
        val geo = Formats.pointsGeoJson(listOf(oldPhonePoint()), emptyMap())
        val golden = File("src/test/resources/t0130/old_phone_point.csv").readText()
        val goldenGeo = File("src/test/resources/t0130/old_phone_point.geojson").readText()
        assertEquals("099b70e 와 같은 CSV", golden, csv)
        assertEquals("099b70e 와 같은 GeoJSON", goldenGeo, geo)
        assertTrue(csv.contains("SINGLE"))
        val sp = SavedPoint(name = "P-0003", type = "유구 경계점", lat = 36.5, lon = 127.0, northing = 400000.0, easting = 200000.0, crsEpsg = 5186,
            groundHeightM = 50.2, sigmaHM = 5.0, status = SurveyStatus.CAUTION, cautionFlag = true, epochs = 5,
            antenna = AntennaRecord("X", "ARP", "ARP", 1.8, "VERTICAL", 0.0, 0.0, 1.8), fixQuality = 1, satellites = 0, measuredAtMs = 0L)
        assertEquals(PointCaution.LOW_SOURCE, PointCaution.reason(sp))
        // 기존 점은 불러와도 그대로(지우거나 고치지 않음)
        val store = MemStore(listOf(sp)); val c = ctl(FakeGnssSource(FakeScenarios.allFixed()), store)
        assertEquals(listOf(sp), c.points.toList()); assertEquals(listOf(sp), store.saved)
    }

    @Test fun noSigmaFixed_listLabel_distinctFromFloatAndAge() {
        val base = SavedPoint(name = "P", type = "t", lat = 0.0, lon = 0.0, northing = 0.0, easting = 0.0, crsEpsg = 5186,
            groundHeightM = 0.0, sigmaHM = null, status = SurveyStatus.CAUTION, cautionFlag = true, epochs = 5,
            antenna = AntennaRecord("X", "ARP", "ARP", 1.8, "VERTICAL", 0.0, 0.0, 1.8), fixQuality = 4, satellites = 20, measuredAtMs = 0L,
            correctionAgeSec = 1.0)
        assertEquals("정밀도 정보 없음(고정)", PointCaution.reason(base))
        assertEquals(PointCaution.FLOAT, PointCaution.reason(base.copy(fixQuality = 5)))
        assertEquals(PointCaution.AGE_OVER, PointCaution.reason(base.copy(correctionAgeSec = 99.0)))
        assertEquals("고정 · 정밀도 정보 없음", PointCaution.label(base)) // 0.1.30 T23-c 게이트: 같은 문구
    }

    @Test fun hdopShownAsHdop_whenPdopMissing_neverAsSigma() {
        val g = GnssView(StatusResult(SurveyStatus.CAUTION, StatusReason.NO_SIGMA, null), FixQuality.RTK_FIXED, 20, 37.0, 127.0, null, 0.9, 30, 50.0, 1000L, pdop = null)
        val m = HeaderModel.metrics(g)
        assertTrue(m, m.contains("HDOP 0.9")); assertFalse(m, m.contains("H 0.9"))
    }
}
