package com.linuxify.app.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
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
 * Also holds a PARTIAL wake lock while running: without it, Doze suspends
 * network and defers CPU once the screen goes off, silently freezing long
 * downloads/servers. This costs battery — it runs only while the user has
 * live sessions/services, and is released the moment the last one ends.
 *
 * Honest scope: this protects against ordinary background eviction. It cannot
 * protect against the user force-stopping the app, OEM task managers ( MIUI/
 * etc. kill lists), or extreme memory pressure. Documented, not promised.
 */
class RuntimeForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        RuntimeNotifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linuxify:runtime")
            ?.apply { setReferenceCounted(false); acquire(WAKELOCK_MS) }
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
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 42
        /** 6 h re-arm cap: the system caps wakelocks anyway; re-acquire by an explicit
            user action (opening the app / restarting a run) after that. */
        private const val WAKELOCK_MS = 6 * 60 * 60 * 1000L

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
            if (com.linuxify.app.terminal.TerminalSessionRegistry.hasLiveSessions()) return
            if (com.linuxify.app.srv.ServiceManager.activeCount > 0) return
            ctx.stopService(Intent(ctx, RuntimeForegroundService::class.java))
        }
    }
}
