package org.jellyfin.mobile.offline.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.offline.LocalCopyResolver
import org.jellyfin.mobile.offline.OfflineApiClients
import org.jellyfin.mobile.offline.OfflinePreferences
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.model.api.MediaType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Collections
import java.util.WeakHashMap

/**
 * Fork: offline. Decides what to show when the web app can't reach the server: the offline screen if there
 * are downloaded videos, otherwise upstream's connect screen.
 */
object OfflineEntry : KoinComponent {
    private const val PING_TIMEOUT_MS = 3_000L

    private val preferences: OfflinePreferences by inject()
    private val appPreferences: AppPreferences by inject()
    private val localCopyResolver: LocalCopyResolver by inject()
    private val apiClients: OfflineApiClients by inject()

    private val handling: MutableSet<Fragment> = Collections.newSetFromMap(WeakHashMap())

    /**
     * @param showConnectScreen upstream's handling, used when the offline screen isn't shown.
     */
    fun onServerUnreachable(fragment: Fragment, showConnectScreen: () -> Unit) {
        if (!preferences.openOfflineScreen) {
            showConnectScreen()
            return
        }
        // The web view reports errors more than once (error callback and connection timeout), handle one at a time
        if (!handling.add(fragment)) return
        fragment.lifecycleScope.launch {
            try {
                handle(fragment, showConnectScreen)
            } finally {
                handling.remove(fragment)
            }
        }
    }

    private suspend fun handle(fragment: Fragment, showConnectScreen: () -> Unit) {
        // Some web app errors happen while the server is fine (e.g. missing credentials): keep upstream's handling
        val offline = !isServerReachable() && hasOfflineContent()
        // The fragment may have been replaced in the meantime
        if (!fragment.isAdded) return
        val fragmentManager = fragment.parentFragmentManager
        val canSwitch = fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !fragmentManager.isStateSaved
        when {
            !offline -> showConnectScreen()
            // Don't cover a video in full screen; the offline screen shows up on the next connection error
            !canSwitch || OfflineNavigation.isFullscreenPlayerOpen(fragmentManager) -> Unit
            else -> OfflineNavigation.swap<OfflineFragment>(fragment)
        }
    }

    /**
     * Whether the offline screen has something to show: a downloaded video.
     */
    suspend fun hasOfflineContent(): Boolean =
        localCopyResolver.findAll().values.any { it.download.item.mediaType == MediaType.VIDEO }

    /**
     * Whether the server the app is signed in to answers.
     */
    suspend fun isServerReachable(): Boolean = withContext(Dispatchers.IO) {
        val serverId = appPreferences.currentServerId ?: return@withContext false
        val userId = appPreferences.currentUserId ?: return@withContext false
        val target = apiClients.get(serverId, userId) ?: return@withContext false
        withTimeoutOrNull(PING_TIMEOUT_MS) {
            runCatching { target.api.systemApi.getPublicSystemInfo() }.isSuccess
        } ?: false
    }
}
