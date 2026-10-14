package kr.archsurvey

import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.ntrip.RtcmMountCompat
import org.junit.Assert.*
import org.junit.Test

class CmrMount0149Test {
    private val r10 = DeviceProfiles.all.first { it.id == DeviceProfiles.R10_ID }
    private val sp60 = DeviceProfiles.all.first { it.id == DeviceProfiles.SP60_ID }

    @Test fun detect() {
        assertEquals("CMRX", RtcmMountCompat.detectCmr("VRS-CMRx"))
        assertEquals("CMR+", RtcmMountCompat.detectCmr("VRS-CMR+"))
        assertEquals("CMR", RtcmMountCompat.detectCmr("VRS-CMR"))
        assertNull(RtcmMountCompat.detectCmr("VRS-RTCM32"))
        assertEquals("CMRX", RtcmMountCompat.detectCmr("X", "CMRx", ""))
    }
    @Test fun r10Note() {
        assertEquals("R10 CMRx 사용 가능", RtcmMountCompat.r10CmrNote(r10, "VRS-CMRx"))
        assertNull(RtcmMountCompat.r10CmrNote(sp60, "VRS-CMRx"))
        assertNull(RtcmMountCompat.warnIfIncompatible(r10, "VRS-CMRx"))
    }
    @Test fun sp60Warn() {
        assertNotNull(RtcmMountCompat.warnIfIncompatible(sp60, "VRS-CMRx"))
        assertNull(RtcmMountCompat.warnIfIncompatible(sp60, "VRS-RTCM32"))
        assertFalse(RtcmMountCompat.isTooNew(sp60, "VRS-CMRx"))
    }
}
