// Fork: offline. Server, storage and database errors of any kind must not stop the offline sync.
@file:Suppress("TooGenericExceptionCaught")

package org.jellyfin.mobile.offline.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Fork: offline. Runs [OfflineMaintenance] once a network connection is available.
 */
class OfflineSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val maintenance by inject<OfflineMaintenance>()

    private val scheduler by inject<OfflineSyncScheduler>()

    override suspend fun doWork(): Result {
        val includePlaylists = inputData.getBoolean(KEY_INCLUDE_PLAYLISTS, false)
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        return try {
            val complete = maintenance.run(includePlaylists)
            // Retry one-off syncs that couldn't do everything as a separate delayed run, so a pending retry never
            // holds back a sync for new downloads or watch states; periodic work simply runs again later
            if (!complete && attempt < MAX_RETRIES && !tags.contains(TAG_PERIODIC)) {
                scheduler.retryLater(attempt + 1, includePlaylists)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Offline sync failed")
            Result.failure()
        }
    }

    companion object {
        const val KEY_INCLUDE_PLAYLISTS = "include_playlists"
        const val KEY_ATTEMPT = "attempt"
        const val TAG_PERIODIC = "offline_sync_periodic"
        private const val MAX_RETRIES = 5
    }
}

/**
 * Fork: offline. Schedules [OfflineSyncWorker] runs.
 */
class OfflineSyncScheduler(private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Send pending watch states and fetch download extras as soon as possible. One pending run covers all requests;
     * changes made while a run is in progress make it retry (see [OfflineMaintenance.run]).
     */
    fun syncSoon() = enqueue(UNIQUE_SOON, includePlaylists = false, policy = ExistingWorkPolicy.KEEP)

    /**
     * Full run including the playlists, e.g. on app start.
     */
    fun syncAll() = enqueue(UNIQUE_ALL, includePlaylists = true, policy = ExistingWorkPolicy.KEEP)

    fun schedulePeriodic(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf(OfflineSyncWorker.KEY_INCLUDE_PLAYLISTS to true))
            .addTag(OfflineSyncWorker.TAG_PERIODIC)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Run again later after an incomplete run, with a growing delay.
     */
    fun retryLater(attempt: Int, includePlaylists: Boolean) {
        val delaySeconds = RETRY_BASE_SECONDS shl (attempt - 1).coerceIn(0, MAX_RETRY_SHIFT)
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    OfflineSyncWorker.KEY_ATTEMPT to attempt,
                    OfflineSyncWorker.KEY_INCLUDE_PLAYLISTS to includePlaylists,
                ),
            )
            .build()
        // Separate names: a retry without playlists must not replace one with them
        val name = if (includePlaylists) UNIQUE_RETRY_ALL else UNIQUE_RETRY
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    private fun enqueue(name: String, includePlaylists: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(OfflineSyncWorker.KEY_INCLUDE_PLAYLISTS to includePlaylists))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
    }

    private companion object {
        const val UNIQUE_SOON = "offline_sync_soon"
        const val UNIQUE_ALL = "offline_sync_all"
        const val UNIQUE_PERIODIC = "offline_sync_periodic"
        const val UNIQUE_RETRY = "offline_sync_retry"
        const val UNIQUE_RETRY_ALL = "offline_sync_retry_all"
        const val PERIODIC_HOURS = 6L
        const val RETRY_BASE_SECONDS = 30L
        const val MAX_RETRY_SHIFT = 5
    }
}
