package org.jellyfin.mobile.offline.playlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.data.DownloadOriginEntity
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.PlaylistDownloadMode
import org.jellyfin.mobile.offline.data.PlaylistEntity
import org.jellyfin.mobile.offline.data.PlaylistEntryEntity
import org.jellyfin.mobile.offline.data.PlaylistExclusionEntity
import org.jellyfin.mobile.offline.playback.OfflinePlayback
import org.jellyfin.mobile.offline.sync.OfflineDownloads
import org.jellyfin.mobile.offline.sync.PlayStateSync
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import timber.log.Timber
import java.util.UUID

/**
 * Fork: offline. Keeps the downloads of playlists in line with the playlist on the server:
 * downloads wanted videos (see [OfflineRules.wantedItems]) and removes downloads that left the playlist unwatched.
 */
@Suppress("TooManyFunctions")
class PlaylistSync(
    private val offlineDao: OfflineDao,
    private val downloadDao: DownloadDao,
    private val apiClients: OfflineApiClients,
    private val playStateSync: PlayStateSync,
    private val offlinePlayback: OfflinePlayback,
    private val offlineDownloads: OfflineDownloads,
) {
    /** One sync at a time, a page visit and the background worker may overlap. */
    private val mutex = Mutex()

    /**
     * How downloads get started: through the activity for actions of the user (asks for the download folder
     * and the notification permission when needed), or directly from the background.
     */
    fun interface Enqueuer {
        suspend fun enqueue(serverId: Long, userId: Long, itemIds: List<UUID>)
    }

    suspend fun getOrCreatePlaylist(serverId: Long, userId: Long, playlistId: UUID): PlaylistEntity? {
        offlineDao.getPlaylist(serverId, playlistId)?.let { return it }
        val target = apiClients.get(serverId, userId) ?: return null
        val name = runCatching {
            target.api.userLibraryApi.getItem(itemId = playlistId, userId = target.userId).content.name
        }.getOrNull()
        val playlist = PlaylistEntity(serverId = serverId, userId = userId, playlistId = playlistId, name = name.orEmpty())
        offlineDao.upsertPlaylist(playlist)
        return playlist
    }

    suspend fun updateSettings(playlist: PlaylistEntity) = offlineDao.upsertPlaylist(playlist)

    /**
     * Refresh the playlist from the server and, depending on [download], start downloading wanted videos.
     *
     * @return the number of downloads that were started.
     */
    suspend fun sync(playlist: PlaylistEntity, download: Boolean, enqueuer: Enqueuer): Int = mutex.withLock {
        val entries = refreshSnapshot(playlist) ?: return@withLock 0
        val started = if (download) downloadWanted(playlist, entries, enqueuer) else 0
        removeLeftUnwatched()
        started
    }

    /**
     * Download a single video of a playlist, independent of the playlist's mode.
     */
    suspend fun downloadItem(playlist: PlaylistEntity, itemId: UUID, enqueuer: Enqueuer) = mutex.withLock {
        offlineDao.deleteExclusion(playlist.serverId, playlist.playlistId, itemId)
        offlineDao.insertDownloadOrigins(listOf(DownloadOriginEntity(playlist.serverId, itemId, playlist.playlistId)))
        enqueuer.enqueue(playlist.serverId, playlist.userId, listOf(itemId))
    }

    /**
     * Mark a video as "don't download" (deleting its download) or allow it again.
     */
    suspend fun setExcluded(playlist: PlaylistEntity, itemId: UUID, excluded: Boolean) = mutex.withLock {
        if (excluded) {
            offlineDao.insertExclusion(PlaylistExclusionEntity(playlist.serverId, playlist.playlistId, itemId))
            offlineDownloads.delete(playlist.serverId, itemId)
        } else {
            offlineDao.deleteExclusion(playlist.serverId, playlist.playlistId, itemId)
        }
    }

    suspend fun deleteDownload(serverId: Long, itemId: UUID) = mutex.withLock {
        offlineDownloads.delete(serverId, itemId)
    }

    /**
     * Fetch the playlist's entries and store them for offline use.
     *
     * @return the entries, or null if the server couldn't be reached.
     */
    suspend fun refreshSnapshot(playlist: PlaylistEntity): List<PlaylistEntryEntity>? {
        val target = apiClients.get(playlist.serverId, playlist.userId) ?: return null
        return try {
            val items = target.api.playlistsApi.getPlaylistItems(
                playlistId = playlist.playlistId,
                userId = target.userId,
                fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.MEDIA_SOURCE_COUNT),
                enableImages = true,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP),
            ).content.items
            val entries = items.mapIndexed { index, item ->
                PlaylistEntryEntity(playlist.serverId, playlist.playlistId, index, item.id, item)
            }
            offlineDao.replacePlaylistEntries(playlist.serverId, playlist.playlistId, entries)
            playStateSync.applyServerItems(playlist.serverId, playlist.userId, items)
            val name = runCatching {
                target.api.userLibraryApi.getItem(itemId = playlist.playlistId, userId = target.userId).content.name
            }.getOrNull()
            offlineDao.upsertPlaylist(
                playlist.copy(name = name ?: playlist.name, lastSyncedAt = System.currentTimeMillis()),
            )
            entries
        } catch (e: Exception) {
            Timber.w(e, "Could not refresh playlist %s", playlist.playlistId)
            null
        }
    }

    private suspend fun downloadWanted(playlist: PlaylistEntity, entries: List<PlaylistEntryEntity>, enqueuer: Enqueuer): Int {
        val current = offlineDao.getPlaylist(playlist.serverId, playlist.playlistId) ?: playlist
        val excluded = offlineDao.getExclusions(playlist.serverId, playlist.playlistId).map { it.itemId }.toSet()
        val ruleEntries = entries.map { entry ->
            val state = offlineDao.getPlayState(playlist.serverId, playlist.userId, entry.itemId)
            OfflineRules.Entry(
                itemId = entry.itemId,
                isVideo = entry.item.mediaType == MediaType.VIDEO,
                runTimeTicks = entry.item.runTimeTicks,
                played = state?.played ?: entry.item.userData?.played ?: false,
            )
        }
        val wanted = OfflineRules.wantedItems(ruleEntries, excluded, current.maxItems, current.maxRuntimeMinutes)
        if (wanted.isEmpty()) return 0

        val existing = withContext(Dispatchers.IO) {
            downloadDao.getDownloadsByItemIds(wanted).filter { it.serverId == playlist.serverId }.associateBy { it.itemId }
        }
        val missing = wanted.filter { itemId ->
            val download = existing[itemId]
            download == null || download.status == DownloadStatus.ERROR || download.status == DownloadStatus.CANCELLED
        }
        if (missing.isEmpty()) return 0

        offlineDao.insertDownloadOrigins(missing.map { DownloadOriginEntity(playlist.serverId, it, playlist.playlistId) })
        enqueuer.enqueue(playlist.serverId, playlist.userId, missing)
        Timber.i("Started %d downloads for playlist %s", missing.size, playlist.playlistId)
        return missing.size
    }

    /**
     * Delete downloads that were made for playlists and left them while still unwatched. Watched ones are kept
     * until their delete period after watching has passed (smart playlists drop watched videos).
     */
    private suspend fun removeLeftUnwatched() {
        val origins = offlineDao.getDownloadOrigins()
        if (origins.isEmpty()) return
        val snapshots = offlineDao.getAllPlaylistEntries()
            .groupBy { it.playlistId }
            .mapValues { (_, entries) -> entries.map { it.itemId }.toSet() }
        // Playlists without any stored entries are unknown, not empty: a failed refresh must not delete anything
        val knownPlaylists = offlineDao.getPlaylists().filter { it.lastSyncedAt != null }.map { it.playlistId }.toSet()
        val knownSnapshots = knownPlaylists.associateWith { snapshots[it].orEmpty() }

        for ((serverId, serverOrigins) in origins.groupBy { it.serverId }) {
            val byItem = serverOrigins.groupBy { it.itemId }.mapValues { (_, list) -> list.map { it.playlistId }.toSet() }
            val played = byItem.keys.associateWith { offlineDao.isPlayedByAnyUser(serverId, it) }
            val remove = OfflineRules.removedUnwatched(
                origins = byItem,
                snapshots = knownSnapshots,
                isPlayed = { played[it] ?: false },
                playingItemId = offlinePlayback.itemInUse,
            )
            for (itemId in remove) {
                Timber.i("Removing download %s, it left its playlist unwatched", itemId)
                offlineDownloads.delete(serverId, itemId)
            }
        }
    }

    /**
     * Playlists that are synced in the background: automatic ones, plus every playlist with downloads (their
     * snapshot must stay current for the offline screen and for removing downloads that left them).
     */
    suspend fun syncAllInBackground(enqueuer: Enqueuer) {
        val downloadedItems = withContext(Dispatchers.IO) {
            downloadDao.getDownloadsByItemIds(offlineDao.getAllPlaylistEntries().map { it.itemId }.distinct())
        }.map { it.itemId }.toSet()
        val entriesByPlaylist = offlineDao.getAllPlaylistEntries().groupBy { it.playlistId }
        for (playlist in offlineDao.getPlaylists()) {
            val hasDownloads = entriesByPlaylist[playlist.playlistId].orEmpty().any { it.itemId in downloadedItems }
            val auto = playlist.mode == PlaylistDownloadMode.AUTO
            if (!auto && !hasDownloads) continue
            try {
                sync(playlist, download = auto, enqueuer = enqueuer)
            } catch (e: Exception) {
                Timber.e(e, "Background sync of playlist %s failed", playlist.playlistId)
            }
        }
    }
}
