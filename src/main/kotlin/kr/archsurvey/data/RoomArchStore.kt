package kr.archsurvey.data

import androidx.room.withTransaction
import kr.archsurvey.arch.*
import kr.archsurvey.core.NE

/** [ArchStore] 의 Room 구현 */
class RoomArchStore(private val db: SurveyDb, private val project: () -> String, private val clock: () -> Long = System::currentTimeMillis) : ArchStore {
    private val dao get() = db.arch()

    override suspend fun load(): ArchSnapshot {
        val p = project()
        val areas = dao.areas(p).map { Area(it.id, it.name, it.colorIdx) }
        val grids = dao.grids(p).map {
            GridDef(it.id, it.areaId, it.name, NE(it.originN, it.originE), NE(it.dirN, it.dirE), it.spacingM, it.cols, it.rows, it.crsEpsg)
        }
        val verts = dao.outlineVertices(p).groupBy { it.outlineId }
        val outlines = dao.outlines(p).map { o ->
            Outline(o.id, o.label, o.areaId, o.closed, o.finished, verts[o.id].orEmpty().sortedBy { it.orderNo }.map { it.pointRowId })
        }
        return ArchSnapshot(areas, grids, outlines, dao.revisionCounts(p).associate { it.pointRowId to it.n })
    }

    override suspend fun addArea(name: String, colorIdx: Int): Long = dao.insertArea(AreaEntity(project = project(), name = name, colorIdx = colorIdx, createdAtUtcMs = clock()))
    override suspend fun renameArea(id: Long, name: String) = dao.renameArea(id, name)

    override suspend fun deleteArea(id: Long) = db.withTransaction {
        dao.clearPointsOfArea(id); dao.deleteGridsOfArea(id); dao.deleteArea(id)
    }

    override suspend fun addGrid(g: GridDef): Long = dao.insertGrid(
        GridEntity(
            project = project(), areaId = g.areaId, name = g.name, originN = g.origin.n, originE = g.origin.e, dirN = g.direction.n, dirE = g.direction.e,
            crsEpsg = g.crsEpsg, spacingM = g.spacingM, cols = g.cols, rows = g.rows, createdAtUtcMs = clock(),
        ),
    )

    override suspend fun deleteGrid(id: Long) = db.withTransaction { dao.clearPointsOfGrid(id); dao.deleteGrid(id) }

    override suspend fun startOutline(areaId: Long?, label: String): Long =
        dao.insertOutline(OutlineEntity(project = project(), areaId = areaId, label = label, createdAtUtcMs = clock()))

    override suspend fun addVertex(outlineId: Long, pointRowId: Long) = db.withTransaction {
        dao.insertOutlinePoint(OutlinePointEntity(outlineId = outlineId, pointRowId = pointRowId, orderNo = dao.maxOrder(outlineId) + 1)); Unit
    }

    override suspend fun removeVertex(outlineId: Long, pointRowId: Long) = dao.removeOutlinePoint(outlineId, pointRowId)
    override suspend fun finishOutline(id: Long, closed: Boolean) = dao.finishOutline(id, closed)
    override suspend fun deleteOutline(id: Long) = dao.deleteOutline(id)

    override suspend fun revisions(pointRowId: Long): List<Revision> = dao.revisions(pointRowId).map {
        Revision(it.revNo, it.measuredAtUtcMs, it.northing, it.easting, it.groundEllipsoidalHeight, it.sigmaH, it.fixQuality, it.allEpochsFixed, it.epochs, it.device, it.cellName)
    }
}
