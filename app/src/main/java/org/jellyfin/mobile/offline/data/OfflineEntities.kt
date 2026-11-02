package org.jellyfin.mobile.offline.data

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Entity
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/*
 * Fork: offline. All fork tables live in their own database (OfflineDatabase), never in upstream's
 * JellyfinDatabase, so upstream schema bumps can be merged without conflicts. Rows reference upstream data
 * by server id / user id / item id only (no foreign keys across databases); stale rows are cleaned up in code.
 */

/**
 * The latest known watch state of an item, used for offline resume, the offline screen and auto delete.
 * When [dirty] is set the state was changed locally and still has to be sent to the server.
 */
@Entity(tableName = "play_state", primaryKeys = ["server_id", "item_id"])
data class PlayStateEntity(
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "item_id") val itemId: UUID,
    @ColumnInfo(name = "position_ticks") val positionTicks: Long,
    @ColumnInfo(name = "played") val played: Boolean,
    /** When the item was last played (epoch millis), null if unknown. */
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
    /** Since when the item counts as played (epoch millis), drives the auto delete expiry. */
    @ColumnInfo(name = "played_at") val playedAt: Long?,
    @ColumnInfo(name = "dirty") val dirty: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Marks a download as started on behalf of a playlist. Only such downloads are removed again when they leave
 * that playlist unwatched.
 */
@Entity(tableName = "download_origin", primaryKeys = ["server_id", "item_id", "playlist_id"])
data class DownloadOriginEntity(
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "item_id") val itemId: UUID,
    @ColumnInfo(name = "playlist_id") val playlistId: UUID,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

enum class PlaylistDownloadMode {
    /** Nothing is downloaded automatically, single videos can still be downloaded by hand. */
    OFF,

    /** New videos are downloaded when the "download new" button is pressed. */
    SEMI,

    /** New videos are downloaded whenever the playlist is shown and periodically in the background. */
    AUTO,
}

/**
 * A playlist known to the app: either one with a download mode, or one that has downloaded videos.
 */
@Entity(tableName = "playlist", primaryKeys = ["server_id", "playlist_id"])
data class PlaylistEntity(
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "playlist_id") val playlistId: UUID,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "mode") val mode: PlaylistDownloadMode = PlaylistDownloadMode.OFF,
    /** Download at most this many videos (in playlist order), null for all. */
    @ColumnInfo(name = "max_items") val maxItems: Int? = null,
    /** Skip videos longer than this many minutes, null for no limit. */
    @ColumnInfo(name = "max_runtime_minutes") val maxRuntimeMinutes: Int? = null,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
)

/**
 * Snapshot of a playlist's entries in order, so the playlist can be shown and played while offline.
 */
@Entity(tableName = "playlist_entry", primaryKeys = ["server_id", "playlist_id", "position"])
data class PlaylistEntryEntity(
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "playlist_id") val playlistId: UUID,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "item_id") val itemId: UUID,
    @ColumnInfo(name = "item") val item: BaseItemDto,
)

/**
 * A video the user marked as "don't download" within a playlist.
 */
@Entity(tableName = "playlist_exclusion", primaryKeys = ["server_id", "playlist_id", "item_id"])
data class PlaylistExclusionEntity(
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "playlist_id") val playlistId: UUID,
    @ColumnInfo(name = "item_id") val itemId: UUID,
)

/**
 * An external (sidecar) subtitle file downloaded next to a downloaded video.
 */
@Entity(tableName = "subtitle_file", primaryKeys = ["server_id", "item_id", "stream_index"])
data class SubtitleFileEntity(
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "item_id") val itemId: UUID,
    @ColumnInfo(name = "stream_index") val streamIndex: Int,
    @ColumnInfo(name = "uri") val uri: Uri,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "language") val language: String?,
    @ColumnInfo(name = "title") val title: String?,
)

/**
 * Full item details (chapters, user data, ...) and media segments of a downloaded video, fetched once the
 * download finished so the player behaves the same while offline.
 */
@Entity(tableName = "item_cache", primaryKeys = ["server_id", "item_id"])
data class ItemCacheEntity(
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "item_id") val itemId: UUID,
    @ColumnInfo(name = "item") val item: BaseItemDto?,
    /** JSON encoded list of media segments. */
    @ColumnInfo(name = "segments") val segments: String?,
    /** Whether the external subtitles of the item were downloaded completely. */
    @ColumnInfo(name = "subtitles_done") val subtitlesDone: Boolean,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
