package kr.archsurvey.pg.r10

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale

/** 0.0.14: EXIF GPS 위경도·높이(해발) + 수평 정확도 + XMP(정확도·FIX). FIX 만 위치를 넣음 */
object GeoExif {
    fun xmp(t: GnssTag?, levered: Boolean, c: GeoCam.Cam? = null): String {
        val f = { v: Double? -> v?.let { String.format(Locale.US, "%.3f", it) } ?: "" }
        return """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:Camera="http://pix4d.com/camera/1.0/" xmlns:pg="http://archsurvey.kr/pg/1.0/" Camera:GPSXYAccuracy="${f(c?.rmse ?: t?.sigmaH)}" Camera:GPSZAccuracy="${f(if (c?.tier == GeoCam.Tier.XY) t?.sigmaU else c?.rmse ?: t?.sigmaU)}" pg:Fix="${t?.quality ?: ""}" pg:LeverArm="$levered" pg:RawLat="${t?.lat ?: ""}" pg:RawLon="${t?.lon ?: ""}" pg:RawAlt="${t?.altMsl ?: ""}" pg:FitTier="${c?.tier ?: "RAW"}" pg:FitRmse="${f(c?.rmse)}" pg:LagMs="${c?.lagMs ?: ""}" pg:Yaw="${f(c?.yawDeg)}" pg:Speed="${f(c?.speed)}" pg:Height="MSL"/></rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""
    }

    fun write(file: File, lat: Double, lon: Double, altMsl: Double?, t: GnssTag?, levered: Boolean, c: GeoCam.Cam? = null) {
        ExifInterface(file).apply {
            setLatLong(lat, lon)
            altMsl?.let { setAltitude(it) }
            (c?.rmse ?: t?.sigmaH)?.let { setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, String.format(Locale.US, "%d/1000", (it * 1000).toLong())) }
            setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, "WGS-84")
            setAttribute(ExifInterface.TAG_XMP, xmp(t, levered, c))
            saveAttributes()
        }
    }
}
