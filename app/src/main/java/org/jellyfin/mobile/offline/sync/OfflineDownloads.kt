package org.jellyfin.mobile.offline.sync

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.DownloadManager
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.playlist.PlaylistSync
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import java.util.UUID

/**
 * Fork: offline. Starts and removes downloads only through upstream's [DownloadManager] (and the activity's
 * download request for user actions), so upstream changes to how downloads are stored apply here too.
 */
@Suppress("LongParameterList")
class OfflineDownloads(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val downloadManager: DownloadManager,
    private val offlineDao: OfflineDao,
    private val downloadExtras: DownloadExtras,
    private val serverDao: ServerDao,
    private val userDao: UserDao,
    private val appPreferences: AppPreferences,
    private val storageManager: StorageManager,
    private val activityEventHandler: ActivityEventHandler,
    private val apiClient: ApiClient,
) {
    /** Deletions one at a time, each re-checking the download right before removing it. */
    private val mutex = Mutex()

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
        // Right after the app starts the client has the server but not the user yet (the web app signs in later)
        check(apiClient.baseUrl == server.hostname && apiClient.accessToken == user.accessToken) {
            "The app is not signed in yet"
        }
        downloadManager.enqueueItems(server, user, itemIds)
    }

    /**
     * Downloads of [itemIds] on [serverId] (any server if null), queried in chunks (SQLite limits the number of
     * query parameters).
     */
    suspend fun downloadsOf(
        serverId: Long?,
        itemIds: Collection<UUID>,
    ): Map<UUID, DownloadEntity> = withContext(Dispatchers.IO) {
        itemIds.distinct()
            .chunked(QUERY_CHUNK)
            .flatMap { chunk -> downloadDao.getDownloadsByItemIds(chunk) }
            .filter { serverId == null || it.serverId == serverId }
            .associateBy { it.itemId }
    }

    /**
     * Delete the download of [itemId] including its files and the fork's extra data.
     *
     * [stillWanted] is checked again on the current download right before deleting (it may have been re-queued or
     * started playing since the caller decided), returning false keeps the download.
     *
     * Deletes only the download's own files, and only those no other download uses: upstream puts downloads with
     * the same title into one folder and reuses files with the same name.
     *
     * @return true if the download is gone.
     */
    suspend fun delete(
        serverId: Long,
        itemId: UUID,
        stillWanted: (DownloadEntity) -> Boolean = { true },
    ): Boolean = mutex.withLock {
        val download = downloadsOf(serverId, listOf(itemId))[itemId]
        if (download != null) {
            if (!stillWanted(download)) return@withLock false
            val filesDeleted = withContext(Dispatchers.IO) { deleteFiles(download) }
            if (!filesDeleted) {
                Timber.w("Could not delete the files of download %s, keeping it", itemId)
                return@withLock false
            }
            downloadManager.delete(download.id, deleteFiles = false)
        }
        offlineDao.deleteItemData(serverId, itemId)
        downloadExtras.deleteFiles(serverId, itemId)
        true
    }

    /**
     * @return false if a file of the download that no other download uses couldn't be deleted.
     */
    private suspend fun deleteFiles(download: DownloadEntity): Boolean {
        // An unmounted card or a lost folder permission makes every file look missing, keep the download then
        if (!storageManager.isStorageLocationAccessible()) return false
        val usedElsewhere = downloadDao.getAllDownloadsWithFiles().first()
            .filter { it.download.id != download.id }
            .flatMap { other -> other.files.map { it.uri } }
            .toSet()
        var success = true
        for (file in downloadDao.getFiles(download.id)) {
            if (file.uri in usedElsewhere) continue
            try {
                val document = DocumentFile.fromSingleUri(context, file.uri)
                if (document != null && document.exists() && !document.delete()) success = false
            } catch (e: SecurityException) {
                Timber.w(e, "Could not delete %s", file.fileName)
                success = false
            }
        }
        // Remove the folder once nothing else is in it
        val folder = storageManager.getStorageLocation()?.findFile(download.path)
        if (folder != null && folder.isDirectory && folder.listFiles().isEmpty()) folder.delete()
        return success
    }

    private companion object {
        const val QUERY_CHUNK = 500
    }
}
