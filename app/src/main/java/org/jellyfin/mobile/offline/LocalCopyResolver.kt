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
 * Fork: offline. Finds downloads that can actually be played: upstream's `getDownloadByItemId` also returns
 * queued, failed and cancelled downloads, and the file may have been removed from the storage location.
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

    suspend fun find(itemId: UUID): LocalCopy? = withContext(Dispatchers.IO) {
        val download = downloadDao.getDownloadByItemId(itemId) ?: return@withContext null
        if (download.status != DownloadStatus.DOWNLOADED) return@withContext null
        toLocalCopy(DownloadFiles(download, downloadDao.getFiles(download.id)))
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
        if (!exists(mainFile.uri)) return null
        val image = downloadFiles.files.find { it.type == DownloadFileType.IMAGE_PRIMARY && it.status == DownloadStatus.DOWNLOADED }
        return LocalCopy(downloadFiles.download, mainFile.uri, image?.uri)
    }

    private fun exists(uri: Uri): Boolean = try {
        DocumentFile.fromSingleUri(context, uri)?.exists() == true
    } catch (e: SecurityException) {
        // Storage location permission was revoked
        false
    }
}
