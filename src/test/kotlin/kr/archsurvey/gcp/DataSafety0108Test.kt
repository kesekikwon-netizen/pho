package kr.archsurvey.gcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.gcp.ControllerTest.Companion.measure
import kr.archsurvey.gcp.ControllerTest.Companion.siteWithObj
import kr.archsurvey.gcp.data.SafeFile
import kr.archsurvey.gcp.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataSafety0108Test {
    @Test fun safeFile_replacesWhole_noTmpLeft_oldKeptOnFailure() {
        val dir = Files.createTempDirectory("sf").toFile(); val f = File(dir, "a.xml")
        SafeFile.write(f, "old"); SafeFile.write(f, "새 내용")
        assertEquals("새 내용", f.readText()); assertEquals(listOf("a.xml"), dir.list()!!.toList())
        runCatching { SafeFile.writeWith(f) { it.write("half".toByteArray()); throw java.io.IOException("disk full") } }
        assertEquals("새 내용", f.readText())   // 쓰다 죽어도 예전 파일 그대로
        assertEquals(listOf("a.xml"), dir.list()!!.toList())
    }

    @Test fun checkpoint_roundTrip_andGarbageIgnored() {
        val cp = MeasureCheckpoint(1, 2, 3, 4, 5_000, 0.4f)
        assertEquals(cp, MeasureCheckpoint.decode(cp.encode()))
        assertNull(MeasureCheckpoint.decode("")); assertNull(MeasureCheckpoint.decode("v1|x|2")); assertNull(MeasureCheckpoint.decode("v9|1|2|3|4|5|0.1"))
    }

    @Test fun processDeath_duringAveraging_returnsToThatTarget_andTargetsKept() {
        val r = ControllerTest.rig().siteWithObj(); val c = r.c
        r.measure(1, 194884.0, 573986.0); r.measure(2, 194880.0, 573989.0)
        val s = c.session!!; val o = c.obj!!; val site = c.book!!.site
        // 측정 중 프로세스가 죽은 상태 = 체크포인트만 남음
        r.prefs.measureCheckpoint = MeasureCheckpoint(site.id, o.id, s.id, 3, r.t, 0.6f).encode()
        r.prefs.lastSiteId = -1
        val c2 = GcpController(r.repo!!, r.files, r.gnss, r.prefs, ControllerTest.antennaDb, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined, clock = { r.t + 60_000 })
        c2.start()
        assertEquals(Screen.TARGETS, c2.screen)
        assertEquals(o.id, c2.obj!!.id); assertEquals(s.id, c2.session!!.id)
        assertEquals(3, c2.interrupted!!.tag); assertEquals(3, c2.nextTag())
        assertEquals(2, c2.activeShots().size)          // 잰 표적은 그대로
        assertTrue(c2.ui.toast!!.contains("표적 #3"))
        assertEquals("", r.prefs.measureCheckpoint)      // 한 번만
    }

    @Test fun staleCheckpoint_isDropped() {
        val r = ControllerTest.rig().siteWithObj(); val c = r.c
        r.prefs.measureCheckpoint = MeasureCheckpoint(c.book!!.site.id, c.obj!!.id, c.session!!.id, 3, r.t, 0.6f).encode()
        val c2 = GcpController(r.repo!!, r.files, r.gnss, r.prefs, ControllerTest.antennaDb, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined, clock = { r.t + MeasureCheckpoint.MAX_AGE_MS + 1 })
        c2.start()
        assertNull(c2.interrupted); assertEquals("", r.prefs.measureCheckpoint)
    }

    @Test fun mapErrorChip_neverShowsRawException() {
        val raw = kr.archsurvey.map.VWorldTileResponse.networkErrorKo("https://api.vworld.kr/req/wms?LAYERS=lp_pa_cbnd_bubun", "SocketTimeoutException")
        val e = MapErrorChip.of(false, raw)!!
        assertFalse((e.what + e.effect).contains("Exception"))
        assertEquals("지적도를 잠시 못 불러왔어요", e.what); assertTrue(e.retry)
        assertFalse(MapErrorChip.of(false, "배경맵을 받지 못했어요 (UnknownHostException)")!!.let { it.what + it.effect }.contains("Exception"))
        assertNull(MapErrorChip.of(false, null))
        assertFalse(MapErrorChip.of(true, null)!!.retry)
    }
}
