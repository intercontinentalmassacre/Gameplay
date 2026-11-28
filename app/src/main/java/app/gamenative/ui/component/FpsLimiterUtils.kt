package app.gamenative.ui.component

import java.util.Locale

internal fun parsePositiveFpsLimit(value: String): Int? = value.toIntOrNull()?.takeIf { it > 0 }

/** The cap the gamescope shim gets: the user's cap even while LSFG is generating. */
internal fun gamescopeFpsCap(limiterEnabled: Boolean, target: Int): Int =
    if (limiterEnabled) target else 0

internal fun parseBooleanExtra(value: String): Boolean? =
    when (value.trim().lowercase(Locale.US)) {
        "true" -> true
        "false" -> false
        else -> null
    }

internal fun fpsLimiterSteps(maxFps: Int, minFps: Int = 5): List<Int> {
    val sanitizedMax = maxFps.coerceAtLeast(minFps)
    val flooredMax = (sanitizedMax / 5) * 5
    return buildList {
        var value = minFps
        while (value <= flooredMax) {
            add(value)
            value += 5
        }
        if (sanitizedMax != flooredMax) add(sanitizedMax)
    }
}

/**
 * Returns the index of the step that is the floor of [currentValue] — i.e. the highest
 * step that is still ≤ currentValue.  Falls back to 0 if currentValue is below the
 * first step, so navigation never goes in the wrong direction on a restored value that
 * isn't an exact multiple of 5.
 */
internal fun fpsLimiterCurrentIndex(steps: List<Int>, currentValue: Int): Int =
    steps.indexOfLast { it <= currentValue }.coerceAtLeast(0)

internal fun fpsLimiterProgress(currentValue: Int, maxFps: Int, minFps: Int = 5): Float {
    val steps = fpsLimiterSteps(maxFps, minFps)
    val currentIndex = fpsLimiterCurrentIndex(steps, currentValue)
    return if (steps.lastIndex <= 0) 1f else currentIndex.toFloat() / steps.lastIndex.toFloat()
}

internal fun nextFpsLimiterValue(currentValue: Int, maxFps: Int, minFps: Int = 5): Int {
    val steps = fpsLimiterSteps(maxFps, minFps)
    val currentIndex = fpsLimiterCurrentIndex(steps, currentValue)
    return steps[(currentIndex + 1).coerceAtMost(steps.lastIndex)]
}

internal fun previousFpsLimiterValue(currentValue: Int, maxFps: Int, minFps: Int = 5): Int {
    val steps = fpsLimiterSteps(maxFps, minFps)
    val currentIndex = fpsLimiterCurrentIndex(steps, currentValue)
    return steps[(currentIndex - 1).coerceAtLeast(0)]
}
