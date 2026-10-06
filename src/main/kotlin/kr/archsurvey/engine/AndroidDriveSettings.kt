package kr.archsurvey.engine

import kr.archsurvey.data.SettingsStore
import kr.archsurvey.ui.DriveSettings

class AndroidDriveSettings(private val s: SettingsStore) : DriveSettings {
    override var autoBackup: Boolean
        get() = s.driveAutoBackup
        set(v) { s.driveAutoBackup = v }
    override var autoUploadPoints: Boolean
        get() = s.driveAutoUploadPoints
        set(v) { s.driveAutoUploadPoints = v }
    override var accountEmail: String
        get() = s.driveAccountEmail
        set(v) { s.driveAccountEmail = v }
    override var folderId: String
        get() = s.driveFolderId
        set(v) { s.driveFolderId = v }
    override var lastBackupAt: Long
        get() = s.driveLastBackupAt
        set(v) { s.driveLastBackupAt = v }
    override var lastUploadAt: Long
        get() = s.driveLastUploadAt
        set(v) { s.driveLastUploadAt = v }
    override fun pointsFileId(project: String) = s.drivePointsFileId(project)
    override fun setPointsFileId(project: String, id: String?) = s.setDrivePointsFileId(project, id)
    override fun clearIds() = s.clearDriveIds()
}
