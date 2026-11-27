package app.gamenative.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.BuildConfig
import app.gamenative.Constants
import app.gamenative.R
import app.gamenative.ui.component.dialog.state.GameFeedbackDialogState
import timber.log.Timber

/** Community-owned destinations for the post-game feedback flow. */
object CommunityFeedbackLinks {
    fun open(context: Context, url: String = Constants.Misc.GITHUB_ISSUES_LINK) {
        val activity = BrightnessManager.findActivity(context)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            (activity ?: context).startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Timber.w(error, "No app available to open GitHub Issues")
            SnackbarManager.show(context.getString(R.string.github_issues_no_browser))
        }
    }

    /** No data leaves the device until the player submits this pre-filled issue. */
    fun githubIssueUrl(state: GameFeedbackDialogState): String {
        val rating = state.rating.takeIf { it > 0 }?.let { "$it/5" } ?: "Not selected"
        val tags = state.selectedTags
            .map { it.replace('_', ' ') }
            .sorted()
            .joinToString()
            .ifBlank { "Not selected" }
        val notes = state.feedbackText.trim().ifBlank { "Describe what happened here." }
        val body = """
            ## What happened?

            $notes

            ## Quick report

            - Gameplay version: ${BuildConfig.VERSION_NAME}
            - Game/container: ${state.appId.ifBlank { "Unknown" }}
            - Rating: $rating
            - Symptoms: $tags
            - Device and Android version: [please add]
            - Driver and container settings: [please add]

            <!-- Remove any private details before submitting. Attach logs only if you are comfortable sharing them. -->
        """.trimIndent()

        return Uri.Builder()
            .scheme("https")
            .authority("github.com")
            .appendPath("intercontinentalmassacre")
            .appendPath("Gameplay")
            .appendPath("issues")
            .appendPath("new")
            .appendQueryParameter("title", "Game feedback: ${state.appId.ifBlank { "Gameplay" }}")
            .appendQueryParameter("body", body)
            .build()
            .toString()
    }
}
