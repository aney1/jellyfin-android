package org.jellyfin.mobile.offline.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
        scheduler.syncAll()

        scope.launch {
            downloadDao.getAllDownloads()
                .map { downloads -> downloads.filter { it.status == DownloadStatus.DOWNLOADED }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .drop(1)
                .debounce(DEBOUNCE_MS)
                .collect { scheduler.syncSoon() }
        }
    }

    /**
     * The background sync setting changed.
     */
    fun onSettingsChanged() = scheduler.schedulePeriodic(preferences.backgroundSync)

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
