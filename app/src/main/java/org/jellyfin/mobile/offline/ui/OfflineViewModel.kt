package org.jellyfin.mobile.offline.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.offline.LocalCopyResolver
import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.PlayStateEntity
import org.jellyfin.mobile.offline.data.PlaylistEntryEntity
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.sdk.api.client.extensions.systemApi
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
    private val offlineDao: OfflineDao,
    private val localCopyResolver: LocalCopyResolver,
    private val activityEventHandler: ActivityEventHandler,
    private val apiClients: OfflineApiClients,
) : ViewModel() {
    data class Entry(
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

    val state: StateFlow<State> = combine(
        downloadDao.getAllDownloads(),
        offlineDao.observePlaylists(),
        offlineDao.observeAllPlaylistEntries(),
        offlineDao.observePlayStates(),
    ) { _, playlists, entries, playStates ->
        val localCopies = localCopyResolver.findAll()
        val states = playStates.associateBy { it.itemId }
        val entriesByPlaylist = entries.groupBy { it.playlistId }

        val offlinePlaylists = playlists
            .map { playlist ->
                Playlist(
                    id = playlist.playlistId,
                    name = playlist.name,
                    entries = entriesByPlaylist[playlist.playlistId].orEmpty().map { entry ->
                        entry.toEntry(localCopies[entry.itemId], states[entry.itemId])
                    },
                )
            }
            .filter { it.availableCount > 0 }
            .sortedBy { it.name.lowercase() }

        val inPlaylists = entries.map(PlaylistEntryEntity::itemId).toSet()
        val videos = localCopies.values
            .filter { it.download.itemId !in inPlaylists && it.download.item.mediaType == MediaType.VIDEO }
            .sortedByDescending { it.download.createdAt }
            .map { copy -> toEntry(copy.download.item, copy, states[copy.download.itemId]) }

        State(loading = false, playlists = offlinePlaylists, videos = videos)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), State())

    private val _openPlaylistId = MutableStateFlow<UUID?>(null)
    val openPlaylist: StateFlow<Playlist?> = combine(state, _openPlaylistId) { state, id ->
        id?.let { state.playlists.find { playlist -> playlist.id == it } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _serverReachable = MutableStateFlow(false)
    val serverReachable: StateFlow<Boolean> = _serverReachable.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                _serverReachable.value = pingServer()
                delay(PING_INTERVAL_MS)
            }
        }
    }

    fun openPlaylist(id: UUID?) {
        _openPlaylistId.value = id
    }

    /**
     * Play [entry] and continue with the following available videos, like the web app does for playlists.
     */
    fun play(entries: List<Entry>, entry: Entry) {
        val available = entries.filter { it.available }
        val startIndex = available.indexOf(entry).takeIf { it >= 0 } ?: return
        val playOptions = PlayOptions(
            ids = available.map { it.itemId },
            mediaSourceId = null,
            startIndex = startIndex,
            startPosition = entry.positionTicks.takeIf { !entry.played && it > 0 }?.ticks,
            audioStreamIndex = null,
            subtitleStreamIndex = null,
            playFromDownloads = true,
        )
        activityEventHandler.emit(ActivityEvent.LaunchNativePlayer(playOptions))
    }

    private suspend fun pingServer(): Boolean {
        val owner = offlineDao.getPlaylists().firstOrNull()?.let { it.serverId to it.userId } ?: return false
        val target = apiClients.get(owner.first, owner.second) ?: return false
        return withTimeoutOrNull(PING_TIMEOUT_MS) {
            runCatching { target.api.systemApi.getPublicSystemInfo() }.isSuccess
        } ?: false
    }

    private fun PlaylistEntryEntity.toEntry(copy: LocalCopyResolver.LocalCopy?, state: PlayStateEntity?) =
        toEntry(copy?.download?.item ?: item, copy, state)

    private fun toEntry(item: BaseItemDto, copy: LocalCopyResolver.LocalCopy?, state: PlayStateEntity?): Entry {
        val played = state?.played ?: item.userData?.played ?: false
        val position = state?.positionTicks ?: item.userData?.playbackPositionTicks ?: 0
        val runTime = item.runTimeTicks
        return Entry(
            itemId = item.id,
            title = item.title(),
            subtitle = item.subtitle(),
            runTimeTicks = runTime,
            imageUri = copy?.primaryImageUri,
            available = copy != null,
            played = played,
            progress = if (!played && position > 0 && runTime != null && runTime > 0) position.toFloat() / runTime else null,
            positionTicks = position,
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
        const val PING_TIMEOUT_MS = 5_000L
    }
}
