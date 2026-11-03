package org.jellyfin.mobile.offline.ui

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.ui.PlayerFragment
import java.util.Collections
import java.util.WeakHashMap

/**
 * Fork: offline. Switches between the web app and the offline screen without touching an open player.
 *
 * Upstream's replaceFragment would also remove the PlayerFragment that lives in the same container, and adding the
 * new screen above the player would make it the fragment that receives back presses (MainActivity routes them to
 * the last added fragment). So while a full screen player is open the switch waits until it is closed. Below a
 * mini player a screen that hands back presses on to the player (the offline screen) can switch right away.
 */
object OfflineNavigation {
    /** Screens with a switch waiting for the player to close, one per screen. */
    private val pending: MutableSet<Fragment> = Collections.newSetFromMap(WeakHashMap())

    /**
     * The native player in [fragmentManager], if any.
     */
    fun player(fragmentManager: FragmentManager): PlayerFragment? =
        fragmentManager.fragments.filterIsInstance<PlayerFragment>().firstOrNull()

    /**
     * Replace [current] with a new [target], right away or once the open player is closed.
     *
     * @param underMiniPlayer switch right away below a mini player; [target] then gets the back presses and has
     * to pass them on to the player (see [OfflineFragment.onInterceptBackPressed]).
     * @param stillWanted checked again before a switch that had to wait, the situation may have changed by then.
     * @return false if the switch waits for the player to be closed.
     */
    fun swap(
        current: Fragment,
        target: Class<out Fragment>,
        args: Bundle? = null,
        underMiniPlayer: Boolean = false,
        stillWanted: suspend () -> Boolean = { true },
    ): Boolean {
        val fragmentManager = current.parentFragmentManager
        val player = player(fragmentManager)
        if (player == null) {
            switch(current, fragmentManager, target, args)
            return true
        }
        if (underMiniPlayer && player.isMiniPlayer) {
            switch(current, fragmentManager, target, args)
            // Keep the mini player floating above the new screen, once the transaction has added it
            player.requireActivity().window.decorView.post { player.bringMiniPlayerToFront() }
            return true
        }
        if (!pending.add(current)) return false
        afterPlayerClosed(fragmentManager) {
            pending.remove(current)
            if (!current.isAdded) return@afterPlayerClosed
            current.lifecycleScope.launch {
                if (stillWanted()) switch(current, fragmentManager, target, args)
            }
        }
        return false
    }

    private fun switch(
        current: Fragment,
        fragmentManager: FragmentManager,
        target: Class<out Fragment>,
        args: Bundle?,
    ) {
        if (!current.isAdded || fragmentManager.isStateSaved) return
        fragmentManager.beginTransaction()
            .remove(current)
            .add(R.id.fragment_container, target, args)
            .commit()
    }

    private fun afterPlayerClosed(fragmentManager: FragmentManager, action: () -> Unit) {
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
