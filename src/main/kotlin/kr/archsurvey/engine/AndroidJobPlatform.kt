package kr.archsurvey.engine

import android.content.Context
import kr.archsurvey.data.BackupService
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.ui.JobException
import kr.archsurvey.ui.JobInfo
import kr.archsurvey.ui.JobNames
import kr.archsurvey.ui.JobPlatform
import java.io.File

/** [JobPlatform] 의 Android 구현. 작업 = points.project 값. 점이 아직 없는 새 작업 이름은 설정에 따로 적어 둔다. */
class AndroidJobPlatform(private val ctx: Context, private val settings: SettingsStore, private val versionName: String) : JobPlatform {
    private val dao get() = SurveyDb.get(ctx).dao()
    override val current: String get() = settings.project

    private fun safe(s: String) = s.replace(Regex("[^0-9A-Za-z가-힣._-]"), "_")

    private suspend fun names(): List<String> =
        (dao.projects() + settings.extraProjects + settings.project).distinct().sortedWith(compareBy({ it != settings.project }, { it }))

    override suspend fun list(): List<JobInfo> = names().map { info(it) }

    override suspend fun info(name: String) = JobInfo(
        name, dao.liveCount(name), dao.livePhotoCount(name), dao.liveVoiceCount(name), dao.firstAt(name), dao.lastAt(name), dao.fakeCount(name),
    )

    override suspend fun create(name: String) {
        val n = name.trim()
        JobNames.problem(n, names())?.let { throw JobException(it) }
        settings.extraProjects = settings.extraProjects + n
        settings.project = n
    }

    override suspend fun open(name: String) {
        if (name !in names()) throw JobException("그런 작업이 없어요")
        settings.project = name
    }

    override suspend fun delete(name: String): String {
        val backup = BackupService(ctx).create("before_delete", versionName)
        dao.deleteProject(name)
        File(ctx.getExternalFilesDir("Pictures") ?: File(ctx.filesDir, "Pictures"), safe(name)).deleteRecursively()
        File(ctx.getExternalFilesDir("Recordings") ?: File(ctx.filesDir, "Recordings"), safe(name)).deleteRecursively()
        settings.extraProjects = settings.extraProjects - name
        if (name == settings.project) {
            val rest = names().filter { it != name }
            settings.project = rest.firstOrNull() ?: "작업1"
        }
        return backup.name
    }
}
