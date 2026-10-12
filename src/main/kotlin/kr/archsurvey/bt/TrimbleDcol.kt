package kr.archsurvey.bt

/**
 * 0.1.33 R10 자동설정: Trimble Data Collector Format(DCOL, "TRIMCOMM") 패킷 — 이 앱이 쓰는 최소 부분만.
 *
 * 근거(build-reports/r10-autoconfig-research.md):
 * - 패킷: STX 02 · STATUS · TYPE · LENGTH · DATA · CHECKSUM=(STATUS+TYPE+LENGTH+DATA) mod 256 · ETX 03
 *   https://receiverhelp.trimble.com/oem-gnss/api-data-collector-format-packet-structure.html
 * - 64h APPFILE: TX# · PAGE 0 · MAXPAGE 0 · 파일제어블록(03 규격판, 00 모든 기기, 01 즉시 적용, 00 공장값 리셋 안 함) · 레코드
 * - 07h 출력 메시지 레코드: 07 04 TYPE PORT FREQ OFFSET. 포트(0-based) 17·18·19 = Bluetooth 가상 시리얼. FREQ 03 = 1 Hz, 00 = 끔
 *   https://receiverhelp.trimble.com/oem-gnss/icd-command64h-appfile-output.html
 * - ENQ 05 → ACK 06 / NAK 15. 64h 의 응답도 단일 바이트 06/15
 *
 * 안전: 메시지 종류 FF(모든 포트 끄기)·00(그 포트 전부 끄기), 공장값 플래그, 응용파일 삭제/활성화(68h/6Dh)는 **만들 수조차 없게** 막는다.
 * 허용 종류는 NMEA GGA/GST/GSA/GSV 뿐, 포트는 Bluetooth 17–19 뿐, 주기는 1 Hz·끔 뿐.
 */
object TrimbleDcol {
    const val STX = 0x02
    const val ETX = 0x03
    const val ENQ = 0x05
    const val ACK = 0x06
    const val NAK = 0x15
    const val TYPE_APPFILE = 0x64

    // 출력 메시지 종류 (ICD "Output message type byte values")
    const val NMEA_GGA = 0x06
    const val NMEA_GST = 0x0D
    const val NMEA_GSV = 0x12
    const val NMEA_GSA = 0x26
    val SAFE_TYPES: Map<String, Int> = linkedMapOf("GGA" to NMEA_GGA, "GST" to NMEA_GST, "GSA" to NMEA_GSA, "GSV" to NMEA_GSV)

    const val FREQ_OFF = 0x00
    const val FREQ_1HZ = 0x03

    /** Bluetooth 가상 시리얼 포트(0-based). R10 웹 UI 「Bluetooth 1/2/3」과 같은지는 [현장확인] */
    val BT_PORTS: List<Int> = listOf(17, 18, 19)

    fun enq(): ByteArray = byteArrayOf(ENQ.toByte())

    /** 일반 DCOL 패킷. 길이 255 초과 금지 */
    fun packet(type: Int, data: ByteArray, status: Int = 0): ByteArray {
        require(data.size <= 255) { "DCOL data too long" }
        val out = ByteArray(data.size + 6)
        out[0] = STX.toByte(); out[1] = status.toByte(); out[2] = type.toByte(); out[3] = data.size.toByte()
        System.arraycopy(data, 0, out, 4, data.size)
        var cs = status + type + data.size
        for (b in data) cs += b.toInt() and 0xFF
        out[4 + data.size] = (cs and 0xFF).toByte()
        out[5 + data.size] = ETX.toByte()
        return out
    }

    /** 07h 출력 메시지 레코드(NMEA, 4바이트형). 허용 범위 밖은 예외 — 위험한 레코드는 만들 수 없다 */
    fun outputRecord(nmeaType: Int, port: Int, freq: Int): ByteArray {
        require(nmeaType in SAFE_TYPES.values) { "message type 0x%02X not allowed".format(nmeaType) }
        require(port in BT_PORTS) { "port $port not allowed" }
        require(freq == FREQ_1HZ || freq == FREQ_OFF) { "freq $freq not allowed" }
        return byteArrayOf(0x07, 0x04, nmeaType.toByte(), port.toByte(), freq.toByte(), 0x00)
    }

    /** 한 페이지짜리 64h APPFILE. 즉시 적용·공장값 리셋 안 함 고정 */
    fun appFile(tx: Int, records: List<ByteArray>): ByteArray {
        require(records.isNotEmpty())
        val body = ArrayList<Byte>()
        body += (tx and 0xFF).toByte(); body += 0; body += 0          // TX#, PAGE, MAXPAGE
        body += 0x03; body += 0x00; body += 0x01; body += 0x00         // 규격판 3, 모든 기기, 즉시 적용, 공장값 아님
        for (r in records) for (b in r) body += b
        return packet(TYPE_APPFILE, body.toByteArray())
    }

    /** 문장 이름(GGA…)들을 [port] 에서 1 Hz(on) 또는 끔(off) */
    fun setOutputs(tx: Int, port: Int, sentences: List<String>, on: Boolean): ByteArray =
        appFile(tx, sentences.map { outputRecord(SAFE_TYPES.getValue(it), port, if (on) FREQ_1HZ else FREQ_OFF) })

    /**
     * 0.1.41 양방향 확인(읽기 전용): 06h GETSERIAL → 07h RSERIAL(수신기 일련번호). 설정을 바꾸지 않는다.
     * https://receiverhelp.trimble.com/oem-gnss/icd-command06h-getserial.html
     */
    const val TYPE_GETSERIAL = 0x06
    const val TYPE_RSERIAL = 0x07
    fun getSerial(): ByteArray = packet(TYPE_GETSERIAL, ByteArray(0))
    /** RSERIAL 데이터 앞부분의 글자(일련번호) */
    fun serialText(data: ByteArray): String = data.take(8).map { it.toInt() and 0xFF }.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()

    /** 0.1.41: 블루투스 포트 여러 개에 한 번에(같은 허용 범위 검사) */
    fun setOutputsPorts(tx: Int, ports: List<Int>, sentences: List<String>, on: Boolean): ByteArray =
        appFile(tx, ports.flatMap { p -> sentences.map { outputRecord(SAFE_TYPES.getValue(it), p, if (on) FREQ_1HZ else FREQ_OFF) } })

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}

sealed interface DcolEvent {
    object Ack : DcolEvent { override fun toString() = "ACK" }
    object Nak : DcolEvent { override fun toString() = "NAK" }
    data class Frame(val status: Int, val type: Int, val data: ByteArray) : DcolEvent {
        override fun toString() = "FRAME %02Xh len=%d".format(type, data.size)
    }
}

/**
 * 수신 스트림 관찰자: NMEA 문장 밖의 ACK(06)·NAK(15)와 체크섬이 맞는 DCOL 프레임(02…03)을 골라낸다.
 * NMEA 파서와 따로 같은 바이트를 본다(NMEA 문장 텍스트에는 02/06/15 가 나올 수 없다).
 * 프레임 체크섬·ETX 가 틀리면 버리고 다시 맞춘다. 어떤 입력에도 예외를 던지지 않는다.
 */
class DcolScanner {
    private enum class St { IDLE, STATUS, TYPE, LEN, DATA, CS, ETX }
    private var st = St.IDLE
    private var status = 0; private var type = 0; private var len = 0; private var sum = 0; private var cs = 0
    private var data = ByteArray(0); private var pos = 0
    var badFrames = 0L; private set

    fun feed(buf: ByteArray, n: Int = buf.size): List<DcolEvent> {
        var out: ArrayList<DcolEvent>? = null
        fun add(e: DcolEvent) { (out ?: ArrayList<DcolEvent>(2).also { out = it }).add(e) }
        for (i in 0 until n) {
            val b = buf[i].toInt() and 0xFF
            when (st) {
                St.IDLE -> when (b) {
                    TrimbleDcol.ACK -> add(DcolEvent.Ack)
                    TrimbleDcol.NAK -> add(DcolEvent.Nak)
                    TrimbleDcol.STX -> st = St.STATUS
                }
                St.STATUS -> { status = b; sum = b; st = St.TYPE }
                St.TYPE -> { type = b; sum += b; st = St.LEN }
                St.LEN -> { len = b; sum += b; data = ByteArray(len); pos = 0; st = if (len == 0) St.CS else St.DATA }
                St.DATA -> { data[pos++] = b.toByte(); sum += b; if (pos >= len) st = St.CS }
                St.CS -> { cs = b; st = St.ETX }
                St.ETX -> {
                    if (b == TrimbleDcol.ETX && (sum and 0xFF) == cs) add(DcolEvent.Frame(status, type, data)) else badFrames++
                    st = St.IDLE
                }
            }
        }
        return out ?: emptyList()
    }
}
