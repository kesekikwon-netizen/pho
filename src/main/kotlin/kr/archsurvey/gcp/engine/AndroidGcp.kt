package kr.archsurvey.gcp.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kr.archsurvey.gcp.model.*
import kr.archsurvey.gcp.ui.GcpFilesPort
import kr.archsurvey.gcp.ui.GcpPrefs
import kr.archsurvey.gcp.ui.SaveOutcome
import java.io.File
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 파일 저장(측량 앱 0.1.37 GcpAutoSaver 와 같은 방식):
 *  - 앱 폴더 <외부 파일>/iTwinGCP/<유적>/<ID>_<대상>/ — 공유(FileProvider)용
 *  - 공용 Documents/iTwinGCP/<유적>/<ID>_<대상>/ (Android 10+, MediaStore · 권한 없음 · 같은 이름 덮어씀)
 *    USB 로 PC 에 꽂으면 「내장 저장공간 › Documents › iTwinGCP › <유적>」
 */
class AndroidGcpFiles(private val ctx: Context, private val zone: ZoneId = ZoneId.of("Asia/Seoul")) : GcpFilesPort {
    private fun base(): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, Names.ROOT)
    private fun localSite(site: Site) = File(base(), Names.siteFolder(site))
    private fun localObj(site: Site, o: Obj) = File(localSite(site), Names.objFolder(o))

    override fun saveSession(book: SiteBook, o: Obj, s: Session): SaveOutcome {
        val epsg = book.site.epsg
        val shots = book.active(s)
        val xmlName = Names.xmlName(o, s, epsg)
        val dir = localObj(book.site, o).apply { mkdirs() }
        val xml = File(dir, xmlName); val txt = File(dir, Names.refCsvName(o, s)).apply { parentFile?.mkdirs() }
        kr.archsurvey.gcp.data.SafeFile.write(xml, ItwinXml.write(shots, epsg))
        kr.archsurvey.gcp.data.SafeFile.write(txt, ItwinXml.refCsv(shots))
        val list = File(localSite(book.site), Names.listCsvName(book.site))
        kr.archsurvey.gcp.data.SafeFile.write(list, ListCsv.write(book, zone)) // BOM 은 ListCsv 가 붙임
        var where = "앱 폴더"
        var err: String? = null
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                publish(listOf(xml), "Documents/${Names.objRel(book.site, o)}")
                publish(listOf(txt), "Documents/${Names.objRel(book.site, o)}/" + Names.refCsvName(o, s).substringBeforeLast('/'))
                publish(listOf(list), "Documents/${Names.siteRel(book.site)}")
                where = whereLabel(book.site)
            }.onFailure { err = "Documents 폴더에 쓰지 못했어요(${it.javaClass.simpleName}) · 앱 폴더에는 저장됨" }
        }
        return SaveOutcome(err == null, xmlName, where, err)
    }

    override fun whereLabel(site: Site) = if (Build.VERSION.SDK_INT >= 29) "내 파일 › Documents › ${Names.ROOT.replace("/", " › ")} › ${Names.siteFolder(site)}" else "앱 폴더(Android › data › ${ctx.packageName} › files) › ${Names.ROOT.replace("/", " › ")} › ${Names.siteFolder(site)}"

    /** 0.1.18 M6: 같은 이름 zip 이 이미 있으면 직전본을 <이름>.bak 하나로 남긴다(그 전 .bak 은 덮어씀) */
    @androidx.annotation.RequiresApi(29)
    private fun backupExisting(name: String, rel: String) {
        val cr = ctx.contentResolver
        val coll = android.provider.MediaStore.Files.getContentUri("external")
        val src = cr.query(coll, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf("$rel/", name), null)
            ?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(coll, c.getLong(0)) else null } ?: return
        val tmp = File(ctx.cacheDir, "$name.bak")
        (cr.openInputStream(src) ?: return).use { i -> tmp.outputStream().use { i.copyTo(it) } }
        publish(listOf(tmp), rel)
        tmp.delete()
    }

    @androidx.annotation.RequiresApi(29)
    private fun publish(files: List<File>, rel: String) {
        val cr = ctx.contentResolver
        val coll = android.provider.MediaStore.Files.getContentUri("external")
        for (f in files) {
            val mime = when (f.extension.lowercase()) { "xml" -> "text/xml"; "csv" -> "text/comma-separated-values"; "zip" -> "application/zip"; "bak" -> "application/octet-stream"; else -> "text/plain" }
            val existing = cr.query(coll, arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf("$rel/", f.name), null)
                ?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(coll, c.getLong(0)) else null }
            val uri = existing ?: cr.insert(coll, android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$rel/")
            }) ?: error("insert")
            (cr.openOutputStream(uri, "wt") ?: error("open")).use { o -> f.inputStream().use { it.copyTo(o) } }
        }
    }

    private fun uri(f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)

    override fun shareSession(book: SiteBook, o: Obj, s: Session): Boolean {
        val r = saveSession(book, o, s)
        val dir = localObj(book.site, o)
        val files = listOf(File(dir, r.xmlName), File(dir, Names.refCsvName(o, s))).filter { it.exists() }
        if (files.isEmpty()) return false
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/xml").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uri(it) }))
            .putExtra(Intent.EXTRA_SUBJECT, "${o.name} ${s.no}차 iTwin 기준점").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { ctx.startActivity(Intent.createChooser(send, "${o.name} XML 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    override var lastZipWhere: String? = null; private set
    override fun shareZip(plan: HandoffPlan): Boolean {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "handoff").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val zip = File(dir, plan.zipName)
        kr.archsurvey.gcp.data.SafeFile.writeWith(zip) { out -> val bo = out.buffered(); val z = ZipOutputStream(bo); writeZip(z, plan); z.finish(); bo.flush() }
        // 0.1.17: PC 에서 바로 찾게 공용 Documents/iTwinGCP/<유적>/ 에도 zip 사본(실패해도 공유는 계속)
        lastZipWhere = null
        val appWhere = "앱 폴더(Android › data › ${ctx.packageName} › files › handoff) › ${zip.name}"
        if (Build.VERSION.SDK_INT >= 29 && plan.siteRel.isNotEmpty()) runCatching { backupExisting(zip.name, "Documents/${plan.siteRel}"); publish(listOf(zip), "Documents/${plan.siteRel}") }
            .onSuccess { lastZipWhere = "내 파일 › Documents › ${plan.siteRel.replace("/", " › ")} › ${zip.name}" }
            .onFailure { kr.archsurvey.diag.CrashLog.record("zip 공용 폴더 저장", it); lastZipWhere = "Documents 에 못 써서 " + appWhere }
        else lastZipWhere = appWhere // 0.1.18 M5: Android 9 이하 — 새 권한 없이 앱 폴더(경로 한 줄로 알림)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri(zip))
            .putExtra(Intent.EXTRA_SUBJECT, plan.zipName.removeSuffix(".zip")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { ctx.startActivity(Intent.createChooser(send, "iTwin으로 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    companion object {
        /** zip 내용 그대로 UTF-8(목록.csv BOM 은 ListCsv 가 붙임). 파일 이름도 UTF-8(zip EFS 표시) */
        fun writeZip(z: ZipOutputStream, plan: HandoffPlan) {
            for ((path, text) in plan.entries) {
                z.putNextEntry(ZipEntry(path))
                z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
        }
    }
}

class AndroidGcpPrefs(ctx: Context) : GcpPrefs {
    private val p = ctx.getSharedPreferences("gcp", Context.MODE_PRIVATE)
    override var epochs: Int get() = p.getInt("epochs", 10).coerceIn(1, 100); set(v) { p.edit().putInt("epochs", v).apply() }
    override var lastEpsg: Int get() = p.getInt("lastEpsg", 5186); set(v) { p.edit().putInt("lastEpsg", v).apply() }
    override var volumeKey: Boolean get() = p.getBoolean("volumeKey", false); set(v) { p.edit().putBoolean("volumeKey", v).apply() }
    override var lastSiteId: Long get() = p.getLong("lastSite", -1); set(v) { p.edit().putLong("lastSite", v).apply() }
    // 0.1.8: commit() — 프로세스가 곧 죽어도 디스크에 남게
    override var measureCheckpoint: String get() = p.getString("measureCp", "") ?: ""; set(v) { p.edit().putString("measureCp", v).commit() }
    override fun pole(profileId: String): Double? = if (p.contains("pole_$profileId")) p.getFloat("pole_$profileId", 1.6f).toDouble().let { Math.round(it * 1000) / 1000.0 } else null
    override fun setPole(profileId: String, m: Double) { p.edit().putFloat("pole_$profileId", m.toFloat()).apply() }
}
