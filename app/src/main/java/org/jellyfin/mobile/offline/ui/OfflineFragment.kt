package org.jellyfin.mobile.offline.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ServerState
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.webapp.WebViewFragment
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Fork: offline. Shown instead of the connect screen when the server can't be reached and there are downloads:
 * downloaded playlists and videos, played with the regular native player (mini player included).
 */
class OfflineFragment : ComposeFragment(), BackPressInterceptor {
    private val viewModel: OfflineViewModel by viewModel()
    private val mainViewModel: MainViewModel by activityViewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.openPlaylist.collectLatest {
                (activity as? MainActivity)?.updateBackPressedCallback()
            }
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val openPlaylist by viewModel.openPlaylist.collectAsState()
        val serverReachable by viewModel.serverReachable.collectAsState()

        OfflineScreen(
            state = state,
            openPlaylist = openPlaylist,
            serverReachable = serverReachable,
            onOpenPlaylist = { viewModel.openPlaylist(it.id) },
            onClosePlaylist = { viewModel.openPlaylist(null) },
            onPlayPlaylist = viewModel::playPlaylist,
            onPlayVideo = viewModel::playVideo,
            onReconnect = ::reconnect,
            onChangeServer = viewModel::changeServer,
        )
    }

    private fun reconnect() {
        val server = (mainViewModel.serverState.value as? ServerState.Available)?.server ?: return
        if (!isAdded || parentFragmentManager.isStateSaved) return
        // Keeps a (mini) player that is playing a download: then the web app comes once it is closed
        val switched = OfflineNavigation.swap(
            current = this,
            target = WebViewFragment::class.java,
            args = Bundle().apply {
                putParcelable(Constants.FRAGMENT_WEB_VIEW_EXTRA_SERVER, server)
            },
        )
        if (!switched) {
            Toast.makeText(requireContext(), R.string.offline_reconnect_after_player, Toast.LENGTH_SHORT).show()
        }
    }

    override val canInterceptBackPress: Boolean
        get() = viewModel.isPlaylistOpen

    override fun onInterceptBackPressed(): Boolean {
        if (!viewModel.isPlaylistOpen) return false
        viewModel.openPlaylist(null)
        return true
    }
}
