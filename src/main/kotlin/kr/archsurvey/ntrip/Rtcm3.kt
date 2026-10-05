package kr.archsurvey.ntrip

/** CRC-24Q (RTCM 3 / Qualcomm). 다항식 0x1864CFB, 초기값 0. 검사값: "123456789" -> 0xCDE703 */
object Crc24q {
    private val TABLE = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 16
            repeat(8) { c = if (c and 0x800000 != 0) (c shl 1) xor 0x1864CFB else c shl 1 }
            t[i] = c and 0xFFFFFF
        }
    }

    fun compute(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
        var crc = 0
        for (i in off until off + len) {
            crc = ((crc shl 8) and 0xFFFFFF) xor TABLE[((crc shr 16) xor (buf[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }
}

/**
 * RTCM 3 프레임 분리기. 스트림에서 0xD3 + 길이(10비트) + 본문 + CRC24Q 를 찾아 **온전한 프레임만** 내보낸다.
 * - BT 로 중간에 끊긴 조각·캐스터가 보낸 HTML/오류 문구 같은 쓰레기를 수신기로 넘기지 않기 위함.
 * - CRC 가 틀리면 프리앰블 다음 바이트부터 다시 동기화한다.
 * - 메시지 번호(1005/1077 등)별 개수를 세어 두면 "보정 데이터 종류"를 화면에 보일 수 있다.
 * 스레드 안전하지 않음(수신 스레드 하나가 호출).
 */
class Rtcm3Framer(private val maxBuffer: Int = 2048) {
    private var buf = ByteArray(maxBuffer)
    private var len = 0
    var framesOk = 0L; private set
    var crcErrors = 0L; private set
    var skippedBytes = 0L; private set
    var lastFrameAtMs = 0L; private set
    val messageCounts = HashMap<Int, Int>()

    /** 바이트를 공급하고 완성된 프레임마다 [onFrame] 호출(새 배열). [nowMs] 는 호출자 시계. */
    fun feed(src: ByteArray, n: Int, nowMs: Long, onFrame: (ByteArray) -> Unit) {
        var i = 0
        while (i < n) {
            val room = buf.size - len
            val take = minOf(room, n - i)
            System.arraycopy(src, i, buf, len, take)
            len += take; i += take
            drain(nowMs, onFrame)
            if (take == 0 && len >= buf.size) { // 이론상 불가(프레임 최대 1029B)이지만 방어
                skippedBytes += len; len = 0
            }
        }
    }

    private fun drain(nowMs: Long, onFrame: (ByteArray) -> Unit) {
        var pos = 0
        while (pos < len) {
            if ((buf[pos].toInt() and 0xFF) != 0xD3) { pos++; skippedBytes++; continue }
            if (len - pos < 3) break
            val plen = ((buf[pos + 1].toInt() and 0x03) shl 8) or (buf[pos + 2].toInt() and 0xFF)
            // 예약 비트(상위 6비트)는 0 이어야 한다
            if ((buf[pos + 1].toInt() and 0xFC) != 0) { pos++; skippedBytes++; continue }
            val total = 3 + plen + 3
            if (len - pos < total) break
            val crcCalc = Crc24q.compute(buf, pos, 3 + plen)
            val crcGot = ((buf[pos + 3 + plen].toInt() and 0xFF) shl 16) or ((buf[pos + 4 + plen].toInt() and 0xFF) shl 8) or (buf[pos + 5 + plen].toInt() and 0xFF)
            if (crcCalc != crcGot) { crcErrors++; pos++; skippedBytes++; continue }
            val frame = buf.copyOfRange(pos, pos + total)
            if (plen >= 2) {
                val type = ((buf[pos + 3].toInt() and 0xFF) shl 4) or ((buf[pos + 4].toInt() and 0xFF) shr 4)
                messageCounts[type] = (messageCounts[type] ?: 0) + 1
            }
            framesOk++; lastFrameAtMs = nowMs
            pos += total
            onFrame(frame)
        }
        if (pos > 0) { System.arraycopy(buf, pos, buf, 0, len - pos); len -= pos }
    }
}
