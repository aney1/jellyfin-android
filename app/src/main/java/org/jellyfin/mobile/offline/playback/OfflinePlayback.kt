package org.jellyfin.mobile.offline.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.PlayStateEntity
import org.jellyfin.mobile.offline.sync.OfflineSyncScheduler
import org.jellyfin.mobile.offline.sync.PlayStateSync
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.sdk.model.extensions.inWholeTicks
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: offline. Records playback of downloaded videos.
 *
 * Upstream doesn't report local playback to the server at all. Here every event updates the local
 * [PlayStateEntity] (marked dirty) the same way the server would update its watch state, and [PlayStateSync]
 * sends it: right away for stops and skips, at most every minute for progress, and through
 * [OfflineSyncScheduler] once a connection is available again.
 *
 * No playback sessions are reported, the server only gets the resulting watch state.
 */
class OfflinePlayback(
    private val downloadDao: DownloadDao,
    private val offlineDao: OfflineDao,
    private val playStateSync: PlayStateSync,
    private val syncScheduler: OfflineSyncScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Local state changes are applied strictly in order. */
    private val localEvents = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val lastPushAttempt = ConcurrentHashMap<UUID, Long>()

    @Volatile
    private var lastScheduledSync = 0L

    /** The downloaded item that is playing or about to play, it must not be deleted by the cleanup. */
    @Volatile
    var itemInUse: UUID? = null
        private set

    init {
        scope.launch {
            for (event in localEvents) {
                try {
                    event()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to record local playback state")
                }
            }
        }
    }

    /**
     * The player switches to [itemId] (a downloaded item), or to a streamed item if null.
     */
    fun setItemInUse(itemId: UUID?) {
        itemInUse = itemId
    }

    fun onStart(source: LocalJellyfinMediaSource, positionTicks: Long) {
        itemInUse = source.itemId
        record(source) { owner, old ->
            // Like the server, starting a video makes it unplayed again until it's watched to the end
            val state = update(owner, source.itemId, old, positionTicks, played = false)
            if (old?.played == true) push(state, immediately = true)
        }
    }

    fun onProgress(source: LocalJellyfinMediaSource, positionTicks: Long) {
        record(source) { owner, old ->
            val state = update(owner, source.itemId, old, positionTicks, played = old?.played ?: false)
            push(state, immediately = false)
        }
    }

    fun onStop(source: LocalJellyfinMediaSource, positionTicks: Long, finished: Boolean) {
        if (itemInUse == source.itemId) itemInUse = null
        val runTimeTicks = source.runTime.inWholeTicks.takeIf { it > 0 } ?: source.item?.runTimeTicks ?: 0
        record(source) { owner, old ->
            val result = PlayStateRules.onStop(positionTicks, runTimeTicks, finished, old?.played ?: false)
            val state = update(owner, source.itemId, old, result.positionTicks, result.played)
            push(state, immediately = true)
        }
    }

    /**
     * Mark a downloaded item as played, e.g. when the user skipped it.
     */
    fun markPlayed(source: LocalJellyfinMediaSource) {
        record(source) { owner, old ->
            val state = update(owner, source.itemId, old, 0, played = true)
            push(state, immediately = true)
        }
    }

    private fun record(source: LocalJellyfinMediaSource, block: suspend (owner: Pair<Long, Long>, old: PlayStateEntity?) -> Unit) {
        localEvents.trySend {
            val owner = owner(source) ?: return@trySend
            block(owner, offlineDao.getPlayState(owner.first, owner.second, source.itemId))
        }
    }

    /**
     * Server and user of the download that is playing: its id is the play session id of the media source.
     */
    private suspend fun owner(source: LocalJellyfinMediaSource): Pair<Long, Long>? {
        val download = source.playSessionId.toLongOrNull()?.let { downloadDao.getDownload(it) }
        return download?.let { it.serverId to it.userId }
    }

    private suspend fun update(
        owner: Pair<Long, Long>,
        itemId: UUID,
        old: PlayStateEntity?,
        positionTicks: Long,
        played: Boolean,
    ): PlayStateEntity {
        val now = System.currentTimeMillis()
        val state = PlayStateEntity(
            serverId = owner.first,
            userId = owner.second,
            itemId = itemId,
            positionTicks = positionTicks,
            played = played,
            lastPlayedAt = now,
            playedAt = if (played) old?.playedAt?.takeIf { old.played } ?: now else null,
            dirty = true,
            // Strictly increasing per row, a sync only clears the dirty flag of the exact version it sent
            updatedAt = maxOf(now, (old?.updatedAt ?: 0) + 1),
            serverPlayed = old?.serverPlayed,
            serverLastPlayedAt = old?.serverLastPlayedAt,
        )
        offlineDao.upsertPlayState(state)
        return state
    }

    private fun push(state: PlayStateEntity, immediately: Boolean) {
        // Watched to the end: let the auto delete look at it soon (it may be set to delete right away)
        if (state.played) syncScheduler.syncSoon()
        val now = System.currentTimeMillis()
        if (!immediately && now - (lastPushAttempt[state.itemId] ?: 0) < PROGRESS_PUSH_INTERVAL_MS) return
        lastPushAttempt[state.itemId] = now
        scope.launch {
            if (!playStateSync.push(state.serverId, state.userId, state.itemId)) scheduleSync()
        }
    }

    private fun scheduleSync() {
        val now = System.currentTimeMillis()
        if (now - lastScheduledSync < SCHEDULE_INTERVAL_MS) return
        lastScheduledSync = now
        syncScheduler.syncSoon()
    }

    private companion object {
        const val PROGRESS_PUSH_INTERVAL_MS = 60_000L
        const val SCHEDULE_INTERVAL_MS = 5 * 60_000L
    }
}
