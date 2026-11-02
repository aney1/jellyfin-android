package org.jellyfin.mobile.offline.sync

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.data.ItemCacheEntity
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.OfflineJson
import org.jellyfin.mobile.offline.data.SubtitleFileEntity
import org.jellyfin.mobile.offline.playback.isDownloadableExternalSubtitle
import org.jellyfin.mobile.player.deviceprofile.CodecHelpers
import org.jellyfin.mobile.player.mediasegments.MediaSegmentRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.MediaSegmentDto
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
    private val okHttpClient: OkHttpClient,
) {
    private val segmentsSerializer = ListSerializer(MediaSegmentDto.serializer())

    /**
     * Fetch the extras of all finished downloads that don't have them yet, and refresh empty or failed media
     * segments now and then (the server may detect them after the download).
     *
     * @return true if everything was fetched.
     */
    suspend fun fetchMissing(downloads: List<DownloadEntity>): Boolean = withContext(Dispatchers.IO) {
        var complete = true
        val now = System.currentTimeMillis()
        for (download in downloads) {
            if (download.status != DownloadStatus.DOWNLOADED) continue
            val cache = offlineDao.getItemCache(download.serverId, download.itemId)
            val segmentsStale = cache?.segments.let { it == null || it == EMPTY_LIST } &&
                now - (cache?.fetchedAt ?: 0) > SEGMENT_REFRESH_MS
            if (cache != null && cache.item != null && cache.subtitlesDone && !segmentsStale) continue
            try {
                complete = fetch(download, cache) && complete
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.i("Could not fetch offline extras of %s: %s", download.itemId, e.message)
                complete = false
            }
        }
        complete
    }

    /**
     * @return true if everything was fetched.
     */
    private suspend fun fetch(download: DownloadEntity, cache: ItemCacheEntity?): Boolean {
        val target = apiClients.get(download.serverId, download.userId) ?: return false
        // Each part on its own: one failing must not block the others
        val item = runApi { target.api.userLibraryApi.getItem(itemId = download.itemId, userId = target.userId).content }
        val segments = runApi {
            target.api.mediaSegmentsApi.getItemSegments(
                itemId = download.itemId,
                includeSegmentTypes = MediaSegmentRepository.SUPPORTED_TYPES,
            ).content.items
        }
        val subtitlesDone = cache?.subtitlesDone == true || downloadSubtitles(download, target)

        offlineDao.upsertItemCache(
            ItemCacheEntity(
                serverId = download.serverId,
                itemId = download.itemId,
                item = item ?: cache?.item,
                segments = segments?.let { OfflineJson.encodeToString(segmentsSerializer, it) } ?: cache?.segments,
                subtitlesDone = subtitlesDone,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
        return item != null && segments != null && subtitlesDone
    }

    private fun fetch(api: ApiClient, url: String): ByteArray {
        val authorization = AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        )
        val request = Request.Builder().url(url).header("Authorization", authorization).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw InvalidStatusException(response.code)
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private suspend fun <T> runApi(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.i("Offline extras request failed: %s", e.message)
        null
    }

    private suspend fun downloadSubtitles(download: DownloadEntity, target: OfflineApiClients.Target): Boolean {
        val source = download.item.mediaSources?.firstOrNull() ?: return true
        val mediaSourceId = source.id ?: return true
        val existing = offlineDao.getSubtitleFiles(download.serverId, download.itemId).associateBy { it.streamIndex }
        var complete = true
        for (stream in source.mediaStreams.orEmpty()) {
            if (!stream.isDownloadableExternalSubtitle) continue
            val format = downloadFormat(stream.codec)
            val known = existing[stream.index]
            if (known != null && known.uri.path?.let(::File)?.exists() == true) continue

            try {
                // The SDK's getSubtitle() expects JSON, subtitles are plain text: fetch the url directly
                val url = target.api.subtitleApi.getSubtitleUrl(
                    routeItemId = download.itemId,
                    routeMediaSourceId = mediaSourceId,
                    routeIndex = stream.index,
                    routeFormat = format,
                )
                val file = subtitleFile(download.serverId, download.itemId, stream.index, format)
                withContext(Dispatchers.IO) {
                    val bytes = fetch(target.api, url)
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidStatusException) {
                // The subtitle is gone on the server (4xx): nothing to wait for, play the download without it
                if (e.status !in CLIENT_ERRORS) complete = false
                Timber.i("Could not download subtitle %d of %s: HTTP %d", stream.index, download.itemId, e.status)
            } catch (e: Exception) {
                Timber.i("Could not download subtitle %d of %s: %s", stream.index, download.itemId, e.message)
                complete = false
            }
        }
        return complete
    }

    /**
     * Media segments stored for a downloaded item, null if there are none (not a download, or not fetched yet).
     */
    suspend fun cachedSegments(itemId: UUID): List<MediaSegmentDto>? =
        offlineDao.getItemCacheByItemId(itemId)?.segments?.let { json ->
            runCatching { OfflineJson.decodeFromString(segmentsSerializer, json) }.getOrNull()
        }

    /**
     * Remove the stored subtitle files of an item; the database rows are removed by the caller.
     */
    suspend fun deleteFiles(serverId: Long, itemId: UUID) = withContext(Dispatchers.IO) {
        File(subtitleRoot(), "$serverId/${itemId.hex()}").deleteRecursively()
    }

    /**
     * Remove subtitle folders of items that aren't downloaded anymore but have no database rows left.
     */
    suspend fun deleteStrayFiles(downloaded: Set<Pair<Long, UUID>>) = withContext(Dispatchers.IO) {
        val keep = downloaded.map { (serverId, itemId) -> "$serverId/${itemId.hex()}" }.toSet()
        subtitleRoot().listFiles().orEmpty().forEach { serverDirectory ->
            serverDirectory.listFiles().orEmpty().forEach { itemDirectory ->
                if ("${serverDirectory.name}/${itemDirectory.name}" !in keep) itemDirectory.deleteRecursively()
            }
        }
    }

    private fun subtitleFile(serverId: Long, itemId: UUID, index: Int, format: String) =
        File(subtitleRoot(), "$serverId/${itemId.hex()}/$index.$format")

    private fun subtitleRoot() = File(context.filesDir, SUBTITLE_DIRECTORY)

    private fun UUID.hex() = toString().replace("-", "")

    private companion object {
        const val SUBTITLE_DIRECTORY = "offline_subtitles"
        const val EMPTY_LIST = "[]"
        const val SEGMENT_REFRESH_MS = 24L * 60 * 60 * 1000
        val CLIENT_ERRORS = 400..499

        /**
         * The format to request from the server: text formats the player can render directly are kept, other
         * text formats are converted to SubRip by the server (image based ones are never requested).
         */
        fun downloadFormat(codec: String?): String = when (val normalized = codec?.lowercase()) {
            "ass", "ssa", "ttml" -> normalized
            "webvtt", "vtt" -> "vtt"
            else -> "srt"
        }
    }
}
