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

    companion object {
        const val PREF_PREFER_DOWNLOADS = "pref_offline_prefer_downloads"
        const val PREF_OPEN_OFFLINE_SCREEN = "pref_offline_open_offline_screen"
        const val PREF_PLAYLIST_CONTROLS = "pref_offline_playlist_controls"
        const val PREF_BACKGROUND_SYNC = "pref_offline_background_sync"
        const val PREF_DELETE_WATCHED_AFTER = "pref_offline_delete_watched_after"

        const val DEFAULT_DELETE_WATCHED_AFTER_DAYS = 1
        const val DELETE_NEVER = -1
    }
}
