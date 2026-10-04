package kr.archsurvey

import kr.archsurvey.ai.AiLinkSetting
import kr.archsurvey.ai.StatusSnapshot
import kr.archsurvey.ai.StatusSnapshotBuilder
import kr.archsurvey.core.*
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.DrawerMenu
import kr.archsurvey.ui.Phase
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/** WP-8: StatusSnapshot 에 좌표·계정이 들어갈 자리가 없음을 확인한다. */
class AiSnapshotTests {
    // 일부러 눈에 띄는 값 — 출력 어디에도 나오면 안 된다
    private val LAT = 37.123456789
    private val LON = 127.987654321
    private val SECRET_MOUNT = "VRS-RTCM31-SECRET"

    private fun gga() = GgaMessage(
        raw = "\$GNGGA,120000.00,3707.40740,N,12759.25926,E,4,18,0.7,55.5,M,25.0,M,1.0,$SECRET_MOUNT*00",
        utcTime = "120000.00", lat = LAT, lon = LON, quality = FixQuality.RTK_FIXED, satellites = 18, hdop = 0.7,
        altitudeMsl = 55.5, geoidSeparation = 25.0, correctionAgeSec = 1.0, baseStationId = SECRET_MOUNT,
    )
    private fun gst() = GstMessage("\$GNGST", 0.5, 0.008, 0.006, 0.012)
    private fun snap(now: Long = 1_000_000L) = StatusSnapshotBuilder.build(
        nowMs = 1_700_000_000_000L, appVersionCode = 1,
        gnss = GnssSnapshot(gga = gga(), gst = gst(), lastGgaAtMs = now - 300, lastAnyAtMs = now - 300, sentencesTotal = 120, checksumErrors = 2),
        link = LinkStatus(LinkState.STREAMING, attempt = 1, failures = 0),
        sourceClockMs = now, phase = Phase.IDLE, savedPointCount = 7, ntrip = NtripState.RECEIVING, batteryPct = 64,
    )

    @Test fun builderReducesStateToExpectedValues() {
        val s = snap()
        assertEquals(1, s.schema)
        assertEquals(SurveyStatus.FIXED, s.surveyStatus)
        assertEquals(FixQuality.RTK_FIXED, s.fixQuality)
        assertEquals(18, s.satellites)
        assertEquals(300L, s.dataAgeMs)
        assertEquals(7, s.savedPointCount)
        assertEquals(NtripState.RECEIVING, s.ntrip)
        assertEquals(0.01, s.sigmaHM!!, 1e-3)   // sqrt(0.008²+0.006²)
    }

    @Test fun neverReceivedGgaGivesNullAge() {
        val s = StatusSnapshotBuilder.build(1L, 1, GnssSnapshot(), LinkStatus(LinkState.CONNECTING), 5_000, Phase.IDLE, 0)
        assertNull(s.dataAgeMs); assertEquals(SurveyStatus.UNAVAILABLE, s.surveyStatus); assertEquals(0, s.satellites)
    }

    /** 필드 타입은 숫자·불리언·열거형(+null)뿐. 문자열·객체·컬렉션이 끼어들 수 없다. */
    @Test fun onlyEnumNumberBooleanFields() {
        val allowed = setOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, java.lang.Integer::class.java, java.lang.Long::class.java, java.lang.Double::class.java,
            java.lang.Boolean::class.java)
        val fields = StatusSnapshot::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertTrue(fields.size >= 15)
        for (f in fields) assertTrue("허용되지 않는 필드 타입: ${f.name}: ${f.type}", f.type in allowed || f.type.isEnum)
    }

    /** 이름으로도 막는다: 좌표·계정·주소·이름·메모 류 */
    @Test fun noCoordinateOrAccountFieldNames() {
        val banned = listOf("lat", "lon", "east", "north", "coord", "position", "pos", "alt", "height", "user", "pass", "pw", "account",
            "id", "token", "key", "mount", "host", "url", "addr", "mac", "name", "memo", "note", "text", "message", "msg", "photo", "path")
        val names = StatusSnapshot::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name.lowercase() }
        for (n in names) for (b in banned) {
            // "satellites"(lat 포함) "schema" "sentencesTotal" 등 정상 이름이 걸리지 않게 단어 경계(앞 소문자 시작/대문자 경계)로 검사
            val words = Regex("[a-z]+").findAll(
                StatusSnapshot::class.java.declaredFields.first { it.name.lowercase() == n }.name.replace(Regex("([A-Z])"), " $1").lowercase()
            ).map { it.value }.toList()
            assertFalse("금지된 단어 '$b' 가 필드 이름에 있음: $n", b in words)
        }
        // 정상 이름 몇 개는 통과해야(검사가 너무 느슨하지 않은지 확인용)
        assertTrue("satellites" in names)
    }

    @Test fun outputsContainNoCoordinatesOrSecrets() {
        val s = snap()
        val text = s.toJson() + s.toFlatMap().toString()
        for (bad in listOf("37.1234", "127.9876", "3707.40", "12759.25", SECRET_MOUNT, "RTCM", "GNGGA")) assertFalse("나오면 안 됨: $bad", text.contains(bad))
        // 값은 숫자/불리언/null/영문 대문자 열거형 이름뿐
        for ((k, v) in s.toFlatMap()) when (v) {
            null, is Int, is Long, is Double, is Boolean -> {}
            is String -> assertTrue("$k=$v", Regex("[A-Z][A-Z0-9_]*").matches(v))
            else -> fail("예상 밖 타입 $k: ${v::class}")
        }
        // JSON 모양
        assertTrue(s.toJson().startsWith("{\"schema\":1,\"atMs\":1700000000000"))
        assertEquals(s.toFlatMap().size, Regex("\":").findAll(s.toJson()).count())
    }

    @Test fun deterministicAndReadOnly() {
        assertEquals(snap(), snap())
        // GnssSnapshot/LinkStatus 는 불변 data class — 빌더가 입력을 바꿀 수 없다
        val g = GnssSnapshot(gga = gga()); val before = g.copy()
        StatusSnapshotBuilder.build(1, 1, g, LinkStatus(), 2000, Phase.IDLE, 0)
        assertEquals(before, g)
    }

    @Test fun settingIsFixedOffAndMenuSlotIsDisabled() {
        assertFalse(AiLinkSetting.enabled)
        assertFalse(AiLinkSetting.TRANSMISSION_IMPLEMENTED)
        val item = DrawerMenu.allItems.single { it.id == "ai_toggle" }
        assertEquals(AiLinkSetting.LABEL, item.label); assertFalse(item.enabled)
        assertTrue(item.note!!.contains("꺼짐"))
    }

    /** ai 패키지 소스에 네트워크·파일 쓰기·설정 저장 코드가 없다 */
    @Test fun aiPackageHasNoTransmissionCode() {
        val dir = File("src/main/kotlin/kr/archsurvey/ai")
        val files = dir.listFiles { f -> f.extension == "kt" }!!.toList()
        assertTrue(files.isNotEmpty())
        val banned = listOf("java.net", "javax.net", "okhttp", "HttpURLConnection", "Socket", "WebSocket", "URL(", "retrofit",
            "FileOutputStream", "SharedPreferences", "FirebaseMessaging", "WorkManager", "startService", "Intent(")
        for (f in files) {
            // 설명 주석은 제외하고 코드 줄만 본다
            val code = f.readLines().filter { val t = it.trim(); !(t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) }.joinToString("\n")
            for (b in banned) assertFalse("${f.name} 에 $b", code.contains(b))
        }
    }
}
