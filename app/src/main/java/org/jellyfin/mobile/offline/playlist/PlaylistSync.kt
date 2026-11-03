// Fork: offline. Server, storage and database errors of any kind must not stop the offline sync.
// Loops skip items that need nothing or failed with `continue`.
@file:Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")

package org.jellyfin.mobile.offline.playlist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.data.DownloadOriginEntity
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.PlaylistDownloadMode
import org.jellyfin.mobile.offline.data.PlaylistEntity
import org.jellyfin.mobile.offline.data.PlaylistEntryEntity
import org.jellyfin.mobile.offline.data.PlaylistExclusionEntity
import org.jellyfin.mobile.offline.playback.OfflinePlayback
import org.jellyfin.mobile.offline.sync.OfflineDownloads
import org.jellyfin.mobile.offline.sync.PlayStateSync
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/**
 * Fork: offline. Keeps the downloads of playlists in line with the playlist on the server:
 * downloads wanted videos (see [OfflineRules.wantedItems]) and removes downloads that left the playlist unwatched.
 */
@Suppress("TooManyFunctions")
class PlaylistSync(
    private val offlineDao: OfflineDao,
    private val apiClients: OfflineApiClients,
    private val playStateSync: PlayStateSync,
    private val offlinePlayback: OfflinePlayback,
    private val offlineDownloads: OfflineDownloads,
) {
    /** One sync at a time, a page visit and the background worker may overlap. */
    private val mutex = Mutex()

    /**
     * Items whose download was requested recently. A request from the playlist page waits in the activity (download
     * folder dialog, permission) before the download exists, another sync must not request it again meanwhile:
     * upstream's DownloadManager would add it twice.
     */
    private val recentlyRequested = ConcurrentHashMap<UUID, Long>()

    /**
     * How downloads get started: through the activity for actions of the user (asks for the download folder
     * and the notification permission when needed), or directly from the background.
     */
    fun interface Enqueuer {
        suspend fun enqueue(serverId: Long, userId: Long, itemIds: List<UUID>)
    }

    suspend fun getPlaylist(serverId: Long, playlistId: UUID): PlaylistEntity? =
        offlineDao.getPlaylist(serverId, playlistId)

    /**
     * Start tracking a playlist; only done for an explicit action of the user (mode, limits, single download).
     */
    suspend fun getOrCreatePlaylist(
        serverId: Long,
        userId: Long,
        playlistId: UUID,
        name: String?,
    ): PlaylistEntity? = withContext(Dispatchers.IO) {
        offlineDao.getPlaylist(serverId, playlistId)?.let { return@withContext it }
        val playlistName = name?.takeIf { it.isNotBlank() } ?: fetchName(serverId, userId, playlistId)
        offlineDao.upsertPlaylist(
            PlaylistEntity(
                serverId = serverId,
                userId = userId,
                playlistId = playlistId,
                name = playlistName.orEmpty(),
            ),
        )
        offlineDao.getPlaylist(serverId, playlistId)
    }

    suspend fun setMode(playlist: PlaylistEntity, mode: PlaylistDownloadMode) =
        offlineDao.updatePlaylistMode(playlist.serverId, playlist.playlistId, mode)

    suspend fun setLimits(playlist: PlaylistEntity, maxItems: Int?, maxRuntimeMinutes: Int?) =
        offlineDao.updatePlaylistLimits(playlist.serverId, playlist.playlistId, maxItems, maxRuntimeMinutes)

    suspend fun setName(playlist: PlaylistEntity, name: String) {
        if (name.isNotBlank() && name != playlist.name) {
            offlineDao.updatePlaylistName(playlist.serverId, playlist.playlistId, name)
        }
    }

    /**
     * Refresh the playlist from the server and, depending on [download], start downloading wanted videos.
     *
     * @return the number of downloads that were started.
     */
    suspend fun sync(playlist: PlaylistEntity, download: Boolean, enqueuer: Enqueuer): Int = locked {
        val entries = refreshSnapshot(playlist) ?: return@locked 0
        val started = if (download) downloadWanted(playlist, entries, enqueuer) else 0
        removeLeftPlaylists(setOf(playlist.playlistId))
        started
    }

    /** Server requests must not run on the main thread (the SDK reads responses on the calling thread). */
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    /**
     * Background pass: automatic playlists download new videos, and every playlist with downloads is refreshed
     * (for the offline screen and for removing downloads that left it).
     *
     * @return true if every playlist was synced.
     */
    suspend fun syncAllInBackground(enqueuer: Enqueuer): Boolean = locked {
        var complete = true
        val downloaded = offlineDownloads.downloadsOf(null, offlineDao.getAllPlaylistMemberships().map { it.itemId })
        val memberships = offlineDao.getAllPlaylistMemberships().groupBy({ it.playlistId }, { it.itemId })
        val origins = offlineDao.getDownloadOrigins().map { it.playlistId }.toSet()
        val refreshed = mutableSetOf<UUID>()
        for (playlist in offlineDao.getPlaylists()) {
            val auto = playlist.mode == PlaylistDownloadMode.AUTO
            val hasDownloads = playlist.playlistId in origins ||
                memberships[playlist.playlistId].orEmpty().any { it in downloaded }
            if (!auto && !hasDownloads) continue
            try {
                val entries = refreshSnapshot(playlist)
                if (entries == null) {
                    complete = false
                    continue
                }
                refreshed += playlist.playlistId
                if (auto) downloadWanted(playlist, entries, enqueuer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Background sync of playlist %s failed", playlist.playlistId)
                complete = false
            }
        }
        removeLeftPlaylists(refreshed)
        pruneUnused(downloaded.keys, origins)
        complete
    }

    /**
     * Download a single video of a playlist, independent of the playlist's mode.
     */
    suspend fun downloadItem(playlist: PlaylistEntity, itemId: UUID, enqueuer: Enqueuer) = locked {
        offlineDao.deleteExclusion(playlist.serverId, playlist.playlistId, itemId)
        val entries = when (playlist.lastSyncedAt) {
            null -> refreshSnapshot(playlist)?.map { it.itemId }
            else -> offlineDao.getPlaylistEntryIds(playlist.serverId, playlist.playlistId)
        }
        // Only tie the download to the playlist when the stored playlist contains it, otherwise it's a plain download
        if (entries != null && itemId in entries) {
            offlineDao.insertDownloadOrigins(
                listOf(DownloadOriginEntity(playlist.serverId, itemId, playlist.playlistId)),
            )
        }
        request(playlist, listOf(itemId), System.currentTimeMillis(), enqueuer)
    }

    /**
     * Mark [itemIds] as requested while the request is on its way; a failed request may be retried right away.
     */
    private suspend fun request(playlist: PlaylistEntity, itemIds: List<UUID>, now: Long, enqueuer: Enqueuer) {
        itemIds.forEach { recentlyRequested[it] = now }
        var sent = false
        try {
            enqueuer.enqueue(playlist.serverId, playlist.userId, itemIds)
            sent = true
        } finally {
            if (!sent) itemIds.forEach { recentlyRequested.remove(it, now) }
        }
    }

    /**
     * Mark a video as "don't download" or allow it again. Excluding removes the download only if it was made for
     * this playlist alone; manual downloads and those other playlists want are kept.
     */
    suspend fun setExcluded(playlist: PlaylistEntity, itemId: UUID, excluded: Boolean) = locked {
        if (!excluded) {
            offlineDao.deleteExclusion(playlist.serverId, playlist.playlistId, itemId)
            return@locked
        }
        offlineDao.insertExclusion(PlaylistExclusionEntity(playlist.serverId, playlist.playlistId, itemId))
        val origins = offlineDao.getDownloadOriginsOfItem(playlist.serverId, itemId)
        if (origins.none { it.playlistId == playlist.playlistId }) return@locked
        offlineDao.deleteDownloadOrigin(playlist.serverId, itemId, playlist.playlistId)
        if (origins.all { it.playlistId == playlist.playlistId }) deleteUnlessPlaying(playlist.serverId, itemId)
    }

    /**
     * Delete the download of a video. In playlists that download by themselves it is also excluded, otherwise the
     * next sync would download it again.
     */
    suspend fun deleteDownload(playlist: PlaylistEntity, itemId: UUID) = locked {
        if (playlist.mode != PlaylistDownloadMode.OFF) {
            offlineDao.insertExclusion(PlaylistExclusionEntity(playlist.serverId, playlist.playlistId, itemId))
        }
        deleteUnlessPlaying(playlist.serverId, itemId)
    }

    private suspend fun deleteUnlessPlaying(serverId: Long, itemId: UUID) {
        val deleted = offlineDownloads.delete(serverId, itemId) { offlinePlayback.itemInUse != itemId }
        if (!deleted) Timber.i("Not deleting download %s, it is playing", itemId)
    }

    /**
     * Fetch the playlist's entries and store them for offline use.
     *
     * @return the entries, or null if the server couldn't be reached.
     */
    private suspend fun refreshSnapshot(playlist: PlaylistEntity): List<PlaylistEntryEntity>? {
        val target = apiClients.get(playlist.serverId, playlist.userId) ?: return null
        return try {
            val versionsBefore = playStateSync.versionsOf(playlist.serverId, playlist.userId)
            val items = target.api.playlistsApi.getPlaylistItems(
                playlistId = playlist.playlistId,
                userId = target.userId,
                fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.MEDIA_SOURCE_COUNT),
                enableImages = true,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP),
            ).content.items
            val entries = items.mapIndexed { index, item ->
                PlaylistEntryEntity(playlist.serverId, playlist.playlistId, index, item.id, item)
            }
            offlineDao.replacePlaylistEntries(playlist.serverId, playlist.playlistId, entries)
            playStateSync.applyServerItems(playlist.serverId, playlist.userId, items, versionsBefore)
            if (playlist.name.isBlank()) {
                fetchName(playlist.serverId, playlist.userId, playlist.playlistId)?.let { setName(playlist, it) }
            }
            offlineDao.updatePlaylistSynced(playlist.serverId, playlist.playlistId, System.currentTimeMillis())
            entries
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not refresh playlist %s", playlist.playlistId)
            null
        }
    }

    private suspend fun fetchName(serverId: Long, userId: Long, playlistId: UUID): String? {
        val target = apiClients.get(serverId, userId) ?: return null
        return try {
            target.api.userLibraryApi.getItem(itemId = playlistId, userId = target.userId).content.name
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.i(e, "Could not fetch the playlist name")
            null
        }
    }

    private suspend fun downloadWanted(
        playlist: PlaylistEntity,
        entries: List<PlaylistEntryEntity>,
        enqueuer: Enqueuer,
    ): Int {
        // Read the settings again, they may have changed while the playlist was refreshed
        val current = offlineDao.getPlaylist(playlist.serverId, playlist.playlistId) ?: return 0
        val excluded = offlineDao.getExclusions(playlist.serverId, playlist.playlistId).map { it.itemId }.toSet()
        val ruleEntries = entries.map { entry ->
            val state = offlineDao.getPlayState(playlist.serverId, playlist.userId, entry.itemId)
            OfflineRules.Entry(
                itemId = entry.itemId,
                isVideo = entry.item.mediaType == MediaType.VIDEO,
                runTimeTicks = entry.item.runTimeTicks,
                played = state?.played ?: entry.item.userData?.played ?: false,
            )
        }
        val wanted = OfflineRules.wantedItems(ruleEntries, excluded, current.maxItems, current.maxRuntimeMinutes)
        if (wanted.isEmpty()) return 0

        val existing = offlineDownloads.downloadsOf(playlist.serverId, wanted)
        // A download the user cancelled is left alone, failed ones are retried
        val now = System.currentTimeMillis()
        val missing = wanted.filter { itemId ->
            val download = existing[itemId]
            val pending = download == null && now - (recentlyRequested[itemId] ?: 0) < REQUEST_PENDING_MS
            !pending && (download == null || download.status == DownloadStatus.ERROR)
        }
        if (missing.isEmpty()) return 0

        offlineDao.insertDownloadOrigins(
            missing.map { DownloadOriginEntity(playlist.serverId, it, playlist.playlistId) },
        )
        request(playlist, missing, now, enqueuer)
        Timber.i("Started %d downloads for playlist %s", missing.size, playlist.playlistId)
        return missing.size
    }

    /**
     * Delete downloads that were made for playlists and left them while still unwatched. Watched ones are kept
     * until their delete period after watching has passed (smart playlists drop watched videos).
     *
     * @param refreshed playlists whose snapshot was just refreshed successfully.
     */
    private suspend fun removeLeftPlaylists(refreshed: Set<UUID>) {
        val origins = offlineDao.getDownloadOrigins()
        if (origins.isEmpty()) return
        val now = System.currentTimeMillis()
        val playlists = offlineDao.getPlaylists()
        val memberships = offlineDao.getAllPlaylistMemberships()
            .groupBy({ it.playlistId }, { it.itemId })
            .mapValues { it.value.toSet() }

        val updatedOrigins = updateMissingSince(origins, refreshed, memberships, now)

        val knownSnapshots = playlists
            .filter { it.lastSyncedAt != null }
            .associate { it.playlistId to memberships[it.playlistId].orEmpty() }
        val userOfPlaylist = playlists.associate { it.playlistId to it.userId }

        for ((serverId, serverOrigins) in updatedOrigins.groupBy { it.serverId }) {
            val byItem = serverOrigins.groupBy({ it.itemId }, { OfflineRules.Origin(it.playlistId, it.missingSince) })
            val candidates = OfflineRules.leftPlaylists(
                origins = byItem,
                knownSnapshots = knownSnapshots,
                refreshed = refreshed,
                now = now,
                grace = REMOVAL_GRACE,
                itemInUse = offlinePlayback.itemInUse,
            )
            // Each item is checked with the user whose playlist it was downloaded for
            val candidatesByUser = candidates.groupBy { itemId ->
                byItem[itemId].orEmpty().firstNotNullOfOrNull { userOfPlaylist[it.playlistId] }
            }
            for ((userId, userCandidates) in candidatesByUser) {
                if (userId != null) removeUnwatched(serverId, userId, userCandidates.toSet())
            }
        }
    }

    /**
     * Track since when each origin's item is missing from its (just refreshed) playlist.
     */
    private suspend fun updateMissingSince(
        origins: List<DownloadOriginEntity>,
        refreshed: Set<UUID>,
        memberships: Map<UUID, Set<UUID>>,
        now: Long,
    ): List<DownloadOriginEntity> = origins.map { origin ->
        if (origin.playlistId !in refreshed) return@map origin
        val present = memberships[origin.playlistId].orEmpty().contains(origin.itemId)
        val missingSince = if (present) null else origin.missingSince ?: now
        if (missingSince != origin.missingSince) {
            offlineDao.setOriginMissingSince(origin.serverId, origin.itemId, origin.playlistId, missingSince)
        }
        origin.copy(missingSince = missingSince)
    }

    /**
     * Delete the [candidates] that are unwatched according to the server right now; if it can't be asked, keep all.
     */
    private suspend fun removeUnwatched(serverId: Long, userId: Long, candidates: Set<UUID>) {
        try {
            playStateSync.refresh(serverId, userId, candidates)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.i(e, "Could not check watch state before removing downloads")
            return
        }
        for (itemId in candidates.filterNot { offlineDao.isPlayedByAnyUser(serverId, it) }) {
            try {
                val deleted = offlineDownloads.delete(serverId, itemId) { offlinePlayback.itemInUse != itemId }
                if (deleted) Timber.i("Removed download %s, it left its playlist unwatched", itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Could not remove download %s", itemId)
            }
        }
    }

    /**
     * Forget playlists that don't download anything and have nothing downloaded anymore.
     */
    private suspend fun pruneUnused(downloadedItems: Set<UUID>, playlistsWithOrigins: Set<UUID>) {
        val memberships = offlineDao.getAllPlaylistMemberships().groupBy({ it.playlistId }, { it.itemId })
        for (playlist in offlineDao.getPlaylists()) {
            val unused = playlist.mode == PlaylistDownloadMode.OFF &&
                playlist.playlistId !in playlistsWithOrigins &&
                memberships[playlist.playlistId].orEmpty().none { it in downloadedItems } &&
                offlineDao.getExclusions(playlist.serverId, playlist.playlistId).isEmpty() &&
                playlist.maxItems == null &&
                playlist.maxRuntimeMinutes == null
            if (unused) offlineDao.deletePlaylist(playlist.serverId, playlist.playlistId)
        }
    }

    private companion object {
        val REMOVAL_GRACE = 10.minutes
        const val REQUEST_PENDING_MS = 10 * 60_000L
    }
}
