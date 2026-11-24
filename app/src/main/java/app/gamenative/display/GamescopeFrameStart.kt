package app.gamenative.display

/** Holds the final uninterrupted run of publishes until Gamescope's game-content threshold. */
internal class GamescopeFrameStart {
    @Volatile
    var gameContentSeen = false
        private set

    private val streak = LongArray(CONTENT_FRAMES)
    private var streakSize = 0

    /** Returns true once, after backfilling the start of the game at its original frame times. */
    inline fun onFrame(time: Long, countFrame: (Long) -> Unit): Boolean {
        if (gameContentSeen) {
            countFrame(time)
            return false
        }

        if (streakSize > 0 && time - streak[streakSize - 1] > CONTENT_GAP_MS) streakSize = 0
        streak[streakSize++] = time
        if (streakSize < CONTENT_FRAMES) return false

        for (index in 0 until streakSize) countFrame(streak[index])
        gameContentSeen = true
        return true
    }

    companion object {
        const val CONTENT_FRAMES = 60
        private const val CONTENT_GAP_MS = 250L
    }
}
