package kr.archsurvey.t0156

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.dig.PrepInput
import kr.archsurvey.dig.StepState
import kr.archsurvey.dig.TodayPrep
import kr.archsurvey.export.ExportFormat
import kr.archsurvey.job.JobFolderRegistry
import kr.archsurvey.job.JobFolders
import kr.archsurvey.job.MemoryJobFolderStore
import kr.archsurvey.job.SendFolder
import kr.archsurvey.job.TargetNames
import kr.archsurvey.t0130.Mem
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private val ROOT = File(System.getProperty("user.dir"))
private fun src(rel: String) = File(ROOT, "src/main/kotlin/kr/archsurvey/$rel").readText()

internal class Jobs56(start: String, group: String?) : JobPlatform {
    val reg = JobFolderRegistry(MemoryJobFolderStore()).apply { assign(start, group) }
    private val names = linkedSetOf(start)
    override var current: String = start; private set
    override suspend fun list() = names.map { info(it) }
    override suspend fun info(name: String) = JobInfo(name, 0, 0, 0, null, null, folder = folderOf(name))
    override suspend fun create(name: String) { names.add(name); current = name }
    override suspend fun create(name: String, group: String?) { reg.assign(name, group); create(name) }
    override suspend fun open(name: String) { current = name }
    override suspend fun delete(name: String): String { names.remove(name); return "b.zip" }
    override fun groups() = reg.groups()
    override fun folderOf(name: String) = JobFolders.display(reg.folderOf(name))
    fun add(n: String, g: String?) { names.add(n); reg.assign(n, g) }
}

internal class FakeSend(var points: Int, private val whereText: String = "내 파일 › Documents › 발굴조사 › 강릉 › 작업1 › 측량 › 보내기 › 작업1_측량.zip") : ExportPlatform {
    var made = 0; var shared = 0
    override suspend fun summary() = ExportSummary("작업1", points, 0, 0, 0)
    override suspend fun export(includePhotos: Boolean, includeVoices: Boolean): ExportOutput { made++; return ExportOutput("작업1_측량.zip", 10, "/x/작업1_측량.zip", points, 0, 0, where = whereText) }
    override fun share(out: ExportOutput) { shared++ }
    override fun saveAs(out: ExportOutput, onDone: (Boolean) -> Unit) = onDone(true)
    override fun sendWhere() = whereText
}

/** 0.1.56 위원회 사양(14-council-survey.md) 합격 기준 — 순수 JVM 부분 */
class Council0156PureTest {
    // ---- M1 플로트 확인은 작업당 1번(아래쪽 시트), 연속 10점 = 탭 11, 단독은 막힘 ----
    @Test fun m1_float10_taps11_sheetOnce_singleBlocked() {
        val store = Mem(); val (s, c) = rig(strict = false, store = store)
        c.projectName = "작업1"
        s.push(FixQuality.RTK_FLOAT, 0.12)
        assertEquals(SurveyStatus.CAUTION, c.gv.status.status)
        var taps = 0; var sheets = 0; var centered = 0
        repeat(10) {
            c.onMeasure(); taps++
            c.ui.confirm?.let { r -> if (r.sheet) sheets++ else centered++; c.ui.answer(true); taps++ }
            s.now += 1000; s.push(FixQuality.RTK_FLOAT, 0.12, lat = 37.0 + (it + 1) * 0.0005)
        }
        assertEquals(10, store.saved.size)
        assertEquals("탭 11회(첫 확인 1 + 10)", 11, taps)
        assertEquals(1, sheets); assertEquals("지도 중앙 창 0", 0, centered)
        assertEquals("◐ 플로트 ±12cm로 저장 중", c.floatBanner())
        // 단독(○) = 계속 막힘
        s.now += 1000; s.push(FixQuality.SINGLE, null)
        assertEquals(SurveyStatus.UNAVAILABLE, c.gv.status.status)
        c.onMeasure(); s.now += 1000; s.push(FixQuality.SINGLE, null, lat = 37.02)
        assertEquals(10, store.saved.size); assertNull(c.ui.confirm)
        // FIX 로 갔다 다시 플로트 = 묻지 않음
        s.now += 1000; s.push(FixQuality.RTK_FIXED, 0.01, lat = 37.03); s.now += 1000; s.push(FixQuality.RTK_FLOAT, 0.12, lat = 37.03)
        c.onMeasure(); assertNull(c.ui.confirm); s.now += 1000; s.push(FixQuality.RTK_FLOAT, 0.12, lat = 37.03)
        assertEquals(11, store.saved.size)
        // 다른 작업 = 다시 1번 묻기
        s.now += 1000; s.push(FixQuality.RTK_FLOAT, 0.12, lat = 37.04)
        c.projectName = "작업2"; c.onMeasure(); assertTrue(c.ui.confirm!!.sheet)
    }

    // ---- M2 VRS 꺼짐은 ✓ 아님 ----
    private fun prep(ntripOn: Boolean, ok: Boolean, fix: Boolean) = TodayPrep.steps(PrepInput(true, "R10", ntripOn, ok, "VRS", 1.6, "R10", true, 5186, "수신기", true, "BM1", true, true, true, fixNow = fix)).first { it.id == "vrs" }
    @Test fun m2_vrsOff_notCheck_unlessFix() {
        val off = prep(false, false, false)
        assertNotEquals(StepState.DONE, off.state); assertEquals(TodayPrep.VRS_OFF_WARN, off.sub); assertEquals("켜기", off.action); assertTrue(off.warn)
        val fix = prep(false, false, true)
        assertEquals(StepState.DONE, fix.state); assertEquals(TodayPrep.VRS_OFF_FIX, fix.sub); assertFalse(fix.warn)
        assertEquals(StepState.DONE, prep(true, true, false).state)
        assertTrue("[켜기] = VRS 설정 화면 1탭", src("ui/DigScreens.kt").contains("\"vrs\" -> c.connection?.openVrs()"))
    }

    // ---- M3 새 작업 좌표계 = 지난 작업 값 + 주황 줄 ----
    @Test fun m3_lastJobZone_kept_mismatchLine() {
        val (s, c) = rig(false, DeviceProfiles.R10_ID)
        c.projectName = "작업1"; c.zone = KoreanTmZone.EAST
        s.push(FixQuality.RTK_FIXED, 0.01, lon = 127.0) // 중부 위치
        val cfgs = MemoryJobConfigPlatform()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val jc = JobController(c, Jobs56("작업1", null), scope, cfgs); c.jobs = jc
        jc.openNew()
        assertEquals(KoreanTmZone.EAST, jc.formZone)
        assertEquals("지난 작업 동부(EPSG 5187) · 지금 위치는 중부(EPSG 5186)", jc.zoneMismatchText())
        jc.newName = "작업2"; jc.create()
        assertEquals(5187, cfgs.load("작업2")!!.epsg)
        jc.openNew(); jc.applyRecommendedZone(); assertEquals(KoreanTmZone.CENTRAL, jc.formZone); assertNull(jc.zoneMismatchText())
    }

    // ---- M4 빈 이름 = 만들기 꺼짐, 오류 한 곳 ----
    @Test fun m4_emptyName_disabled_noToast() {
        val (_, c) = rig(false)
        c.projectName = "작업1"
        val jc = JobController(c, Jobs56("작업1", null), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)); c.jobs = jc
        jc.openNew(); jc.newName = "  "
        assertFalse(jc.canCreate)
        jc.create()
        assertNotNull(jc.error); assertNull("카드+토스트 동시 0", c.ui.toast)
    }

    // ---- M5 경로 한 가지, 토스트 0, 「PC로 보내기」 ----
    @Test fun m5_samePathBeforeAfter_noToast() {
        val (_, c) = rig(false)
        val p = FakeSend(3); val ec = ExportController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)); c.exporter = ec
        ec.openScreen()
        val before = ec.where
        ec.runAndShare()
        assertEquals(before, ec.savedText); assertEquals(1, p.shared)
        assertNull("토스트 0", c.ui.toast)
        assertEquals("PC로 보내기", SendFolder.BUTTON)
        val scr = src("ui/ExportScreen.kt")
        assertTrue(scr.contains("양식 바꿔 다시 만들기")); assertFalse(scr.contains("파일 다시 고르기"))
    }

    // ---- M6 새 뿌리 · 예전 폴더 그대로 · 새 권한 0 · minSdk 21 ----
    @Test fun m6_newRoot_oldUntouched_noNewPermission_minSdk21() {
        assertEquals("발굴조사/강릉 교동/작업1/측량/보내기", SendFolder.sendRel("ExcavationSurvey/강릉 교동/작업1"))
        assertEquals("발굴조사/작업1/측량/보내기", SendFolder.sendRel("Documents/ExcavationSurvey/작업1"))
        assertEquals("내 파일 › Documents › 발굴조사 › 작업1 › 측량 › 보내기 › 작업1_측량.zip", SendFolder.display("ExcavationSurvey/작업1", "작업1"))
        val tmp = createTempDir()
        val old = File(tmp, "ExcavationSurvey/작업1/내보내기/a.zip").apply { parentFile.mkdirs(); writeText("old") }
        val z = File(tmp, "z.zip").apply { writeText("new") }
        SendFolder.place(File(tmp, SendFolder.sendRel("ExcavationSurvey/작업1")), z, SendFolder.zipName("작업1"))
        assertEquals("old", old.readText())
        val gradle = File(ROOT, "build.gradle.kts").readText()
        assertTrue(gradle.contains("minSdk = 21"))
        val man = File(ROOT, "src/main/AndroidManifest.xml").readText()
        val perms = Regex("uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(man).map { it.groupValues[1] }.toSet()
        val head = ProcessBuilder("git", "show", "56ef695:src/main/AndroidManifest.xml").directory(ROOT).start().inputStream.bufferedReader().readText()
        val perms0 = Regex("uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(head).map { it.groupValues[1] }.toSet()
        assertTrue(perms0.isNotEmpty()); assertEquals("새 권한 0", perms0, perms)
        assertTrue(src("ui/MoreMenu.kt").contains("예전 보내기 폴더 열기"))
    }

    // ---- M7 파일 Target #N, 화면 표적 #N ----
    @Test fun m7_targetNames() {
        assertEquals("Target #3", TargetNames.toFile("표적 #3")); assertEquals("Target #3", TargetNames.toFile("Target #3"))
        assertEquals("표적 #3", TargetNames.toScreen("Target #3")); assertEquals("P-0001", TargetNames.toScreen("P-0001"))
        assertTrue(src("export/ExportService.kt").contains("TargetNames.toFile(p.pointId)"))
        val store = Mem(); val (s, c) = rig(false, store = store); s.push(FixQuality.RTK_FIXED, 0.01)
        c.onMeasure(); s.now += 1000; s.push(FixQuality.RTK_FIXED, 0.01)
        assertEquals(1, c.points.size)
        val p0 = c.points[0]; c.points[0] = p0.copy(name = "Target #3")
        val names = c.mapState().points.map { it.name }
        assertTrue(names.toString(), names.contains("표적 #3") && names.none { "Target" in it })
    }

    // ---- M8 잰 점 0 = 보내기 꺼짐, 빈 zip 0 ----
    @Test fun m8_zeroPoints_disabled_noZip() {
        val p = FakeSend(0); val ec = ExportController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        ec.openScreen()
        assertFalse(ec.canSend)
        ec.runAndShare()
        assertEquals("빈 zip 0", 0, p.made); assertEquals(SendFolder.EMPTY, ec.error)
    }

    // ---- S5 같은 작업 3번 = 파일 1 + 이전 1, 「(1)」 0, info.txt ----
    @Test fun s5_overwrite_prev1() {
        val d = createTempDir()
        repeat(3) { i -> val z = File(d, "z$i.zip").apply { writeText("v$i") }; SendFolder.place(File(d, "send"), z, SendFolder.zipName("작업1")) }
        val send = File(d, "send")
        assertEquals(listOf("작업1_측량.zip"), send.listFiles()!!.filter { it.isFile }.map { it.name })
        assertEquals(listOf("작업1_측량.zip"), File(send, "이전").listFiles()!!.map { it.name })
        assertEquals("v2", File(send, "작업1_측량.zip").readText()); assertEquals("v1", File(send, "이전/작업1_측량.zip").readText())
        assertTrue(SendFolder.infoText("작업1", 3, "2026-10-15 17:00:00").contains("만든 때: 2026-10-15 17:00:00"))
        assertTrue(src("export/ExportService.kt").contains("\"info.txt\""))
    }

    // ---- S3·S2·S7 ----
    @Test fun s2_s3_s7_newJobForm() {
        val (_, c) = rig(false)
        c.projectName = "작업1"
        val jobs = Jobs56("작업1", "강릉"); jobs.add("작업0", "삼척"); jobs.add("작업x", "울진"); jobs.add("작업y", "포항")
        val jc = JobController(c, jobs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)); c.jobs = jc
        jc.openNew()
        assertEquals("칸은 빈 채", "", jc.newSite)
        assertTrue(jc.recentFolders.size <= 3); assertTrue(jc.recentFolders.all { it in listOf("강릉", "삼척", "울진", "포항") })
        jc.pickRecentFolder(jc.recentFolders[0]); assertEquals(jc.recentFolders[0], jc.newSite)
        jc.newName = "작업0"; assertEquals("작업0", jc.sameName)
        jc.openSameName(); assertEquals("작업0", jobs.current)
        assertEquals("동부(EPSG 5187)", KoreanTmZone.EAST.nameEpsg)
        assertTrue(src("ui/ToolScreens.kt").contains("폴더 (비우면 발굴조사 바로 아래)"))
    }

    // ---- C2·C3 ----
    @Test fun c2_c3_words() {
        assertTrue(src("ui/DigMapUi.kt").contains("\"경계 닫기(면)\"")); assertFalse(src("ui/DigMapUi.kt").contains("else \"면 닫기\""))
        assertTrue(src("ui/ToolScreens.kt").contains("\"이 작업에만 적용\"")); assertFalse(src("ui/ToolScreens.kt").contains("\"이 작업에 저장\""))
    }
}

/** 0.1.56 화면 합격 기준(Robolectric — 실기기 아님) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Council0156UiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun tap(tag: String) { val n = rule.onNodeWithTag(tag); runCatching { n.performScrollTo() }; n.performClick(); rule.waitForIdle() }

    private fun setup(points: Int = 3, home: Boolean = true): SurveyController {
        TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.R10_ID)
        c.projectName = "작업1"
        c.jobs = JobController(c, Jobs56("작업1", "강릉"), scope)
        c.exporter = ExportController(FakeSend(points), scope)
        c.homeOpen = home
        c.ui.toastMs = 600_000L
        s.push(FixQuality.RTK_FIXED, 0.005)
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
        return c
    }

    @Test fun m4_createDisabled_label() {
        setup(); tap("home_new_job")
        rule.onNodeWithTag("job_create").assertIsNotEnabled()
        rule.onNodeWithText("이름을 쓰세요").assertExists()
        rule.onNodeWithTag("job_name").performTextInput("교동 2호")
        rule.onNodeWithTag("job_create").assertIsEnabled()
    }

    @Test fun m5_m8_s4_exportScreen() {
        val c = setup(points = 3); tap("home_export")
        rule.onNodeWithTag("export_run").assertIsEnabled()
        rule.onNodeWithTag("export_run").assert(hasText("PC로 보내기", substring = true) or hasAnyDescendant(hasText("PC로 보내기")))
        rule.onNodeWithTag("export_fmt_csv").assertDoesNotExist() // S4 접힘
        rule.onNodeWithTag("export_options").assertExists()
        val before = rule.onNodeWithTag("export_where").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        tap("export_run")
        val after = rule.onNodeWithTag("export_saved_path").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        assertEquals(before, after); assertNull(c.ui.toast)
        rule.onNodeWithText("양식 바꿔 다시 만들기", useUnmergedTree = true).assertExists()
    }

    @Test fun m8_emptyJob() {
        setup(points = 0); tap("home_export")
        rule.onNodeWithTag("export_run").assertIsNotEnabled()
        rule.onAllNodesWithText("잰 점이 없어요", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
    }

    @Test fun s6_thumbRow_noDup() {
        setup()
        for (t in listOf("home_new_job", "home_card_open", "home_export")) assertEquals(1, rule.onAllNodesWithTag(t).fetchSemanticsNodes().size)
        rule.onNodeWithTag("home_thumb_row").onChildren().assertCountEquals(3)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag("home_thumb_row").fetchSemanticsNode().boundsInRoot
        assertTrue("아래 40% 안", row.center.y >= root.height * 0.6f)
    }

    @Test fun s8_s9_tapPaths() {
        val c = setup()
        rule.onNodeWithTag("home_pole_ok").assertExists()
        assertFalse(c.dig.poleConfirmedToday)
        tap("home_pole_ok")
        assertTrue("엄지 1탭, 화면 이동 0", c.dig.poleConfirmedToday); assertTrue(c.homeOpen)
        assertTrue(src("ui/MoreMenu.kt").contains("\"more_setup\"")); assertNotNull(c)
    }
}
