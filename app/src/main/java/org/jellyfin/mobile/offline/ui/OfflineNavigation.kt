package org.jellyfin.mobile.offline.ui

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.add
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.ui.PlayerFragment

/**
 * Fork: offline. Switches between the web app and the offline screen without touching an open player.
 *
 * Upstream's replaceFragment would also remove the PlayerFragment that lives in the same container, and adding the
 * new screen above the player would make it the fragment that receives back presses (MainActivity routes them to
 * the last added fragment). So while a player is open the switch waits until it is closed.
 */
object OfflineNavigation {
    /**
     * The native player in [fragmentManager], if any.
     */
    fun player(fragmentManager: FragmentManager): PlayerFragment? =
        fragmentManager.fragments.filterIsInstance<PlayerFragment>().firstOrNull()

    /**
     * Replace [current] with a new [T], right away or once the open player is closed.
     *
     * @return false if the switch waits for the player to be closed.
     */
    inline fun <reified T : Fragment> swap(current: Fragment, args: Bundle? = null): Boolean {
        val fragmentManager = current.parentFragmentManager
        val switch = {
            if (current.isAdded && !fragmentManager.isStateSaved) {
                fragmentManager.beginTransaction()
                    .remove(current)
                    .add<T>(R.id.fragment_container, args = args)
                    .commit()
            }
        }
        if (player(fragmentManager) == null) {
            switch()
            return true
        }
        afterPlayerClosed(fragmentManager, switch)
        return false
    }

    fun afterPlayerClosed(fragmentManager: FragmentManager, action: () -> Unit) {
        fragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                    if (f !is PlayerFragment || player(fm) != null) return
                    fm.unregisterFragmentLifecycleCallbacks(this)
                    action()
                }
            },
            false,
        )
    }
}
