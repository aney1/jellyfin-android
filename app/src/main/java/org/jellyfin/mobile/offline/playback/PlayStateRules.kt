package org.jellyfin.mobile.offline.playback

/**
 * Fork: offline. Mirrors the Jellyfin server's rules for turning a stop position into a watch state
 * (`UserDataManager.UpdatePlayState` with the default MinResumePct = 5, MaxResumePct = 90 and
 * MinResumeDurationSeconds = 300), so offline playback ends up with the same result as online playback.
 */
object PlayStateRules {
    private const val MIN_RESUME_PERCENT = 5
    private const val MAX_RESUME_PERCENT = 90
    private const val MIN_RESUME_DURATION_TICKS = 300L * 10_000_000L
    private const val PERCENT = 100

    data class Result(val played: Boolean, val positionTicks: Long)

    /**
     * @param finished whether playback reached the end of the video.
     * @param wasPlayed whether the item counted as played before the stop (starting playback unmarks it).
     */
    fun onStop(positionTicks: Long, runTimeTicks: Long, finished: Boolean, wasPlayed: Boolean): Result {
        if (finished) return Result(played = true, positionTicks = 0)
        // Unknown length: unlike the server (which marks it played), keep the position, so a missing run time
        // never makes a download count as watched and get deleted
        if (runTimeTicks <= 0) return Result(played = wasPlayed, positionTicks = positionTicks)
        if (positionTicks <= 0) return Result(played = wasPlayed, positionTicks = 0)

        val percent = positionTicks.toDouble() * PERCENT / runTimeTicks
        return when {
            percent < MIN_RESUME_PERCENT -> Result(played = wasPlayed, positionTicks = 0)
            percent > MAX_RESUME_PERCENT -> Result(played = true, positionTicks = 0)
            runTimeTicks < MIN_RESUME_DURATION_TICKS -> Result(played = true, positionTicks = 0)
            else -> Result(played = wasPlayed, positionTicks = positionTicks)
        }
    }
}
