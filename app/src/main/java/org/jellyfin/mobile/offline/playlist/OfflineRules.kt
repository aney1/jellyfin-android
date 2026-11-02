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

    /**
     * Downloads that were started for a playlist and left all such playlists while still unwatched.
     *
     * @param origins playlist ids each downloaded item was downloaded for.
     * @param snapshots current entries of every known playlist. Items still in any known playlist are kept, and
     * origins pointing to a playlist without a snapshot are treated as still present.
     */
    fun removedUnwatched(
        origins: Map<UUID, Set<UUID>>,
        snapshots: Map<UUID, Set<UUID>>,
        isPlayed: (UUID) -> Boolean,
        playingItemId: UUID?,
    ): Set<UUID> = origins.filter { (itemId, playlistIds) ->
        itemId != playingItemId &&
            !isPlayed(itemId) &&
            playlistIds.all { playlistId -> snapshots[playlistId]?.contains(itemId) == false } &&
            snapshots.values.none { itemId in it }
    }.keys

    data class WatchState(
        val itemId: UUID,
        val played: Boolean,
        val playedAt: Long?,
        val dirty: Boolean,
        /** When the download was requested, a re-download of something watched long ago gets the full period. */
        val downloadedAt: Long,
    )

    /**
     * Downloaded videos whose delete period after being watched (or after being downloaded, whichever is later)
     * has passed. Videos with watch state changes not yet sent to the server are kept until they are.
     */
    fun expiredWatched(
        states: List<WatchState>,
        now: Long,
        deleteAfter: Duration?,
        playingItemId: UUID?,
    ): Set<UUID> {
        if (deleteAfter == null) return emptySet()
        return states.filter { state ->
            state.played &&
                !state.dirty &&
                state.playedAt != null &&
                state.itemId != playingItemId &&
                now - maxOf(state.playedAt, state.downloadedAt) >= deleteAfter.inWholeMilliseconds
        }.map { it.itemId }.toSet()
    }
}
