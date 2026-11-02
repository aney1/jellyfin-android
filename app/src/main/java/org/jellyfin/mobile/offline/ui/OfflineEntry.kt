package org.jellyfin.mobile.offline.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.offline.LocalCopyResolver
import org.jellyfin.mobile.offline.OfflinePreferences
import org.jellyfin.mobile.utils.extensions.replaceFragment
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fork: offline. Decides what to show when the web app can't reach the server: the offline screen if there
 * are downloads, otherwise upstream's connect screen.
 */
object OfflineEntry : KoinComponent {
    private val preferences: OfflinePreferences by inject()
    private val localCopyResolver: LocalCopyResolver by inject()

    /**
     * @param showConnectScreen upstream's handling, used when the offline screen isn't shown.
     */
    fun onServerUnreachable(fragment: Fragment, showConnectScreen: () -> Unit) {
        if (!preferences.openOfflineScreen) {
            showConnectScreen()
            return
        }
        fragment.lifecycleScope.launch {
            val hasDownloads = localCopyResolver.findAll().isNotEmpty()
            val canReplace = fragment.isAdded &&
                fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !fragment.parentFragmentManager.isStateSaved
            when {
                hasDownloads && canReplace -> fragment.parentFragmentManager.replaceFragment<OfflineFragment>()
                else -> showConnectScreen()
            }
        }
    }
}
