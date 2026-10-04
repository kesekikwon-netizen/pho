package kr.archsurvey.georef

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

enum class BgKind { GEOTIFF, DXF, NGII_DXF, MBTILES, PMTILES }

/** 레이어 행에 표시하는 정합 상태(§3.5.4 ⑥). 미정합 배경은 지도에 올리지 않는다. */
enum class BgStatus { ALIGNED_AUTO, ALIGNED_USER_CRS, ALIGNED_MANUAL, UNALIGNED }

/** 수동 정렬 결과. 원본 파일은 바꾸지 않고 이 변환만 저장한다. */
data class ManualAlignment(
    /** 파일 공간(화소 열/행 또는 DXF X/Y) -> 작업 좌표계 좌표 */
    val fileToTarget: Affine, val targetEpsg: Int,
    val rmsMeters: Double, val maxResidualMeters: Double, val kind: FitKind,
    val points: List<ControlPoint>,
)

/**
 * 업로드 배경 한 개의 저장 모델 (`bgLayer`). 순수 데이터 + JSON 직렬화.
 * Room 테이블 연결은 DB 스키마 작업(WP-6)과 충돌하지 않도록 아직 하지 않았다(JSON 파일 저장소로 시험).
 */
data class BgLayer(
    val id: String, val kind: BgKind, val displayName: String,
    /** 앱 전용 폴더 안 사본의 경로(원본 파일은 바꾸지 않는다) */
    val filePath: String, val fileSizeBytes: Long,
    val rasterWidth: Int?, val rasterHeight: Int?,
    /** 파일 공간 -> 파일 좌표계 좌표 (래스터=파일에서 읽은 변환, DXF=항등 또는 X/Y 교환) */
    val fileToSource: Affine?, val sourceEpsg: Int?, val crsSource: CrsSource,
    val manual: ManualAlignment?,
    val status: BgStatus,
    val enabled: Boolean = true,
    /** 0.0~1.0. 정합 직후 기본 50% (s34c) */
    val opacity: Double = 0.5,
    val rmsMeters: Double? = null,
    val warnings: Set<AlignWarning> = emptySet(),
    val createdAtMillis: Long = 0L,
) {
    init { require(opacity in 0.0..1.0) { "opacity 범위 오류: $opacity" } }

    /** 지도에 올려도 되는가. 미정합이면 항상 false (잘못된 위치에 도면이 보이는 사고 방지). */
    val displayable: Boolean get() = status != BgStatus.UNALIGNED

    val shouldDraw: Boolean get() = displayable && enabled && opacity > 0.0

    /** 레이어 행 배지 문구 */
    val badge: String get() = when (status) {
        BgStatus.ALIGNED_AUTO -> "정합됨(자동)"
        BgStatus.ALIGNED_USER_CRS -> "정합됨(좌표계 지정)"
        BgStatus.ALIGNED_MANUAL -> "정합됨(수동, RMS ${"%.2f".format(rmsMeters ?: manual?.rmsMeters ?: 0.0)} m)"
        BgStatus.UNALIGNED -> "미정합"
    }

    /** 파일 공간의 점을 [targetEpsg] 좌표계(작업 좌표계) 좌표로. 정합이 안 된 레이어는 null. */
    fun fileToTarget(x: Double, y: Double, targetEpsg: Int): XY? {
        if (!displayable) return null
        val tgt = KoreanCrs.byEpsg(targetEpsg) ?: return null
        manual?.let { m ->
            val p = m.fileToTarget.apply(x, y)
            if (m.targetEpsg == targetEpsg) return p
            val from = KoreanCrs.byEpsg(m.targetEpsg) ?: return null
            return KoreanCrs.fromLonLat(tgt, KoreanCrs.toLonLat(from, p.x, p.y))
        }
        val aff = fileToSource ?: return null
        val src = sourceEpsg?.let { KoreanCrs.byEpsg(it) } ?: return null
        val p = aff.apply(x, y)
        if (src.epsg == targetEpsg) return p
        return KoreanCrs.fromLonLat(tgt, KoreanCrs.toLonLat(src, p.x, p.y))
    }

    /** 래스터 4모서리(좌상·우상·우하·좌하)의 위경도. 래스터가 아니거나 정합 전이면 null. */
    fun cornersLonLat(): List<LonLat>? {
        val w = rasterWidth ?: return null; val h = rasterHeight ?: return null
        if (!displayable) return null
        val px = listOf(XY(0.0, 0.0), XY(w.toDouble(), 0.0), XY(w.toDouble(), h.toDouble()), XY(0.0, h.toDouble()))
        manual?.let { m ->
            val c = KoreanCrs.byEpsg(m.targetEpsg) ?: return null
            return px.map { val p = m.fileToTarget.apply(it); KoreanCrs.toLonLat(c, p.x, p.y) }
        }
        val aff = fileToSource ?: return null
        val src = sourceEpsg?.let { KoreanCrs.byEpsg(it) } ?: return null
        return px.map { val p = aff.apply(it); KoreanCrs.toLonLat(src, p.x, p.y) }
    }

    fun withOpacity(v: Double) = copy(opacity = v.coerceIn(0.0, 1.0))
    fun withEnabled(v: Boolean) = copy(enabled = v)

    companion object {
        /** 자동/좌표계 지정 정합 결과 -> 레이어. 정합 실패 상태이면 UNALIGNED 로 저장(되돌릴 수 있게 파일은 보관). */
        fun fromOutcome(id: String, kind: BgKind, name: String, file: File, o: AlignOutcome, nowMillis: Long): BgLayer {
            val st = when (o.status) {
                AlignStatus.ALIGNED_AUTO -> BgStatus.ALIGNED_AUTO
                AlignStatus.ALIGNED_USER_CRS -> BgStatus.ALIGNED_USER_CRS
                else -> BgStatus.UNALIGNED
            }
            return BgLayer(id, kind, name, file.path, file.length(), o.rasterWidth, o.rasterHeight,
                o.fileToCrs, o.crs?.epsg, o.crsSource, null, st, warnings = o.warnings, createdAtMillis = nowMillis)
        }

        /** 수동 정렬 적용. 기준점 residual 이 계산된 [fit] 필요. */
        fun withManual(base: BgLayer, fit: FitResult, points: List<ControlPoint>, targetEpsg: Int): BgLayer =
            base.copy(manual = ManualAlignment(fit.affine, targetEpsg, fit.rms, fit.maxResidual, fit.kind, points),
                status = BgStatus.ALIGNED_MANUAL, rmsMeters = fit.rms)

        fun toJson(l: BgLayer): JSONObject = JSONObject().apply {
            put("v", 1); put("id", l.id); put("kind", l.kind.name); put("name", l.displayName)
            put("path", l.filePath); put("size", l.fileSizeBytes)
            l.rasterWidth?.let { put("rw", it) }; l.rasterHeight?.let { put("rh", it) }
            l.fileToSource?.let { put("fileToSource", arr(it.toList())) }
            l.sourceEpsg?.let { put("sourceEpsg", it) }
            put("crsSource", l.crsSource.name)
            l.manual?.let { m ->
                put("manual", JSONObject().apply {
                    put("affine", arr(m.fileToTarget.toList())); put("targetEpsg", m.targetEpsg)
                    put("rms", m.rmsMeters); put("max", m.maxResidualMeters); put("kind", m.kind.name)
                    put("points", JSONArray().apply { m.points.forEach { put(arr(listOf(it.src.x, it.src.y, it.dst.x, it.dst.y))) } })
                })
            }
            put("status", l.status.name); put("enabled", l.enabled); put("opacity", l.opacity)
            l.rmsMeters?.let { put("rms", it) }
            put("warnings", JSONArray(l.warnings.map { it.name })); put("created", l.createdAtMillis)
        }

        fun fromJson(o: JSONObject): BgLayer {
            fun dl(a: JSONArray) = List(a.length()) { a.getDouble(it) }
            val m = o.optJSONObject("manual")?.let { j ->
                ManualAlignment(Affine.fromList(dl(j.getJSONArray("affine"))), j.getInt("targetEpsg"), j.getDouble("rms"), j.getDouble("max"),
                    FitKind.valueOf(j.getString("kind")),
                    j.getJSONArray("points").let { pa -> List(pa.length()) { i -> val q = dl(pa.getJSONArray(i)); ControlPoint(XY(q[0], q[1]), XY(q[2], q[3])) } })
            }
            return BgLayer(
                o.getString("id"), BgKind.valueOf(o.getString("kind")), o.getString("name"), o.getString("path"), o.getLong("size"),
                if (o.has("rw")) o.getInt("rw") else null, if (o.has("rh")) o.getInt("rh") else null,
                o.optJSONArray("fileToSource")?.let { Affine.fromList(dl(it)) },
                if (o.has("sourceEpsg")) o.getInt("sourceEpsg") else null, CrsSource.valueOf(o.getString("crsSource")), m,
                BgStatus.valueOf(o.getString("status")), o.getBoolean("enabled"), o.getDouble("opacity"),
                if (o.has("rms")) o.getDouble("rms") else null,
                o.getJSONArray("warnings").let { a -> (0 until a.length()).mapNotNull { runCatching { AlignWarning.valueOf(a.getString(it)) }.getOrNull() }.toSet() },
                o.getLong("created"))
        }
        private fun arr(v: List<Double>) = JSONArray().apply { v.forEach { put(it) } }
    }
}

/** 배경 레이어 저장소. 동시 표시 상한(기본 3개, §3.2)은 표시 단계에서 적용. */
interface BgLayerStore {
    fun list(): List<BgLayer>
    fun get(id: String): BgLayer?
    fun put(layer: BgLayer)
    /** 삭제 가능(§3.2). 파일 사본도 지우려면 호출 쪽에서 처리 */
    fun remove(id: String): Boolean
}

class InMemoryBgLayerStore : BgLayerStore {
    private val m = LinkedHashMap<String, BgLayer>()
    override fun list() = m.values.toList()
    override fun get(id: String) = m[id]
    override fun put(layer: BgLayer) { m[layer.id] = layer }
    override fun remove(id: String) = m.remove(id) != null
}

/** 한 레이어 = 폴더 안 JSON 파일 1개. 임시 파일에 쓴 뒤 바꿔치기해 도중 종료에도 깨지지 않게 한다. */
class JsonFileBgLayerStore(private val dir: File) : BgLayerStore {
    init { dir.mkdirs() }
    private fun f(id: String) = File(dir, "bg_${id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.json")
    override fun list() = (dir.listFiles { x -> x.name.startsWith("bg_") && x.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { runCatching { BgLayer.fromJson(JSONObject(it.readText())) }.getOrNull() }.sortedBy { it.createdAtMillis }
    override fun get(id: String) = f(id).takeIf { it.exists() }?.let { runCatching { BgLayer.fromJson(JSONObject(it.readText())) }.getOrNull() }
    override fun put(layer: BgLayer) {
        val t = File(dir, "bg_tmp_${System.nanoTime()}.part")
        t.writeText(BgLayer.toJson(layer).toString())
        val dst = f(layer.id)
        if (!t.renameTo(dst)) { dst.delete(); check(t.renameTo(dst)) { "저장 실패" } }
    }
    override fun remove(id: String) = f(id).delete()
}
