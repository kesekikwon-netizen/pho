package kr.archsurvey.data

import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.ui.PointStore
import kr.archsurvey.ui.SavedPoint

/** [PointStore] 의 Room 구현. SavedPoint <-> PointEntity 변환을 한곳에 둔다. */
class RoomPointStore(
    private val db: SurveyDb,
    private val project: String,
    private val device: String,
) : PointStore {
    private val repo = SurveyRepository(db)
    private val dao get() = db.dao()

    override suspend fun loadAll(): List<SavedPoint> = repo.livePoints(project).map { fromEntity(it) }

    override suspend fun maxSeq(): Int = dao.lastPointId(project)?.let { id -> Regex("(\\d+)$").find(id)?.value?.toIntOrNull() } ?: 0

    override suspend fun save(p: SavedPoint): Long = repo.savePoint(toEntity(p))

    override suspend fun hideLast() { repo.undoLast(project) }

    private fun toEntity(p: SavedPoint): PointEntity {
        val apc = p.antenna.apcHeightM
        return PointEntity(
            project = project, pointId = p.name, positionType = p.type, memo = p.memo,
            lat = p.lat, lon = p.lon,
            ellipsoidalHeight = p.groundHeightM?.plus(apc), altitudeMsl = null, geoidSeparation = null,
            northing = p.northing, easting = p.easting, crsEpsg = p.crsEpsg,
            fixQuality = p.fixQuality, allEpochsFixed = p.status == SurveyStatus.FIXED, satellites = p.satellites,
            hdop = null, sigmaE = null, sigmaN = null, sigmaH = p.sigmaHM, sigmaU = null,
            correctionAgeSec = null, baseStationId = null, epochs = p.epochs,
            antennaHeight = apc, antennaModel = p.antenna.modelId, antennaRefPoint = p.antenna.refPointId,
            antennaInputMode = p.antenna.method, antennaInputValue = p.antenna.inputM,
            antennaPcoV = p.antenna.pcoV, antennaPcoH = p.antenna.pcoH,
            groundEllipsoidalHeight = p.groundHeightM,
            device = device, measuredAtUtcMs = p.measuredAtMs,
        )
    }

    private fun fromEntity(e: PointEntity): SavedPoint {
        val rec = kr.archsurvey.core.AntennaRecord(
            modelId = e.antennaModel, refPointId = e.antennaRefPoint, refPointLabel = e.antennaRefPoint,
            inputM = e.antennaInputValue, method = e.antennaInputMode, pcoV = e.antennaPcoV, pcoH = e.antennaPcoH, apcHeightM = e.antennaHeight,
        )
        val caution = !e.allEpochsFixed
        return SavedPoint(
            name = e.pointId, type = e.positionType, lat = e.lat, lon = e.lon, northing = e.northing, easting = e.easting, crsEpsg = e.crsEpsg,
            groundHeightM = e.groundEllipsoidalHeight, sigmaHM = e.sigmaH,
            status = if (caution) SurveyStatus.CAUTION else SurveyStatus.FIXED, cautionFlag = caution, epochs = e.epochs, antenna = rec,
            fixQuality = e.fixQuality, satellites = e.satellites, measuredAtMs = e.measuredAtUtcMs, rowId = e.rowId, memo = e.memo,
        )
    }
}
