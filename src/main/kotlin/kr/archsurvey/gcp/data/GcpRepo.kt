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

    /** 새 유적. 약호 문제면 예외 메시지(IllegalArgumentException) */
    suspend fun createSite(name: String, codeRaw: String, epsg: Int): Site {
        val code = ObjIds.normalizeCode(codeRaw)
        ObjIds.codeProblem(code, dao.sites().map { it.code })?.let { throw IllegalArgumentException(it) }
        require(name.isNotBlank()) { "유적 이름을 넣으세요" }
        require(epsg in 5185..5188) { "좌표계는 EPSG 5185~5188 중에서 고르세요" }
        val id = dao.insertSite(SiteRow(name = name.trim(), code = code, epsg = epsg, createdAt = clock()))
        return dao.site(id)!!.model()
    }

    /** 새 대상 + 1차. [slots] = 1차 표적 칸(근처 번호 겹침을 피해 고른 번호) */
    suspend fun createObj(siteId: Long, kind: ObjKind, name: String, operator: String, sessionLabel: String, slots: List<Int>): Obj {
        val site = dao.site(siteId) ?: throw IllegalStateException("유적이 없어요")
        val seq = ObjIds.nextSeq(dao.seqs(siteId, operator))
        val code = ObjIds.format(site.code, operator, seq)
        val now = clock()
        val id = dao.insertObj(ObjRow(siteId = siteId, seq = seq, code = code, kind = kind.id, name = name.trim(), operator = operator, createdAt = now))
        dao.insertSession(SessionRow(objId = id, no = 1, label = sessionLabel, slots = slots.joinToString(","), createdAt = now, changedAt = now))
        return dao.obj(id)!!.model()
    }

    /** 이름 바꾸기: ID 는 그대로, 옛 이름은 목록.csv 「옛 이름」 에 남김 */
    suspend fun renameObj(objId: Long, newName: String): Obj {
        val o = dao.obj(objId) ?: throw IllegalStateException("대상이 없어요")
        val nn = newName.trim(); require(nn.isNotEmpty()) { "이름을 넣으세요" }
        if (nn == o.name) return o.model()
        val olds = (o.oldNames.split('\n').filter { it.isNotBlank() } + o.name).distinct().filter { it != nn }
        dao.updateObj(o.copy(name = nn, oldNames = olds.joinToString("\n")))
        return dao.obj(objId)!!.model()
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

    suspend fun markXmlSaved(sessionId: Long, at: Long) { dao.session(sessionId)?.let { dao.updateSession(it.copy(xmlSavedAt = at)) } }
    suspend fun setPhotoDone(sessionId: Long, done: Boolean) { dao.session(sessionId)?.let { dao.updateSession(it.copy(photoDoneAt = if (done) clock() else null)) } }
    suspend fun markDelivered(ids: List<Long>) { if (ids.isNotEmpty()) dao.markDelivered(ids, clock()) }
}
