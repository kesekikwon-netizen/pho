package kr.archsurvey

import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.diag.CrashLog
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.9 화면 없는 논리: 연결 단계, 알려진 마운트, 크래시 기록·가림, 측정값 직렬화, 피드백 허브, 테마 대비 */
class Logic019Tests {
    @Test fun connectStepsFollowLinkNtripAndFix() {
        val idle = ConnectSteps.compute(LinkStatus(), NtripStatus(), true, 0)
        assertEquals(List(5) { StepState.WAIT }, idle)
        val a = ConnectSteps.compute(LinkStatus(LinkState.STREAMING), NtripStatus(NtripState.CONNECTED), true, 1)
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.DONE, StepState.ACTIVE, StepState.ACTIVE), a)
        val fixed = ConnectSteps.compute(LinkStatus(LinkState.STREAMING), NtripStatus(NtripState.RECEIVING), true, 4)
        assertTrue(fixed.all { it == StepState.DONE })
        val auth = ConnectSteps.compute(LinkStatus(LinkState.STREAMING), NtripStatus(NtripState.AUTH_FAILED), true, 1)
        assertEquals(StepState.FAIL, auth[2])
        val noVrs = ConnectSteps.compute(LinkStatus(LinkState.CONNECTED_NO_DATA), NtripStatus(), false, 0)
        assertEquals(listOf(StepState.DONE, StepState.ACTIVE, StepState.SKIP, StepState.SKIP, StepState.WAIT), noVrs)
    }

    @Test fun knownMountsAreMarkedUnverifiedWithSource() {
        val r1 = KnownMounts.forHost("RTS1.ngii.go.kr").map { it.mountPoint }
        assertEquals(listOf("VRS-RTCM31", "VRS-CMRx", "VRS-RTCM23", "RTK-RTCM32"), r1)
        val r2 = KnownMounts.forHost(" rts2.NGII.go.kr ").map { it.mountPoint }
        assertTrue(r2.containsAll(listOf("VRS-RTCM31", "VRS-RTCM32", "FKP-RTCM31", "SSR-SSRG")))
        assertTrue(KnownMounts.forHost("rts1.ngii.go.kr").all { "미확인" in it.identifier })
        assertTrue(KnownMounts.SOURCE.contains("미확인") && KnownMounts.SOURCE.contains("2022"))
        assertTrue(KnownMounts.forHost("caster.example.com").isEmpty())
        assertEquals("custom", KnownMounts.services.last().id)
    }

    @Test fun crashLogRecordsRedactsAndClears() {
        val f = File.createTempFile("crash", ".txt").apply { delete() }
        CrashLog.useFile(f)
        CrashLog.record("시험", IllegalStateException("login failed password=secret123 Authorization: Basic dXNlcjpwdw=="))
        CrashLog.record("시험2", RuntimeException("x"))
        val txt = CrashLog.read()
        assertEquals(2, CrashLog.count())
        assertFalse(txt.contains("secret123")); assertFalse(txt.contains("dXNlcjpwdw=="))
        assertTrue(txt.contains("IllegalStateException")); assertTrue(txt.contains("시험2"))
        CrashLog.clear()
        assertEquals(0, CrashLog.count()); assertEquals("", CrashLog.read())
    }

    @Test fun epochSamplesRoundTrip() {
        val l = listOf(EpochSample(451234.5678, 198765.4321, 52.1234), EpochSample(451234.5690, 198765.4300, null))
        val back = EpochSample.decode(EpochSample.encode(l))
        assertEquals(2, back.size)
        assertEquals(451234.5678, back[0].n, 1e-4); assertEquals(198765.43, back[1].e, 1e-4); assertNull(back[1].h)
        assertNull(EpochSample.encode(emptyList())); assertTrue(EpochSample.decode(null).isEmpty()); assertTrue(EpochSample.decode("x,y;").isEmpty())
    }

    @Test fun feedbackHubConfirmAndChoice() {
        val h = FeedbackHub()
        var ok = 0; var picked = -1
        h.ask(ConfirmRequest("t", "b", "확인") { ok++ })
        h.answer(false); assertEquals(0, ok); assertNull(h.confirm)
        h.ask(ConfirmRequest("t", "b", "확인") { ok++ }); h.answer(true); assertEquals(1, ok)
        h.choose("고르기", listOf("a", "b"), 0, "x") { picked = it }
        h.pick(5); assertEquals(-1, picked)
        h.choose("고르기", listOf("a", "b"), 0, "x") { picked = it }; h.pick(1); assertEquals(1, picked); assertNull(h.choice)
        val s0 = h.toastSeq; h.toast("저장됨"); assertEquals("저장됨", h.toast); assertEquals(s0 + 1, h.toastSeq)
    }

    private fun lum(c: Long): Double {
        fun ch(v: Long): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
    private fun ratio(a: Long, b: Long): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun whiteThemeMeetsHighContrastTargets() {
        val white = 0xFFFFFFL
        assertTrue("본문 7:1", ratio(0x111111, white) >= 7.0)
        assertTrue("보조 글자 7:1", ratio(0x474747, white) >= 7.0)
        assertTrue("강조 글자 7:1", ratio(0x8A3A1F, white) >= 7.0)
        assertTrue("고정색 위 흰 글자 7:1", ratio(0x155A96, white) >= 7.0)
        assertTrue("경계선 3:1", ratio(0x858585, white) >= 3.0)
        assertTrue("입력 테두리 3:1", ratio(0x767676, white) >= 3.0)
    }
}
