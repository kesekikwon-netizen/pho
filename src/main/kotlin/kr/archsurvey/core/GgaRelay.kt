package kr.archsurvey.core

object GgaRelay {
    /** 캐스터 업로드용 GGA 정규화: $GNGGA 등은 $GPGGA 로 바꾸고 체크섬 재계산, CRLF 종료. 올바른 GGA 가 아니면 null */
    fun forCaster(raw: String?): String? {
        if (raw == null) return null
        val s = raw.trim()
        if (!s.startsWith("$") || s.length < 10) return null
        val star = s.lastIndexOf('*')
        val body = if (star > 0) s.substring(1, star) else s.substring(1)
        if (body.length < 5 || !body.substring(2, 5).equals("GGA")) return null
        val fixed = "GP" + body.substring(2)
        return NmeaParser.build(fixed)
    }
}
