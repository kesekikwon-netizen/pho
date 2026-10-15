package kr.archsurvey.t0158

import kr.archsurvey.diag.CrashLog
import kr.archsurvey.ntrip.NtripConfig
import org.junit.Assert.*
import org.junit.Test

class Redact0158Test {
    @Test fun authHeaderAndUserMasked() {
        val s = CrashLog.redact("GET /VRS-RTCM34 HTTP/1.1\r\nUser-Agent: NTRIP x\r\nAuthorization: Basic QUJDOkRFRg==\r\nuser=kim01 password=secret9")
        assertFalse(s.contains("QUJDOkRFRg")); assertFalse(s.contains("kim01")); assertFalse(s.contains("secret9"))
        assertTrue(s.contains("User-Agent: NTRIP x"))
    }
    @Test fun registeredSecretMaskedAnywhere() {
        CrashLog.registerSecret("fieldUser77"); CrashLog.registerSecret("pw-Zx81")
        val s = CrashLog.redact("401 for fieldUser77 with pw-Zx81 at host")
        assertFalse(s.contains("fieldUser77")); assertFalse(s.contains("pw-Zx81"))
    }
    @Test fun configToStringHidesUser() {
        val t = NtripConfig(host = "rts1.ngii.go.kr", port = 2101, mountPoint = "VRS-RTCM34", username = "abcuser", password = "zz").toString()
        assertFalse(t.contains("abcuser")); assertFalse(t.contains("zz,"))
    }
}
