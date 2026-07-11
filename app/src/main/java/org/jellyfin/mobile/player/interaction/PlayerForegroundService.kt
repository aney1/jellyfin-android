package org.jellyfin.mobile.player.interaction

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.getSystemService
import org.jellyfin.mobile.R
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.VIDEO_PLAYER_NOTIFICATION_ID
import org.jellyfin.mobile.utils.createMediaNotificationChannel

/**
 * Foreground service that keeps the app process alive while the native video player
 * plays with the screen locked or the app in the background.
 *
 * Without it, the process only holds cached priority once the activity stops, so the
 * system freezes it (and thereby playback) a few seconds after the screen turns off.
 *
 * The service reuses the media notification posted by [PlayerNotificationHelper]
 * (same notification ID), which continues to update it via [NotificationManager.notify].
 */
class PlayerForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = activePlayerNotification() ?: buildFallbackNotification()
        if (AndroidVersion.isAtLeastQ) {
            startForeground(VIDEO_PLAYER_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(VIDEO_PLAYER_NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    /**
     * Returns the notification already posted by [PlayerNotificationHelper], if any,
     * so that promoting it to a foreground notification causes no visible change.
     */
    private fun activePlayerNotification(): Notification? = when {
        AndroidVersion.isAtLeastM -> getSystemService<NotificationManager>()
            ?.activeNotifications
            ?.firstOrNull { statusBarNotification -> statusBarNotification.id == VIDEO_PLAYER_NOTIFICATION_ID }
            ?.notification
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun buildFallbackNotification(): Notification {
        getSystemService<NotificationManager>()?.let(::createMediaNotificationChannel)
        return Notification.Builder(this).apply {
            if (AndroidVersion.isAtLeastO) {
                setChannelId(Constants.MEDIA_NOTIFICATION_CHANNEL_ID)
            } else {
                setPriority(Notification.PRIORITY_LOW)
            }
            setSmallIcon(R.drawable.ic_notification)
            setContentTitle(getString(R.string.app_name))
            setVisibility(Notification.VISIBILITY_PUBLIC)
        }.build()
    }
}
