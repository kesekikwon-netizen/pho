package kr.archsurvey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.PointEntity
import kr.archsurvey.device.FakeGnssSource
import kr.archsurvey.device.FakeScenarios
import kr.archsurvey.device.LinkState
import kr.archsurvey.export.Formats
import kr.archsurvey.ui.Phase
import kr.archsurvey.ui.PointStore
import kr.archsurvey.ui.SavedPoint
import kr.archsurvey.ui.SettingsPlatform
import kr.archsurvey.ui.SettingsState
import kr.archsurvey.ui.SurveyController
import kr.archsurvey.ui.statusBandSub
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** 0.1.26 측점 품질 게이트: 엄격/기본 savePolicy · CSV max_corr_age_s · 엄격 시 측점 차단 */
class QualityGate0126Tests {
    private val now = 10_000L
    private fun snap(q: FixQuality, sigmaH: Double?, ageSec: Double? = 1.0, ageMs: Long = 500): GnssSnapshot {
        val gga = GgaMessage("raw", "090000.00", 36.5, 127.0, q, 20, 0.7, 38.0, 26.0, ageSec, "0000")
        val gst = sigmaH?.let { GstMessage("raw", 0.01, it / Math.sqrt(2.0), it / Math.sqrt(2.0), 0.03) }
        return GnssSnapshot(gga = gga, gst = gst, lastGgaAtMs = now - ageMs, lastAnyAtMs = now - ageMs)
    }

    @Test fun defaultWarnAllowsCaution() {
        assertEquals(SavePolicy.ALLOW_WITH_WARNING, StatusJudge.savePolicy(SurveyStatus.CAUTION, strictMode = false))
        assertEquals(SavePolicy.ALLOW, StatusJudge.savePolicy(SurveyStatus.FIXED, false))
        assertEquals(SavePolicy.BLOCK, StatusJudge.savePolicy(SurveyStatus.UNAVAILABLE, false))
    }

    @Test fun strictBlocksCautionKeepsFixed() {
        assertEquals(SavePolicy.BLOCK, StatusJudge.savePolicy(SurveyStatus.CAUTION, strictMode = true))
        assertEquals(SavePolicy.ALLOW, StatusJudge.savePolicy(SurveyStatus.FIXED, true))
        assertEquals(SavePolicy.BLOCK, StatusJudge.savePolicy(SurveyStatus.UNAVAILABLE, true))
    }

    @Test fun fixedWithoutAgeIsUnknownAgeCaution_notFresh() {
        val r = StatusJudge.judge(snap(FixQuality.RTK_FIXED, 0.01, ageSec = null), LinkState.STREAMING, now)
        assertEquals(SurveyStatus.CAUTION, r.status)
        assertEquals(StatusReason.UNKNOWN_AGE, r.reason)
        assertEquals(SavePolicy.ALLOW_WITH_WARNING, StatusJudge.savePolicy(r.status, false))
        assertEquals(SavePolicy.BLOCK, StatusJudge.savePolicy(r.status, true))
    }

    @Test fun strictBlocksFloatOldAgeHighSigmaNoSigma() {
        val cases = listOf(
            StatusJudge.judge(snap(FixQuality.RTK_FLOAT, 0.01), LinkState.STREAMING, now),
            StatusJudge.judge(snap(FixQuality.RTK_FIXED, 0.01, ageSec = 20.0), LinkState.STREAMING, now),
            StatusJudge.judge(snap(FixQuality.RTK_FIXED, 0.05), LinkState.STREAMING, now),
            StatusJudge.judge(snap(FixQuality.RTK_FIXED, null), LinkState.STREAMING, now),
        )
        for (r in cases) {
            assertEquals(r.reason.name, SurveyStatus.CAUTION, r.status)
            assertEquals(r.reason.name, SavePolicy.BLOCK, StatusJudge.savePolicy(r.status, true))
            assertEquals(r.reason.name, SavePolicy.ALLOW_WITH_WARNING, StatusJudge.savePolicy(r.status, false))
        }
        // 0.1.30 T24: 폰 GPS 판정 경로 삭제 — 수신기 없이 데이터만 있으면(연결 대기) 저장 막힘
        val noLink = GnssSnapshot(
            gga = GgaMessage("raw", "090000.00", 36.5, 127.0, FixQuality.SINGLE, 12, 1.0, 50.0, null, null, null),
            lastGgaAtMs = now - 200, lastAnyAtMs = now - 200,
        )
        val pr = StatusJudge.judge(noLink, LinkState.CONNECTED_NO_DATA, now)
        assertEquals(SurveyStatus.UNAVAILABLE, pr.status)
        assertEquals(SavePolicy.BLOCK, StatusJudge.savePolicy(pr.status, false))
    }

    @Test fun csvExportsMaxCorrAgeAndFixAndSigma() {
        val pt = PointEntity(
            rowId = 1, project = "T", pointId = "P-1", positionType = "유구", memo = "",
            lat = 37.0, lon = 127.0, ellipsoidalHeight = 100.0, altitudeMsl = 75.0, geoidSeparation = 25.0,
            northing = 1.0, easting = 2.0, crsEpsg = 5186, fixQuality = 4, allEpochsFixed = true,
            satellites = 18, hdop = 0.8, sigmaE = 0.01, sigmaN = 0.012, sigmaH = 0.0156, sigmaU = 0.02,
            correctionAgeSec = 1.5, baseStationId = "0001", epochs = 3,
            maxCorrectionAgeSec = 3.7, minCorrectionAgeSec = 0.8,
            antennaHeight = 1.8, device = "R10", measuredAtUtcMs = 0L,
        )
        val csv = Formats.pointsCsv(listOf(pt), emptyMap(), TimeZone.getTimeZone("Asia/Seoul"))
        val header = csv.trim().split("\r\n")[0].removePrefix("\uFEFF").split(",")
        assertTrue(header.contains("corr_age_s"))
        assertTrue(header.contains("max_corr_age_s"))
        assertTrue(header.contains("fix"))
        assertTrue(header.contains("sigma_h"))
        assertEquals(Formats.POINT_HEADER, header)
        val row = csv.trim().split("\r\n")[1]
        assertTrue(row.contains("RTK_FIXED"))
        assertTrue(row.contains("1.5") || row.contains("1.50") || row.contains("1.500"))
        assertTrue("max age", row.contains("3.7") || row.contains("3.70") || row.contains("3.700"))
        assertTrue(row.contains("0.0156"))
    }

    private class MemStore : PointStore {
        val saved = ArrayList<SavedPoint>()
        override suspend fun loadAll() = saved.toList()
        override suspend fun maxSeq() = saved.size
        override suspend fun save(p: SavedPoint): Long { saved.add(p); return saved.size.toLong() }
        override suspend fun hideLast() {}
        override suspend fun updateMeta(rowId: Long, type: String, memo: String) {}
    }

    private class Sett(override var requireFixed: Boolean = false) : SettingsPlatform {
        override var zone = KoreanTmZone.CENTRAL
        override var poleRight = true
        override var sound = true
        override var vibration = true
        override var keepScreenOn = false
    }


    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private fun ctrl(f: FakeGnssSource) =
        SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = 5, store = MemStore())

    @Test fun controllerStrictBlocksFloatWithoutDialog() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk())
        val c = ctrl(f)
        c.settingsState = SettingsState(c, Sett(requireFixed = true))
        repeat(2) { f.emitNext() }; f.skipToNextSegment(); f.emitNext()
        assertEquals(SurveyStatus.CAUTION, c.gv.status.status)
        c.onMeasure()
        assertEquals(Phase.IDLE, c.phase)
        assertNull(c.ui.confirm)
        assertTrue(c.message!!.contains("엄격"))
        assertTrue(c.points.isEmpty())
    }

    @Test fun controllerDefaultCautionAsksConfirm() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk())
        val c = ctrl(f)
        c.settingsState = SettingsState(c, Sett(requireFixed = false))
        repeat(2) { f.emitNext() }; f.skipToNextSegment(); f.emitNext()
        assertEquals(SurveyStatus.CAUTION, c.gv.status.status)
        c.onMeasure()
        assertEquals(Phase.IDLE, c.phase)
        assertNotNull(c.ui.confirm)
        assertTrue(c.ui.confirm!!.title.contains("주의"))
        assertTrue(c.points.isEmpty())
        c.ui.answer(true)
        assertEquals(Phase.ACQUIRING, c.phase)
        repeat(6) { f.emitNext() }
        assertEquals(1, c.points.size)
        assertTrue(c.points.single().cautionFlag)
    }

    @Test fun statusBandSub_strictCautionMatchesMeasureButtonCopy() {
        val s = statusBandSub(SurveyStatus.CAUTION, StatusReason.FLOAT, notConnected = false, requireFixed = true)
        assertEquals("엄격 모드 · FIX만 측점 · ${StatusReason.FLOAT.textKo}", s)
        val unknown = statusBandSub(SurveyStatus.CAUTION, StatusReason.UNKNOWN_AGE, false, true)
        assertEquals("엄격 모드 · FIX만 측점 · ${StatusReason.UNKNOWN_AGE.textKo}", unknown)
    }

    @Test fun statusBandSub_defaultCautionKeepsWarnCopy() {
        val s = statusBandSub(SurveyStatus.CAUTION, StatusReason.FLOAT, notConnected = false, requireFixed = false)
        assertEquals("저장 시 표시 기록 · ${StatusReason.FLOAT.textKo}", s)
        assertNull(statusBandSub(SurveyStatus.FIXED, StatusReason.OK, false, true))
        assertEquals("측점 막힘 · ${StatusReason.NOT_CONNECTED.textKo}",
            statusBandSub(SurveyStatus.UNAVAILABLE, StatusReason.NOT_CONNECTED, false, false))
    }

}

class QualityGate0126GeoJsonTest {
    @Test fun geoJsonHasAgeFields() {
        val pt = PointEntity(
            rowId = 1, project = "T", pointId = "P-1", lat = 37.0, lon = 127.0, ellipsoidalHeight = 1.0, altitudeMsl = 1.0, geoidSeparation = 0.0,
            northing = 1.0, easting = 2.0, crsEpsg = 5186, fixQuality = 5, allEpochsFixed = false,
            satellites = 10, hdop = 1.0, sigmaE = null, sigmaN = null, sigmaH = 0.2, sigmaU = 0.3,
            correctionAgeSec = 2.0, baseStationId = null, epochs = 3, maxCorrectionAgeSec = 12.5,
            antennaHeight = 1.8, device = "SP60", measuredAtUtcMs = 0L,
        )
        val p = org.json.JSONObject(Formats.pointsGeoJson(listOf(pt), emptyMap()))
            .getJSONArray("features").getJSONObject(0).getJSONObject("properties")
        assertEquals("RTK_FLOAT", p.getString("fix"))
        assertEquals(2.0, p.getDouble("corr_age_s"), 1e-9)
        assertEquals(12.5, p.getDouble("max_corr_age_s"), 1e-9)
        assertEquals(0.2, p.getDouble("sigma_h"), 1e-9)
    }

}
