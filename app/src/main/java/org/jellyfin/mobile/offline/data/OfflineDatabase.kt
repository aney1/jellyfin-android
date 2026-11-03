package org.jellyfin.mobile.offline.data

import android.net.Uri
import androidx.core.net.toUri
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/**
 * Fork: offline. Separate from upstream's JellyfinDatabase on purpose, see OfflineEntities.kt.
 */
@Database(
    entities = [
        PlayStateEntity::class,
        DownloadOriginEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        PlaylistExclusionEntity::class,
        SubtitleFileEntity::class,
        ItemCacheEntity::class,
    ],
    version = 1,
)
@TypeConverters(OfflineDatabase.Converters::class)
abstract class OfflineDatabase : RoomDatabase() {
    abstract val offlineDao: OfflineDao

    class Converters {
        @TypeConverter
        fun fromUuid(uuid: UUID?): String? = uuid?.toString()

        @TypeConverter
        fun toUuid(value: String?): UUID? = value?.toUUIDOrNull()

        @TypeConverter
        fun fromBaseItemDto(baseItem: BaseItemDto?): String? =
            baseItem?.let { OfflineJson.encodeToString(BaseItemDto.serializer(), it) }

        @TypeConverter
        fun toBaseItemDto(json: String?): BaseItemDto? =
            json?.let { OfflineJson.decodeFromString(BaseItemDto.serializer(), it) }

        @TypeConverter
        fun fromUri(uri: Uri?): String? = uri?.toString()

        @TypeConverter
        fun toUri(value: String?): Uri? = value?.toUri()

        @TypeConverter
        fun fromMode(mode: PlaylistDownloadMode?): String? = mode?.name

        @TypeConverter
        fun toMode(value: String?): PlaylistDownloadMode? = value?.let { name ->
            PlaylistDownloadMode.entries.find { it.name == name } ?: PlaylistDownloadMode.OFF
        }
    }

    companion object {
        const val NAME = "offline"
    }
}

/**
 * Lenient JSON for stored server objects, so SDK model changes between app updates don't break old rows.
 */
val OfflineJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}
