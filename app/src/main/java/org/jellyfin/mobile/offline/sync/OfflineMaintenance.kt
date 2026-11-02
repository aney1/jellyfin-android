package org.jellyfin.mobile.offline.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.OfflinePreferences
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.playback.OfflinePlayback
import org.jellyfin.mobile.offline.playlist.OfflineRules
import org.jellyfin.mobile.offline.playlist.PlaylistSync
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.MediaType
import timber.log.Timber

/**
 * Fork: offline. One maintenance pass, run by [OfflineSyncWorker]:
 * send pending watch states, fetch download extras, refresh watch states (videos watched elsewhere),
 * sync playlists, and delete watched downloads whose delete period has passed.
 */
@Suppress("LongParameterList")
class OfflineMaintenance(
    private val apiClient: ApiClient,
    private val apiClientController: ApiClientController,
    private val downloadDao: DownloadDao,
    private val offlineDao: OfflineDao,
    private val preferences: OfflinePreferences,
    private val playStateSync: PlayStateSync,
    private val downloadExtras: DownloadExtras,
    private val playlistSync: PlaylistSync,
    private val offlineDownloads: OfflineDownloads,
    private val offlinePlayback: OfflinePlayback,
    private val apiClients: OfflineApiClients,
    private val appPreferences: AppPreferences,
) {
    private val mutex = Mutex()

    /**
     * @return true if everything that needs the server was done.
     */
    suspend fun run(includePlaylists: Boolean): Boolean = mutex.withLock {
        // A worker may run without the app being open, the global api client is configured by the activity.
        // Only fill in a completely unconfigured client, never touch one the app is setting up (server switch).
        if (apiClient.baseUrl == null && apiClient.accessToken == null) {
            runCatching { apiClientController.loadSavedServerUser() }
        }

        var complete = playStateSync.flush()

        val downloads = loadDownloads()
        complete = downloadExtras.fetchMissing(downloads) && complete

        // Auto delete only decides on watch states that were just confirmed by the server
        val refreshedOwners = mutableSetOf<Pair<Long, Long>>()
        for ((owner, ownerDownloads) in downloads.groupBy { it.serverId to it.userId }) {
            try {
                playStateSync.refresh(owner.first, owner.second, ownerDownloads.map { it.itemId })
                refreshedOwners += owner
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.i("Could not refresh watch states: %s", e.message)
                complete = false
            }
        }

        cachePlayerSettings()

        if (includePlaylists && preferences.backgroundSync) {
            playlistSync.syncAllInBackground(offlineDownloads.backgroundEnqueuer)
        }

        deleteExpired(downloads, refreshedOwners)
        removeOrphans(loadDownloads())
        // Watch states recorded while this run was going: run again (the worker retries incomplete runs)
        complete && offlineDao.getDirtyPlayStates().isEmpty()
    }

    /**
     * Keep the user's player settings for playback while offline (the player loads them from the server).
     */
    private suspend fun cachePlayerSettings() = withContext(Dispatchers.IO) {
        try {
            val serverId = appPreferences.currentServerId ?: return@withContext
            val userId = appPreferences.currentUserId ?: return@withContext
            val api = apiClients.get(serverId, userId)?.api ?: return@withContext
            val user = api.userApi.getCurrentUser().content
            user.configuration?.let { preferences.cachedAutoPlay = it.enableNextEpisodeAutoPlay }
            val displayPreferences = api.displayPreferencesApi.getDisplayPreferences(
                displayPreferencesId = Constants.DISPLAY_PREFERENCES_ID_USER_SETTINGS,
                client = Constants.DISPLAY_PREFERENCES_CLIENT_EMBY,
            ).content
            preferences.cachedCustomPrefs = displayPreferences.customPrefs.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.i("Could not cache player settings: %s", e.message)
        }
    }

    private suspend fun loadDownloads(): List<DownloadEntity> = withContext(Dispatchers.IO) { downloadDao.getAllDownloads().first() }

    private suspend fun deleteExpired(downloads: List<DownloadEntity>, refreshedOwners: Set<Pair<Long, Long>>) {
        val deleteAfter = preferences.deleteWatchedAfter ?: return
        // Only finished video downloads: queued ones may be a re-download the user just asked for
        val candidates = downloads.filter { download ->
            download.status == DownloadStatus.DOWNLOADED &&
                download.item.mediaType == MediaType.VIDEO &&
                (download.serverId to download.userId) in refreshedOwners
        }
        val states = candidates.mapNotNull { download ->
            offlineDao.getPlayState(download.serverId, download.userId, download.itemId)?.let { state ->
                download to OfflineRules.WatchState(
                    itemId = state.itemId,
                    played = state.played,
                    playedAt = state.playedAt,
                    dirty = state.dirty,
                    downloadedAt = download.requestedAt,
                )
            }
        }
        val expired = OfflineRules.expiredWatched(
            states = states.map { it.second },
            now = System.currentTimeMillis(),
            deleteAfter = deleteAfter,
            countFrom = preferences.deleteCountFrom,
            itemInUse = offlinePlayback.itemInUse,
        )
        for ((download, _) in states.filter { it.second.itemId in expired }) {
            try {
                val deleted = offlineDownloads.delete(download.serverId, download.itemId) { current ->
                    // Still the same finished download, and not started in the meantime
                    current.status == DownloadStatus.DOWNLOADED &&
                        current.requestedAt == download.requestedAt &&
                        offlinePlayback.itemInUse != download.itemId
                }
                if (deleted) Timber.i("Deleted watched download %s", download.itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Could not delete watched download %s", download.itemId)
            }
        }
    }

    private val DownloadEntity.requestedAt: Long get() = maxOf(createdAt, modifiedAt)

    /**
     * Remove fork data of downloads that were deleted elsewhere (e.g. in upstream's downloads screen).
     */
    private suspend fun removeOrphans(downloads: List<DownloadEntity>) {
        val downloaded = downloads.map { it.serverId to it.itemId }.toSet()
        val itemsWithData = offlineDao.getItemCaches().map { it.serverId to it.itemId } +
            offlineDao.getAllSubtitleFiles().map { it.serverId to it.itemId }
        for ((serverId, itemId) in itemsWithData.toSet() - downloaded) {
            offlineDao.deleteItemData(serverId, itemId)
            downloadExtras.deleteFiles(serverId, itemId)
        }
        downloadExtras.deleteStrayFiles(downloaded)

        // Origins are written right before the download is requested, give that some time
        val now = System.currentTimeMillis()
        for (origin in offlineDao.getDownloadOrigins()) {
            if ((origin.serverId to origin.itemId) !in downloaded && now - origin.createdAt > ORIGIN_GRACE_MS) {
                offlineDao.deleteDownloadOrigins(origin.serverId, origin.itemId)
            }
        }

        val keep = (downloaded.map { it.second } + offlineDao.getAllPlaylistMemberships().map { it.itemId }).toSet()
        for (state in offlineDao.getCleanPlayStates()) {
            if (state.itemId !in keep) offlineDao.deletePlayState(state.serverId, state.userId, state.itemId)
        }
    }

    private companion object {
        const val ORIGIN_GRACE_MS = 60L * 60 * 1000
    }
}
