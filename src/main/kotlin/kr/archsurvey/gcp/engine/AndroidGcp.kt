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
        val xml = File(dir, xmlName); val txt = File(dir, Names.txtName(o, s, epsg))
        xml.writeText(ItwinXml.write(shots, epsg), Charsets.UTF_8)
        txt.writeText(ItwinXml.txt(shots), Charsets.UTF_8)
        val list = File(localSite(book.site), Names.listCsvName(book.site))
        list.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + ListCsv.write(book, zone).toByteArray(Charsets.UTF_8))
        var where = "앱 폴더"
        var err: String? = null
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                publish(listOf(xml, txt), "Documents/${Names.objRel(book.site, o)}")
                publish(listOf(list), "Documents/${Names.siteRel(book.site)}")
                where = whereLabel(book.site)
            }.onFailure { err = "Documents 폴더에 쓰지 못했어요(${it.javaClass.simpleName}) · 앱 폴더에는 저장됨" }
        }
        return SaveOutcome(err == null, xmlName, where, err)
    }

    override fun whereLabel(site: Site) = "내 파일 › Documents › ${Names.ROOT} › ${Names.siteFolder(site)}"

    @androidx.annotation.RequiresApi(29)
    private fun publish(files: List<File>, rel: String) {
        val cr = ctx.contentResolver
        val coll = android.provider.MediaStore.Files.getContentUri("external")
        for (f in files) {
            val mime = when (f.extension.lowercase()) { "xml" -> "text/xml"; "csv" -> "text/csv"; else -> "text/plain" }
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
        val files = listOf(File(dir, r.xmlName), File(dir, Names.txtName(o, s, book.site.epsg))).filter { it.exists() }
        if (files.isEmpty()) return false
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/xml").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uri(it) }))
            .putExtra(Intent.EXTRA_SUBJECT, "${o.code} ${o.name} ${s.no}차 iTwin 기준점").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { ctx.startActivity(Intent.createChooser(send, "${o.code} XML 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    override fun shareZip(plan: HandoffPlan): Boolean {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "handoff").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val zip = File(dir, plan.zipName)
        ZipOutputStream(zip.outputStream().buffered()).use { z -> writeZip(z, plan) }
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri(zip))
            .putExtra(Intent.EXTRA_SUBJECT, plan.zipName.removeSuffix(".zip")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { ctx.startActivity(Intent.createChooser(send, "PC로 넘기기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    companion object {
        /** zip 내용: 목록.csv 는 BOM 붙임(엑셀 한글), XML·TXT 는 그대로 */
        fun writeZip(z: ZipOutputStream, plan: HandoffPlan) {
            for ((path, text) in plan.entries) {
                z.putNextEntry(ZipEntry(path))
                if (path.endsWith(".csv")) z.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
        }
    }
}

class AndroidGcpPrefs(ctx: Context) : GcpPrefs {
    private val p = ctx.getSharedPreferences("gcp", Context.MODE_PRIVATE)
    override var epochs: Int get() = p.getInt("epochs", 10).coerceIn(1, 100); set(v) { p.edit().putInt("epochs", v).apply() }
    override var operator: String get() = p.getString("operator", "") ?: ""; set(v) { p.edit().putString("operator", v).apply() }
    override var lastEpsg: Int get() = p.getInt("lastEpsg", 5186); set(v) { p.edit().putInt("lastEpsg", v).apply() }
    override var volumeKey: Boolean get() = p.getBoolean("volumeKey", false); set(v) { p.edit().putBoolean("volumeKey", v).apply() }
    override var lastSiteId: Long get() = p.getLong("lastSite", -1); set(v) { p.edit().putLong("lastSite", v).apply() }
    override fun pole(profileId: String): Double? = if (p.contains("pole_$profileId")) p.getFloat("pole_$profileId", 1.6f).toDouble().let { Math.round(it * 1000) / 1000.0 } else null
    override fun setPole(profileId: String, m: Double) { p.edit().putFloat("pole_$profileId", m.toFloat()).apply() }
}
