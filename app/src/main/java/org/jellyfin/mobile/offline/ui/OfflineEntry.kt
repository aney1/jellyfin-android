package org.jellyfin.mobile.offline.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.offline.LocalCopyResolver
import org.jellyfin.mobile.offline.OfflinePreferences
import org.jellyfin.mobile.webapp.WebViewFragment
import org.jellyfin.sdk.model.api.MediaType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * Fork: offline. Decides what to show when the web app can't reach the server: the offline screen if there
 * are downloaded videos, otherwise upstream's connect screen.
 */
object OfflineEntry : KoinComponent {
    private const val PING_TIMEOUT_SECONDS = 3L

    private val preferences: OfflinePreferences by inject()
    private val appPreferences: AppPreferences by inject()
    private val serverDao: ServerDao by inject()
    private val localCopyResolver: LocalCopyResolver by inject()
    private val okHttpClient: OkHttpClient by inject()

    private val pingClient by lazy {
        okHttpClient.newBuilder().callTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

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
        // The web view's server (lateinit, may not be set yet), otherwise the one the app uses
        val hostname = runCatching { (fragment as? WebViewFragment)?.server?.hostname }.getOrNull() ?: currentHostname()
        // Some web app errors happen while the server is fine (e.g. missing credentials): keep upstream's handling
        val offline = !isServerReachable(hostname) && hasOfflineContent()
        // The fragment may have been replaced in the meantime
        if (!fragment.isAdded) return
        val fragmentManager = fragment.parentFragmentManager
        val canSwitch = fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !fragmentManager.isStateSaved
        when {
            !offline -> showConnectScreen()
            !canSwitch -> Unit
            // Below a mini player right away; with a full screen player once it is closed, if still unreachable
            else -> OfflineNavigation.swap(fragment, OfflineFragment::class.java, underMiniPlayer = true) {
                !isServerReachable(hostname)
            }
        }
    }

    /**
     * Whether the offline screen has something to show: a downloaded video.
     */
    suspend fun hasOfflineContent(): Boolean =
        localCopyResolver.findAll().values.any { it.download.item.mediaType == MediaType.VIDEO }

    /**
     * The address of the server the app uses.
     */
    suspend fun currentHostname(): String? = withContext(Dispatchers.IO) {
        appPreferences.currentServerId?.let { serverDao.getServer(it) }?.hostname
    }

    /**
     * Whether the server at [hostname] answers. Uses the public system info, so no signed-in user is needed.
     */
    suspend fun isServerReachable(hostname: String?): Boolean = withContext(Dispatchers.IO) {
        val url = hostname?.trimEnd('/')?.let { "$it/System/Info/Public" } ?: return@withContext false
        try {
            val request = Request.Builder().url(url).build()
            pingClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            Timber.d("Server not reachable: %s", e.message)
            false
        } catch (e: IllegalArgumentException) {
            Timber.d("Invalid server address: %s", e.message)
            false
        }
    }
}
