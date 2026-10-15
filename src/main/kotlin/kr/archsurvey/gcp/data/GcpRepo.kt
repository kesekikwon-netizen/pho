package kr.archsurvey.gcp.data

import kr.archsurvey.gcp.measure.Measured
import kr.archsurvey.gcp.model.*

/** 장부 저장(Room). 화면은 바꿀 때마다 [book] 을 다시 읽는다(유적 하나 = 대상 수백·표적 수천 이하) */
class GcpRepo(private val dao: GcpDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun sites(): List<Site> = dao.sites().map { it.model() }
    suspend fun site(id: Long): Site? = dao.site(id)?.model()

    suspend fun book(siteId: Long): SiteBook? {
        val s = dao.site(siteId)?.model() ?: return null
        return SiteBook(s, dao.objs(siteId).map { it.model() }, dao.sessions(siteId).map { it.model() }, dao.shots(siteId).map { it.model() })
    }

    /** 새 유적(이름은 고른 것, 겹치면 (2)…). */
    suspend fun createSite(name: String, epsg: Int): Site {
        require(name.isNotBlank()) { "유적 이름을 고르세요" }
        require(epsg in 5185..5188) { "좌표계는 EPSG 5185~5188 중에서 고르세요" }
        val nm = SiteNames.unique(name.trim(), dao.sites().map { it.name })
        val id = dao.insertSite(SiteRow(name = nm, epsg = epsg, createdAt = clock()))
        return dao.site(id)!!.model()
    }

    suspend fun usedNos(siteId: Long, kind: ObjKind): Set<Int> = dao.usedNos(siteId, kind.id).toSet()

    /** 새 대상 + 1차. 같은 종류·같은 호수가 있으면 IllegalArgumentException */
    suspend fun createObj(siteId: Long, kind: ObjKind, no: Int, sessionLabel: String, slots: List<Int>): Obj {
        dao.site(siteId) ?: throw IllegalStateException("유적이 없어요")
        require(ObjNames.valid(no)) { "호수는 1~${ObjNames.MAX_NO}" }
        if (no in usedNos(siteId, kind)) throw IllegalArgumentException("${ObjNames.label(kind, no)} 은(는) 이미 있어요 · 다음 번호 ${ObjNames.next(usedNos(siteId, kind))}")
        val now = clock()
        val id = dao.insertObj(ObjRow(siteId = siteId, kind = kind.id, no = no, createdAt = now))
        dao.insertSession(SessionRow(objId = id, no = 1, label = sessionLabel, slots = slots.joinToString(","), createdAt = now, changedAt = now))
        return dao.obj(id)!!.model()
    }

    /** 0.1.17: 사용자가 이름을 쓴 새 대상(폴더) + 1차. 안쪽 번호는 「기타」 다음 번호(화면에 안 보임) */
    suspend fun createNamedObj(siteId: Long, title: String, sessionLabel: String, slots: List<Int>): Obj {
        dao.site(siteId) ?: throw IllegalStateException("유적이 없어요")
        val names = dao.objs(siteId).map { it.model().name }
        ObjNames.problem(title, names)?.let { throw IllegalArgumentException(it) }
        val now = clock()
        val no = (dao.usedNos(siteId, ObjKind.OTHER.id).maxOrNull() ?: 0) + 1
        val id = dao.insertObj(ObjRow(siteId = siteId, kind = ObjKind.OTHER.id, no = no, createdAt = now, title = title.trim()))
        dao.insertSession(SessionRow(objId = id, no = 1, label = sessionLabel, slots = slots.joinToString(","), createdAt = now, changedAt = now))
        return dao.obj(id)!!.model()
    }

    suspend fun createSession(objId: Long, label: String, slots: List<Int>): Session {
        val prev = dao.sessions(dao.obj(objId)!!.siteId).filter { it.objId == objId }
        val now = clock()
        val id = dao.insertSession(SessionRow(objId = objId, no = (prev.maxOfOrNull { it.no } ?: 0) + 1, label = label, slots = slots.joinToString(","), createdAt = now, changedAt = now))
        return dao.session(id)!!.model()
    }

    suspend fun setSlots(sessionId: Long, slots: List<Int>) { dao.session(sessionId)?.let { dao.updateSession(it.copy(slots = slots.joinToString(","))) } }

    /**
     * 표적 저장(같은 번호의 앞 값은 기록으로 남기고 active 끔). [resetPhoto] = 표적이 움직였다고 답함 → 사진 다시 필요.
     * 저장 뒤 그 차수 반환
     */
    suspend fun saveShot(sessionId: Long, tag: Int, m: Measured, poleM: Double, apcM: Double, device: String, note: String, resetPhoto: Boolean): Session {
        val now = clock()
        val s = dao.session(sessionId) ?: throw IllegalStateException("차수가 없어요")
        dao.replaceShot(ShotRow(sessionId = sessionId, tag = tag, e = m.e, n = m.n, h = m.h, lat = m.lat, lon = m.lon, sigmaH = m.sigmaH, sigmaU = m.sigmaU,
            epochs = m.epochs, measuredAt = now, active = true, poleM = poleM, apcM = apcM, device = device, note = note))
        val slots = s.slots.split(',').mapNotNull { it.trim().toIntOrNull() }.let { if (tag in it) it else it + tag }
        val upd = s.copy(changedAt = now, slots = slots.joinToString(","), photoDoneAt = if (resetPhoto) null else s.photoDoneAt)
        dao.updateSession(upd)
        return upd.model()
    }

    /** 0.1.18 S1 되돌리기: 앞 값을 새 행으로 다시 지금 값으로(다시 잰 값은 기록으로 남음) */
    suspend fun restoreShot(p: Shot) {
        dao.replaceShot(ShotRow(sessionId = p.sessionId, tag = p.tag, e = p.e, n = p.n, h = p.h, lat = p.lat, lon = p.lon, sigmaH = p.sigmaH, sigmaU = p.sigmaU,
            epochs = p.epochs, measuredAt = clock(), active = true, poleM = p.poleM, apcM = p.apcM, device = p.device, note = (p.note + " undo").trim()))
        dao.session(p.sessionId)?.let { dao.updateSession(it.copy(changedAt = clock())) }
    }

    suspend fun markXmlSaved(sessionId: Long, at: Long) { dao.session(sessionId)?.let { dao.updateSession(it.copy(xmlSavedAt = at)) } }
    suspend fun setPhotoDone(sessionId: Long, done: Boolean) { dao.session(sessionId)?.let { dao.updateSession(it.copy(photoDoneAt = if (done) clock() else null)) } }
    suspend fun markDelivered(ids: List<Long>) { if (ids.isNotEmpty()) dao.markDelivered(ids, clock()) }
}
