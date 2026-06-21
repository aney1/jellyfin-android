package org.jellyfin.mobile.player.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration

class MediaSourceResolver(private val apiClient: ApiClient) {
    private val mediaInfoApi: MediaInfoApi = apiClient.mediaInfoApi
    private val userLibraryApi: UserLibraryApi = apiClient.userLibraryApi

    @Suppress("ReturnCount")
    suspend fun resolveMediaSource(
        itemId: UUID,
        mediaSourceId: String? = null,
        deviceProfile: DeviceProfile? = null,
        maxStreamingBitrate: Int? = null,
        startTime: Duration? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        autoOpenLiveStream: Boolean = true,
        enableDirectPlay: Boolean? = null,
        enableDirectStream: Boolean? = null,
        resumeFromSavedPosition: Boolean = false,
    ): Result<RemoteJellyfinMediaSource> {
        // Load additional item info if possible. Fetched before the playback info request so the
        // saved resume position can be passed to the server (needed for transcoded streams).
        val item = try {
            withContext(Dispatchers.IO) {
                userLibraryApi.getItem(itemId).content
            }
        } catch (e: ApiClientException) {
            Timber.e(e, "Failed to load item for media source $itemId")
            null
        }

        // Resume from the saved playback position when requested and no explicit start time was given.
        val resolvedStartTime = startTime
            ?: item?.userData?.playbackPositionTicks
                ?.takeIf { resumeFromSavedPosition && it > 0 }
                ?.ticks

        // Load media source info
        val playSessionId: String
        val mediaSourceInfo: MediaSourceInfo = try {
            val response = withContext(Dispatchers.IO) {
                mediaInfoApi.getPostedPlaybackInfo(
                    itemId = itemId,
                    data = PlaybackInfoDto(
                        // We need to remove the dashes so that the server can find the correct media source.
                        // And if we didn't pass the mediaSourceId, our stream indices would silently get ignored.
                        // https://github.com/jellyfin/jellyfin/blob/9a35fd673203cfaf0098138b2768750f4818b3ab/Jellyfin.Api/Helpers/MediaInfoHelper.cs#L196-L201
                        mediaSourceId = mediaSourceId ?: itemId.toString().replace("-", ""),
                        deviceProfile = deviceProfile,
                        maxStreamingBitrate = maxStreamingBitrate,
                        startTimeTicks = resolvedStartTime?.inWholeTicks,
                        audioStreamIndex = audioStreamIndex,
                        subtitleStreamIndex = subtitleStreamIndex,
                        autoOpenLiveStream = autoOpenLiveStream,
                        enableDirectPlay = enableDirectPlay,
                        enableDirectStream = enableDirectStream,
                    ),
                ).content
            }

            playSessionId = response.playSessionId ?: return Result.failure(PlayerException.UnsupportedContent())

            response.mediaSources.let { sources ->
                sources.find { source -> source.id?.toUUIDOrNull() == itemId } ?: sources.firstOrNull()
            } ?: return Result.failure(PlayerException.UnsupportedContent())
        } catch (e: ApiClientException) {
            Timber.e(e, "Failed to load media source $itemId")
            return Result.failure(PlayerException.NetworkFailure(e))
        }

        // Create JellyfinMediaSource
        return try {
            val source = RemoteJellyfinMediaSource(
                itemId = itemId,
                item = item,
                sourceInfo = mediaSourceInfo,
                playSessionId = playSessionId,
                liveStreamId = mediaSourceInfo.liveStreamId,
                maxStreamingBitrate = maxStreamingBitrate,
                playbackDetails = PlaybackDetails(resolvedStartTime, audioStreamIndex, subtitleStreamIndex),
            )
            Result.success(source)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Cannot create JellyfinMediaSource")
            Result.failure(PlayerException.UnsupportedContent(e))
        }
    }
}
