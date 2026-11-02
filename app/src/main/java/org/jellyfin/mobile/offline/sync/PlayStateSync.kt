package org.jellyfin.mobile.offline.sync

import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.PlayStateEntity
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Fork: offline. Sends locally recorded watch states to the server and refreshes the local copy of the server's
 * watch states (videos watched on other devices).
 *
 * Conflict rule: if the server saw playback more recently than the local change, the server wins.
 */
class PlayStateSync(
    private val offlineDao: OfflineDao,
    private val apiClients: OfflineApiClients,
) {
    /**
     * @return true if every pending state was sent.
     */
    suspend fun flush(): Boolean {
        var allSynced = true
        for (state in offlineDao.getDirtyPlayStates()) {
            try {
                push(state)
            } catch (e: Exception) {
                Timber.i("Could not sync play state of %s: %s", state.itemId, e.message)
                allSynced = false
            }
        }
        return allSynced
    }

    private suspend fun push(state: PlayStateEntity) {
        val target = apiClients.get(state.serverId, state.userId) ?: run {
            // The user is gone (logged out / server removed), nothing to sync to anymore
            offlineDao.markPlayStateClean(state.serverId, state.userId, state.itemId, state.updatedAt)
            return
        }
        val serverData = target.api.itemsApi.getItemUserData(state.itemId, target.userId).content
        val serverLastPlayed = serverData.lastPlayedDate?.toEpochMillis()

        if (serverLastPlayed != null && serverLastPlayed > state.updatedAt) {
            Timber.i("Server has newer watch state for %s, dropping local change", state.itemId)
            offlineDao.upsertPlayState(state.fromServer(serverData))
            return
        }

        target.api.itemsApi.updateItemUserData(
            itemId = state.itemId,
            userId = target.userId,
            data = UpdateUserItemDataDto(
                played = state.played,
                playbackPositionTicks = if (state.played) 0 else state.positionTicks,
                lastPlayedDate = state.lastPlayedAt?.toLocalDateTime(),
            ),
        )
        offlineDao.markPlayStateClean(state.serverId, state.userId, state.itemId, state.updatedAt)
    }

    /**
     * Update the local watch states of [itemIds] from the server. Rows with unsent local changes are kept.
     */
    suspend fun refresh(serverId: Long, userId: Long, itemIds: Collection<UUID>) {
        if (itemIds.isEmpty()) return
        val target = apiClients.get(serverId, userId) ?: return
        for (chunk in itemIds.distinct().chunked(BATCH_SIZE)) {
            val items = target.api.itemsApi.getItems(
                userId = target.userId,
                ids = chunk,
                enableUserData = true,
                enableImages = false,
            ).content.items
            applyServerItems(serverId, userId, items)
        }
    }

    /**
     * Store the watch states contained in items fetched from the server. Rows with unsent local changes are kept.
     */
    suspend fun applyServerItems(serverId: Long, userId: Long, items: List<BaseItemDto>) {
        for (item in items) {
            val userData = item.userData ?: continue
            val existing = offlineDao.getPlayState(serverId, userId, item.id)
            if (existing?.dirty == true) continue
            val updated = (existing ?: emptyState(serverId, userId, item.id)).fromServer(userData)
            if (updated != existing) offlineDao.upsertPlayState(updated)
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
        val lastPlayed = userData.lastPlayedDate?.toEpochMillis()
        return copy(
            positionTicks = userData.playbackPositionTicks,
            played = userData.played,
            lastPlayedAt = lastPlayed ?: lastPlayedAt,
            // Keep the first time the item was seen as played; fall back to the server's last played date
            playedAt = when {
                !userData.played -> null
                played && playedAt != null -> playedAt
                else -> lastPlayed ?: System.currentTimeMillis()
            },
            dirty = false,
            updatedAt = maxOf(updatedAt, lastPlayed ?: 0),
        )
    }

    private companion object {
        const val BATCH_SIZE = 50
    }
}

fun LocalDateTime.toEpochMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
