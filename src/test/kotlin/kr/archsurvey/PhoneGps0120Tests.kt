package kr.archsurvey

import kr.archsurvey.core.*
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.PhoneFallbackGnssSource
import kr.archsurvey.map.MapBase
import kr.archsurvey.map.MapLayers
import kr.archsurvey.map.VWorldAccess
import kr.archsurvey.map.VWorldDefaults
import kr.archsurvey.ui.GnssView
import kr.archsurvey.ui.HeaderModel
import kr.archsurvey.ui.Tone
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.20: 폰 GPS 라벨·판정·저장 정책. LocationManager 실수신 없음(순수 판정). */
class PhoneGps0120Tests {
    private val now = 10_000L

    private fun phoneSnap(sigmaH: Double = 5.0, ageMs: Long = 200): GnssSnapshot {
        val gga = GgaMessage("PHONE", "090000.00", 36.5, 127.0, FixQuality.SINGLE, 0, null, 40.0, null, null, null)
        val gst = GstMessage("PHONE", sigmaH, sigmaH / Math.sqrt(2.0), sigmaH / Math.sqrt(2.0), sigmaH)
        return GnssSnapshot(gga = gga, gst = gst, lastGgaAtMs = now - ageMs, lastAnyAtMs = now - ageMs, fromPhone = true)
    }

    private fun rxSingle(): GnssSnapshot {
        val gga = GgaMessage("raw", "090000.00", 36.5, 127.0, FixQuality.SINGLE, 12, 1.2, 38.0, 26.0, null, null)
        return GnssSnapshot(gga = gga, gst = GstMessage("raw", 1.0, 0.7, 0.7, 1.5), lastGgaAtMs = now - 200, lastAnyAtMs = now - 200, fromPhone = false)
    }

    @Test fun phoneGps_connectedNoData_stillAllowCaution() {
        val r = StatusJudge.judge(phoneSnap(), LinkState.CONNECTED_NO_DATA, now)
        assertEquals(SurveyStatus.CAUTION, r.status)
        assertEquals(StatusReason.PHONE_GPS, r.reason)
        assertEquals(SavePolicy.ALLOW_WITH_WARNING, StatusJudge.savePolicy(r.status))
    }

    @Test fun phoneGps_isCautionNotFixed_andAllowWithWarning() {
        val r = StatusJudge.judge(phoneSnap(), LinkState.STREAMING, now)
        assertEquals(SurveyStatus.CAUTION, r.status)
        assertEquals(StatusReason.PHONE_GPS, r.reason)
        assertEquals(SavePolicy.ALLOW_WITH_WARNING, StatusJudge.savePolicy(r.status))
        assertFalse("폰 GPS 는 RTK 가 아님", phoneSnap().quality.isRtk)
    }

    @Test fun receiverSingle_stillBlocked() {
        val r = StatusJudge.judge(rxSingle(), LinkState.STREAMING, now)
        assertEquals(SurveyStatus.UNAVAILABLE, r.status)
        assertEquals(StatusReason.SINGLE_OR_DGPS, r.reason)
        assertEquals(SavePolicy.BLOCK, StatusJudge.savePolicy(r.status))
    }

    @Test fun phoneGps_neverLooksLikeRtkFixed() {
        val r = StatusJudge.judge(phoneSnap(sigmaH = 0.01), LinkState.STREAMING, now)
        assertNotEquals(SurveyStatus.FIXED, r.status)
        assertNotEquals(StatusReason.OK, r.reason)
        assertEquals("폰GPS", HeaderModel.fixLabel(FixQuality.SINGLE, fromPhone = true))
        assertEquals("단독", HeaderModel.fixLabel(FixQuality.SINGLE, fromPhone = false))
    }

    @Test fun headerPill_showsPhoneGps() {
        val gv = GnssView(
            StatusResult(SurveyStatus.CAUTION, StatusReason.PHONE_GPS, 5.0),
            FixQuality.SINGLE, 0, 36.5, 127.0, fromPhone = true,
        )
        val p = HeaderModel.pill(gv, null, false, now)
        assertEquals("폰GPS", p.fix)
        assertEquals(Tone.CAUTION, p.fixTone)
        assertEquals("보정 없음", p.correction)
    }

    @Test fun fallback_usePhoneOnlyWhenIdleOrNeedsUser() {
        assertTrue(PhoneFallbackGnssSource.usePhone(LinkState.IDLE))
        assertTrue(PhoneFallbackGnssSource.usePhone(LinkState.NEEDS_USER))
        assertFalse(PhoneFallbackGnssSource.usePhone(LinkState.STREAMING))
        assertFalse(PhoneFallbackGnssSource.usePhone(LinkState.STALE))
        assertFalse(PhoneFallbackGnssSource.usePhone(LinkState.CONNECTING))
        assertFalse(PhoneFallbackGnssSource.usePhone(LinkState.CONNECTED_NO_DATA))
        assertFalse(PhoneFallbackGnssSource.usePhone(LinkState.RECONNECTING))
    }

    @Test fun mapLayers_withoutAnchorStillResolveWithBuiltin() {
        val layers = MapLayers.resolve(MapBase.SATELLITE_CADASTRAL, VWorldAccess(VWorldDefaults.BUILTIN_API_KEY, VWorldDefaults.BUILTIN_DOMAIN))
        assertEquals(listOf("sat", "cad"), layers.map { it.id })
        assertTrue(layers[0].urlOf(16, 55897, 11254).contains("Satellite"))
    }

    @Test fun mapBase_blankIdDefaultsToSatCad() {
        assertEquals(MapBase.SATELLITE_CADASTRAL, MapBase.fromId(null))
        assertEquals(MapBase.SATELLITE_CADASTRAL, MapBase.fromId(""))
        assertEquals(MapBase.NONE, MapBase.fromId("NONE"))
        assertEquals(MapBase.NONE, MapBase.fromId("junk"))
    }

    @Test fun versionIs0_1_27Build28() {
        val g = File("build.gradle.kts").readText()
        assertTrue(g.contains("versionCode = 29"))
        assertTrue(g.contains("versionName = \"0.1.28\""))
    }
}
