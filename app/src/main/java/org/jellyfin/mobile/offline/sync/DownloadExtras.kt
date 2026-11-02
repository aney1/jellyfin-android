package org.jellyfin.mobile.offline.sync

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.data.ItemCacheEntity
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.OfflineJson
import org.jellyfin.mobile.offline.data.SubtitleFileEntity
import org.jellyfin.mobile.offline.playback.IMAGE_SUBTITLE_CODECS
import org.jellyfin.mobile.player.deviceprofile.CodecHelpers
import org.jellyfin.mobile.player.mediasegments.MediaSegmentRepository
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Fork: offline. Everything a downloaded video needs besides the file itself, so the player behaves the same
 * while offline: external (sidecar) subtitles, full item details (chapters, ...) and media segments.
 *
 * Subtitles are stored in app storage instead of the download folder: the player's cache key factory derives
 * keys from item ids in the URL path, which document URIs of the download folder don't have.
 */
class DownloadExtras(
    private val context: Context,
    private val offlineDao: OfflineDao,
    private val apiClients: OfflineApiClients,
) {
    private val segmentsSerializer = ListSerializer(MediaSegmentDto.serializer())

    /**
     * Fetch the extras of all finished downloads that don't have them yet.
     *
     * @return true if everything was fetched.
     */
    suspend fun fetchMissing(downloads: List<DownloadEntity>): Boolean {
        var complete = true
        for (download in downloads) {
            if (download.status != DownloadStatus.DOWNLOADED) continue
            val cache = offlineDao.getItemCache(download.serverId, download.itemId)
            if (cache != null && cache.item != null && cache.subtitlesDone) continue
            try {
                fetch(download, cache)
            } catch (e: Exception) {
                Timber.i("Could not fetch offline extras of %s: %s", download.itemId, e.message)
                complete = false
            }
        }
        return complete
    }

    private suspend fun fetch(download: DownloadEntity, cache: ItemCacheEntity?) {
        val target = apiClients.get(download.serverId, download.userId) ?: return
        val item = target.api.userLibraryApi.getItem(itemId = download.itemId, userId = target.userId).content
        val segments = runCatching {
            target.api.mediaSegmentsApi.getItemSegments(
                itemId = download.itemId,
                includeSegmentTypes = MediaSegmentRepository.SUPPORTED_TYPES,
            ).content.items
        }.getOrNull()
        val subtitlesDone = cache?.subtitlesDone == true || downloadSubtitles(download, target)

        offlineDao.upsertItemCache(
            ItemCacheEntity(
                serverId = download.serverId,
                itemId = download.itemId,
                item = item,
                segments = segments?.let { OfflineJson.encodeToString(segmentsSerializer, it) } ?: cache?.segments,
                subtitlesDone = subtitlesDone,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun downloadSubtitles(download: DownloadEntity, target: OfflineApiClients.Target): Boolean {
        val source = download.item.mediaSources?.firstOrNull() ?: return true
        val mediaSourceId = source.id ?: return true
        val existing = offlineDao.getSubtitleFiles(download.serverId, download.itemId).associateBy { it.streamIndex }
        var complete = true
        for (stream in source.mediaStreams.orEmpty()) {
            if (stream.type != MediaStreamType.SUBTITLE || !stream.isExternal) continue
            val format = downloadFormat(stream.codec) ?: continue
            val known = existing[stream.index]
            if (known != null && known.uri.path?.let(::File)?.exists() == true) continue

            try {
                val content = target.api.subtitleApi.getSubtitle(
                    routeItemId = download.itemId,
                    routeMediaSourceId = mediaSourceId,
                    routeIndex = stream.index,
                    routeFormat = format,
                ).content
                val file = subtitleFile(download.serverId, download.itemId, stream.index, format)
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }
                offlineDao.upsertSubtitleFile(
                    SubtitleFileEntity(
                        serverId = download.serverId,
                        itemId = download.itemId,
                        streamIndex = stream.index,
                        uri = Uri.fromFile(file),
                        mimeType = requireNotNull(CodecHelpers.getSubtitleMimeType(format)),
                        language = stream.language,
                        title = stream.displayTitle ?: stream.title,
                    ),
                )
            } catch (e: Exception) {
                Timber.i("Could not download subtitle %d of %s: %s", stream.index, download.itemId, e.message)
                complete = false
            }
        }
        return complete
    }

    /**
     * Media segments stored for a downloaded item, used when the server can't be reached.
     */
    suspend fun cachedSegments(itemId: UUID): List<MediaSegmentDto> =
        offlineDao.getItemCacheByItemId(itemId)?.segments?.let { json ->
            runCatching { OfflineJson.decodeFromString(segmentsSerializer, json) }.getOrNull()
        }.orEmpty()

    /**
     * Remove the stored subtitle files of an item; the database rows are removed by the caller.
     */
    suspend fun deleteFiles(serverId: Long, itemId: UUID) = withContext(Dispatchers.IO) {
        File(subtitleRoot(), "$serverId/${itemId.hex()}").deleteRecursively()
    }

    private fun subtitleFile(serverId: Long, itemId: UUID, index: Int, format: String) =
        File(subtitleRoot(), "$serverId/${itemId.hex()}/$index.$format")

    private fun subtitleRoot() = File(context.filesDir, SUBTITLE_DIRECTORY)

    private fun UUID.hex() = toString().replace("-", "")

    private companion object {
        const val SUBTITLE_DIRECTORY = "offline_subtitles"

        /**
         * The format to request from the server: text formats the player can render directly are kept, other
         * text formats are converted to SubRip by the server, image based formats are skipped.
         */
        fun downloadFormat(codec: String?): String? = when (val normalized = codec?.lowercase()) {
            null -> "srt"
            "subrip", "srt" -> "srt"
            "ass", "ssa", "ttml" -> normalized
            "webvtt", "vtt" -> "vtt"
            in IMAGE_SUBTITLE_CODECS -> null
            else -> "srt"
        }
    }
}
