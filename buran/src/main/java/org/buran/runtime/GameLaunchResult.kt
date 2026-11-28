package org.buran.runtime

import android.app.Activity
import android.content.Intent

internal data class GameLaunchResult(
    val gameId: String,
    val outcome: Outcome,
    val exitCode: Int? = null,
    val message: String? = null,
) {
    enum class Outcome(val wireName: String) {
        EXITED("exited"), STOPPED("stopped"), FAILED("failed"),
    }

    fun toIntent() = Intent().putExtra(GAME_ID, gameId).putExtra(OUTCOME, outcome.wireName).apply {
        exitCode?.let { putExtra(EXIT_CODE, it) }
        message?.let { putExtra(MESSAGE, it) }
    }

    companion object {
        const val GAME_ID = "gameId"
        private const val OUTCOME = "outcome"
        private const val EXIT_CODE = "exitCode"
        private const val MESSAGE = "message"

        fun fromActivityResult(resultCode: Int, data: Intent?): GameLaunchResult? {
            if (resultCode != Activity.RESULT_OK || data == null) return null
            val gameId = data.getStringExtra(GAME_ID) ?: return null
            val outcome = Outcome.entries.find { it.wireName == data.getStringExtra(OUTCOME) } ?: return null
            val exitCode = if (data.hasExtra(EXIT_CODE)) data.getIntExtra(EXIT_CODE, 0) else null
            return GameLaunchResult(gameId, outcome, exitCode, data.getStringExtra(MESSAGE))
        }
    }
}
