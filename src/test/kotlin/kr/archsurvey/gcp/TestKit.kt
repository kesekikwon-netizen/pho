package kr.archsurvey.gcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.archsurvey.core.*
import kr.archsurvey.device.GnssSource
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.gcp.model.*
import kr.archsurvey.gcp.ui.*

/** 시험용 수신기: 손으로 GGA·GST 를 흘린다(앱 본체에는 가짜 GNSS 없음) */
class TestGnss(state: LinkState = LinkState.STREAMING) : GnssSource {
    val l = MutableStateFlow(LinkStatus(state))
    val g = MutableStateFlow(GnssSnapshot())
    var now = 1_000_000L
    private var sec = 0
    override val link: StateFlow<LinkStatus> = l
    override val gnss: StateFlow<GnssSnapshot> = g
    override fun clockMs() = now
    override fun stop() {}

    /** 강릉 근처 한 점(EPSG:5187 E≈194884, N≈573986 근처) */
    fun emit(q: FixQuality = FixQuality.RTK_FIXED, lat: Double = 37.7712, lon: Double = 128.8790, msl: Double? = 5.8, sigma: Double? = 0.006, age: Double? = 1.0) {
        now += 1000; sec++
        val gga = GgaMessage("", "%06d.00".format(sec), lat, lon, q, 20, 0.7, msl, 26.0, age, "0149")
        val gst = sigma?.let { GstMessage("", 0.1, it / Math.sqrt(2.0), it / Math.sqrt(2.0), 0.013) }
        g.value = g.value.copy(gga = gga, gst = gst ?: g.value.gst, lastGgaAtMs = now, lastAnyAtMs = now, lastGstAtMs = if (gst != null) now else g.value.lastGstAtMs)
    }
}

class MemPrefs : GcpPrefs {
    override var epochs = 3
    override var operator = ""
    override var lastEpsg = 5187
    override var volumeKey = false
    override var lastSiteId = -1L
    val poles = HashMap<String, Double>()
    override fun pole(profileId: String) = poles[profileId]
    override fun setPole(profileId: String, m: Double) { poles[profileId] = m }
}

class MemFiles : GcpFilesPort {
    val saved = ArrayList<Pair<String, String>>()   // 파일 이름 → XML
    var zips = ArrayList<HandoffPlan>()
    var fail = false
    override fun saveSession(book: SiteBook, o: Obj, s: Session): SaveOutcome {
        if (fail) return SaveOutcome(false, Names.xmlName(o, s, book.site.epsg), "", "디스크 가득")
        val name = "${Names.objRel(book.site, o)}/${Names.xmlName(o, s, book.site.epsg)}"
        saved += name to ItwinXml.write(book.active(s), book.site.epsg)
        return SaveOutcome(true, Names.xmlName(o, s, book.site.epsg), whereLabel(book.site))
    }
    override fun shareSession(book: SiteBook, o: Obj, s: Session) = true
    override fun shareZip(plan: HandoffPlan): Boolean { zips += plan; return true }
    override fun whereLabel(site: Site) = "Documents/iTwinGCP/${Names.siteFolder(site)}"
}

fun shot(id: Long, session: Long, tag: Int, e: Double, n: Double, h: Double, at: Long = id, active: Boolean = true) =
    Shot(id, session, tag, e, n, h, 37.7, 128.9, 0.006, 0.013, 10, at, active)

/** 시험 좌표(EPSG:5187 E,N) → 대략의 위경도(기준점 둘레 수 km 안에서 충분) */
object Geo {
    private val ref = TmProjection.forward(37.7712, 128.8790, KoreanTmZone.EAST)
    fun latLon(e: Double, n: Double): Pair<Double, Double> {
        var la = 37.7712 + (n - ref.northing) / 110_950.0; var lo = 128.8790 + (e - ref.easting) / 88_070.0
        repeat(6) { val p = TmProjection.forward(la, lo, KoreanTmZone.EAST); la += (n - p.northing) / 110_950.0; lo += (e - p.easting) / 88_070.0 }
        return la to lo
    }
}
fun TestGnss.emitAt(e: Double, n: Double) { val (la, lo) = Geo.latLon(e, n); emit(lat = la, lon = lo) }
