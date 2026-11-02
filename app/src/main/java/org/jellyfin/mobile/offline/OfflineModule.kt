package org.jellyfin.mobile.offline

import androidx.room.Room
import org.jellyfin.mobile.offline.data.OfflineDatabase
import org.jellyfin.mobile.offline.sync.OfflineCoordinator
import org.jellyfin.mobile.offline.sync.OfflineMaintenance
import org.jellyfin.mobile.offline.sync.OfflineSyncScheduler
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
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
    single { OfflineMaintenance(get(), get()) }
    single { OfflineCoordinator(get(), get(), get()) }
}
