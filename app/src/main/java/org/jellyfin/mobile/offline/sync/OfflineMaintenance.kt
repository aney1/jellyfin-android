package org.jellyfin.mobile.offline.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber

/**
 * Fork: offline. One maintenance pass, run by [OfflineSyncWorker]:
 * send pending watch states, fetch download extras and refresh watch states (videos watched elsewhere).
 */
class OfflineMaintenance(
    private val apiClient: ApiClient,
    private val apiClientController: ApiClientController,
    private val downloadDao: DownloadDao,
    private val offlineDao: OfflineDao,
    private val playStateSync: PlayStateSync,
    private val downloadExtras: DownloadExtras,
) {
    private val mutex = Mutex()

    /**
     * @return true if everything that needs the server was done.
     */
    @Suppress("UnusedParameter")
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

        removeOrphans()
        complete
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
