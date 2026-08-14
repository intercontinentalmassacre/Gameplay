package app.gamenative.ui.data

import app.gamenative.data.GameSource

enum class DownloadItemStatus {
    DOWNLOADING,
    PAUSED,
    RESUMABLE,
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class DownloadItemState(
    val appId: String,
    val gameSource: GameSource,
    val gameName: String,
    val iconUrl: String,
    val progress: Float?,
    val bytesDownloaded: Long?,
    val bytesTotal: Long?,
    val etaMs: Long?,
    val statusMessage: String?,
    val isActive: Boolean?,
    val isPartial: Boolean,
    val status: DownloadItemStatus,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val uniqueId: String
        get() = "${gameSource.name}_$appId"

    val canPause: Boolean
        get() = status == DownloadItemStatus.DOWNLOADING

    val canResume: Boolean
        get() = isPartial && (
            status == DownloadItemStatus.PAUSED ||
                status == DownloadItemStatus.RESUMABLE ||
                status == DownloadItemStatus.FAILED
            )

    val canCancel: Boolean
        get() = status == DownloadItemStatus.DOWNLOADING || isPartial

    val isFinished: Boolean
        get() = !isPartial && (
            status == DownloadItemStatus.COMPLETED ||
                status == DownloadItemStatus.CANCELLED ||
                status == DownloadItemStatus.FAILED
            )
}

data class CancelConfirmation(
    val appId: String,
    val gameSource: GameSource,
    val gameName: String,
)

enum class ContainerFileStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    READY,
    FAILED,
}

/**
 * One container file component surfaced in the Downloads → Containers tab.
 * The same backing archive is used for first-install of any game; downloading
 * it ahead of time is purely a latency optimisation.
 */
data class ContainerFileItemState(
    val componentId: String,
    val nameResId: Int,
    val descriptionResId: Int,
    val progress: Float?,
    val bytesDownloaded: Long?,
    val status: ContainerFileStatus,
    val statusMessage: String?,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val uniqueId: String get() = componentId

    val canDownload: Boolean get() = status == ContainerFileStatus.NOT_DOWNLOADED || status == ContainerFileStatus.FAILED
    val canPause: Boolean get() = status == ContainerFileStatus.DOWNLOADING
    val canResume: Boolean get() = status == ContainerFileStatus.PAUSED || status == ContainerFileStatus.FAILED
    val canRemove: Boolean get() = status == ContainerFileStatus.READY || status == ContainerFileStatus.PAUSED
    val isReady: Boolean get() = status == ContainerFileStatus.READY
    val isActive: Boolean get() = status == ContainerFileStatus.DOWNLOADING
}

data class ContainerClearConfirmation(
    val totalSizeBytes: Long,
)

data class DownloadsState(
    val downloads: Map<String, DownloadItemState> = emptyMap<String, DownloadItemState>(),
    val cancelConfirmation: CancelConfirmation? = null,
    val containers: Map<String, ContainerFileItemState> = emptyMap<String, ContainerFileItemState>(),
    val containerClearConfirmation: ContainerClearConfirmation? = null,
)
