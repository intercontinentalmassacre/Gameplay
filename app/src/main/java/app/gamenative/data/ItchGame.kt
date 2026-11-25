package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Locally cached itch.io project plus everything Gameplay knows about its
 * installation.
 *
 * Account-scoped fields ([isOwned], [accountId]) are derived from the connected
 * itch.io account. On sign-out they are cleared for non-installed rows (which are
 * deleted) while installed rows survive so the game stays launchable from the
 * "Installed" section.
 *
 * [gameId] is the itch.io numeric project id and is always prefixed with
 * `ITCH_` when exposed as a library appId, so it can never collide with Steam,
 * GOG, Epic, Amazon or local game identifiers.
 */
@Entity(
    tableName = "itch_games",
    indices = [
        Index("account_id"),
        Index("is_installed"),
        Index("is_owned"),
    ],
)
data class ItchGame(
    @PrimaryKey
    @ColumnInfo("game_id")
    val gameId: Long,
    @ColumnInfo("title")
    val title: String = "",
    @ColumnInfo("url")
    val url: String = "",
    @ColumnInfo("cover_url")
    val coverUrl: String = "",
    @ColumnInfo("author")
    val author: String = "",
    @ColumnInfo("author_url")
    val authorUrl: String = "",
    @ColumnInfo("is_owned")
    val isOwned: Boolean = true,
    @ColumnInfo("account_id")
    val accountId: Long? = null,
    @ColumnInfo("is_installed")
    val isInstalled: Boolean = false,
    @ColumnInfo("install_kind")
    val installKind: String = INSTALL_KIND_NONE,
    @ColumnInfo("install_root")
    val installRoot: String = "",
    @ColumnInfo("launch_target")
    val launchTarget: String = "",
    @ColumnInfo("launch_args")
    val launchArgs: String = "",
    @ColumnInfo("selected_upload_id")
    val selectedUploadId: Long? = null,
    @ColumnInfo("selected_upload_name")
    val selectedUploadName: String = "",
    @ColumnInfo("installed_upload_id")
    val installedUploadId: Long? = null,
    @ColumnInfo("installed_upload_name")
    val installedUploadName: String = "",
    @ColumnInfo("installed_build_id")
    val installedBuildId: Long? = null,
    @ColumnInfo("installed_user_version")
    val installedUserVersion: String? = null,
    @ColumnInfo("installed_file_updated_at")
    val installedFileUpdatedAt: String? = null,
    @ColumnInfo("installed_file_size")
    val installedFileSize: Long = 0L,
    @ColumnInfo("installed_at_ms")
    val installedAtMs: Long = 0L,
    @ColumnInfo("update_status")
    val updateStatus: String = UPDATE_STATUS_UNKNOWN,
    @ColumnInfo("update_checked_at_ms")
    val updateCheckedAtMs: Long = 0L,
    @ColumnInfo("update_message")
    val updateMessage: String? = null,
    @ColumnInfo("size_on_disk")
    val sizeOnDisk: Long = 0L,
    @ColumnInfo("last_error")
    val lastError: String? = null,
    @ColumnInfo("added_at_ms")
    val addedAtMs: Long = 0L,
    @ColumnInfo("updated_at_ms")
    val updatedAtMs: Long = 0L,
) {
    val appId: String get() = "${GameSource.ITCH.name}_$gameId"

    val isButlerBuild: Boolean get() = installedBuildId != null

    fun installedRevisionOrNull(): app.gamenative.itch.ItchInstalledRevision? {
        if (!isInstalled) return null
        val kind = when (installKind) {
            INSTALL_KIND_EXE -> app.gamenative.itch.ItchInstallerKind.EXE
            else -> app.gamenative.itch.ItchInstallerKind.ZIP
        }
        return app.gamenative.itch.ItchInstalledRevision(
            kind = kind,
            uploadId = installedUploadId,
            buildId = installedBuildId,
            userVersion = installedUserVersion,
            fileUpdatedAt = installedFileUpdatedAt,
            fileSize = installedFileSize,
            filename = installedUploadName,
        )
    }

    companion object {
        const val INSTALL_KIND_NONE = "NONE"
        const val INSTALL_KIND_ZIP = "ZIP"
        const val INSTALL_KIND_EXE = "EXE"

        const val UPDATE_STATUS_UNKNOWN = "UNKNOWN"
        const val UPDATE_STATUS_NOT_INSTALLED = "NOT_INSTALLED"
        const val UPDATE_STATUS_UP_TO_DATE = "UP_TO_DATE"
        const val UPDATE_STATUS_AVAILABLE = "UPDATE_AVAILABLE"
        const val UPDATE_STATUS_UPLOAD_MISSING = "UPLOAD_MISSING"
    }
}
