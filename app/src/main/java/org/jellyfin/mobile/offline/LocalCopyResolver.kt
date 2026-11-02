package org.jellyfin.mobile.offline

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import java.util.UUID

/**
 * Fork: offline. Finds downloads that can actually be played: upstream's `getDownloadByItemId` returns any one
 * row for the item id, also queued, failed and cancelled downloads or one of another server, and the file may
 * have been removed from the storage location.
 */
class LocalCopyResolver(
    private val context: Context,
    private val downloadDao: DownloadDao,
) {
    data class LocalCopy(
        val download: DownloadEntity,
        val fileUri: Uri,
        val primaryImageUri: Uri?,
    )

    /**
     * @param serverId only accept a download of this server, null for any server.
     * @param userId only accept a download of this user, null for any user.
     */
    suspend fun find(itemId: UUID, serverId: Long? = null, userId: Long? = null): LocalCopy? = withContext(Dispatchers.IO) {
        val candidates = downloadDao.getDownloadsByItemIds(listOf(itemId)).filter { download ->
            download.status == DownloadStatus.DOWNLOADED &&
                (serverId == null || download.serverId == serverId) &&
                (userId == null || download.userId == userId)
        }
        for (download in candidates) {
            toLocalCopy(DownloadFiles(download, downloadDao.getFiles(download.id)))?.let { return@withContext it }
        }
        null
    }

    /**
     * All playable downloads by item id.
     */
    suspend fun findAll(): Map<UUID, LocalCopy> = withContext(Dispatchers.IO) {
        downloadDao.getAllDownloadsWithFiles().first()
            .filter { it.download.status == DownloadStatus.DOWNLOADED }
            .mapNotNull(::toLocalCopy)
            .associateBy { it.download.itemId }
    }

    private fun toLocalCopy(downloadFiles: DownloadFiles): LocalCopy? {
        val mainFile = downloadFiles.files.find { it.type == DownloadFileType.ITEM && it.status == DownloadStatus.DOWNLOADED }
            ?: return null
        if (!exists(mainFile.uri, mainFile.size)) return null
        val image = downloadFiles.files.find { it.type == DownloadFileType.IMAGE_PRIMARY && it.status == DownloadStatus.DOWNLOADED }
        return LocalCopy(downloadFiles.download, mainFile.uri, image?.uri)
    }

    private fun exists(uri: Uri, expectedSize: Long): Boolean = try {
        val file = DocumentFile.fromSingleUri(context, uri)
        // Same check as upstream's StorageManager.verify: present and complete
        file != null && file.exists() && (expectedSize <= 0 || file.length() == expectedSize)
    } catch (e: SecurityException) {
        // Storage location permission was revoked
        false
    }
}
