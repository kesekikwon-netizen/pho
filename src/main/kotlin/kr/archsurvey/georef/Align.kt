package kr.archsurvey.georef

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** 현재 작업 영역(대표 위치 + 허용 반경). 반경 20 km 는 [임시값]. */
data class WorkArea(val center: LonLat, val radiusKm: Double = 20.0)

enum class AlignStatus {
    /** 파일 안 좌표계로 자동 정합 성공 */
    ALIGNED_AUTO,
    /** 사용자가 지정한 좌표계로 정합 성공 */
    ALIGNED_USER_CRS,
    /** 좌표는 있으나 좌표계를 모르거나 변환 불가 -> s34d: 좌표계 직접 지정 / 수동 정렬 */
    NEEDS_CRS,
    /** 좌표 정보 없음(로컬 좌표 포함) -> 수동 정렬 */
    NEEDS_MANUAL,
    /** 좌표계는 있으나 한국 밖이거나 작업 지점에서 너무 멂 -> 경고 */
    MISMATCH,
}

enum class CrsSource { FILE_TAG, FILE_PRJ, USER, NONE }

enum class AlignWarning {
    NO_COORDINATES, GCP_TIEPOINTS, USER_DEFINED_CRS, PRJ_NOT_MATCHED, CRS_NOT_IN_LIST, DATUM_NOT_CONVERTED,
    OUT_OF_KOREA, FAR_FROM_WORK, NO_WORK_AREA, MULTIPLE_CRS_PLAUSIBLE, LOCAL_COORDINATES, AXIS_SWAP_SUGGESTED,
    UNITS_NOT_METER, UNIT_SUSPECT, OUTLIER_VERTICES, ROTATED_RASTER, PIXEL_IS_POINT_SHIFTED, REPROJECTION_APPROX_ERROR,
    CRS_OVERRIDDEN, FILE_UNREADABLE, BINARY_DXF, WORLD_FILE_USED, CRS_FROM_PRJ_PARAMETERS,
}

data class CrsCandidate(
    val crs: CrsDef,
    /** 변환 불가 좌표계(5174)는 null */
    val inKorea: Boolean?, val distanceKm: Double?, val note: String?,
) { val convertible get() = crs.supported }

data class AlignOutcome(
    val status: AlignStatus,
    val crs: CrsDef?, val crsSource: CrsSource,
    /** 파일 공간(래스터=화소 열/행, DXF=X/Y) -> 좌표계 좌표. 래스터에 좌표가 없으면 null */
    val fileToCrs: Affine?,
    /** 좌표계 좌표에서의 범위 */
    val extent: Extent?,
    val cornersLonLat: List<LonLat>,
    val distanceToWorkKm: Double?,
    val candidates: List<CrsCandidate>,
    val warnings: Set<AlignWarning>,
    /** 쉬운 말 안내문(건설 용어 없음) */
    val messages: List<String>,
    val rasterWidth: Int? = null, val rasterHeight: Int? = null,
    /** 래스터를 4모서리 아핀으로 근사했을 때 최대 오차(EPSG:3857 기준 화소 수). 좌표계 일치 시 0 */
    val approxErrorPx: Double? = null,
    val suggestSwapXY: Boolean = false,
) {
    /** 지도에 올려도 되는가: 정합 성공 상태만 */
    val displayable get() = status == AlignStatus.ALIGNED_AUTO || status == AlignStatus.ALIGNED_USER_CRS
}

object UploadAligner {
    /** 래스터 4모서리 아핀 근사 오차가 이 화소 수를 넘으면 경고 [임시값, 현장검증] */
    const val APPROX_ERROR_WARN_PX = 1.0

    // ---------------- GeoTIFF ----------------
    fun analyzeGeoTiff(file: File, work: WorkArea?, userCrs: CrsDef? = null): AlignOutcome {
        val tiff = GeoTiffReader.read(file)
        if (tiff is GeoTiffReader.Result.Fail)
            return empty(AlignStatus.NEEDS_MANUAL, setOf(AlignWarning.FILE_UNREADABLE), listOf(tiff.message))
        val info = (tiff as GeoTiffReader.Result.Ok).info
        val w = linkedSetOf<AlignWarning>(); val msg = mutableListOf<String>()
        var affine = info.affine
        var source = CrsSource.FILE_TAG
        if (info.hasMultipleTiepoints) { w += AlignWarning.GCP_TIEPOINTS }
        if (info.pixelIsPoint) w += AlignWarning.PIXEL_IS_POINT_SHIFTED
        // 월드파일
        if (affine == null) {
            WorldFile.findWorldFile(file)?.let { wf ->
                when (val r = WorldFile.parse(wf.readText())) {
                    is WorldFile.Result.Ok -> { affine = r.affine; w += AlignWarning.WORLD_FILE_USED }
                    is WorldFile.Result.Bad -> msg += "월드파일을 읽지 못했어요: ${r.message}"
                }
            }
        }
        val aff = affine
        if (aff == null) {
            w += AlignWarning.NO_COORDINATES
            msg += if (info.hasMultipleTiepoints) "이 도면은 기준점 방식 좌표라서 자동으로 맞출 수 없어요. 수동 정렬을 사용하세요"
            else "도면에 좌표 정보가 없어요. 좌표를 아는 기준점 2~3개로 직접 맞춰 주세요"
            return empty(AlignStatus.NEEDS_MANUAL, w, msg, info.width, info.height)
        }
        // 좌표계 결정: 사용자 > 파일 태그 > .prj
        var crs: CrsDef? = null
        var unknownReason: AlignWarning? = null
        if (info.epsg != null) {
            crs = KoreanCrs.byEpsg(info.epsg)
            if (crs == null) { unknownReason = AlignWarning.CRS_NOT_IN_LIST; msg += "EPSG:${info.epsg} 는 앱 목록에 없는 좌표계예요" }
        } else if (info.userDefinedCrs) { unknownReason = AlignWarning.USER_DEFINED_CRS; msg += "도면에 사용자 정의 좌표계가 들어 있어 자동으로 알 수 없어요" }
        if (crs == null) {
            val prjFile = WorldFile.findPrj(file)
            if (prjFile != null) {
                val pr = PrjParser.parse(runCatching { prjFile.readText() }.getOrDefault(""))
                if (pr.crs != null) { crs = pr.crs; source = CrsSource.FILE_PRJ; unknownReason = null; if (pr.match == PrjMatch.PARAMETERS) w += AlignWarning.CRS_FROM_PRJ_PARAMETERS }
                else { w += AlignWarning.PRJ_NOT_MATCHED; msg += "좌표계 파일(.prj)을 읽었지만 목록의 좌표계와 맞지 않아요" }
            }
        }
        if (userCrs != null) {
            if (crs != null && crs.epsg != userCrs.epsg) w += AlignWarning.CRS_OVERRIDDEN
            crs = userCrs; source = CrsSource.USER; unknownReason = null
        }
        if (crs == null) source = CrsSource.NONE
        if (unknownReason != null) w += unknownReason
        if (info.isRotated) w += AlignWarning.ROTATED_RASTER
        val cornersPx = listOf(XY(0.0, 0.0), XY(info.width.toDouble(), 0.0), XY(info.width.toDouble(), info.height.toDouble()), XY(0.0, info.height.toDouble()))
        val cornersCrs = cornersPx.map { aff.apply(it) }
        val ext = bounds(cornersCrs)
        return decide(ext, crs, source, aff, work, w, msg, info.width, info.height, userChosen = userCrs != null, cornersCrs = cornersCrs)
    }

    // ---------------- DXF ----------------
    fun analyzeDxf(file: File, work: WorkArea?, userCrs: CrsDef? = null, swapXY: Boolean = false): AlignOutcome {
        val scan = try { DxfScan.scan(file) } catch (e: DxfScan.BinaryDxfException) {
            return empty(AlignStatus.NEEDS_MANUAL, setOf(AlignWarning.BINARY_DXF), listOf(e.message ?: "바이너리 DXF"))
        } catch (e: java.io.IOException) {
            return empty(AlignStatus.NEEDS_MANUAL, setOf(AlignWarning.FILE_UNREADABLE), listOf("파일을 읽을 수 없어요"))
        }
        val w = linkedSetOf<AlignWarning>(); val msg = mutableListOf<String>()
        msg += scan.warnings
        val full = scan.extent
        val robust = scan.robustExtent
        if (full == null || robust == null) {
            w += AlignWarning.NO_COORDINATES
            return empty(AlignStatus.NEEDS_MANUAL, w, msg + "도면에서 좌표를 찾지 못했어요")
        }
        if (scan.insunits != null && scan.insunits != 6 && scan.insunits != 0) {
            w += AlignWarning.UNITS_NOT_METER; msg += "도면 단위 코드가 ${scan.insunits} 예요(미터=6). 미터가 아니면 위치가 맞지 않아요"
        }
        if (max(full.width, full.height) > 1_000_000.0 && scan.insunits != 6) { w += AlignWarning.UNIT_SUSPECT; msg += "도면 크기가 1,000 km 를 넘어요. 단위가 mm 인지 확인하세요" }
        if (robust.width * robust.height < 0.01 * full.width * full.height) { w += AlignWarning.OUTLIER_VERTICES; msg += "원점 근처에 떨어진 점이 섞여 있어요. 판정은 대부분의 점이 모인 범위로 했어요" }
        val toCrs = if (swapXY) Affine(0.0, 1.0, 0.0, 1.0, 0.0, 0.0) else Affine.IDENTITY
        val judgeExt = if (swapXY) robust.swapped() else robust
        val fullExt = if (swapXY) full.swapped() else full
        val out = decide(judgeExt, userCrs, if (userCrs != null) CrsSource.USER else CrsSource.NONE, toCrs, work, w, msg, null, null, userChosen = userCrs != null, displayExtent = fullExt)
        // 축 뒤바뀜 힌트: 지금 방식으로는 쓸 후보가 없는데 X/Y 를 바꾸면 후보가 생기는 경우
        if (!swapXY && out.status != AlignStatus.ALIGNED_USER_CRS && userCrs == null && out.candidates.none { it.inKorea == true && (it.distanceKm == null || work == null || it.distanceKm <= work.radiusKm) }) {
            val alt = candidatesFor(robust.swapped(), work)
            if (alt.any { it.inKorea == true && (work == null || (it.distanceKm ?: Double.MAX_VALUE) <= work.radiusKm) }) {
                return out.copy(warnings = out.warnings + AlignWarning.AXIS_SWAP_SUGGESTED, suggestSwapXY = true,
                    messages = out.messages + "X 와 Y 를 서로 바꾸면 위치가 맞을 수 있어요(북=X 도면)")
            }
        }
        return out
    }

    // ---------------- 공통 판정 ----------------
    private fun decide(
        ext: Extent, crsIn: CrsDef?, source: CrsSource, fileToCrs: Affine, work: WorkArea?,
        w: MutableSet<AlignWarning>, msg: MutableList<String>, rw: Int?, rh: Int?, userChosen: Boolean,
        displayExtent: Extent? = null, cornersCrs: List<XY>? = null,
    ): AlignOutcome {
        val shownExt = displayExtent ?: ext
        // 파일이 자기 좌표계를 밝혔지만 앱이 못 쓰는 경우(목록에 없음·사용자 정의)는 '모른다'가 아니라 '직접 지정' 분기
        val claimedUnknown = AlignWarning.CRS_NOT_IN_LIST in w || AlignWarning.USER_DEFINED_CRS in w
        if (crsIn == null) {
            val cands = candidatesFor(ext, work)
            val plausible = cands.filter { it.convertible && it.inKorea == true && (work == null || (it.distanceKm ?: Double.MAX_VALUE) <= work.radiusKm) }
            val inKoreaAny = cands.any { it.inKorea == true }
            if (work == null) w += AlignWarning.NO_WORK_AREA
            return when {
                claimedUnknown -> {
                    msg += "이 도면의 좌표계를 앱이 아직 쓰지 못해요. 후보 중에서 직접 골라 주세요"
                    AlignOutcome(AlignStatus.NEEDS_CRS, null, source, fileToCrs, shownExt, emptyList(), null, cands, w, msg, rw, rh)
                }
                looksLocal(ext) -> {
                    w += AlignWarning.LOCAL_COORDINATES
                    msg += "도면 좌표가 현장 임시 좌표로 보여요(원점 근처). 좌표를 아는 기준점으로 수동 정렬하세요"
                    AlignOutcome(AlignStatus.NEEDS_MANUAL, null, source, fileToCrs, shownExt, emptyList(), null, cands, w, msg, rw, rh)
                }
                !inKoreaAny -> {
                    w += AlignWarning.OUT_OF_KOREA
                    msg += "어느 좌표계로 봐도 한국 영역 안에 들어오지 않아요. 좌표계나 단위를 확인하세요"
                    AlignOutcome(AlignStatus.MISMATCH, null, source, fileToCrs, shownExt, emptyList(), null, cands, w, msg, rw, rh)
                }
                plausible.isEmpty() -> {
                    w += AlignWarning.FAR_FROM_WORK
                    msg += "한국 안이지만 현재 작업 지점에서 너무 멀어요(${work?.radiusKm?.toInt()} km 초과). 좌표계를 직접 지정하거나 수동 정렬하세요"
                    AlignOutcome(AlignStatus.MISMATCH, null, source, fileToCrs, shownExt, emptyList(), null, cands, w, msg, rw, rh)
                }
                else -> {
                    if (plausible.count { it.convertible } > 1) w += AlignWarning.MULTIPLE_CRS_PLAUSIBLE
                    msg += "좌표계를 도면에서 알 수 없어요. 후보 중에서 직접 골라 주세요"
                    AlignOutcome(AlignStatus.NEEDS_CRS, null, source, fileToCrs, shownExt, emptyList(), plausible.first().distanceKm, cands, w, msg, rw, rh)
                }
            }
        }
        val crs = crsIn
        if (!crs.supported) {
            w += AlignWarning.DATUM_NOT_CONVERTED
            msg += "EPSG:${crs.epsg} 는 구 한국측지계라서 위치가 약 300 m 어긋나요. 수동 정렬을 권해요"
            return AlignOutcome(AlignStatus.NEEDS_CRS, crs, source, fileToCrs, shownExt, emptyList(), null, candidatesFor(ext, work), w, msg, rw, rh)
        }
        val ev = evaluate(ext, crs, work)
        val cornersLL = (cornersCrs ?: shownExt.corners()).map { KoreanCrs.toLonLat(crs, it.x, it.y) }
        val cands = candidatesFor(ext, work)
        if (!ev.inKorea) {
            w += AlignWarning.OUT_OF_KOREA
            msg += "읽은 좌표가 한국 영역 밖이에요. 좌표계가 맞는지 확인하세요"
            return AlignOutcome(AlignStatus.MISMATCH, crs, source, fileToCrs, shownExt, cornersLL, ev.distanceKm, cands, w, msg, rw, rh)
        }
        if (work == null) w += AlignWarning.NO_WORK_AREA
        else if (ev.distanceKm != null && ev.distanceKm > work.radiusKm) {
            w += AlignWarning.FAR_FROM_WORK
            val alt = cands.firstOrNull { it.convertible && it.crs.epsg != crs.epsg && it.inKorea == true && (it.distanceKm ?: Double.MAX_VALUE) <= work.radiusKm }
            msg += "도면 위치가 현재 작업 지점에서 ${"%.0f".format(ev.distanceKm)} km 떨어져 있어요." +
                (alt?.let { " EPSG:${it.crs.epsg} 로 보면 작업 지점 근처예요. 좌표계를 확인하세요" } ?: " 좌표계를 확인하거나 수동 정렬하세요")
            return AlignOutcome(AlignStatus.MISMATCH, crs, source, fileToCrs, shownExt, cornersLL, ev.distanceKm, cands, w, msg, rw, rh)
        }
        // 래스터 4모서리 근사 오차
        var approx: Double? = null
        if (rw != null && rh != null) {
            approx = approxErrorPx(fileToCrs, rw, rh, crs)
            if (approx > UploadAligner.APPROX_ERROR_WARN_PX) { w += AlignWarning.REPROJECTION_APPROX_ERROR; msg += "도면이 크거나 기울어서 모서리 근사 오차가 ${"%.1f".format(approx)} 화소예요. PC에서 타일로 변환해 올리는 걸 권해요" }
        }
        val st = if (userChosen) AlignStatus.ALIGNED_USER_CRS else AlignStatus.ALIGNED_AUTO
        return AlignOutcome(st, crs, source, fileToCrs, shownExt, cornersLL, ev.distanceKm, cands, w, msg, rw, rh, approx)
    }

    private class Eval(val inKorea: Boolean, val distanceKm: Double?)

    private fun evaluate(ext: Extent, crs: CrsDef, work: WorkArea?): Eval {
        val pts = ext.corners() + XY(ext.centerX, ext.centerY)
        val ll = try { pts.map { KoreanCrs.toLonLat(crs, it.x, it.y) } } catch (e: Exception) { return Eval(false, null) }
        val inK = ll.all { KoreanCrs.inKorea(it) && it.lon.isFinite() && it.lat.isFinite() }
        var dist: Double? = null
        if (work != null) {
            val wp = KoreanCrs.fromLonLat(crs, work.center)
            val cx = wp.x.coerceIn(ext.minX, ext.maxX); val cy = wp.y.coerceIn(ext.minY, ext.maxY)
            dist = haversineKm(work.center, KoreanCrs.toLonLat(crs, cx, cy))
        }
        return Eval(inK, dist)
    }

    /** 좌표계를 모를 때 후보. 정렬: 한국 안 + 작업 지점 근접 우선, 같으면 기본 순서(5186 우선). */
    fun candidatesFor(ext: Extent, work: WorkArea?): List<CrsCandidate> {
        val out = ArrayList<CrsCandidate>()
        for (crs in KoreanCrs.projected) {
            if (!crs.supported) continue
            val ev = evaluate(ext, crs, work)
            out += CrsCandidate(crs, ev.inKorea, ev.distanceKm, crs.note)
        }
        // 5174: 5181 과 E/N 범위가 같다 -> 5181 후보가 한국 안일 때만 경고 후보로 추가
        val c5181 = out.firstOrNull { it.crs.epsg == 5181 }
        if (c5181?.inKorea == true) out += CrsCandidate(KoreanCrs.EPSG_5174, null, null, KoreanCrs.EPSG_5174.note)
        // 지리 좌표(도)
        if (abs(ext.minX) <= 180 && abs(ext.maxX) <= 180 && abs(ext.minY) <= 90 && abs(ext.maxY) <= 90) {
            val ev = evaluate(ext, KoreanCrs.WGS84, work); out += CrsCandidate(KoreanCrs.WGS84, ev.inKorea, ev.distanceKm, null)
        }
        fun rank(c: CrsCandidate): Int = when {
            !c.convertible -> 4
            c.inKorea != true -> 3
            work != null && (c.distanceKm ?: Double.MAX_VALUE) > work.radiusKm -> 2
            else -> 0
        }
        val order = KoreanCrs.all.map { it.epsg }
        return out.sortedWith(compareBy<CrsCandidate>({ rank(it) }, { it.distanceKm ?: Double.MAX_VALUE }, { order.indexOf(it.crs.epsg) }))
    }

    /** 모든 좌표 절댓값이 10만 미만이면 현장 임시(로컬) 좌표로 본다 [임시값]. 한국 TM 좌표는 보통 이보다 크다. */
    private fun looksLocal(e: Extent) = max(max(abs(e.minX), abs(e.maxX)), max(abs(e.minY), abs(e.maxY))) < 100_000.0

    private fun bounds(p: List<XY>) = Extent(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })

    private fun empty(st: AlignStatus, w: Set<AlignWarning>, m: List<String>, rw: Int? = null, rh: Int? = null) =
        AlignOutcome(st, null, CrsSource.NONE, null, null, emptyList(), null, emptyList(), w, m, rw, rh)

    /**
     * 래스터를 EPSG:3857 로 표시할 때, 4모서리(TL·TR·BL)로 만든 아핀과 5x5 격자 정확 변환의 최대 차이(화소 수).
     * 파일 좌표계가 지리(도)이거나 투영이든 동일 방식. 지원하지 않는 좌표계는 NaN 대신 Double.MAX_VALUE.
     */
    fun approxErrorPx(fileToCrs: Affine, w: Int, h: Int, crs: CrsDef): Double {
        fun exact(c: Double, r: Double): XY {
            val p = fileToCrs.apply(c, r)
            return WebMercator.forward(KoreanCrs.toLonLat(crs, p.x, p.y))
        }
        val tl = exact(0.0, 0.0); val tr = exact(w.toDouble(), 0.0); val bl = exact(0.0, h.toDouble())
        val ap = Affine((tr.x - tl.x) / w, (bl.x - tl.x) / h, tl.x, (tr.y - tl.y) / w, (bl.y - tl.y) / h, tl.y)
        var worst = 0.0
        for (i in 0..4) for (j in 0..4) {
            val c = w * i / 4.0; val r = h * j / 4.0
            val e = exact(c, r); val a = ap.apply(c, r)
            worst = max(worst, hypot(e.x - a.x, e.y - a.y))
        }
        val c0 = w / 2.0; val r0 = h / 2.0
        val px = hypot(exact(c0 + 1, r0).x - exact(c0, r0).x, exact(c0 + 1, r0).y - exact(c0, r0).y)
        return if (px > 0) worst / px else Double.MAX_VALUE
    }
}
