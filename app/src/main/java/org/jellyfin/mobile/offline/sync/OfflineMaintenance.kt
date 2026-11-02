package org.jellyfin.mobile.offline.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.sdk.api.client.ApiClient

/**
 * Fork: offline. One maintenance pass, run by [OfflineSyncWorker]. The individual tasks are added by the
 * following offline features.
 */
class OfflineMaintenance(
    private val apiClient: ApiClient,
    private val apiClientController: ApiClientController,
) {
    private val mutex = Mutex()

    /**
     * @return true if everything that needs the server was done.
     */
    @Suppress("UnusedParameter")
    suspend fun run(includePlaylists: Boolean): Boolean = mutex.withLock {
        // A worker may run without the app being open, the global api client is configured by the activity
        if (apiClient.accessToken == null) {
            runCatching { apiClientController.loadSavedServerUser() }
        }
        true
    }
}
