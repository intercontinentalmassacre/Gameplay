package app.gamenative.ui.data

import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class Achievement(
    val displayName: String,
    val name: String?,
    val isUnlocked: Boolean,
    val description: String,
    val unlockTimestamp: Int,
    val hidden: Boolean,
    val icon: String,
    val iconGray: String?,
    val progressCurrent: Float? = null,
    val progressMax: Float? = null,
) {
    val hasProgress: Boolean
        get() = progressMax != null && progressMax > 0f

    fun formattedUnlockDateTime(): Pair<String, String>? {
        if (unlockTimestamp == 0) return null
        val locale = Locale.getDefault()
        val instant = Date(unlockTimestamp * 1000L)
        return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(instant) to
            DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(instant)
    }
}
