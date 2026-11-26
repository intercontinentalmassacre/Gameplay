package app.gamenative.itch

/** How the game was installed, which decides how it can be updated. */
enum class ItchInstallerKind {
    /** Portable ZIP extracted into a Gameplay-managed, versioned folder. */
    ZIP,

    /** Windows EXE/MSI installer that itself writes into the Wine prefix. */
    EXE,
}

/**
 * What Gameplay actually installed. Every field is persisted so updates can be
 * reasoned about offline; [buildId] is the reliable butler build id, while
 * [fileUpdatedAt]/[fileSize] are the only evidence itch.io exposes for files
 * uploaded by hand.
 */
data class ItchInstalledRevision(
    val kind: ItchInstallerKind,
    val uploadId: Long?,
    val buildId: Long?,
    val userVersion: String?,
    val fileUpdatedAt: String?,
    val fileSize: Long,
    val filename: String,
)

enum class ItchUpdateStatus {
    NOT_INSTALLED,

    /**
     * Only reported when the API gives trustworthy evidence: the same butler
     * build id is still current. Never claimed for hand-uploaded files.
     */
    UP_TO_DATE,
    UPDATE_AVAILABLE,

    /** The API cannot tell whether the installed file is current. */
    VERSION_UNKNOWN,

    /** The stored upload id is gone (removed/hidden). No automatic channel switch. */
    UPLOAD_MISSING,
}

enum class ItchUpdateReason {
    BUILD_CHANGED,
    FILE_CHANGED,
    MANUAL_UPLOAD_LIMITED_EVIDENCE,
    UPLOAD_REMOVED,
    NO_WINDOWS_BUILD,
    INSTALLER_REQUIRES_MANUAL_UPDATE,
}

data class ItchUpdateDecision(
    val status: ItchUpdateStatus,
    val upload: ItchUpload?,
    val reason: ItchUpdateReason?,
    val manualInstaller: Boolean = false,
) {
    /** True when Gameplay may download and swap the install automatically. */
    val canAutoUpdate: Boolean
        get() = status == ItchUpdateStatus.UPDATE_AVAILABLE && !manualInstaller
}

/**
 * Compares the installed revision with the current upload list for the *same*
 * project. It deliberately never considers a different upload/channel: updating
 * a full game to a demo or another channel is an explicit user decision.
 */
object ItchUpdateChecker {
    fun evaluate(
        installed: ItchInstalledRevision?,
        uploads: List<ItchUpload>,
    ): ItchUpdateDecision {
        if (installed == null) {
            return ItchUpdateDecision(ItchUpdateStatus.NOT_INSTALLED, null, null)
        }
        val manualInstaller = installed.kind == ItchInstallerKind.EXE
        val uploadId = installed.uploadId
            ?: return ItchUpdateDecision(
                status = ItchUpdateStatus.VERSION_UNKNOWN,
                upload = null,
                reason = ItchUpdateReason.MANUAL_UPLOAD_LIMITED_EVIDENCE,
                manualInstaller = manualInstaller,
            )

        val upload = uploads.firstOrNull { it.id == uploadId }
            ?: return ItchUpdateDecision(
                status = ItchUpdateStatus.UPLOAD_MISSING,
                upload = null,
                reason = ItchUpdateReason.UPLOAD_REMOVED,
                manualInstaller = manualInstaller,
            )

        if (upload.build == null && installed.buildId != null) {
            // The build metadata disappeared (e.g. re-uploaded by hand).
            return ItchUpdateDecision(
                status = ItchUpdateStatus.VERSION_UNKNOWN,
                upload = upload,
                reason = ItchUpdateReason.MANUAL_UPLOAD_LIMITED_EVIDENCE,
                manualInstaller = manualInstaller,
            )
        }

        val buildChanged = installed.buildId != null &&
            upload.build?.id != null &&
            installed.buildId != upload.build?.id
        val fileChanged = evidenceFileChanged(installed, upload)

        val status = when {
            buildChanged -> ItchUpdateStatus.UPDATE_AVAILABLE
            upload.build?.id != null && installed.buildId != null -> ItchUpdateStatus.UP_TO_DATE
            fileChanged -> ItchUpdateStatus.UPDATE_AVAILABLE
            else -> ItchUpdateStatus.VERSION_UNKNOWN
        }
        val reason = when {
            buildChanged -> ItchUpdateReason.BUILD_CHANGED
            status == ItchUpdateStatus.UP_TO_DATE -> null
            fileChanged -> ItchUpdateReason.FILE_CHANGED
            manualInstaller -> ItchUpdateReason.INSTALLER_REQUIRES_MANUAL_UPDATE
            else -> ItchUpdateReason.MANUAL_UPLOAD_LIMITED_EVIDENCE
        }
        return ItchUpdateDecision(
            status = status,
            upload = upload,
            reason = reason,
            manualInstaller = manualInstaller,
        )
    }

    private fun evidenceFileChanged(installed: ItchInstalledRevision, upload: ItchUpload): Boolean {
        val currentUpdatedAt = upload.updatedAt.takeIf(String::isNotBlank)
        val installedUpdatedAt = installed.fileUpdatedAt?.takeIf(String::isNotBlank)
        if (currentUpdatedAt != null && installedUpdatedAt != null) {
            return currentUpdatedAt != installedUpdatedAt
        }
        return upload.size > 0L && installed.fileSize > 0L && upload.size != installed.fileSize
    }

    /** Builds the persisted revision marker from an upload that was just installed. */
    fun revisionFor(
        kind: ItchInstallerKind,
        upload: ItchUpload,
    ): ItchInstalledRevision = ItchInstalledRevision(
        kind = kind,
        uploadId = upload.id,
        buildId = upload.build?.id,
        userVersion = upload.build?.userVersion,
        fileUpdatedAt = upload.updatedAt.takeIf(String::isNotBlank),
        fileSize = upload.size,
        filename = upload.filename,
    )
}
