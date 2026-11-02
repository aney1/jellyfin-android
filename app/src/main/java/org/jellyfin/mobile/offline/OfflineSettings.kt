package org.jellyfin.mobile.offline

import de.Maxr1998.modernpreferences.PreferenceScreen
import de.Maxr1998.modernpreferences.helpers.categoryHeader
import de.Maxr1998.modernpreferences.helpers.checkBox
import de.Maxr1998.modernpreferences.helpers.defaultOnCheckedChange
import de.Maxr1998.modernpreferences.helpers.singleChoice
import de.Maxr1998.modernpreferences.preferences.choice.SelectionItem
import org.jellyfin.mobile.R
import org.jellyfin.mobile.offline.sync.OfflineCoordinator
import org.koin.core.context.GlobalContext

/**
 * Fork: offline. The settings section of the offline features, added to upstream's settings screen with a single
 * call so the settings fragment barely changes.
 */
fun PreferenceScreen.Builder.offlineSettings() {
    categoryHeader(PREF_CATEGORY_OFFLINE) {
        titleRes = R.string.pref_category_offline
    }
    checkBox(OfflinePreferences.PREF_PREFER_DOWNLOADS) {
        titleRes = R.string.pref_offline_prefer_downloads
        summaryRes = R.string.pref_offline_prefer_downloads_summary
        defaultValue = true
    }
    checkBox(OfflinePreferences.PREF_PLAYLIST_CONTROLS) {
        titleRes = R.string.pref_offline_playlist_controls
        summaryRes = R.string.pref_offline_playlist_controls_summary
        defaultValue = true
    }
    checkBox(OfflinePreferences.PREF_BACKGROUND_SYNC) {
        titleRes = R.string.pref_offline_background_sync
        summaryRes = R.string.pref_offline_background_sync_summary
        defaultValue = true
        defaultOnCheckedChange {
            GlobalContext.get().get<OfflineCoordinator>().onSettingsChanged()
        }
    }
    checkBox(OfflinePreferences.PREF_OPEN_OFFLINE_SCREEN) {
        titleRes = R.string.pref_offline_open_offline_screen
        summaryRes = R.string.pref_offline_open_offline_screen_summary
        defaultValue = true
    }
    val deleteOptions = listOf(
        SelectionItem(OfflinePreferences.DELETE_NEVER.toString(), R.string.pref_offline_delete_never),
        SelectionItem("0", R.string.pref_offline_delete_immediately),
        SelectionItem("1", R.string.pref_offline_delete_1_day),
        SelectionItem("3", R.string.pref_offline_delete_3_days),
        SelectionItem("7", R.string.pref_offline_delete_7_days),
        SelectionItem("14", R.string.pref_offline_delete_14_days),
        SelectionItem("30", R.string.pref_offline_delete_30_days),
    )
    singleChoice(OfflinePreferences.PREF_DELETE_WATCHED_AFTER, deleteOptions) {
        titleRes = R.string.pref_offline_delete_watched
        initialSelection = OfflinePreferences.DEFAULT_DELETE_WATCHED_AFTER_DAYS.toString()
    }
}

private const val PREF_CATEGORY_OFFLINE = "pref_category_offline"
