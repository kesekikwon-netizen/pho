package kr.archsurvey.comm

import kr.archsurvey.core.NmeaParser
import kr.archsurvey.ntrip.Crc24q

object CommTestUtil {
    fun rtcmFrame(type: Int, payloadLen: Int = 12, fill: Int = 0x55): ByteArray {
        val len = payloadLen
        val p = ByteArray(len) { fill.toByte() }
        p[0] = (type shr 4).toByte(); p[1] = ((type and 0xF) shl 4).toByte()
        val body = byteArrayOf(0xD3.toByte(), (len shr 8).toByte(), len.toByte()) + p
        val crc = Crc24q.compute(body)
        return body + byteArrayOf((crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    fun gga(time: String = "092751.00", q: Int = 4, age: String = "1.0", lat: String = "3733.9900", lon: String = "12658.6800", sats: Int = 12): String =
        NmeaParser.build("GNGGA,$time,$lat,N,$lon,E,$q,$sats,0.9,45.5,M,25.3,M,$age,0001")

    fun gst(time: String = "092751.00", sLat: String = "0.01", sLon: String = "0.01"): String =
        NmeaParser.build("GNGST,$time,1.2,0.01,0.01,0,$sLat,$sLon,0.02")
}
