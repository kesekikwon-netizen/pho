package kr.archsurvey

import kr.archsurvey.data.SettingsStore
import kr.archsurvey.engine.AndroidMapPlatform
import kr.archsurvey.map.MapBase
import kr.archsurvey.map.MapLayers
import kr.archsurvey.map.VWorldDefaults
import kr.archsurvey.map.VWorldUrls
import kr.archsurvey.map.VWorldTileResponse
import kr.archsurvey.ui.VWorldTestText
import kr.archsurvey.ui.JobConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 제품 기본 V월드 키 시드·폴백 · 레거시 2DCache 주 경로 · 공식 DOMAIN=DEFAULT 폴백. 실기기·실타일 미시험 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VWorldBuiltinKeyTest {

    private fun store(): SettingsStore {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        runCatching { ctx.getSharedPreferences("secure_settings", 0).edit().clear().commit() }
        return SettingsStore(ctx)
    }

    @Test fun blankStorage_resolvesToBuiltin() {
        val s = store()
        assertEquals(VWorldDefaults.BUILTIN_API_KEY, s.mapKey)
        assertTrue(s.hasMapKey)
        assertEquals(36, s.mapKey.length)
        assertTrue(s.mapKey.contains('-'))
        assertEquals("", s.rawStoredMapKey())
    }

    @Test fun ensureBuiltin_seedsSecureOrMem() {
        val s = store()
        assertEquals("", s.rawStoredMapKey())
        s.ensureBuiltinMapKey()
        assertEquals(VWorldDefaults.BUILTIN_API_KEY, s.rawStoredMapKey())
        assertEquals(VWorldDefaults.BUILTIN_API_KEY, s.mapKey)
        // 두 번 호출해도 덮어쓰지 않음(이미 있음)
        s.mapKey = "USER-KEY-AAAA-BBBB-CCCC-DDDD"
        s.ensureBuiltinMapKey()
        assertEquals("USER-KEY-AAAA-BBBB-CCCC-DDDD", s.rawStoredMapKey())
    }

    @Test fun userOverride_thenClear_fallsBackToBuiltin() {
        val s = store()
        val custom = "CUSTOM-KEY-OVERRIDE-9999"
        s.mapKey = custom
        assertEquals(custom, s.mapKey)
        s.mapKey = ""
        assertEquals(VWorldDefaults.BUILTIN_API_KEY, s.mapKey)
    }

    @Test fun androidMapPlatform_accessUsesFallback() {
        val s = store()
        val p = AndroidMapPlatform(s)
        assertEquals(VWorldDefaults.BUILTIN_API_KEY, p.access().key)
        p.key = ""
        assertEquals(VWorldDefaults.BUILTIN_API_KEY, p.access().key)
        p.ensureBuiltinKey()
        assertEquals(VWorldDefaults.BUILTIN_API_KEY, s.rawStoredMapKey())
    }

    @Test fun mapLayers_satelliteCadWithBuiltin() {
        val a = AndroidMapPlatform(store()).access()
        val layers = MapLayers.resolve(MapBase.SATELLITE_CADASTRAL, a)
        assertEquals(listOf("sat", "cad"), layers.map { it.id })
        val cadUrl = layers[1].urlOf(17, 1, 1)
        assertTrue(cadUrl.contains("2d.vworld.kr:8895/2DCache"))
        assertTrue(cadUrl.contains("LAYERS=LP_PA_CBND_BUBUN,LP_PA_CBND_BONBUN"))
        assertTrue(cadUrl.contains("STYLES=LP_PA_CBND_BUBUN,LP_PA_CBND_BONBUN")) // 면 기본
        assertTrue(cadUrl.contains("TRANSPARENT=TRUE"))
        assertFalse(cadUrl.contains("DOMAIN=")) // 레거시는 DOMAIN 없음
        assertFalse(a.lineOnly)
    }

    @Test fun jobConfigDefault_isSatelliteCad() {
        assertEquals(MapBase.SATELLITE_CADASTRAL, JobConfig().mapBase)
    }

    @Test fun blankDomain_officialSendsDefault_legacyOmits() {
        val s = store()
        assertEquals("", s.mapDomain)
        assertEquals("", AndroidMapPlatform(s).access().domain)
        // 주 경로(레거시)는 DOMAIN 없음
        assertFalse(VWorldUrls.cadastral(VWorldDefaults.BUILTIN_API_KEY, "", 17, 1, 1).contains("DOMAIN="))
        assertFalse(VWorldUrls.cadastral(VWorldDefaults.BUILTIN_API_KEY, "DEFAULT", 17, 1, 1).contains("DOMAIN="))
        // 공식 폴백: 빈칸·DEFAULT → DOMAIN=DEFAULT
        assertTrue(VWorldUrls.cadastralOfficial(VWorldDefaults.BUILTIN_API_KEY, "", 17, 1, 1).contains("DOMAIN=DEFAULT"))
        assertTrue(VWorldUrls.cadastralOfficial(VWorldDefaults.BUILTIN_API_KEY, "DEFAULT", 17, 1, 1).contains("DOMAIN=DEFAULT"))
        assertTrue(VWorldUrls.cadastralOfficial(VWorldDefaults.BUILTIN_API_KEY, "https://example.com", 17, 1, 1).contains("DOMAIN=https"))
    }

    @Test fun keyStatusKo_showsLast4Only() {
        val s = VWorldDefaults.keyStatusKo(VWorldDefaults.BUILTIN_API_KEY)
        assertTrue(s.contains("내장 인증키 사용 중"))
        assertTrue(s.contains("…4783"))
        assertFalse(s.contains(VWorldDefaults.BUILTIN_API_KEY))
        assertTrue(VWorldDefaults.keyStatusKo("ABCD-EFGH-9999").contains("저장 인증키 사용 중 · …9999"))
        assertTrue(VWorldDefaults.keyStatusKo("").contains("아직 키가 없어요"))
    }

    @Test fun maskedKeyPlaceholder_showsBulletsAndLast4() {
        val p = VWorldDefaults.maskedKeyPlaceholder(VWorldDefaults.BUILTIN_API_KEY)
        assertTrue(p.startsWith("••••"))
        assertTrue(p.endsWith("4783"))
        assertFalse(p.contains(VWorldDefaults.BUILTIN_API_KEY))
        assertEquals("", VWorldDefaults.maskedKeyPlaceholder(""))
    }

    @Test fun tileResponse_imageTypeCaseInsensitiveAndMagic() {
        assertTrue(VWorldTileResponse.isImageContentType("IMAGE/PNG"))
        assertTrue(VWorldTileResponse.isImageContentType("image/png; charset=UTF-8"))
        assertFalse(VWorldTileResponse.isImageContentType("text/xml"))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertTrue(VWorldTileResponse.hasImageMagic(png))
        assertTrue(VWorldTileResponse.hasImageMagic(jpg))
        assertTrue(VWorldTileResponse.acceptAsImage("application/octet-stream", png))
        assertFalse(VWorldTileResponse.acceptAsImage("text/xml", byteArrayOf(0x3C, 0x3F))) // <?
    }

    @Test fun tileResponse_cadastralErrorDistinctFromBase() {
        val wms = "https://api.vworld.kr/req/wms?LAYERS=lp_pa_cbnd_bonbun,lp_pa_cbnd_bubun&KEY=****"
        val leg = "http://2d.vworld.kr:8895/2DCache/gis/map/WMS2?LAYERS=LP_PA_CBND_BUBUN,LP_PA_CBND_BONBUN&APIKEY=****"
        val wmts = "https://api.vworld.kr/req/wmts/1.0.0/****/Satellite/16/1/1.jpeg"
        assertTrue(VWorldTileResponse.isCadastralRequest(wms))
        assertTrue(VWorldTileResponse.isCadastralRequest(leg))
        assertFalse(VWorldTileResponse.isCadastralRequest(wmts))
        val cadErr = VWorldTileResponse.fetchErrorKo(wms, 200, "Unauthorized", "INCORRECT_KEY")
        assertTrue(cadErr.contains("지적 WMS"))
        assertTrue(cadErr.contains("[INCORRECT_KEY]"))
        assertFalse(cadErr.startsWith("배경맵 오류"))
        val satErr = VWorldTileResponse.fetchErrorKo(wmts, 200, "Unauthorized")
        assertTrue(satErr.startsWith("배경맵 오류"))
    }

    @Test fun tileResponse_parseServiceException() {
        val xml = """<?xml version="1.0"?><ServiceExceptionReport><ServiceException code="INCORRECT_KEY">Domain mismatch</ServiceException></ServiceExceptionReport>"""
        val ex = VWorldTileResponse.parseServiceException(xml)
        assertEquals("INCORRECT_KEY", ex.code)
        assertEquals("Domain mismatch", ex.text)
        val err = VWorldTileResponse.fetchErrorKo("http://2d.vworld.kr:8895/2DCache/x", 200, ex.text, ex.code)
        assertTrue(err.contains("[INCORRECT_KEY]"))
        assertTrue(err.contains("Domain mismatch"))
        assertFalse(err.contains(VWorldDefaults.BUILTIN_API_KEY))
    }

    @Test fun mapLayers_modeHint_satelliteOnly() {
        assertTrue(MapLayers.modeHint(MapBase.SATELLITE)!!.contains("지적선"))
        assertEquals(null, MapLayers.modeHint(MapBase.SATELLITE_CADASTRAL))
        assertEquals("V월드 위성만 (지적선 없음)", MapBase.SATELLITE.labelKo)
        assertEquals("V월드 위성 + 지적선", MapBase.SATELLITE_CADASTRAL.labelKo)
    }

    @Test fun vworldTestText_requiresBothSuccess() {
        val ok = "배경지도(WMTS): 성공 (그림 12 B)\n지적도(WMS): 성공 (그림 34 B)"
        assertTrue(VWorldTestText.isOk(ok))
        assertFalse(VWorldTestText.isOk("배경지도(WMTS): 성공 (그림 12 B)\n지적도(WMS): 실패 HTTP 200"))
        assertFalse(VWorldTestText.isOk("성공")) // 단독 "성공" 은 연결 시험 형식이 아님
    }

    /** VW-0124-1: 레거시 실패 후 공식 폴백 성공 문구도 연결 시험 OK */
    @Test fun vworldTestText_acceptsOfficialFallbackSuccess() {
        val fallback = "배경지도(WMTS): 성공 (그림 12 B)\n지적도(WMS): 폴백 성공(공식) 성공 (그림 34 B)"
        assertTrue(VWorldTestText.isOk(fallback))
        assertFalse(
            VWorldTestText.isOk(
                "배경지도(WMTS): 성공 (그림 12 B)\n지적도(WMS): 레거시: 실패 HTTP 200 / 공식: 실패 HTTP 200"
            )
        )
        assertFalse(VWorldTestText.isOk("배경지도(WMTS): 성공 (그림 12 B)\n지적도(WMS): 실패 HTTP 200"))
        assertFalse(VWorldTestText.isOk("배경지도(WMTS): 실패\n지적도(WMS): 폴백 성공(공식) 성공 (그림 1 B)"))
    }
}
