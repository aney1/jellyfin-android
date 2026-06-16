package org.jellyfin.mobile.utils

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener

/**
 * Listener that watches the current device orientation.
 * It makes sure that the orientation sensor can still be used (if enabled)
 * after toggling the orientation manually.
 */
class SmartOrientationListener(private val activity: Activity) : OrientationEventListener(activity) {
    override fun onOrientationChanged(orientation: Int) {
        if (orientation == ORIENTATION_UNKNOWN) return

        if (activity.isAutoRotateOn()) {
            val isAtTarget = when (activity.requestedOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> orientation in Constants.ORIENTATION_PORTRAIT_RANGE
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> orientation in Constants.ORIENTATION_LANDSCAPE_RANGE
                else -> false
            }
            if (isAtTarget) {
                // Reset to unspecified orientation
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            return
        }

        // System rotation is locked. While the player is shown in landscape, still allow flipping
        // between landscape and reverse-landscape via the sensor, like the YouTube app.
        // SCREEN_ORIENTATION_SENSOR_LANDSCAPE follows the sensor for both landscape orientations and
        // ignores the rotation lock, while leaving the landscape <-> portrait flow unchanged (that
        // stays controlled by the fullscreen button).
        val isCurrentlyLandscape =
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (
            isCurrentlyLandscape &&
            orientation in Constants.ORIENTATION_LANDSCAPE_RANGE &&
            activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
}
