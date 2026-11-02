package org.jellyfin.mobile.offline

import androidx.room.Room
import org.jellyfin.mobile.offline.data.OfflineDatabase
import org.jellyfin.mobile.offline.playback.OfflinePlayback
import org.jellyfin.mobile.offline.playback.OfflineQueueSupport
import org.jellyfin.mobile.offline.playlist.PlaylistSync
import org.jellyfin.mobile.offline.sync.DownloadExtras
import org.jellyfin.mobile.offline.sync.OfflineCoordinator
import org.jellyfin.mobile.offline.sync.OfflineDownloads
import org.jellyfin.mobile.offline.sync.OfflineMaintenance
import org.jellyfin.mobile.offline.sync.OfflineSyncScheduler
import org.jellyfin.mobile.offline.sync.PlayStateSync
import org.jellyfin.mobile.offline.ui.OfflineViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Fork: offline. Everything of the offline features, registered next to upstream's modules.
 */
val offlineModule = module {
    single {
        Room.databaseBuilder(androidApplication(), OfflineDatabase::class.java, OfflineDatabase.NAME)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()
    }
    single { get<OfflineDatabase>().offlineDao }

    single { OfflinePreferences(androidContext()) }
    single { OfflineApiClients(get(), get()) }
    single { LocalCopyResolver(androidContext(), get()) }
    single { OfflineSyncScheduler(androidContext()) }
    single { OfflinePlayback(get(), get(), get(), get()) }
    single { OfflineQueueSupport(get(), get(), get(), get(), get()) }
    single { PlayStateSync(get(), get()) }
    single { DownloadExtras(androidContext(), get(), get()) }
    single { OfflineDownloads(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { PlaylistSync(get(), get(), get(), get(), get(), get()) }
    single { OfflineMaintenance(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { OfflineCoordinator(get(), get(), get()) }

    viewModel { OfflineViewModel(get(), get(), get(), get(), get()) }
}
