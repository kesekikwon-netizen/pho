package kr.archsurvey

import kr.archsurvey.core.*
import kr.archsurvey.device.LinkState
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

/**
 * 0.1.20 폰 GPS 시험 → 0.1.30 T24 로 바뀜: 폰 위치 판정·저정밀 측점·폰 폴백 경로는 삭제됨.
 * 남은 것: 수신기 단독 측위 차단, 미연결 머리글 문구, 배경맵(미연결) 시험, 버전 고정.
 */
class PhoneGps0120Tests {
    private val now = 10_000L

    private fun rxSingle(): GnssSnapshot {
        val gga = GgaMessage("raw", "090000.00", 36.5, 127.0, FixQuality.SINGLE, 12, 1.2, 38.0, 26.0, null, null)
        return GnssSnapshot(gga = gga, gst = GstMessage("raw", 1.0, 0.7, 0.7, 1.5), lastGgaAtMs = now - 200, lastAnyAtMs = now - 200)
    }

    @Test fun receiverSingle_stillBlocked() {
        val r = StatusJudge.judge(rxSingle(), LinkState.STREAMING, now)
        assertEquals(SurveyStatus.UNAVAILABLE, r.status)
        assertEquals(StatusReason.SINGLE_OR_DGPS, r.reason)
        assertEquals(SavePolicy.BLOCK, StatusJudge.savePolicy(r.status))
    }

    @Test fun t24_noPhoneReasonOrFieldLeft() {
        assertFalse("PHONE_GPS 판정 이유 삭제", StatusReason.entries.any { it.name.contains("PHONE") })
        assertFalse("GnssSnapshot.fromPhone 삭제", GnssSnapshot::class.java.declaredFields.any { it.name == "fromPhone" })
        assertFalse("GnssView.fromPhone 삭제", GnssView::class.java.declaredFields.any { it.name == "fromPhone" })
        assertEquals("단독", HeaderModel.fixLabel(FixQuality.SINGLE))
    }

    @Test fun t24_headerPill_noReceiver_neverPhone() {
        val p = HeaderModel.pill(GnssView(), null, false, now)
        assertEquals("수신기 없음", p.fix)
        assertEquals(Tone.NO_RECEIVER, p.fixTone)
        assertFalse(p.fix.contains("폰"))
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

    @Test fun versionIs0_1_31Build32() {
        val g = File("build.gradle.kts").readText()
        assertTrue(g.contains("versionCode = 48"))   // 0.1.45
        assertTrue(g.contains("versionName = \"0.1.47\""))
    }
}
