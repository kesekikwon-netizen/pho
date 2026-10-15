package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Session
import java.util.Locale

/** 0.0.12 사진별 자세 기록(poses.csv): ARCore 로컬, 세계←카메라(OpenGL 축), 사원수, 내부표정(이 JPG 화소), 시각, 촬영 이유 */
object PoseLog {
    const val FILE = "poses.csv"
    const val HEADER = "id,file,time_ms,tx,ty,tz,qx,qy,qz,qw,fx,fy,cx,cy,width,height,reason,tags," +
        "r10_lat,r10_lon,r10_alt_msl,r10_quality,r10_fix,r10_sigma_h,r10_sigma_u,r10_age_ms,r10_sats,cam_epsg,cam_e,cam_n,cam_h,fit_tier,fit_rmse_m,lag_ms,yaw_deg,speed_mps"
    fun csv(s: Session, geo: kr.archsurvey.pg.r10.GeoCam.Result = kr.archsurvey.pg.r10.GeoCam.compute(s)): String = buildString {
        append(HEADER).append("\r\n")
        for (st in s.stills) {
            val p = st.pose
            fun f(v: Double) = String.format(Locale.US, "%.6f", v)
            append(st.id).append(',').append(st.file).append(',').append(st.time).append(',')
            if (p != null) append(p.joinToString(",") { f(it) }) else append(",,,,,,")
            append(',').append(f(st.fx)).append(',').append(f(st.fy)).append(',').append(f(st.cx)).append(',').append(f(st.cy))
            append(',').append(st.w).append(',').append(st.h).append(',').append(st.reason ?: "")
            append(',').append(st.tags.joinToString(" ") { "#" + kr.archsurvey.pg.tag.TargetNaming.targetNo(it.id) })
            val g = st.gnss
            fun o(v: Double?, d: Int) = v?.let { String.format(Locale.US, "%.${d}f", it) } ?: ""
            if (g != null) append(',').append(o(g.lat, 9)).append(',').append(o(g.lon, 9)).append(',').append(o(g.altMsl, 4)).append(',').append(g.quality).append(',').append(if (g.fixed) 1 else 0)
                .append(',').append(o(g.sigmaH, 4)).append(',').append(o(g.sigmaU, 4)).append(',').append(g.ageMs).append(',').append(g.sats)
            else append(",,,,,,,,,")
            val c = geo.cams[st.id]
            if (c != null) append(',').append(geo.epsg).append(',').append(o(c.e, 4)).append(',').append(o(c.n, 4)).append(',').append(o(c.h, 4)).append(',').append(c.tier).append(',').append(o(c.rmse, 4)).append(',').append(c.lagMs).append(',').append(o(c.yawDeg, 3))
            else append(",,,,,,,,")
            append(',').append(o(st.speed, 3))
            append("\r\n")
        }
    }
}
