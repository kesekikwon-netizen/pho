package kr.archsurvey.pg.r10

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale

/** 0.0.14: EXIF GPS 위경도·높이(해발) + 수평 정확도 + XMP(정확도·FIX). FIX 만 위치를 넣음 */
object GeoExif {
    /** 0.0.15: Camera:Yaw/Pitch/Roll = 사진마다 실제 카메라 방향(Yaw 는 맞춤 있을 때만). pg:FitYaw = AR→방위 회전 */
    fun xmp(t: GnssTag?, levered: Boolean, c: GeoCam.Cam? = null, pitch: Double? = c?.camPitch, roll: Double? = c?.camRoll): String {
        val f = { v: Double? -> v?.let { String.format(Locale.US, "%.3f", it) } ?: "" }
        val yaw = c?.camYaw
        val ori = (if (yaw != null) " Camera:Yaw=\"${f(yaw)}\"" else "") + (if (pitch != null) " Camera:Pitch=\"${f(pitch)}\"" else "") + (if (roll != null) " Camera:Roll=\"${f(roll)}\"" else "")
        return """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:Camera="http://pix4d.com/camera/1.0/" xmlns:pg="http://archsurvey.kr/pg/1.0/" Camera:GPSXYAccuracy="${f(c?.rmse ?: t?.sigmaH)}" Camera:GPSZAccuracy="${f(c?.sigmaZ ?: t?.sigmaU)}"$ori pg:Fix="${t?.quality ?: ""}" pg:LeverArm="$levered" pg:RawLat="${t?.lat ?: ""}" pg:RawLon="${t?.lon ?: ""}" pg:RawAlt="${t?.altMsl ?: ""}" pg:FitTier="${c?.tier ?: "RAW"}" pg:FitRmse="${f(c?.rmse)}" pg:LagMs="${c?.lagMs ?: ""}" pg:FitYaw="${f(c?.yawDeg)}" pg:Speed="${f(c?.speed)}" pg:ArSession="${c?.arSid ?: ""}" pg:Height="MSL"/></rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""
    }

    /** 0.0.16: 이미 연 ExifInterface 에 GPS·XMP 만 넣음(저장은 부르는 쪽이 한 번) */
    fun apply(e: ExifInterface, lat: Double, lon: Double, altMsl: Double?, t: GnssTag?, levered: Boolean, c: GeoCam.Cam? = null, pitch: Double? = c?.camPitch, roll: Double? = c?.camRoll) {
        e.setLatLong(lat, lon)
        altMsl?.let { e.setAltitude(it) }
        (c?.rmse ?: t?.sigmaH)?.let { e.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, String.format(Locale.US, "%d/1000", (it * 1000).toLong())) }
        e.setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, "WGS-84")
        e.setAttribute(ExifInterface.TAG_XMP, xmp(t, levered, c, pitch, roll))
    }

    fun write(file: File, lat: Double, lon: Double, altMsl: Double?, t: GnssTag?, levered: Boolean, c: GeoCam.Cam? = null, pitch: Double? = c?.camPitch, roll: Double? = c?.camRoll) {
        ExifInterface(file).apply {
            setLatLong(lat, lon)
            altMsl?.let { setAltitude(it) }
            (c?.rmse ?: t?.sigmaH)?.let { setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, String.format(Locale.US, "%d/1000", (it * 1000).toLong())) }
            setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, "WGS-84")
            setAttribute(ExifInterface.TAG_XMP, xmp(t, levered, c, pitch, roll))
            saveAttributes()
        }
    }
}
