package kr.archsurvey.data

import androidx.room.withTransaction
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.ui.PointStore
import kr.archsurvey.ui.SavedPoint

/** [PointStore] 의 Room 구현. SavedPoint <-> PointEntity 변환을 한곳에 둔다. */
class RoomPointStore(
    private val db: SurveyDb,
    private val project: () -> String,
    private val device: () -> String,
) : PointStore {
    private val repo = SurveyRepository(db)
    private val dao get() = db.dao()

    override suspend fun loadAll(): List<SavedPoint> = repo.livePoints(project()).map { fromEntity(it) }

    override suspend fun maxSeq(): Int = dao.lastPointId(project())?.let { id -> Regex("(\\d+)$").find(id)?.value?.toIntOrNull() } ?: 0

    override suspend fun save(p: SavedPoint): Long = repo.savePoint(toEntity(p))

    override suspend fun hideLast() { repo.undoLast(project()) }

    override suspend fun updateMeta(rowId: Long, type: String, memo: String) { dao.updateMemoType(rowId, memo, type) }

    override suspend fun resurvey(rowId: Long, updated: SavedPoint) = db.withTransaction {
        val old = dao.getPoint(rowId) ?: return@withTransaction
        val arch = db.arch()
        arch.insertRevision(
            PointRevisionEntity(
                pointRowId = rowId, revNo = arch.revisionCount(rowId) + 1, measuredAtUtcMs = old.measuredAtUtcMs, lat = old.lat, lon = old.lon,
                northing = old.northing, easting = old.easting, crsEpsg = old.crsEpsg, groundEllipsoidalHeight = old.groundEllipsoidalHeight,
                sigmaH = old.sigmaH, fixQuality = old.fixQuality, allEpochsFixed = old.allEpochsFixed, epochs = old.epochs, device = old.device, cellName = old.cellName,
            ),
        )
        dao.updatePoint(measurementOnto(old, updated, device()))
    }

    override suspend fun undoResurvey(rowId: Long, old: SavedPoint) = db.withTransaction {
        val cur = dao.getPoint(rowId) ?: return@withTransaction
        val arch = db.arch()
        val last = arch.revisions(rowId).lastOrNull()
        dao.updatePoint(measurementOnto(cur, old, last?.device ?: cur.device))
        if (last != null) arch.deleteRevision(last.id)
    }

    /** [base] 의 번호·종류·메모·사진 연결을 두고 측정값만 [m] 의 것으로 바꾼 행 */
    private fun measurementOnto(base: PointEntity, m: SavedPoint, deviceName: String): PointEntity {
        val e = toEntity(m)
        return e.copy(rowId = base.rowId, project = base.project, pointId = base.pointId, positionType = base.positionType, memo = base.memo, device = deviceName)
    }

    private fun toEntity(p: SavedPoint): PointEntity {
        val apc = p.antenna.apcHeightM
        return PointEntity(
            project = project(), pointId = p.name, positionType = p.type, memo = p.memo,
            lat = p.lat, lon = p.lon,
            ellipsoidalHeight = p.apcEllipsoidalM ?: p.groundHeightM?.plus(apc), altitudeMsl = p.apcMslM, geoidSeparation = p.geoidSepM,
            northing = p.northing, easting = p.easting, crsEpsg = p.crsEpsg,
            fixQuality = p.fixQuality, allEpochsFixed = p.status == SurveyStatus.FIXED, satellites = p.satellites,
            hdop = p.hdop, sigmaE = p.sigmaE, sigmaN = p.sigmaN, sigmaH = p.sigmaHM, sigmaU = p.sigmaU,
            correctionAgeSec = p.correctionAgeSec, baseStationId = p.baseStationId, epochs = p.epochs,
            antennaHeight = apc, antennaModel = p.antenna.modelId, antennaRefPoint = p.antenna.refPointId,
            antennaInputMode = p.antenna.method, antennaInputValue = p.antenna.inputM,
            antennaPcoV = p.antenna.pcoV, antennaPcoH = p.antenna.pcoH,
            groundEllipsoidalHeight = p.groundHeightM, groundMsl = p.groundMslM,
            device = device(), measuredAtUtcMs = p.measuredAtMs,
            areaId = p.areaId, gridId = p.gridId, cellName = p.cellName, cellU = p.cellU, cellV = p.cellV,
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
            apcEllipsoidalM = e.ellipsoidalHeight, apcMslM = e.altitudeMsl, geoidSepM = e.geoidSeparation, groundMslM = e.groundMsl,
            hdop = e.hdop, sigmaE = e.sigmaE, sigmaN = e.sigmaN, sigmaU = e.sigmaU, correctionAgeSec = e.correctionAgeSec, baseStationId = e.baseStationId,
            areaId = e.areaId, gridId = e.gridId, cellName = e.cellName, cellU = e.cellU, cellV = e.cellV,
        )
    }
}
