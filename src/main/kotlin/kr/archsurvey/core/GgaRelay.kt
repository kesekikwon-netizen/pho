package kr.archsurvey.core

object GgaRelay {
    /**
     * 캐스터 업로드용 GGA 정규화: $GNGGA 등은 $GPGGA 로 바꾸고 체크섬 재계산, CRLF 종료.
     * 올바른 GGA 가 아니거나, **위치가 없는 GGA(품질 0·위·경도 빈칸)** 면 null — 빈 위치를 VRS 에 올리면 서버가 접속을 끊거나 엉뚱한 기준점을 줄 수 있다.
     */
    fun forCaster(raw: String?): String? {
        if (raw == null) return null
        val s = raw.trim()
        if (!s.startsWith("$") || s.length < 10) return null
        val star = s.lastIndexOf('*')
        val body = if (star > 0) s.substring(1, star) else s.substring(1)
        if (body.length < 5 || !body.substring(2, 5).equals("GGA")) return null
        val fixed = "GP" + body.substring(2)
        val out = NmeaParser.build(fixed)
        val parsed = NmeaParser().parseLine(out) as? GgaMessage ?: return null
        return if (parsed.hasPosition) out else null
    }
}
