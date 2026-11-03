// Fork: offline. Server, storage and database errors of any kind must not stop the offline sync.
// Loops skip items that need nothing or failed with `continue`.
@file:Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")

package org.jellyfin.mobile.offline.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.PlayStateEntity
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/**
 * Fork: offline. The only component that sends the watch state of downloaded videos to the server, so local
 * changes are sent strictly one at a time and in order. Also refreshes the local copy of the server's watch
 * states (videos watched on other devices).
 *
 * Conflicts are decided with the last state both sides agreed on (see [PlayStateEntity.serverPlayed]):
 * - nothing changed on the server since: the local change is sent,
 * - the item was played elsewhere at a known time: the newer of both wins,
 * - the server state changed without a playback time (marked played/unplayed in the web app): the server wins.
 */
class PlayStateSync(
    private val offlineDao: OfflineDao,
    private val apiClients: OfflineApiClients,
) {
    private val mutex = Mutex()

    /**
     * Send all unsent local changes.
     *
     * @return true if every pending state was sent.
     */
    suspend fun flush(): Boolean {
        var allSynced = true
        for (state in offlineDao.getDirtyPlayStates()) {
            allSynced = push(state.serverId, state.userId, state.itemId) && allSynced
        }
        return allSynced
    }

    /**
     * Send the current local state of one item, if it has unsent changes.
     *
     * @return true if there is nothing left to send.
     */
    suspend fun push(serverId: Long, userId: Long, itemId: UUID): Boolean = withContext(Dispatchers.IO) {
        pushSerialized(serverId, userId, itemId)
    }

    private suspend fun pushSerialized(serverId: Long, userId: Long, itemId: UUID): Boolean = mutex.withLock {
        // Read inside the lock: always send the newest local state
        val state = offlineDao.getPlayState(serverId, userId, itemId) ?: return@withLock true
        if (!state.dirty) return@withLock true
        try {
            pushLocked(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidStatusException) {
            if (e.status == HTTP_NOT_FOUND && isItemGone(state)) {
                // The item is gone on the server, there is nothing to sync it to anymore
                offlineDao.deletePlayState(serverId, userId, itemId)
                true
            } else {
                Timber.i("Could not sync play state of %s: HTTP %d", itemId, e.status)
                false
            }
        } catch (e: Exception) {
            Timber.i("Could not sync play state of %s: %s", itemId, e.message)
            false
        }
    }

    /**
     * A 404 can also come from a proxy or a server that is restarting: only drop local changes when the item itself
     * is confirmed missing.
     */
    private suspend fun isItemGone(state: PlayStateEntity): Boolean {
        val target = apiClients.get(state.serverId, state.userId) ?: return false
        return try {
            target.api.userLibraryApi.getItem(itemId = state.itemId, userId = target.userId)
            false
        } catch (e: InvalidStatusException) {
            e.status == HTTP_NOT_FOUND
        }
    }

    private suspend fun pushLocked(state: PlayStateEntity): Boolean {
        val target = apiClients.get(state.serverId, state.userId)
        if (target == null) {
            // Server or user removed from the app: nothing to send to anymore. Logged out: send after the next login.
            if (!apiClients.exists(state.serverId, state.userId)) {
                offlineDao.deletePlayState(state.serverId, state.userId, state.itemId)
            }
            return true
        }
        val serverData = target.api.itemsApi.getItemUserData(state.itemId, target.userId).content

        if (serverWins(state, serverData)) {
            Timber.i("Watch state of %s changed on the server, dropping the local change", state.itemId)
            offlineDao.replaceWithServerState(
                entity = state.fromServer(serverData),
                expectedUpdatedAt = state.updatedAt,
                allowDirty = true,
            )
            return true
        }

        target.api.itemsApi.updateItemUserData(
            itemId = state.itemId,
            userId = target.userId,
            data = UpdateUserItemDataDto(
                played = state.played,
                playbackPositionTicks = if (state.played) 0 else state.positionTicks,
                lastPlayedDate = state.lastPlayedAt?.toLocalDateTime(),
                playCount = if (state.played && !serverData.played) serverData.playCount + 1 else null,
            ),
        )
        offlineDao.markPlayStateSynced(
            serverId = state.serverId,
            userId = state.userId,
            itemId = state.itemId,
            updatedAt = state.updatedAt,
            serverPlayed = state.played,
            serverLastPlayedAt = state.lastPlayedAt ?: serverData.lastPlayedDate?.toEpochMillisOrNull(),
        )
        return true
    }

    private fun serverWins(state: PlayStateEntity, serverData: UserItemDataDto): Boolean {
        val serverLastPlayed = serverData.lastPlayedDate?.toEpochMillisOrNull()
        val hasBaseline = state.serverPlayed != null
        // Played elsewhere at a known time: the newer one wins
        if (serverLastPlayed != null && (!hasBaseline || !sameTime(serverLastPlayed, state.serverLastPlayedAt))) {
            return serverLastPlayed > state.updatedAt
        }
        // Marked played/unplayed on the server since the last sync: that explicit change wins
        return hasBaseline && serverData.played != state.serverPlayed
    }

    /**
     * Update the local watch states of [itemIds] from the server. Rows with unsent local changes are kept.
     */
    suspend fun refresh(serverId: Long, userId: Long, itemIds: Collection<UUID>) = withContext(Dispatchers.IO) {
        if (itemIds.isEmpty()) return@withContext
        val target = apiClients.get(serverId, userId) ?: error("No api client for server user ($serverId, $userId)")
        for (chunk in itemIds.distinct().chunked(BATCH_SIZE)) {
            val versionsBefore = versionsOf(serverId, userId)
            val items = target.api.itemsApi.getItems(
                userId = target.userId,
                ids = chunk,
                enableUserData = true,
                enableImages = false,
            ).content.items
            applyServerItems(serverId, userId, items, versionsBefore)
        }
    }

    /**
     * Versions of the local rows of a server user, to be read before a request whose answer is passed to
     * [applyServerItems].
     */
    suspend fun versionsOf(serverId: Long, userId: Long): Map<UUID, Long> =
        offlineDao.getPlayStatesOf(serverId, userId).associate { it.itemId to it.updatedAt }

    /**
     * Store the watch states contained in items fetched from the server. Rows with unsent local changes are kept,
     * and so are rows that changed since [versionsBefore] were read (the answer may be older than them).
     */
    suspend fun applyServerItems(
        serverId: Long,
        userId: Long,
        items: List<BaseItemDto>,
        versionsBefore: Map<UUID, Long>,
    ) {
        for (item in items) {
            val userData = item.userData ?: continue
            val existing = offlineDao.getPlayState(serverId, userId, item.id)
            if (existing?.dirty == true || existing?.updatedAt != versionsBefore[item.id]) continue
            val updated = (existing ?: emptyState(serverId, userId, item.id)).fromServer(userData)
            offlineDao.replaceWithServerState(updated, expectedUpdatedAt = versionsBefore[item.id], allowDirty = false)
        }
    }

    private fun emptyState(serverId: Long, userId: Long, itemId: UUID) = PlayStateEntity(
        serverId = serverId,
        userId = userId,
        itemId = itemId,
        positionTicks = 0,
        played = false,
        lastPlayedAt = null,
        playedAt = null,
        dirty = false,
        updatedAt = 0,
    )

    private fun PlayStateEntity.fromServer(userData: UserItemDataDto): PlayStateEntity {
        val lastPlayed = userData.lastPlayedDate?.toEpochMillisOrNull()
        return copy(
            positionTicks = userData.playbackPositionTicks,
            played = userData.played,
            lastPlayedAt = lastPlayed ?: lastPlayedAt,
            // When it was last watched: the auto delete counts from there
            playedAt = when {
                !userData.played -> null
                lastPlayed != null && (playedAt == null || lastPlayed > playedAt) -> lastPlayed
                playedAt != null -> playedAt
                else -> System.currentTimeMillis()
            },
            dirty = false,
            updatedAt = maxOf(updatedAt + 1, lastPlayed ?: 0),
            serverPlayed = userData.played,
            serverLastPlayedAt = lastPlayed,
        )
    }

    private fun sameTime(a: Long?, b: Long?): Boolean = when {
        a == null || b == null -> a == b
        else -> abs(a - b) <= TIME_TOLERANCE_MS
    }

    private companion object {
        const val BATCH_SIZE = 50
        const val HTTP_NOT_FOUND = 404
        const val TIME_TOLERANCE_MS = 2_000L
    }
}

/**
 * Null for dates the SDK uses for "unknown" (it decodes unparseable dates as [LocalDateTime.MIN]).
 */
fun LocalDateTime.toEpochMillisOrNull(): Long? = if (year < MIN_SANE_YEAR) {
    null
} else {
    atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private const val MIN_SANE_YEAR = 1971

fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
