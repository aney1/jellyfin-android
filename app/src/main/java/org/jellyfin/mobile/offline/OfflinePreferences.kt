package org.jellyfin.mobile.offline

import android.content.Context
import android.content.SharedPreferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Fork: offline. Settings of the offline features. Uses the same preference file as upstream's AppPreferences
 * (which the settings screen writes to), with fork-only keys.
 */
class OfflinePreferences(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    /** Play the downloaded copy of a video instead of streaming it, when one exists. */
    val preferDownloads: Boolean
        get() = sharedPreferences.getBoolean(PREF_PREFER_DOWNLOADS, true)

    /** Open the offline screen when the server can't be reached and there are downloads. */
    val openOfflineScreen: Boolean
        get() = sharedPreferences.getBoolean(PREF_OPEN_OFFLINE_SCREEN, true)

    /** Show the download controls on playlist pages. */
    val playlistControls: Boolean
        get() = sharedPreferences.getBoolean(PREF_PLAYLIST_CONTROLS, true)

    /** Sync playlists in automatic mode periodically in the background. */
    val backgroundSync: Boolean
        get() = sharedPreferences.getBoolean(PREF_BACKGROUND_SYNC, true)

    /**
     * How long a watched download is kept, null to keep watched downloads forever.
     */
    val deleteWatchedAfter: Duration?
        get() {
            val days = sharedPreferences.getString(PREF_DELETE_WATCHED_AFTER, null)?.toIntOrNull() ?: DEFAULT_DELETE_WATCHED_AFTER_DAYS
            return if (days < 0) null else days.days
        }

    /**
     * Watching before this time (epoch millis) never makes a download expire, so updating to a version with auto
     * delete doesn't remove everything that was watched before. Set when first asked for.
     */
    val deleteCountFrom: Long
        get() {
            val stored = sharedPreferences.getLong(PREF_DELETE_COUNT_FROM, 0)
            if (stored > 0) return stored
            val now = System.currentTimeMillis()
            sharedPreferences.edit().putLong(PREF_DELETE_COUNT_FROM, now).apply()
            return now
        }

    companion object {
        const val PREF_PREFER_DOWNLOADS = "pref_offline_prefer_downloads"
        const val PREF_OPEN_OFFLINE_SCREEN = "pref_offline_open_offline_screen"
        const val PREF_PLAYLIST_CONTROLS = "pref_offline_playlist_controls"
        const val PREF_BACKGROUND_SYNC = "pref_offline_background_sync"
        const val PREF_DELETE_WATCHED_AFTER = "pref_offline_delete_watched_after"
        private const val PREF_DELETE_COUNT_FROM = "pref_offline_delete_count_from"

        const val DEFAULT_DELETE_WATCHED_AFTER_DAYS = 1
        const val DELETE_NEVER = -1
    }
}
