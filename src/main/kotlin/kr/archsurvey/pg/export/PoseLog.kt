package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Session
import java.util.Locale

/** 0.0.12 사진별 자세 기록(poses.csv): ARCore 로컬, 세계←카메라(OpenGL 축), 사원수, 내부표정(이 JPG 화소), 시각, 촬영 이유 */
object PoseLog {
    const val FILE = "poses.csv"
    const val HEADER = "id,file,time_ms,tx,ty,tz,qx,qy,qz,qw,fx,fy,cx,cy,width,height,reason,tags"
    fun csv(s: Session): String = buildString {
        append(HEADER).append("\r\n")
        for (st in s.stills) {
            val p = st.pose
            fun f(v: Double) = String.format(Locale.US, "%.6f", v)
            append(st.id).append(',').append(st.file).append(',').append(st.time).append(',')
            if (p != null) append(p.joinToString(",") { f(it) }) else append(",,,,,,")
            append(',').append(f(st.fx)).append(',').append(f(st.fy)).append(',').append(f(st.cx)).append(',').append(f(st.cy))
            append(',').append(st.w).append(',').append(st.h).append(',').append(st.reason ?: "")
            append(',').append(st.tags.joinToString(" ") { "#" + kr.archsurvey.pg.tag.TargetNaming.targetNo(it.id) })
            append("\r\n")
        }
    }
}
