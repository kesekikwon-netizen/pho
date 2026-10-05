package kr.archsurvey.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import kr.archsurvey.R
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * WP-11: 아이콘 벡터 리소스를 JVM(Robolectric 네이티브 그래픽)에서 실제로 그려 안전영역·마스크 미리보기를 만든다.
 * 실제 런처/제조사 마스크 표시가 아니다(런처 시험 없음). 마스크 모양은 아래 근사 도형(원·둥근 사각·스쿼클).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class IconRenderTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val out = File(REPORTS_ROOT + "/icon").apply { mkdirs() }
    private val PX = 10f // 1dp = 10px -> 108dp = 1080px

    private fun drawable(id: Int): Drawable = ContextCompat.getDrawable(ctx, id)!!.mutate()

    private fun render(d: Drawable, dp: Float, tint: Int? = null): Bitmap {
        val s = (dp * PX).toInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        if (tint != null) d.setTint(tint)
        d.draw(Canvas(b))
        return b
    }

    private fun save(b: Bitmap, name: String) = FileOutputStream(File(out, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun maxRadiusPx(b: Bitmap): Double {
        val c = b.width / 2.0; var m = 0.0
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (y in 0 until b.height) for (x in 0 until b.width) if ((px[y * b.width + x] ushr 24) > 8) {
            val r = Math.hypot(x + 0.5 - c, y + 0.5 - c); if (r > m) m = r
        }
        return m
    }

    @Test fun foreground_and_monochrome_inside_66dp_safe_zone() {
        val safeRadiusPx = 33.0 * PX // 66dp 지름
        val fg = render(drawable(R.drawable.ic_launcher_foreground), 108f)
        val mono = render(drawable(R.drawable.ic_launcher_monochrome), 108f)
        val rf = maxRadiusPx(fg); val rm = maxRadiusPx(mono)
        out.resolve("safe_zone_metrics.txt").writeText(
            "foreground max radius = %.1f dp (limit 33.0), monochrome = %.1f dp (limit 33.0)\n".format(rf / PX, rm / PX))
        assertTrue("전경이 안전영역 밖: ${rf / PX}dp", rf <= safeRadiusPx)
        assertTrue("모노크롬이 안전영역 밖: ${rm / PX}dp", rm <= safeRadiusPx)
        assertTrue("전경이 비어 있음", rf > 20 * PX)
    }

    @Test fun foreground_matches_designer_png() {
        val ref = File("/workspace/survey-app/design-system/mockups/v2/icon/final/launcher/icon_B_fg_1024.png")
        assumeTrue(ref.exists())
        val r = BitmapFactory.decodeFile(ref.path)
        val m = Bitmap.createScaledBitmap(render(drawable(R.drawable.ic_launcher_foreground), 108f), 1024, 1024, true)
        var inter = 0; var uni = 0
        val a = IntArray(1024 * 1024); val b = IntArray(1024 * 1024)
        r.getPixels(a, 0, 1024, 0, 0, 1024, 1024); m.getPixels(b, 0, 1024, 0, 0, 1024, 1024)
        for (i in a.indices) { val x = (a[i] ushr 24) > 128; val y = (b[i] ushr 24) > 128; if (x && y) inter++; if (x || y) uni++ }
        val iou = inter.toDouble() / uni
        out.resolve("fg_vs_designer_png.txt").writeText("alpha>128 IoU between vector foreground and designer icon_B_fg_1024.png = %.4f\n".format(iou))
        assertTrue("벡터 전경이 시안 PNG 와 다름 IoU=$iou", iou > 0.95)
    }

    private fun maskPath(kind: String, cx: Float, cy: Float, r: Float): Path = Path().apply {
        when (kind) {
            "circle" -> addCircle(cx, cy, r, Path.Direction.CW)
            "rounded" -> addRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), r * 0.36f, r * 0.36f, Path.Direction.CW)
            else -> { // squircle: |x|^4+|y|^4 = r^4
                for (i in 0..360) {
                    val t = Math.toRadians(i.toDouble()); val c = Math.cos(t); val s = Math.sin(t)
                    val x = cx + r * Math.signum(c) * Math.pow(Math.abs(c), 0.5).toFloat()
                    val y = cy + r * Math.signum(s) * Math.pow(Math.abs(s), 0.5).toFloat()
                    if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
                }
                close()
            }
        }
    }

    @Test fun mask_previews_and_small_sizes() {
        val bg = drawable(R.drawable.ic_launcher_background); val fg = drawable(R.drawable.ic_launcher_foreground)
        val mono = drawable(R.drawable.ic_launcher_monochrome)
        val cell = 108 * PX.toInt() / 2 // 540px per cell (54dp scale: icon drawn at 0.5x -> 540)
        val kinds = listOf("circle", "squircle", "rounded")
        val sheet = Bitmap.createBitmap(cell * 3 + 40 * 4, cell * 4 + 40 * 5, Bitmap.Config.ARGB_8888)
        val cv = Canvas(sheet); cv.drawColor(Color.rgb(0xE9, 0xE6, 0xDD))
        fun layer(d: Drawable, tint: Int? = null): Bitmap = Bitmap.createScaledBitmap(render(d, 108f, tint), cell, cell, true)
        val bgB = layer(bg); val fgB = layer(fg)
        val monoDark = layer(mono, Color.WHITE); val monoLight = layer(mono, Color.rgb(0x1B, 0x6B, 0xB0))
        // 행 0: 라이트 배경 / 행 1: 다크 배경 / 행 2: 모노크롬(밝은 배경) / 행 3: 모노크롬(어두운 배경)
        for (row in 0 until 4) for ((i, k) in kinds.withIndex()) {
            val ox = 40f + i * (cell + 40f); val oy = 40f + row * (cell + 40f)
            val wp = if (row == 1 || row == 3) Color.rgb(0x1E, 0x1E, 0x1C) else Color.rgb(0xE9, 0xE6, 0xDD)
            val p = Paint().apply { color = wp }; cv.drawRect(ox - 20, oy - 20, ox + cell + 20, oy + cell + 20, p)
            cv.save()
            // 마스크 표시영역 72dp = 전체 108dp 의 2/3
            val r = cell * (72f / 108f) / 2
            cv.clipPath(maskPath(k, ox + cell / 2f, oy + cell / 2f, r))
            if (row < 2) { cv.drawBitmap(bgB, ox, oy, null); cv.drawBitmap(fgB, ox, oy, null) }
            else {
                val pb = Paint().apply { color = if (row == 2) Color.rgb(0xCF, 0xE0, 0xF2) else Color.rgb(0x3A, 0x4A, 0x5E) }
                cv.drawRect(ox, oy, ox + cell, oy + cell, pb)
                cv.drawBitmap(if (row == 2) monoLight else monoDark, ox, oy, null)
            }
            cv.restore()
        }
        save(sheet, "mask_preview_circle_squircle_rounded_light_dark_mono.png")

        // 개별 파일: 원형/스쿼클 (라이트)
        for (k in kinds) {
            val s = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888); val c = Canvas(s)
            c.clipPath(maskPath(k, cell / 2f, cell / 2f, cell * (72f / 108f) / 2))
            c.drawBitmap(bgB, 0f, 0f, null); c.drawBitmap(fgB, 0f, 0f, null)
            val crop = Bitmap.createBitmap(s, (cell * 0.1667f).toInt(), (cell * 0.1667f).toInt(), (cell * 0.6667f).toInt(), (cell * 0.6667f).toInt())
            save(crop, "mask_${k}_light.png")
        }
        // 레거시 비트맵
        val legacy = Bitmap.createBitmap(192 * 2 + 30, 192 + 20, Bitmap.Config.ARGB_8888); val lc = Canvas(legacy); lc.drawColor(Color.rgb(0xE9, 0xE6, 0xDD))
        val l1 = drawable(R.mipmap.ic_launcher); l1.setBounds(10, 10, 202, 202); l1.draw(lc)
        // API 34 에서는 anydpi 가 우선이므로 레거시 PNG 는 파일을 직접 읽는다
        val png = BitmapFactory.decodeFile("src/main/res/mipmap-xxxhdpi/ic_launcher.png"); lc.drawBitmap(png, 10f, 10f, null)
        val png2 = BitmapFactory.decodeFile("src/main/res/mipmap-xxxhdpi/ic_launcher_round.png"); lc.drawBitmap(png2, 222f, 10f, null)
        save(legacy, "legacy_xxxhdpi_square_round.png")

        // 24/32/48dp 단순판 (xxhdpi 3x, 라이트·테라코타) + 로고 마크
        val s3 = 3f
        val sm = Bitmap.createBitmap(((24 + 32 + 48 + 96) * s3 + 25 * s3 * 2).toInt() + 40, (96 * s3).toInt() + 40, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sm); sc.drawColor(Color.rgb(0xFA, 0xF9, 0xF5))
        var x = 20f
        for ((id, dp) in listOf(R.drawable.ic_small_mark_ink to 24f, R.drawable.ic_small_mark_ink to 32f, R.drawable.ic_small_mark_terracotta to 48f, R.drawable.ic_logo_mark_terracotta to 96f)) {
            val d = drawable(id); val px = (dp * s3).toInt(); d.setBounds(x.toInt(), 20, x.toInt() + px, 20 + px); d.draw(sc); x += px + 25 * s3
        }
        save(sm, "small_24_32_48dp_and_logo_96dp_at_3x.png")
        assertTrue(File(out, "mask_preview_circle_squircle_rounded_light_dark_mono.png").length() > 10_000)
    }

    @Test fun adaptive_icon_xml_has_monochrome_and_round() {
        for (n in listOf("ic_launcher", "ic_launcher_round")) {
            val t = File("src/main/res/mipmap-anydpi-v26/$n.xml").readText()
            assertTrue(t.contains("<monochrome")); assertTrue(t.contains("<background")); assertTrue(t.contains("<foreground"))
        }
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }
}
