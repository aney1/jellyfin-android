package org.jellyfin.mobile.offline.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.offline.LocalCopyResolver
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.PlayStateEntity
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.extensions.ticks
import java.util.UUID

/**
 * Fork: offline. State of the offline screen: playlists with downloads (all entries, unavailable ones greyed
 * out) and downloaded videos that aren't in any known playlist.
 */
class OfflineViewModel(
    downloadDao: DownloadDao,
    offlineDao: OfflineDao,
    private val localCopyResolver: LocalCopyResolver,
    private val activityEventHandler: ActivityEventHandler,
) : ViewModel() {
    data class Entry(
        /** Position in its list; a playlist can contain the same item more than once. */
        val position: Int,
        val itemId: UUID,
        val title: String,
        val subtitle: String?,
        val runTimeTicks: Long?,
        val imageUri: Uri?,
        val available: Boolean,
        val played: Boolean,
        /** Watched part of the video between 0 and 1, null if not started. */
        val progress: Float?,
        val positionTicks: Long,
    )

    data class Playlist(
        val id: UUID,
        val name: String,
        val entries: List<Entry>,
    ) {
        val availableCount: Int get() = entries.count { it.available }
        val imageUri: Uri? get() = entries.firstOrNull { it.available && it.imageUri != null }?.imageUri
    }

    data class State(
        val loading: Boolean = true,
        val playlists: List<Playlist> = emptyList(),
        val videos: List<Entry> = emptyList(),
    )

    private data class StateKey(val serverId: Long, val userId: Long, val itemId: UUID)

    /** Checking the files is expensive (storage access framework), only redo it when downloads change. */
    private val localCopies = downloadDao.getAllDownloads()
        .map { localCopyResolver.findAll() }
        .flowOn(Dispatchers.IO)

    val state: StateFlow<State> = combine(
        localCopies,
        offlineDao.observePlaylists(),
        offlineDao.observeAllPlaylistEntries(),
        offlineDao.observePlayStates(),
    ) { copies, playlists, entries, playStates ->
        val states = playStates.associateBy { StateKey(it.serverId, it.userId, it.itemId) }
        val entriesByPlaylist = entries.groupBy { it.playlistId }

        val offlinePlaylists = playlists
            .map { playlist ->
                Playlist(
                    id = playlist.playlistId,
                    name = playlist.name,
                    entries = entriesByPlaylist[playlist.playlistId].orEmpty().mapIndexed { index, entry ->
                        val copy = copies[entry.itemId]
                        val state = states[StateKey(playlist.serverId, playlist.userId, entry.itemId)]
                        toEntry(index, copy?.download?.item ?: entry.item, copy, state)
                    },
                )
            }
            .filter { it.availableCount > 0 }
            .sortedBy { it.name.lowercase() }

        val inPlaylists = entries.map { it.itemId }.toSet()
        val videos = copies.values
            .filter { it.download.itemId !in inPlaylists && it.download.item.mediaType == MediaType.VIDEO }
            .sortedByDescending { it.download.createdAt }
            .mapIndexed { index, copy ->
                val download = copy.download
                val state = states[StateKey(download.serverId, download.userId, download.itemId)]
                toEntry(index, download.item, copy, state)
            }

        State(loading = false, playlists = offlinePlaylists, videos = videos)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), State())

    private val openPlaylistId = MutableStateFlow<UUID?>(null)
    val openPlaylist: StateFlow<Playlist?> = combine(state, openPlaylistId) { state, id ->
        id?.let { state.playlists.find { playlist -> playlist.id == it } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Whether the server answers again, checked only while the screen is shown. */
    val serverReachable: StateFlow<Boolean> = flow {
        while (true) {
            emit(OfflineEntry.isServerReachable())
            delay(PING_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val isPlaylistOpen: Boolean get() = openPlaylistId.value != null

    fun openPlaylist(id: UUID?) {
        openPlaylistId.value = id
    }

    /**
     * Play a playlist from [entry] on and continue with the following available videos, like the web app does.
     */
    fun playPlaylist(entries: List<Entry>, entry: Entry) {
        val available = entries.filter { it.available }
        val startIndex = available.indexOfFirst { it.position == entry.position }.takeIf { it >= 0 } ?: return
        launch(available, startIndex)
    }

    /**
     * Play a single downloaded video.
     */
    fun playVideo(entry: Entry) {
        if (entry.available) launch(listOf(entry), 0)
    }

    private fun launch(entries: List<Entry>, startIndex: Int) {
        val start = entries[startIndex]
        val playOptions = PlayOptions(
            ids = entries.map { it.itemId },
            mediaSourceId = null,
            startIndex = startIndex,
            startPosition = start.positionTicks.takeIf { !start.played && it > 0 }?.ticks,
            audioStreamIndex = null,
            subtitleStreamIndex = null,
            playFromDownloads = true,
        )
        activityEventHandler.emit(ActivityEvent.LaunchNativePlayer(playOptions))
    }

    fun changeServer() = activityEventHandler.emit(ActivityEvent.SelectServer)

    private fun toEntry(
        position: Int,
        item: BaseItemDto,
        copy: LocalCopyResolver.LocalCopy?,
        state: PlayStateEntity?,
    ): Entry {
        val played = state?.played ?: item.userData?.played ?: false
        val positionTicks = state?.positionTicks ?: item.userData?.playbackPositionTicks ?: 0
        val runTime = item.runTimeTicks
        return Entry(
            position = position,
            itemId = item.id,
            title = item.title(),
            subtitle = item.subtitle(),
            runTimeTicks = runTime,
            imageUri = copy?.primaryImageUri,
            available = copy != null,
            played = played,
            progress = runTime?.takeIf { !played && positionTicks > 0 && it > 0 }?.let { positionTicks.toFloat() / it },
            positionTicks = positionTicks,
        )
    }

    private fun BaseItemDto.title(): String = when (type) {
        BaseItemKind.EPISODE -> listOfNotNull(
            if (parentIndexNumber != null && indexNumber != null) "S$parentIndexNumber:E$indexNumber" else null,
            name,
        ).joinToString(" - ")
        else -> name.orEmpty()
    }

    private fun BaseItemDto.subtitle(): String? = when (type) {
        BaseItemKind.EPISODE -> seriesName
        else -> productionYear?.toString()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val PING_INTERVAL_MS = 15_000L
    }
}
