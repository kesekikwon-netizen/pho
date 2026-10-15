package kr.archsurvey.t0130

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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

internal class StubSrc : GnssSource {
    var now = 10_000L
    override val link = MutableStateFlow(LinkStatus(LinkState.STREAMING))
    override val gnss = MutableStateFlow(GnssSnapshot())
    override fun clockMs() = now
    override fun stop() {}
    /** 새 GGA 한 개(시각 now) */
    fun push(q: FixQuality, sigma: Double?, age: Double? = 1.0, lat: Double = 37.0, lon: Double = 127.0) {
        gnss.value = GnssSnapshot(
            gga = GgaMessage("raw", "090000.00", lat, lon, q, 30, 0.7, 50.0, 25.0, age, "0001"),
            gst = sigma?.let { GstMessage("raw", 1.0, it / Math.sqrt(2.0), it / Math.sqrt(2.0), 0.02) },
            lastGgaAtMs = now, lastAnyAtMs = now, seenTypes = if (sigma != null) setOf("GGA", "GST") else setOf("GGA"),
        )
    }
}

internal class StrictSett(override var requireFixed: Boolean) : SettingsPlatform {
    override var zone = KoreanTmZone.CENTRAL
    override var poleRight = true
    override var sound = true
    override var vibration = true
    override var keepScreenOn = false
}

internal class Mem : PointStore {
    val saved = ArrayList<SavedPoint>()
    override suspend fun loadAll() = saved.toList()
    override suspend fun maxSeq() = saved.size
    override suspend fun save(p: SavedPoint): Long { saved.add(p); return saved.size.toLong() }
    override suspend fun hideLast() {}
}

private val DB by lazy { AntennaDb.parse(File("src/main/assets/antenna_db.json").readText()) }

internal fun rig(strict: Boolean, device: String = DeviceProfiles.SP60_ID, store: Mem = Mem()): Pair<StubSrc, SurveyController> {
    val s = StubSrc()
    val c = SurveyController(s, DB, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = 1, store = store)
    c.settingsState = SettingsState(c, StrictSett(strict))
    c.deviceIdProvider = { device }
    return s to c
}

/** 0.1.30 T23-b: 엄격 + GST 없음 → 「수신기 정밀도 출력 꺼짐」 + 해결 경로 3가지. 앱이 스스로 규칙·모드를 바꾸지 않음 */
class StrictNoSigma0130Tests {
    @Test fun strictNoGst_blocked_newMessage_threePaths_noSave_noModeChange() {
        val store = Mem(); val (s, c) = rig(strict = true, store = store)
        var retries = 0; c.precisionRetry = { retries++; true }
        val fb = ArrayList<Boolean>(); c.feedback = { fb += it }
        s.push(FixQuality.RTK_FIXED, null)
        assertEquals(StatusReason.NO_SIGMA, c.gv.status.reason)
        c.onMeasure()
        assertEquals("측점 불가 — 수신기 정밀도 출력 꺼짐", c.message)
        val ch = c.ui.choice!!
        assertEquals("수신기 정밀도 출력 꺼짐", ch.title)
        assertEquals(listOf(PrecisionGuide.RETRY_LABEL, PrecisionGuide.SETTINGS_LABEL), ch.options)
        assertTrue(ch.note!!.contains("터미널/SPace"))
        assertEquals(listOf(false), fb)
        assertEquals(Phase.IDLE, c.phase); assertTrue(store.saved.isEmpty())
        // [자동 켜기 다시] → 한 순서만 요청, 저장·모드 변경 없음
        c.ui.pick(0)
        assertEquals(1, retries)
        assertEquals(PrecisionGuide.RETRY_SENT, c.ui.toast)
        assertTrue(c.settingsState!!.requireFixed); assertTrue(store.saved.isEmpty()); assertEquals(Phase.IDLE, c.phase)
        // 다시 눌러도 막힘 그대로 → 설정 열기
        c.onMeasure(); c.ui.pick(1)
        assertEquals(ToolScreen.SETTINGS, c.hub.screen)
        assertEquals(1, retries); assertTrue(c.settingsState!!.requireFixed); assertTrue(store.saved.isEmpty())
    }

    @Test fun strictNoGst_r10_guidanceAndSettingsOnly_noRetry() {
        val (s, c) = rig(strict = true, device = DeviceProfiles.R10_ID)
        c.precisionRetry = { fail("R10 에는 보내지 않음"); false }
        s.push(FixQuality.RTK_FIXED, null)
        c.onMeasure()
        assertEquals(listOf(PrecisionGuide.SETTINGS_LABEL), c.ui.choice!!.options)
        assertTrue(c.ui.choice!!.note!!.contains("웹UI I/O 설정 → BT 포트 → NMEA에서 GST·GSA"))
    }

    @Test fun retryResult_successToast_failNotice() {
        val (_, c) = rig(strict = true)
        c.precisionRetry = { true }
        c.retryPrecision()
        c.onPrecisionResult(kr.archsurvey.bt.PrecisionResult(kr.archsurvey.bt.PrecisionOutcome.SUCCESS, 'C', "-", emptyList(), emptyList(), emptyList(), 1, 1, setOf("GGA", "GST", "GSA"), 0))
        assertEquals(PrecisionGuide.RETRY_OK, c.ui.toast)
        c.retryPrecision()
        c.onPrecisionResult(kr.archsurvey.bt.PrecisionResult(kr.archsurvey.bt.PrecisionOutcome.FAILED_NAK, 'C', "-", emptyList(), emptyList(), emptyList(), null, null, setOf("GGA"), 0))
        assertEquals(PrecisionGuide.SP60_FAIL, c.ui.toast)
    }
}

/** 0.1.30 T23-c(게이트 결정): 표시만 — 상태별 문구 고정. 저장 규칙은 그대로 */
class SigmaDisplay0130Tests {
    private fun gv(c: SurveyController) = c.gv

    @Test fun fixWithSigma_good_values() {
        val (s, c) = rig(strict = false); s.push(FixQuality.RTK_FIXED, 0.01)
        val g = gv(c)
        assertEquals(SurveyStatus.FIXED, g.displayStatus); assertFalse(g.sigmaMissingOnFix)
        assertNull(statusBandSub(g.status.status, g.status.reason, false, false, null))
        assertTrue(HeaderModel.metrics(g), HeaderModel.metrics(g) == "HDOP 0.7 / H 0.010 / V 0.020")
        assertEquals(Tone.FIXED, HeaderModel.pill(g, null, false, 10_000).fixTone)
        assertEquals(SurveyStatus.FIXED, c.mapState().current!!.status)
    }

    @Test fun fixNoSigma_normal_greenDisplay_sameWordsEverywhere_HVdash() {
        val (s, c) = rig(strict = false); s.push(FixQuality.RTK_FIXED, null)
        val g = gv(c)
        assertEquals("저장 판정은 그대로 주의", SurveyStatus.CAUTION, g.status.status)
        assertEquals(SurveyStatus.FIXED, g.displayStatus)
        assertEquals(SurveyStatus.FIXED, c.mapState().current!!.status)
        assertEquals(Tone.FIXED, HeaderModel.pill(g, null, false, 10_000).fixTone)
        assertEquals("고정 · 정밀도 정보 없음", SigmaText.FIX_NO_SIGMA)
        assertEquals(SigmaText.BAND_SUB_NORMAL, statusBandSub(g.status.status, g.status.reason, false, false, null))
        assertEquals("HDOP 0.7 / H — / V — · 정밀도 정보 없음", HeaderModel.metrics(g))
        assertNull(g.status.sigmaHM); assertNull(g.sigmaVM)
        assertFalse(SigmaText.BAND_SUB_NORMAL.contains("주의"))
    }

    @Test fun fixNoSigma_strict_blockedDisplay_notGreen() {
        val (s, c) = rig(strict = true); s.push(FixQuality.RTK_FIXED, null)
        val g = gv(c)
        assertEquals(SurveyStatus.CAUTION, g.displayStatus)
        assertEquals("측점 불가 · 수신기 정밀도 출력 꺼짐", SigmaText.STRICT_BAND_MAIN)
        assertEquals("수신기 정밀도 출력 꺼짐", SigmaText.STRICT_BUTTON_SUB)
        assertTrue(statusBandSub(g.status.status, g.status.reason, false, true, PrecisionGuide.noSigmaHint(DeviceProfiles.SP60_ID))!!.startsWith("엄격 모드 · SP60"))
    }

    @Test fun float_and_ageExceeded_stayCaution() {
        val (s, c) = rig(strict = false)
        s.push(FixQuality.RTK_FLOAT, null)
        assertEquals(SurveyStatus.CAUTION, c.gv.displayStatus); assertFalse(c.gv.sigmaMissingOnFix)
        assertEquals(SurveyStatus.CAUTION, c.mapState().current!!.status)
        s.now += 1; s.push(FixQuality.RTK_FIXED, null, age = 99.0)
        assertEquals(StatusReason.OLD_CORRECTION, c.gv.status.reason)
        assertEquals(SurveyStatus.CAUTION, c.gv.displayStatus)
        assertTrue(statusBandSub(c.gv.status.status, c.gv.status.reason, false, false, null)!!.contains("오래"))
    }

    @Test fun fixNoSigma_normal_savePathUnchanged_reasonOnPoint_toastAndListSameWords() {
        val store = Mem(); val (s, c) = rig(strict = false, store = store)
        s.push(FixQuality.RTK_FIXED, null)
        c.onMeasure()
        assertNull("0.1.58 B3: 확인 창 없음", c.ui.confirm)
        s.now += 1000; s.push(FixQuality.RTK_FIXED, null)
        assertEquals(1, store.saved.size)
        val p = store.saved[0]
        assertTrue("주의 표시는 그대로 기록", p.cautionFlag); assertEquals(SurveyStatus.CAUTION, p.status)
        assertNull(p.sigmaHM)
        assertEquals(PointCaution.NO_SIGMA_FIXED, PointCaution.reason(p))
        assertEquals("정밀도 정보 없음(고정)", PointCaution.reason(p))
        assertEquals("고정 · 정밀도 정보 없음", PointCaution.label(p)); assertEquals("고정 · 정밀도 정보 없음", PointCaution.chip(p))
        assertEquals(SurveyStatus.FIXED, PointCaution.displayStatus(p))
        assertTrue(c.ui.toast!!, c.ui.toast!!.endsWith(" · 고정 · 정밀도 정보 없음"))
        assertEquals(SigmaText.SAVED_MESSAGE, c.message)
        assertFalse(c.mapState().points.single().flagged)
    }

    @Test fun csv_noColumnChange_fixRtkFixed_sigmaBlank() {
        val e = PointEntity(project = "T", pointId = "P-1", positionType = "유구", memo = "", lat = 37.0, lon = 127.0,
            ellipsoidalHeight = 100.0, altitudeMsl = 75.0, geoidSeparation = 25.0, northing = 1.0, easting = 2.0, crsEpsg = 5186,
            fixQuality = 4, allEpochsFixed = false, satellites = 30, hdop = 0.7, sigmaE = null, sigmaN = null, sigmaH = null, sigmaU = null,
            correctionAgeSec = 1.0, baseStationId = "0001", epochs = 3, antennaHeight = 1.8, device = "SP60", measuredAtUtcMs = 0L)
        val csv = Formats.pointsCsv(listOf(e), emptyMap(), TimeZone.getTimeZone("Asia/Seoul"))
        val h = csv.trim().split("\r\n")[0].removePrefix("\uFEFF").split(",")
        assertEquals(Formats.POINT_HEADER, h)
        val row = csv.trim().split("\r\n")[1].split(",")
        assertEquals("RTK_FIXED", row[h.indexOf("fix")]); assertEquals("false", row[h.indexOf("all_epochs_fixed")]); assertEquals("", row[h.indexOf("sigma_h")])
    }
}

/** 0.1.30 T26: 이동 꼬리 최소 수정 */
class Trail0130Tests {
    @Test fun jumpPosition_doesNotConnectLine_norHeading() {
        val (s, c) = rig(strict = false)
        for (i in 0 until 3) { s.now += 1000; s.push(FixQuality.RTK_FIXED, 0.01, lat = 37.0 + i * 0.00001) }
        assertTrue(c.mapState().trailBreaks.isEmpty())
        s.now += 1000; s.push(FixQuality.RTK_FIXED, 0.01, lat = 37.003) // 1초에 약 330 m
        val ms = c.mapState()
        assertEquals(4, ms.trail.size)
        assertEquals(setOf(3), ms.trailBreaks)
        assertNull("점프 위치로 방향을 내지 않음", ms.current!!.headingDeg)
        s.now += 1000; s.push(FixQuality.RTK_FIXED, 0.01, lat = 37.00301)
        assertEquals(setOf(3), c.mapState().trailBreaks); assertNotNull(c.mapState().current!!.headingDeg)
    }

    @Test fun nonFixPosition_neverEntersTrail() {
        val (s, c) = rig(strict = false)
        s.now += 1000; s.push(FixQuality.RTK_FIXED, 0.01, lat = 37.0)
        for (q in listOf(FixQuality.RTK_FLOAT, FixQuality.SINGLE, FixQuality.DGPS)) { s.now += 1000; s.push(q, null, lat = 37.0005) }
        assertEquals(1, c.mapState().trail.size)
        s.now += 1000; s.push(FixQuality.RTK_FIXED, 0.01, lat = 37.00001)
        assertEquals(2, c.mapState().trail.size)
        assertEquals("비고정 뒤 새 선", setOf(1), c.mapState().trailBreaks)
    }

    @Test fun reconnect_clearsTrail() {
        val (s, c) = rig(strict = false)
        for (i in 0 until 3) { s.now += 1000; s.push(FixQuality.RTK_FIXED, 0.01, lat = 37.0 + i * 0.00001) }
        assertEquals(3, c.mapState().trail.size)
        s.link.value = LinkStatus(LinkState.RECONNECTING)
        assertTrue(c.mapState().trail.isEmpty())
        s.link.value = LinkStatus(LinkState.STREAMING)
        s.now += 1000; s.push(FixQuality.RTK_FIXED, 0.01, lat = 37.002)
        assertEquals(1, c.mapState().trail.size); assertTrue(c.mapState().trailBreaks.isEmpty())
    }
}

/** 0.1.30 UX-0130-1: 점 목록 행 상태 = 결과 칩 = 지도 점 (정밀도 정보 없음(고정)이 목록에서만 주의로 보이던 문제) */
class PointRowConsistency0130Tests {
    private fun pt(q: Int, sigma: Double?, floatEpochs: Int = 0, age: Double? = 1.0, caution: Boolean = true) = SavedPoint(
        name = "P-$q-$sigma-$floatEpochs-$age", type = "t", lat = 37.0, lon = 127.0, northing = 0.0, easting = 0.0, crsEpsg = 5186,
        groundHeightM = 0.0, sigmaHM = sigma, status = if (caution) SurveyStatus.CAUTION else SurveyStatus.FIXED, cautionFlag = caution, epochs = 3,
        antenna = AntennaRecord("X", "ARP", "ARP", 1.8, "VERTICAL", 0.0, 0.0, 1.8), fixQuality = q, satellites = 30, measuredAtMs = 0L,
        correctionAgeSec = age, floatEpochs = floatEpochs)

    @Test fun pointListRowStatus_equalsResultChip_andMapMarker() {
        val noSigma = pt(4, null); val fixed = pt(4, 0.01, caution = false); val float = pt(5, 0.02, floatEpochs = 2); val old = pt(4, 0.01, age = 99.0)
        val (s, c) = rig(strict = false)
        s.push(FixQuality.RTK_FIXED, 0.01)
        c.points.addAll(listOf(noSigma, fixed, float, old))
        val markers = c.mapState().points.associateBy { it.name }
        for (p in listOf(noSigma, fixed, float, old)) {
            val row = PointCaution.displayStatus(p) // PointRow 아이콘·색
            val chipFixed = PointCaution.chip(p).startsWith("고정")
            assertEquals(p.name, row == SurveyStatus.FIXED, chipFixed)
            assertEquals(p.name, row != SurveyStatus.FIXED, markers.getValue(p.name).flagged)
        }
        assertEquals(SurveyStatus.FIXED, PointCaution.displayStatus(noSigma))
        assertEquals("고정 · 정밀도 정보 없음", PointCaution.rowReason(noSigma))
        assertEquals(PointCaution.FLOAT, PointCaution.rowReason(float))
        assertEquals(PointCaution.AGE_OVER, PointCaution.rowReason(old))
        assertNull(PointCaution.rowReason(fixed))
        // 목록 행이 실제로 표시 상태를 쓰는지(소스 고정)
        val src = File("src/main/kotlin/kr/archsurvey/ui/ToolScreens.kt").readText()
        val row = src.substringAfter("private fun PointRow(").substringBefore("\n}\n")
        assertTrue(row.contains("PointCaution.displayStatus(p)")); assertFalse(row.contains("statusColors(t, p.status)")); assertFalse(row.contains("StatusIcon(p.status"))
        assertTrue(row.contains("PointCaution.rowReason(p)"))
    }
}
