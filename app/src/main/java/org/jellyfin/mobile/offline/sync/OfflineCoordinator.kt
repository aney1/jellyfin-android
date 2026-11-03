package org.jellyfin.mobile.offline.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.offline.OfflinePreferences
import org.jellyfin.mobile.offline.data.OfflineDao
import java.util.UUID

/**
 * Fork: offline. Started once from the application: schedules the background sync and fetches the extras
 * (subtitles, ...) of downloads as soon as they finish.
 */
class OfflineCoordinator(
    private val downloadDao: DownloadDao,
    private val offlineDao: OfflineDao,
    private val preferences: OfflinePreferences,
    private val scheduler: OfflineSyncScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(FlowPreview::class)
    fun start() {
        scheduler.schedulePeriodic(preferences.backgroundSync)
        // Auto delete only counts watching from now on
        preferences.deleteCountFrom

        // Full sync whenever the app comes to the foreground, not on every process start (background wake-ups
        // by the periodic job, downloads or media buttons already do their own work)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = scheduler.syncAll()
            },
        )

        scope.launch {
            var known: Set<Pair<Long, UUID>>? = null
            downloadDao.getAllDownloads()
                .map { downloads ->
                    downloads.filter { it.status == DownloadStatus.DOWNLOADED }.map { it.serverId to it.itemId }.toSet()
                }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { finished ->
                    // Only newly finished downloads need their extras; removals (e.g. auto delete) don't. After a
                    // process start, only downloads whose extras are still missing.
                    val new = known?.let { finished - it } ?: (finished - completeExtras())
                    if (new.isNotEmpty()) scheduler.syncSoon()
                    known = finished
                }
        }
    }

    private suspend fun completeExtras(): Set<Pair<Long, UUID>> = offlineDao.getItemCaches()
        .filter { it.item != null && it.subtitlesDone }
        .map { it.serverId to it.itemId }
        .toSet()

    /**
     * The background sync setting changed to [enabled].
     */
    fun onBackgroundSyncChanged(enabled: Boolean) = scheduler.schedulePeriodic(enabled)

    /**
     * The delete period changed: run the cleanup soon. Delayed, the setting is stored after this is called.
     */
    fun onDeleteSettingChanged() {
        scope.launch {
            delay(SETTING_APPLY_DELAY_MS)
            scheduler.syncSoon()
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 2_000L
        const val SETTING_APPLY_DELAY_MS = 1_000L
    }
}
