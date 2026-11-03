// Fork: offline. Server, storage and database errors of any kind must not stop the offline sync.
@file:Suppress("TooGenericExceptionCaught")

package org.jellyfin.mobile.offline.sync

import android.content.Context
import androidx.work.BackoffPolicy
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

    override suspend fun doWork(): Result {
        val includePlaylists = inputData.getBoolean(KEY_INCLUDE_PLAYLISTS, false)
        return try {
            val complete = maintenance.run(includePlaylists)
            // Retry one-off syncs that couldn't send everything; periodic work simply runs again later
            val done = complete || runAttemptCount >= MAX_RETRIES || tags.contains(TAG_PERIODIC)
            if (done) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Offline sync failed")
            Result.failure()
        }
    }

    companion object {
        const val KEY_INCLUDE_PLAYLISTS = "include_playlists"
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

    private fun enqueue(name: String, includePlaylists: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(OfflineSyncWorker.KEY_INCLUDE_PLAYLISTS to includePlaylists))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
    }

    private companion object {
        const val UNIQUE_SOON = "offline_sync_soon"
        const val UNIQUE_ALL = "offline_sync_all"
        const val UNIQUE_PERIODIC = "offline_sync_periodic"
        const val PERIODIC_HOURS = 6L
        const val BACKOFF_SECONDS = 30L
    }
}
