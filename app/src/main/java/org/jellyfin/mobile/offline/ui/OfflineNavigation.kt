package org.jellyfin.mobile.offline.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.add
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.ui.PlayerFragment

/**
 * Fork: offline. Switches between the web app and the offline screen without touching an open player: upstream's
 * replaceFragment would also remove the PlayerFragment that lives in the same container.
 */
object OfflineNavigation {
    /**
     * The native player in [fragmentManager], if any.
     */
    fun player(fragmentManager: FragmentManager): PlayerFragment? =
        fragmentManager.fragments.filterIsInstance<PlayerFragment>().firstOrNull()

    /**
     * Whether a player is open in full screen (a switch would cover it).
     */
    fun isFullscreenPlayerOpen(fragmentManager: FragmentManager): Boolean {
        val player = player(fragmentManager) ?: return false
        val miniControls = player.view?.findViewById<View>(R.id.mini_player_controls)
        return miniControls?.isVisible != true
    }

    /**
     * Replace [current] with a new [T], keeping a (mini) player on top.
     */
    inline fun <reified T : Fragment> swap(current: Fragment, args: Bundle? = null) {
        val fragmentManager = current.parentFragmentManager
        val decorView = current.requireActivity().window.decorView
        fragmentManager.beginTransaction()
            .remove(current)
            .add<T>(R.id.fragment_container, args = args)
            .commit()
        // Posted so it runs after the transaction added the new screen
        decorView.post {
            player(fragmentManager)?.view?.let { playerView ->
                playerView.bringToFront()
                (playerView.parent as? ViewGroup)?.invalidate()
            }
        }
    }
}
