package org.jellyfin.mobile.offline.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.offline.OfflinePreferences
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.playback.OfflinePlayback
import org.jellyfin.mobile.offline.playlist.OfflineRules
import org.jellyfin.mobile.offline.playlist.PlaylistSync
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber

/**
 * Fork: offline. One maintenance pass, run by [OfflineSyncWorker]:
 * send pending watch states, fetch download extras, refresh watch states (videos watched elsewhere),
 * sync playlists, and delete watched downloads whose delete period has passed.
 */
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
) {
    private val mutex = Mutex()

    /**
     * @return true if everything that needs the server was done.
     */
    suspend fun run(includePlaylists: Boolean): Boolean = mutex.withLock {
        // A worker may run without the app being open, the global api client is configured by the activity
        if (apiClient.accessToken == null) {
            runCatching { apiClientController.loadSavedServerUser() }
        }

        var complete = playStateSync.flush()

        val downloads = withContext(Dispatchers.IO) { downloadDao.getAllDownloads().first() }
        complete = downloadExtras.fetchMissing(downloads) && complete

        for ((owner, ownerDownloads) in downloads.groupBy { it.serverId to it.userId }) {
            try {
                playStateSync.refresh(owner.first, owner.second, ownerDownloads.map { it.itemId })
            } catch (e: Exception) {
                Timber.i("Could not refresh watch states: %s", e.message)
                complete = false
            }
        }

        if (includePlaylists && preferences.backgroundSync) {
            playlistSync.syncAllInBackground(offlineDownloads.backgroundEnqueuer)
        }

        deleteExpired()
        removeOrphans()
        complete
    }

    private suspend fun deleteExpired() {
        val deleteAfter = preferences.deleteWatchedAfter ?: return
        val downloads = withContext(Dispatchers.IO) { downloadDao.getAllDownloads().first() }
        for ((serverId, serverDownloads) in downloads.groupBy { it.serverId }) {
            val states = serverDownloads.mapNotNull { download ->
                offlineDao.getPlayState(serverId, download.itemId)?.let { state ->
                    OfflineRules.WatchState(state.itemId, state.played, state.playedAt, state.dirty)
                }
            }
            val expired = OfflineRules.expiredWatched(
                states = states,
                now = System.currentTimeMillis(),
                deleteAfter = deleteAfter,
                playingItemId = offlinePlayback.currentItemId,
            )
            for (itemId in expired) {
                Timber.i("Deleting watched download %s", itemId)
                offlineDownloads.delete(serverId, itemId)
            }
        }
    }

    /**
     * Remove fork data of downloads that were deleted elsewhere (e.g. in upstream's downloads screen).
     */
    private suspend fun removeOrphans() {
        val downloaded = withContext(Dispatchers.IO) { downloadDao.getAllDownloads().first() }
            .map { it.serverId to it.itemId }
            .toSet()
        for (cache in offlineDao.getItemCaches()) {
            if ((cache.serverId to cache.itemId) !in downloaded) {
                offlineDao.deleteItemData(cache.serverId, cache.itemId)
                downloadExtras.deleteFiles(cache.serverId, cache.itemId)
            }
        }
        val keep = downloaded.map { it.second } + offlineDao.getAllPlaylistEntries().map { it.itemId }
        offlineDao.deleteCleanPlayStatesExcept(keep.toSet())
    }
}
