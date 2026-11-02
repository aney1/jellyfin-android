package org.jellyfin.mobile.offline.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.PlayStateEntity
import org.jellyfin.mobile.offline.sync.OfflineSyncScheduler
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: offline. Reports playback of downloaded videos.
 *
 * Upstream doesn't report local playback to the server at all. Here every event is first written to the local
 * [PlayStateEntity] (marked dirty), then sent to the server like a streamed video would be. When the server
 * accepted it the row is marked clean again, otherwise [OfflineSyncScheduler] sends it once a connection is
 * available.
 */
class OfflinePlayback(
    private val downloadDao: DownloadDao,
    private val offlineDao: OfflineDao,
    private val apiClients: OfflineApiClients,
    private val syncScheduler: OfflineSyncScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Local state changes are applied strictly in order. */
    private val localEvents = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Server reports are sent in order too, but separately, so a slow server doesn't delay local writes. */
    private val serverMutex = Mutex()

    /** After a failed server request, skip further requests for a while and only record locally. */
    @Volatile
    private var serverBackoffUntil = 0L

    private val owners = ConcurrentHashMap<UUID, Pair<Long, Long>>()

    /** The downloaded item currently playing, it must not be deleted by the cleanup. */
    @Volatile
    var currentItemId: UUID? = null
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

    fun onStart(source: LocalJellyfinMediaSource, positionTicks: Long, isPaused: Boolean) {
        currentItemId = source.itemId
        val now = System.currentTimeMillis()
        record(source.itemId) { owner, old ->
            val state = updateState(owner, source.itemId, old, positionTicks, old?.played ?: false, now)
            report(owner, state) { api ->
                api.playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = source.itemId,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        playSessionId = source.playSessionId,
                        positionTicks = positionTicks,
                        isPaused = isPaused,
                        isMuted = false,
                        canSeek = true,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        }
    }

    fun onProgress(source: LocalJellyfinMediaSource, positionTicks: Long, isPaused: Boolean) {
        val now = System.currentTimeMillis()
        record(source.itemId) { owner, old ->
            val state = updateState(owner, source.itemId, old, positionTicks, old?.played ?: false, now)
            report(owner, state) { api ->
                api.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = source.itemId,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        playSessionId = source.playSessionId,
                        positionTicks = positionTicks,
                        isPaused = isPaused,
                        isMuted = false,
                        canSeek = true,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        }
    }

    fun onStop(source: LocalJellyfinMediaSource, positionTicks: Long, finished: Boolean) {
        if (currentItemId == source.itemId) currentItemId = null
        val now = System.currentTimeMillis()
        val runTimeTicks = source.runTime.inWholeTicks
        record(source.itemId) { owner, old ->
            val result = PlayStateRules.onStop(positionTicks, runTimeTicks, finished, old?.played ?: false)
            val state = updateState(owner, source.itemId, old, result.positionTicks, result.played, now)
            report(owner, state, scheduleOnFailure = true) { api ->
                api.playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = source.itemId,
                        positionTicks = if (finished) runTimeTicks else positionTicks,
                        playSessionId = source.playSessionId,
                        failed = false,
                    ),
                )
                // Same as upstream does for streamed videos
                if (finished) api.playStateApi.markPlayedItem(itemId = source.itemId)
            }
        }
    }

    /**
     * Mark a downloaded item as played, e.g. when the user skipped it.
     */
    fun markPlayed(itemId: UUID) {
        val now = System.currentTimeMillis()
        record(itemId) { owner, old ->
            val state = updateState(owner, itemId, old, 0, true, now)
            report(owner, state, scheduleOnFailure = true) { api ->
                api.playStateApi.markPlayedItem(itemId = itemId)
            }
        }
    }

    private fun record(itemId: UUID, block: suspend (owner: Pair<Long, Long>, old: PlayStateEntity?) -> Unit) {
        localEvents.trySend {
            val owner = owner(itemId) ?: return@trySend
            block(owner, offlineDao.getPlayState(owner.first, itemId))
        }
    }

    private suspend fun owner(itemId: UUID): Pair<Long, Long>? = owners[itemId] ?: withContext(Dispatchers.IO) {
        downloadDao.getDownloadByItemId(itemId)?.let { download -> download.serverId to download.userId }
    }?.also { owners[itemId] = it }

    @Suppress("LongParameterList")
    private suspend fun updateState(
        owner: Pair<Long, Long>,
        itemId: UUID,
        old: PlayStateEntity?,
        positionTicks: Long,
        played: Boolean,
        now: Long,
    ): PlayStateEntity {
        val state = PlayStateEntity(
            serverId = owner.first,
            userId = owner.second,
            itemId = itemId,
            positionTicks = positionTicks,
            played = played,
            lastPlayedAt = now,
            playedAt = if (played) old?.playedAt?.takeIf { old.played } ?: now else null,
            dirty = true,
            updatedAt = now,
        )
        offlineDao.upsertPlayState(state)
        return state
    }

    private fun report(
        owner: Pair<Long, Long>,
        state: PlayStateEntity,
        scheduleOnFailure: Boolean = false,
        request: suspend (org.jellyfin.sdk.api.client.ApiClient) -> Unit,
    ) {
        if (System.currentTimeMillis() < serverBackoffUntil) {
            if (scheduleOnFailure) syncScheduler.syncSoon()
            return
        }
        scope.launch {
            serverMutex.withLock {
                try {
                    val target = apiClients.get(owner.first, owner.second) ?: error("Unknown server user $owner")
                    request(target.api)
                    offlineDao.markPlayStateClean(state.serverId, state.itemId, state.updatedAt)
                } catch (e: Exception) {
                    Timber.i("Server unreachable for local playback report, keeping it for later: %s", e.message)
                    serverBackoffUntil = System.currentTimeMillis() + SERVER_BACKOFF_MS
                    syncScheduler.syncSoon()
                }
            }
        }
    }

    private companion object {
        const val SERVER_BACKOFF_MS = 60_000L
    }
}
