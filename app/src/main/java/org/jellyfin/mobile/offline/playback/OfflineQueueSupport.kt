package org.jellyfin.mobile.offline.playback

import androidx.media3.common.MediaItem
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.offline.LocalCopyResolver
import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.OfflinePreferences
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.player.source.ExternalSubtitleStream
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.io.File
import java.util.UUID
import kotlin.time.Duration

/**
 * Fork: offline. What upstream's QueueManager needs to play downloaded copies like streamed videos:
 * the per-item decision, full item details, the resume position and the downloaded external subtitles.
 */
class OfflineQueueSupport(
    private val preferences: OfflinePreferences,
    private val appPreferences: AppPreferences,
    private val localCopyResolver: LocalCopyResolver,
    private val offlineDao: OfflineDao,
    private val apiClients: OfflineApiClients,
    private val offlinePlayback: OfflinePlayback,
) {
    /** Subtitles of the last played downloads, handed from starting an item to building its media source. */
    private val subtitleConfigurations = object : LinkedHashMap<UUID, List<MediaItem.SubtitleConfiguration>>(
        SUBTITLE_CACHE_SIZE,
        LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, List<MediaItem.SubtitleConfiguration>>?) =
            size > SUBTITLE_CACHE_SIZE
    }

    /**
     * The downloaded copy [itemId] should be played from, or null to stream it.
     *
     * @param mediaSourceId the version requested by the web app, a different version is streamed.
     * @param downloadsOnly playback was started from the offline screen, only downloads can be played.
     */
    suspend fun localCopyFor(itemId: UUID, mediaSourceId: String?, downloadsOnly: Boolean): LocalCopyResolver.LocalCopy? {
        if (downloadsOnly) return localCopyResolver.find(itemId)
        if (!preferences.preferDownloads) return null

        // Only a download of the server and user the web app is signed in to
        val copy = localCopyResolver.find(itemId, appPreferences.currentServerId, appPreferences.currentUserId) ?: return null
        val source = copy.download.item.mediaSources?.firstOrNull() ?: return null
        if (mediaSourceId != null && mediaSourceId.toUUIDOrNull().let { it == null || it != source.id?.toUUIDOrNull() }) {
            return null
        }

        // Until the external subtitles are stored, streaming keeps them available
        val hasExternalSubtitles = source.mediaStreams.orEmpty().any { it.isDownloadableExternalSubtitle }
        if (hasExternalSubtitles && offlineDao.getItemCache(copy.download.serverId, itemId)?.subtitlesDone != true) return null
        return copy
    }

    /**
     * The downloaded item the player uses, protected from the cleanup.
     */
    var itemInUse: UUID?
        get() = offlinePlayback.itemInUse
        set(value) = offlinePlayback.setItemInUse(value)

    /**
     * Full item details (chapters, ...) fetched after the download finished, or the download's own copy.
     */
    suspend fun fullItem(download: DownloadEntity): BaseItemDto =
        offlineDao.getItemCache(download.serverId, download.itemId)?.item ?: download.item

    /**
     * The saved position to resume [download] from: unsent local progress first, then the server's current
     * state if it answers quickly, then the last known state.
     */
    suspend fun resumePosition(download: DownloadEntity, allowNetwork: Boolean): Duration? {
        val local = offlineDao.getPlayState(download.serverId, download.userId, download.itemId)
        val ticks = when {
            local?.dirty == true -> local.positionTicks
            else -> {
                val fresh = if (allowNetwork) fetchUserData(download) else null
                fresh ?: local?.positionTicks ?: fullItem(download).userData?.playbackPositionTicks
            }
        }
        return ticks?.takeIf { it > 0 }?.ticks
    }

    private suspend fun fetchUserData(download: DownloadEntity): Long? {
        val target = apiClients.get(download.serverId, download.userId) ?: return null
        return withTimeoutOrNull(SERVER_TIMEOUT_MS) {
            runCatching {
                target.api.itemsApi.getItemUserData(download.itemId, target.userId).content.playbackPositionTicks
            }.getOrNull()
        }
    }

    /**
     * Load the downloaded external subtitles of [download], returned by [getSubtitleConfigurations] afterwards.
     */
    suspend fun prepareSubtitles(download: DownloadEntity) {
        val files = offlineDao.getSubtitleFiles(download.serverId, download.itemId)
        val configurations = files
            .filter { file -> file.uri.path?.let(::File)?.exists() == true }
            .map { file ->
                MediaItem.SubtitleConfiguration.Builder(file.uri)
                    .setId("${ExternalSubtitleStream.ID_PREFIX}${file.streamIndex}")
                    .setLabel(file.title.orEmpty())
                    .setMimeType(file.mimeType)
                    .setLanguage(file.language ?: Constants.LANGUAGE_UNDEFINED)
                    .build()
            }
        synchronized(subtitleConfigurations) { subtitleConfigurations[download.itemId] = configurations }
    }

    fun getSubtitleConfigurations(itemId: UUID): List<MediaItem.SubtitleConfiguration> =
        synchronized(subtitleConfigurations) { subtitleConfigurations[itemId] }.orEmpty()

    private companion object {
        const val SERVER_TIMEOUT_MS = 2_000L
        const val SUBTITLE_CACHE_SIZE = 8
        const val LOAD_FACTOR = 0.75f
    }
}
