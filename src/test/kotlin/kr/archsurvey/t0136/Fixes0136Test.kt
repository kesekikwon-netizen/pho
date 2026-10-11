package kr.archsurvey.t0136

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kr.archsurvey.bt.*
import kr.archsurvey.core.NmeaParser
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.ui.AppExit
import kr.archsurvey.ui.HeaderTitle
import kr.archsurvey.ui.SkyModel
import org.junit.Assert.*
import org.junit.Test

/** 0.1.36: SP60 GSV 자동 켜기 · SkyPlot 기기별 안내 · 머리줄 긴 작업명 · 종료 창 문구 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fixes0136Test {
    private fun ack() = NmeaParser.build("PASHR,ACK").trim()
    private fun prt(p: String) = NmeaParser.build("PASHR,PRT,$p,6").trim()

    private class Rig(val ts: TestScope, val respond: (String) -> Triple<Long, String?, String?>) {
        val replies = Channel<String>(64)
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        val sent = ArrayList<String>()
        suspend fun send(body: String) {
            sent += body
            val (d, line, type) = respond(body)
            ts.launch { delay(d); line?.let { replies.send(it) }; type?.let { seen += it } }
        }
        suspend fun run(budget: PrecisionBudget = PrecisionBudget()) = PrecisionActivator({ ts.testScheduler.currentTime }).run({ send(it) }, replies, { seen.toSet() }, budget)
    }
    private fun typeOf(b: String) = Regex("NME,(G..),").find(b)?.groupValues?.get(1)

    @Test fun gsvCommandIsAllowed_otherNmeStillForbidden() {
        assertEquals("PASHS,NME,GSV,C,ON,1", PrecisionCommands.enable("GSV", 'C'))
        assertTrue(PrecisionCommands.isAllowed("PASHS,NME,GSV,B,ON,1"))
        for (bad in listOf("PASHS,NME,ALL,C,ON,1", "PASHS,NME,GGA,C,OFF", "PASHS,NME,GSV,C,ON,0.5", "PASHS,NME,GSV,C,OFF", "PASHS,NME,RMC,C,ON,1", "PASHS,POW,OFF"))
            assertFalse(bad, PrecisionCommands.isAllowed(bad))
        assertEquals("\$PASHS,NME,GSV,C,ON,1*" + "%02X".format(NmeaParser.checksum("PASHS,NME,GSV,C,ON,1")) + "\r\n", PrecisionCommands.line("PASHS,NME,GSV,C,ON,1"))
    }

    @Test fun sp60_gstGsaPresent_onlyGsvMissing_sendsPrtThenGsvOnly() = runTest {
        val r = Rig(this) { b -> if (b == PrecisionCommands.PRT_QUERY) Triple(50L, prt("C"), null) else Triple(50L, ack(), typeOf(b)) }
        r.seen += setOf("GGA", "GST", "GSA")
        val res = r.run()
        assertEquals(listOf("PASHQ,PRT", "PASHS,NME,GSV,C,ON,1"), r.sent)
        assertEquals(PrecisionOutcome.SUCCESS, res.outcome); assertTrue(res.gsvSeen); assertFalse(res.gstMissing)
        assertTrue(res.summary().contains("GSV 받음"))
    }

    @Test fun sp60_nothing_sendsGstGsaGsvInOrder() = runTest {
        val r = Rig(this) { b -> if (b == PrecisionCommands.PRT_QUERY) Triple(50L, prt("B"), null) else Triple(50L, ack(), typeOf(b)) }
        r.seen += "GGA"
        val res = r.run()
        assertEquals(listOf("PASHQ,PRT", "PASHS,NME,GST,B,ON,1", "PASHS,NME,GSA,B,ON,1", "PASHS,NME,GSV,B,ON,1"), r.sent)
        assertEquals(PrecisionOutcome.SUCCESS, res.outcome); assertTrue(res.gsvSeen)
    }

    @Test fun sp60_gsvNeverArrives_precisionStillSuccess_noUserWarning() = runTest {
        val r = Rig(this) { b -> if (b == PrecisionCommands.PRT_QUERY) Triple(50L, prt("C"), null) else Triple(50L, ack(), typeOf(b)?.takeIf { it != "GSV" }) }
        r.seen += "GGA"
        val res = r.run()
        assertEquals(PrecisionOutcome.SUCCESS, res.outcome); assertFalse(res.gsvSeen); assertFalse(res.gstMissing)
        assertTrue(res.log.any { it.contains("GSV 없음") })
    }

    @Test fun sp60_allThreeNatural_sendsNothing_keepsBudget() = runTest {
        val r = Rig(this) { error("보내면 안 됨") }
        r.seen += setOf("GGA", "GST", "GSA", "GSV")
        val b = PrecisionBudget()
        assertEquals(PrecisionOutcome.ALREADY_PRESENT, r.run(b).outcome); assertEquals(0, b.used); assertTrue(r.sent.isEmpty())
    }

    @Test fun skyPlotEmptyText_perReceiver() {
        val r10 = SkyModel.emptyText(DeviceProfiles.R10_ID)
        assertTrue(r10.contains("192.168.142.1") && r10.contains("Bluetooth") && r10.contains("GSV") && r10.contains("바꾸지 않아요"))
        assertTrue(SkyModel.emptyText(DeviceProfiles.SP60_ID).contains("요청"))
        assertEquals(SkyModel.EMPTY_GENERIC, SkyModel.emptyText(null))
    }

    @Test fun headerTitle_longNamesShrinkAndWrap() {
        assertEquals(HeaderTitle.Style(19, 1), HeaderTitle.style("광령리"))
        assertEquals(2, HeaderTitle.style("제주 광령리 유적 2차 발굴").maxLines)
        assertEquals(15, HeaderTitle.style("제주 애월읍 광령리 1234번지 유적 시굴조사").sizeSp)
    }

    /** 현장 보고 「끌 때 기기 종료 버튼 눌러도 안 꺼진다」: 앱은 수신기 전원을 끌 수 없음 → 창이 전원 끄기를 약속하지 않아야 함 */
    @Test fun exitDialog_doesNotPromiseReceiverPowerOff() {
        val r = AppExit.deviceStep { }
        assertEquals("수신기 연결 끊기", r.title); assertEquals("연결 끊기", r.okLabel)
        assertFalse(r.title.contains("종료"))
        assertTrue(r.body.contains("전원을 끌 수 없어요"))
        assertEquals("종료", AppExit.appStep { }.okLabel)
    }
}
