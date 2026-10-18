package com.linuxify.app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.linuxify.app.LinuxifyApp
import com.linuxify.app.R

/**
 * Notification plumbing for the Linux runtime.
 *
 *  - Channel [CH_RUNTIME]: the silent, ongoing "Linux is running" notification
 *    owned by [RuntimeForegroundService]. LOW importance: no sound, no heads-up.
 *  - Channel [CH_EVENTS]: discrete "run completed/stopped" events. DEFAULT
 *    importance so they actually show on the lock screen when a long command
 *    finishes while the user is elsewhere.
 *
 * Events are suppressed while the app is in the foreground (the user is
 * watching the run) — the point of these is to tell you your build/server/
 * apt job finished AFTER you left the app.
 */
object RuntimeNotifier {

    const val CH_RUNTIME = "linuxify_runtime"
    const val CH_EVENTS = "linuxify_events"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CH_RUNTIME,
                "Linux runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Persistent notice while the Linux environment runs in the background" },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_EVENTS,
                "Run events",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Tells you when a run or service finished while the app was in the background" },
        )
    }

    /** Post a discrete "something finished" notification unless the app is on screen. */
    fun runCompleted(ctx: Context, title: String, text: String, notifId: Int = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()) {
        if (LinuxifyApp.isInForeground) return
        ensureChannels(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!nm.areNotificationsEnabled()) return
        val n: Notification = NotificationCompat.Builder(ctx, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(notifId, n)
        } catch (_: SecurityException) {
            // notifications blocked per-channel — silently fine
        }
    }
}
