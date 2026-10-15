package kr.archsurvey.t0154

import kr.archsurvey.ntrip.*
import kr.archsurvey.ui.ErrorCards
import kr.archsurvey.ui.NtripForm
import kr.archsurvey.engine.NtripStatus
import org.junit.Assert.*
import org.junit.Test

/** 0.1.54: VRS 아이디·비밀번호 앞뒤 공백·보이지 않는 문자 정리, 헤더, 서버 첫 줄 표시 */
class Cred0154Test {
    private val zw = "\u200B"; private val nb = "\u00A0"; private val bom = "\uFEFF"

    @Test fun clean_strips_edges_only() {
        assertEquals("ngiiUser01", Cred.clean(" \t${zw}ngiiUser01$nb\u3000\n$bom"))
        assertEquals("pa ss", Cred.clean("${nb}pa ss$zw"))      // 가운데 공백은 그대로
        assertEquals("", Cred.clean("$zw$nb "))
        assertEquals("AbC", Cred.clean("AbC"))                  // 대소문자 유지
    }

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test fun header_uses_cleaned_credentials() {
        val dirty = NtripConfig("vrs3.ngii.go.kr", 2101, "VRS-RTCM31", " ${zw}user1$nb", "${bom}secret ", false)
        val req = NtripClient.buildRequest(dirty, "/VRS-RTCM31")
        assertTrue(req.contains("Authorization: Basic ${b64("user1:secret")}\r\n"))
        assertEquals(NtripClient.buildRequest(NtripConfig("vrs3.ngii.go.kr", 2101, "VRS-RTCM31", "user1", "secret", false), "/VRS-RTCM31"), req)
        // 표준 Base64 와 같은지(UTF-8, 패딩)
        for (s in listOf("a:b", "ab:c", "abc:de", "아이디:pw")) assertEquals("Basic " + b64(s), NtripClient.basicAuth(s.substringBefore(':'), s.substringAfter(':')))
        // 공백뿐인 아이디는 Authorization 을 보내지 않는다
        assertFalse(NtripClient.buildRequest(NtripConfig("h", 2101, "m", "$zw $nb", "x", false), "/m").contains("Authorization"))
    }

    @Test fun form_and_loaded_values_are_cleaned() {
        val f = NtripForm(host = "h", port = "2101", mount = "m", user = "\u2060u$nb", password = " p$zw", useV2 = false, enabled = true)
        val c = f.toConfig(); assertEquals("u", c.username); assertEquals("p", c.password)
        val back = NtripForm.from(NtripConfig("h", 2101, "m", "${zw}u ", "${bom}p", false), true)
        assertEquals("u", back.user); assertEquals("p", back.password)
    }

    @Test fun wrong_id_card_shows_server_first_line_never_password() {
        val msg = "아이디/비밀번호가 맞지 않아요 [${Cred.serverLine("HTTP/1.1 401 Unauthorized\r")}]"
        val card = ErrorCards.ntripCard(NtripStatus(NtripState.AUTH_FAILED, msg), "vrs3.ngii.go.kr")!!
        assertEquals("NT-401", card.code)
        assertTrue(card.title, card.title.endsWith("[HTTP/1.1 401 Unauthorized]"))
        val v1 = ErrorCards.ntripCard(NtripStatus(NtripState.AUTH_FAILED, "아이디/비밀번호가 맞지 않아요 [ERROR - Bad Password]"), "vrs3.ngii.go.kr")!!
        assertTrue(v1.title.contains("[ERROR - Bad Password]"))
        val req = NtripClient.buildRequest(NtripConfig("h", 2101, "m", "user1", "TopSecret9", false), "/m")
        assertFalse(card.title.contains("TopSecret9")); assertFalse(card.action.contains("TopSecret9")); assertTrue(req.isNotEmpty())
    }
}
