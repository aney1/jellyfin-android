package org.jellyfin.mobile.offline.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.offline.OfflinePreferences

/**
 * Fork: offline. Started once from the application: schedules the background sync and fetches the extras
 * (subtitles, ...) of downloads as soon as they finish.
 */
class OfflineCoordinator(
    private val downloadDao: DownloadDao,
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
            var known = emptySet<Long>()
            downloadDao.getAllDownloads()
                .map { downloads -> downloads.filter { it.status == DownloadStatus.DOWNLOADED }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { finished ->
                    // Only newly finished downloads need their extras; removals (e.g. auto delete) don't
                    if ((finished - known).isNotEmpty()) scheduler.syncSoon()
                    known = finished
                }
        }
    }

    /**
     * The background sync setting changed to [enabled].
     */
    fun onBackgroundSyncChanged(enabled: Boolean) = scheduler.schedulePeriodic(enabled)

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
