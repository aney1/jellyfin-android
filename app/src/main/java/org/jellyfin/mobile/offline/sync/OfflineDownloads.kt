package org.jellyfin.mobile.offline.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.downloads.DownloadManager
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.playlist.PlaylistSync
import timber.log.Timber
import java.util.UUID

/**
 * Fork: offline. Starts and removes downloads only through upstream's [DownloadManager] (and the activity's
 * download request for user actions), so upstream changes to how downloads are stored apply here too.
 */
class OfflineDownloads(
    private val downloadDao: DownloadDao,
    private val downloadManager: DownloadManager,
    private val offlineDao: OfflineDao,
    private val downloadExtras: DownloadExtras,
    private val serverDao: ServerDao,
    private val userDao: UserDao,
    private val appPreferences: AppPreferences,
    private val activityEventHandler: ActivityEventHandler,
) {
    /**
     * For actions of the user: goes through the activity, which asks for a download folder and the notification
     * permission first if needed.
     */
    val userEnqueuer = PlaylistSync.Enqueuer { _, _, itemIds ->
        activityEventHandler.emit(ActivityEvent.DownloadItems(itemIds))
    }

    /**
     * For background work: enqueues directly, skipped when no download folder was chosen yet.
     */
    val backgroundEnqueuer = PlaylistSync.Enqueuer { serverId, userId, itemIds ->
        if (appPreferences.storageLocation == null) {
            Timber.i("No download folder set, skipping %d playlist downloads", itemIds.size)
            return@Enqueuer
        }
        // Upstream's DownloadManager fetches the items with the app's current api client
        if (serverId != appPreferences.currentServerId || userId != appPreferences.currentUserId) {
            Timber.i("Playlist belongs to another server or user, skipping %d downloads", itemIds.size)
            return@Enqueuer
        }
        val (server, user) = withContext(Dispatchers.IO) { serverDao.getServer(serverId) to userDao.getUser(userId) }
        if (server == null || user == null) return@Enqueuer
        downloadManager.enqueueItems(server, user, itemIds)
    }

    /**
     * Delete the download of [itemId] including its files and the fork's extra data.
     */
    suspend fun delete(serverId: Long, itemId: UUID) {
        val download = withContext(Dispatchers.IO) { downloadDao.getDownloadByItemId(itemId) }
        if (download != null && download.serverId == serverId) {
            downloadManager.delete(download.id, deleteFiles = true)
        }
        offlineDao.deleteItemData(serverId, itemId)
        downloadExtras.deleteFiles(serverId, itemId)
    }
}
