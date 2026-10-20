package com.linuxify.app.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.linuxify.app.MainActivity
import com.linuxify.app.R

/**
 * Foreground service that keeps the Linuxify process alive while the Linux
 * userland has live work: open shell sessions or managed services.
 *
 * Why it exists: a plain background process is at the mercy of the system's
 * memory LRU — the user's long-running job (build, server, apt) dies when
 * Android reclaims the app. Holding a foreground service with a persistent
 * notification is the platform-sanctioned way (and Termux's way) to say
 * "this app is doing real work" — the process is then not killed except under
 * extreme pressure or by the user.
 *
 * Wake lock policy lives in [PowerPolicy] (battery audit, alpha.17): this
 * service itself holds NO wake lock. The lock is conditional — held only
 * while a managed service runs, a long operation (download/install/apt/
 * backup) is in flight, or the user opted in via Settings > Battery with a
 * live session. An idle shell no longer keeps the CPU out of deep sleep.
 *
 * Honest scope: this protects against ordinary background eviction. It cannot
 * protect against the user force-stopping the app, OEM task managers ( MIUI/
 * etc. kill lists), or extreme memory pressure. Documented, not promised.
 */
class RuntimeForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        RuntimeNotifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        // Battery policy (alpha.17): recompute the conditional wake lock for the
        // new reality. Covers session begin (registry.add → start) and service
        // begin (ServiceManager.start → start), including START_STICKY restarts.
        PowerPolicy.reevaluate(this)
        // START_STICKY: if the system does kill us under pressure, ask for a
        // restart (sessions themselves are gone at that point — honest state
        // is re-derived by the app on relaunch).
        return START_STICKY
    }

    private fun startAsForeground() {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, RuntimeNotifier.CH_RUNTIME)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("Linuxify is running")
            .setContentText("Your Linux environment stays alive in the background")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        // Termux-identical 2-arg form: with targetSdk 28 no FGS type is required
        // (typed-FGS enforcement applies to targetSdk 34+ apps).
        startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        // Recompute before teardown: on an idle stop nothing is live, so the
        // policy releases the lock; on a system-cancel with live sessions the
        // user's opt-in keeps it (START_STICKY brings the service back).
        PowerPolicy.reevaluate(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 42

        /** Call whenever live work begins (a shell session spawns, a service starts). */
        fun start(ctx: Context) {
            val i = Intent(ctx, RuntimeForegroundService::class.java)
            try {
                androidx.core.content.ContextCompat.startForegroundService(ctx, i)
            } catch (_: Exception) {
                // Starting from the background is restricted on modern Android;
                // every current caller is in the foreground. Fail honestly-soft.
            }
        }

        /** Call whenever live work ends; stops the service if nothing is left. */
        fun stopIfIdle(ctx: Context) {
            // Recompute the wake lock FIRST: a service/session just ended, so a
            // justification may have dropped even while other work stays live.
            PowerPolicy.reevaluate(ctx)
            if (com.linuxify.app.terminal.TerminalSessionRegistry.hasLiveSessions()) return
            if (com.linuxify.app.srv.ServiceManager.activeCount > 0) return
            ctx.stopService(Intent(ctx, RuntimeForegroundService::class.java))
        }
    }
}
