package org.jellyfin.mobile.offline.playlist

import java.util.UUID
import kotlin.time.Duration

/**
 * Fork: offline. The pure decision logic of playlist downloads and auto delete, kept free of Android and
 * server dependencies so it can be unit tested.
 */
object OfflineRules {
    private const val TICKS_PER_MINUTE = 60L * 10_000_000L

    data class Entry(
        val itemId: UUID,
        val isVideo: Boolean,
        val runTimeTicks: Long?,
        val played: Boolean,
    )

    /**
     * The videos of a playlist that should be on the device, in playlist order: unwatched videos that aren't
     * excluded and not longer than [maxRuntimeMinutes], at most [maxItems] of them.
     *
     * Watched videos are never wanted, otherwise videos removed by the auto delete would be downloaded again.
     */
    fun wantedItems(
        entries: List<Entry>,
        excluded: Set<UUID>,
        maxItems: Int?,
        maxRuntimeMinutes: Int?,
    ): List<UUID> {
        val maxRuntimeTicks = maxRuntimeMinutes?.let { it * TICKS_PER_MINUTE }
        val wanted = entries.asSequence()
            .filter { it.isVideo && !it.played && it.itemId !in excluded }
            .filter { maxRuntimeTicks == null || it.runTimeTicks == null || it.runTimeTicks <= maxRuntimeTicks }
            .map { it.itemId }
            .distinct()
        return (if (maxItems != null) wanted.take(maxItems) else wanted).toList()
    }

    data class Origin(
        val playlistId: UUID,
        /** Since when the playlist doesn't contain the item anymore, null while it does. */
        val missingSince: Long?,
    )

    /**
     * Candidates for removal: downloads that were started for playlists and left all of them, for at least
     * [grace] (so one empty or partial server response doesn't delete anything). The caller still has to check
     * that they are unwatched, with fresh data from the server.
     *
     * @param origins the playlists each downloaded item was downloaded for.
     * @param knownSnapshots current entries of every playlist that was refreshed at least once. Items still in any
     * of them are kept, and origins pointing to a playlist that was never refreshed count as still present.
     */
    fun leftPlaylists(
        origins: Map<UUID, List<Origin>>,
        knownSnapshots: Map<UUID, Set<UUID>>,
        now: Long,
        grace: Duration,
        itemInUse: UUID?,
    ): Set<UUID> = origins.filter { (itemId, itemOrigins) ->
        itemId != itemInUse &&
            itemOrigins.isNotEmpty() &&
            itemOrigins.all { origin ->
                origin.playlistId in knownSnapshots &&
                    origin.missingSince != null &&
                    now - origin.missingSince >= grace.inWholeMilliseconds
            } &&
            knownSnapshots.values.none { itemId in it }
    }.keys

    data class WatchState(
        val itemId: UUID,
        val played: Boolean,
        /** When the item was last watched to the end (epoch millis). */
        val playedAt: Long?,
        val dirty: Boolean,
        /** When the download was requested. */
        val downloadedAt: Long,
    )

    /**
     * Downloaded videos whose delete period after being watched has passed. Only watching after the download (and
     * after the feature was enabled, [countFrom]) counts: downloading something watched long ago to watch it again
     * must not delete it. Videos with watch state changes not yet sent to the server are kept until they are.
     */
    fun expiredWatched(
        states: List<WatchState>,
        now: Long,
        deleteAfter: Duration?,
        countFrom: Long,
        itemInUse: UUID?,
    ): Set<UUID> {
        if (deleteAfter == null) return emptySet()
        return states.filter { state ->
            state.played &&
                !state.dirty &&
                state.playedAt != null &&
                state.itemId != itemInUse &&
                state.playedAt >= maxOf(state.downloadedAt, countFrom) &&
                now - state.playedAt >= deleteAfter.inWholeMilliseconds
        }.map { it.itemId }.toSet()
    }
}
