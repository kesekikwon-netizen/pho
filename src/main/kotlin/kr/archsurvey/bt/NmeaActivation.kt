package kr.archsurvey.bt

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kr.archsurvey.core.NmeaParser
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.DeviceProfiles
import kotlin.coroutines.coroutineContext

/**
 * 연결 직후·데이터 없음일 때 NMEA(GGA)를 켜려는 후보 명령.
 * SP60: SP80 ICD / ProMark 800 공개 문서의 `$PASHS,NME,…` 형태(포트 C≈Bluetooth 추정). SP60 실기 동작은 [현장확인].
 * R10: 여기 후보는 없음 — 0.1.33 부터 [R10AutoConfig](Trimble DCOL 64h 바이너리)가 LinkSupervisor 에서 따로 켠다.
 */
data class NmeaCmd(val body: String, val label: String) {
    fun line(): String = NmeaParser.build(body)
}

enum class NmeaActOutcome { GGA_SEEN, NATURAL_GGA, ACK_SEEN_NO_GGA, NO_RESPONSE, SKIPPED }

data class NmeaActResult(
    val outcome: NmeaActOutcome,
    val tried: List<String>,
    val log: List<String>,
    val elapsedMs: Long,
)

object NmeaActivation {
    /** 수신기별 후보. 빈 목록 = 자동 명령 없음(감시만) */
    fun candidates(profile: DeviceProfile): List<NmeaCmd> = when (profile.id) {
        // 연결당 1회. 포트 C=BT는 SP80 문서 기준 — SP60 실기 미확인, 실패해도 다음으로 계속
        // 순서: PRT/NMO 조회 → OUT 재개(C→A→B) → NME GGA/GST/GSA(C) → DIF → 포트 A/B GGA → 주기생략
        // GSV: 학습 캐시에 SP60 `$PASHS,NME,GSV` 명시 근거 없음 → 0.1.13에서 생략(형제기종 NME 문법만으로는 넣지 않음)
        DeviceProfiles.SP60_ID -> listOf(
            NmeaCmd("PASHQ,PRT", "현재 포트 문자·보드레이트 조회"),
            NmeaCmd("PASHQ,NMO", "NMEA 출력 상태 조회"),
            NmeaCmd("PASHS,OUT,C,ON", "포트 C 주기 메시지 재개"),
            NmeaCmd("PASHS,OUT,A,ON", "포트 A 주기 메시지 재개"),
            NmeaCmd("PASHS,OUT,B,ON", "포트 B 주기 메시지 재개"),
            NmeaCmd("PASHS,NME,GGA,C,ON,1", "GGA 1Hz · 포트 C(Bluetooth 추정)"),
            NmeaCmd("PASHS,NME,GST,C,ON,1", "GST 1Hz · 포트 C"),
            NmeaCmd("PASHS,NME,GSA,C,ON,1", "GSA 1Hz · 포트 C"),
            NmeaCmd("PASHS,DIF,PRT,C,RT3", "포트 C RTCM3 보정 수신(RT3=3.0~3.2)"),
            NmeaCmd("PASHS,DIF,PRT,C,ALL", "포트 C 보정 수신 ALL"),
            NmeaCmd("PASHS,NME,GGA,A,ON,1", "GGA 1Hz · 포트 A"),
            NmeaCmd("PASHS,NME,GGA,B,ON,1", "GGA 1Hz · 포트 B"),
            NmeaCmd("PASHS,NME,GGA,C,ON", "GGA ON · 포트 C(주기 생략)"),
            NmeaCmd("PASHS,NME,GGA,A,ON", "GGA ON · 포트 A(주기 생략)"),
            NmeaCmd("PASHS,NME,GGA,B,ON", "GGA ON · 포트 B(주기 생략)"),
        )
        DeviceProfiles.R10_ID -> emptyList() // $PASH 문자열 명령 없음. R10 은 R10AutoConfig(DCOL) 가 담당
        else -> emptyList()
    }

    fun isNmeaIssue(err: BtError?): Boolean =
        err == BtError.NO_DATA || err == BtError.NO_GGA || err == BtError.NOT_NMEA

    /** 현장 체크리스트(진행 창·오류 카드). 모델별 안내를 붙인다 */
    fun fieldChecklist(profile: DeviceProfile): String = buildString {
        // 0.1.33-r10: R10 에는 SP60 항목(SPace·도난경보·시작잠금)이 없다. 앱이 R10 에 NMEA 켜기 명령을 보낼 수 없다는 것을 분명히
        if (profile.id == DeviceProfiles.R10_ID) {
            // 0.1.33: 앱이 연결 때 DCOL 64h 로 블루투스 포트 출력을 자동으로 켜 본다. 이 문구는 그게 실패했을 때만 보인다
            append("앱이 R10 블루투스 출력을 자동으로 켜 보았지만 위치(GGA)가 오지 않아요 · ① 다른 컨트롤러(Trimble Access 등)·다른 앱 연결 끊고 다시 연결 ② 그래도 안 되면 R10 웹 UI(Wi-Fi 192.168.142.1) 또는 Trimble 컨트롤러: I/O 설정 → Bluetooth 포트 → NMEA GGA·GST 1 Hz 켜기, 「Maintain configuration when connection dropped」 체크, 폰을 연결한 채 저장 ③ 다시 연결 · 자세한 결과는 진단 「R10 자동설정」")
            return@buildString
        }
        append("① 위성 LED(추적) ② 다른 폰·컨트롤러·SPace 끊기 ③ 도난경보·시작잠금 확인 ④ ")
        append(when (profile.id) {
            DeviceProfiles.SP60_ID -> "NMEA(GGA) 켜기(앱 자동 명령 · 안 되면 SPace/Survey Pro) ⑤ 다시 연결"
            DeviceProfiles.R10_ID -> "웹 UI에서 이 블루투스 포트 NMEA(GGA/GST) 켜기 ⑤ 다시 연결"
            else -> "NMEA(GGA) 켜기 ⑤ 다시 연결"
        })
    }

    /**
     * 자연 대기 → 후보 명령을 순차 전송 → 각 명령 뒤 GGA/ACK 감시.
     * [hasGga]가 true가 되면 즉시 성공. 쓰기는 블로킹일 수 있으므로 IO에서 호출.
     */
    suspend fun run(
        cmds: List<NmeaCmd>,
        write: (ByteArray) -> Unit,
        hasGga: () -> Boolean,
        clock: () -> Long,
        naturalWaitMs: Long = 3_000,
        perCmdWaitMs: Long = 1_400,
        afterAllWaitMs: Long = 2_500,
        onStatus: (String) -> Unit = {},
        sawAck: () -> Boolean = { false },
        clearAck: () -> Unit = {},
    ): NmeaActResult {
        val t0 = clock()
        val tried = ArrayList<String>()
        val log = ArrayList<String>()
        fun age() = clock() - t0
        if (hasGga()) {
            log += "자연 GGA 이미 있음 (${age()}ms)"
            return NmeaActResult(NmeaActOutcome.NATURAL_GGA, tried, log, age())
        }
        onStatus("위치(GGA) 기다리는 중…")
        val until = clock() + naturalWaitMs
        while (coroutineContext.isActive && clock() < until) {
            if (hasGga()) {
                log += "자연 GGA 수신 (${age()}ms)"
                return NmeaActResult(NmeaActOutcome.NATURAL_GGA, tried, log, age())
            }
            delay(100)
        }
        if (cmds.isEmpty()) {
            log += "자동 NMEA 명령 없음 — 수신기 설정·현장 체크리스트로"
            onStatus("수신기가 아직 위치(GGA)를 보내지 않아요 · 이 수신기는 앱이 NMEA를 켤 수 없어요(수신기 설정 확인)")
            val end = clock() + afterAllWaitMs
            while (coroutineContext.isActive && clock() < end) {
                if (hasGga()) return NmeaActResult(NmeaActOutcome.NATURAL_GGA, tried, log, age())
                delay(100)
            }
            return NmeaActResult(NmeaActOutcome.SKIPPED, tried, log, age())
        }
        var anyAck = false
        for (cmd in cmds) {
            if (!coroutineContext.isActive) break
            if (hasGga()) {
                log += "GGA 수신 (명령 도중, ${age()}ms)"
                return NmeaActResult(NmeaActOutcome.GGA_SEEN, tried, log, age())
            }
            clearAck()
            val line = cmd.line()
            tried += cmd.body
            onStatus("NMEA 켜는 중 · ${cmd.label}")
            log += "전송 ${cmd.body}"
            try {
                write(line.toByteArray(Charsets.US_ASCII))
            } catch (e: Exception) {
                log += "쓰기 실패: ${e.javaClass.simpleName}"
                break
            }
            val cmdEnd = clock() + perCmdWaitMs
            while (coroutineContext.isActive && clock() < cmdEnd) {
                if (hasGga()) {
                    log += "GGA 수신 after ${cmd.body} (${age()}ms)"
                    return NmeaActResult(NmeaActOutcome.GGA_SEEN, tried, log, age())
                }
                if (sawAck()) { anyAck = true; log += "ACK after ${cmd.body}" }
                delay(80)
            }
            yield()
        }
        val end = clock() + afterAllWaitMs
        onStatus("명령 전송 끝 · GGA 감시 중")
        while (coroutineContext.isActive && clock() < end) {
            if (hasGga()) {
                log += "GGA 수신 (명령 후 대기, ${age()}ms)"
                return NmeaActResult(NmeaActOutcome.GGA_SEEN, tried, log, age())
            }
            delay(100)
        }
        val out = if (anyAck) NmeaActOutcome.ACK_SEEN_NO_GGA else NmeaActOutcome.NO_RESPONSE
        log += "결과 $out (${age()}ms, 시도 ${tried.size}개)"
        return NmeaActResult(out, tried, log, age())
    }
}
