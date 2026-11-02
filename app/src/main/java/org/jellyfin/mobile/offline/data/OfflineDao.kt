package org.jellyfin.mobile.offline.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
@Suppress("TooManyFunctions")
interface OfflineDao {
    // Play state

    @Query("SELECT * FROM play_state WHERE server_id = :serverId AND user_id = :userId AND item_id = :itemId")
    suspend fun getPlayState(serverId: Long, userId: Long, itemId: UUID): PlayStateEntity?

    /**
     * Whether any user of the server watched the item, used where the owning user isn't known.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM play_state WHERE server_id = :serverId AND item_id = :itemId AND played = 1)")
    suspend fun isPlayedByAnyUser(serverId: Long, itemId: UUID): Boolean

    @Query("SELECT * FROM play_state")
    fun observePlayStates(): Flow<List<PlayStateEntity>>

    @Query("SELECT * FROM play_state WHERE dirty = 1")
    suspend fun getDirtyPlayStates(): List<PlayStateEntity>

    @Upsert
    suspend fun upsertPlayState(entity: PlayStateEntity)

    /**
     * Clear the dirty flag only if the row didn't change since it was read, so a newer local change made while
     * syncing is not lost.
     */
    @Query(
        "UPDATE play_state SET dirty = 0 " +
            "WHERE server_id = :serverId AND user_id = :userId AND item_id = :itemId AND updated_at = :updatedAt",
    )
    suspend fun markPlayStateClean(serverId: Long, userId: Long, itemId: UUID, updatedAt: Long): Int

    @Query("SELECT * FROM play_state WHERE dirty = 0")
    suspend fun getCleanPlayStates(): List<PlayStateEntity>

    @Query("DELETE FROM play_state WHERE server_id = :serverId AND user_id = :userId AND item_id = :itemId")
    suspend fun deletePlayState(serverId: Long, userId: Long, itemId: UUID)

    // Download origins

    @Query("SELECT * FROM download_origin")
    suspend fun getDownloadOrigins(): List<DownloadOriginEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDownloadOrigins(entities: List<DownloadOriginEntity>)

    @Query("DELETE FROM download_origin WHERE server_id = :serverId AND item_id = :itemId")
    suspend fun deleteDownloadOrigins(serverId: Long, itemId: UUID)

    // Playlists

    @Query("SELECT * FROM playlist WHERE server_id = :serverId AND playlist_id = :playlistId")
    suspend fun getPlaylist(serverId: Long, playlistId: UUID): PlaylistEntity?

    @Query("SELECT * FROM playlist")
    suspend fun getPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Upsert
    suspend fun upsertPlaylist(entity: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE server_id = :serverId AND playlist_id = :playlistId")
    suspend fun deletePlaylist(serverId: Long, playlistId: UUID)

    @Query("SELECT * FROM playlist_entry WHERE server_id = :serverId AND playlist_id = :playlistId ORDER BY position")
    suspend fun getPlaylistEntries(serverId: Long, playlistId: UUID): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entry ORDER BY playlist_id, position")
    suspend fun getAllPlaylistEntries(): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entry ORDER BY playlist_id, position")
    fun observeAllPlaylistEntries(): Flow<List<PlaylistEntryEntity>>

    @Query("DELETE FROM playlist_entry WHERE server_id = :serverId AND playlist_id = :playlistId")
    suspend fun deletePlaylistEntries(serverId: Long, playlistId: UUID)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistEntries(entities: List<PlaylistEntryEntity>)

    @Transaction
    suspend fun replacePlaylistEntries(serverId: Long, playlistId: UUID, entries: List<PlaylistEntryEntity>) {
        deletePlaylistEntries(serverId, playlistId)
        insertPlaylistEntries(entries)
    }

    @Query("SELECT * FROM playlist_exclusion WHERE server_id = :serverId AND playlist_id = :playlistId")
    suspend fun getExclusions(serverId: Long, playlistId: UUID): List<PlaylistExclusionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExclusion(entity: PlaylistExclusionEntity)

    @Query("DELETE FROM playlist_exclusion WHERE server_id = :serverId AND playlist_id = :playlistId AND item_id = :itemId")
    suspend fun deleteExclusion(serverId: Long, playlistId: UUID, itemId: UUID)

    // Subtitles

    @Query("SELECT * FROM subtitle_file WHERE server_id = :serverId AND item_id = :itemId ORDER BY stream_index")
    suspend fun getSubtitleFiles(serverId: Long, itemId: UUID): List<SubtitleFileEntity>

    @Upsert
    suspend fun upsertSubtitleFile(entity: SubtitleFileEntity)

    // Item cache

    @Query("SELECT * FROM item_cache WHERE server_id = :serverId AND item_id = :itemId")
    suspend fun getItemCache(serverId: Long, itemId: UUID): ItemCacheEntity?

    @Query("SELECT * FROM item_cache WHERE item_id = :itemId LIMIT 1")
    suspend fun getItemCacheByItemId(itemId: UUID): ItemCacheEntity?

    @Query("SELECT * FROM item_cache")
    suspend fun getItemCaches(): List<ItemCacheEntity>

    @Upsert
    suspend fun upsertItemCache(entity: ItemCacheEntity)

    // Cleanup of everything that belongs to a removed download

    @Transaction
    suspend fun deleteItemData(serverId: Long, itemId: UUID) {
        deleteSubtitleFiles(serverId, itemId)
        deleteItemCache(serverId, itemId)
        deleteDownloadOrigins(serverId, itemId)
    }

    @Query("DELETE FROM subtitle_file WHERE server_id = :serverId AND item_id = :itemId")
    suspend fun deleteSubtitleFiles(serverId: Long, itemId: UUID)

    @Query("DELETE FROM item_cache WHERE server_id = :serverId AND item_id = :itemId")
    suspend fun deleteItemCache(serverId: Long, itemId: UUID)
}
