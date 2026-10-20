package com.linuxify.app.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Battery policy: THE single decision point for when Linuxify may hold a
 * partial wake lock.
 *
 * Why this exists: until alpha.16 the foreground service held an
 * unconditional PARTIAL_WAKE_LOCK for up to 6 h whenever any shell session
 * existed — even a completely idle bash kept the CPU out of deep sleep for
 * hours. That is NOT Termux's model: Termux acquires its wake lock only on
 * explicit user request.
 *
 * The policy, in one place and unit-tested:
 *  - a wake lock is justified while a MANAGED SERVICE is running (a web
 *    server must not freeze when the screen turns off),
 *  - or while a LONG OPERATION runs (rootfs download/extract, apt install or
 *    upgrade, project clone, backup/restore) — freezing those mid-flight
 *    stalls them for minutes,
 *  - or when the USER OPTS IN via Settings > Battery ("keep CPU awake while
 *    shells run") AND at least one terminal session is live — the honest,
 *    battery-aware version of the old always-on behavior, for people whose
 *    workflows need a background shell to keep computing.
 *
 * An idle shell session with the toggle off holds NOTHING. The process still
 * survives backgrounding (foreground service + notification do that); the CPU
 * is simply allowed to sleep, and Doze may defer the session's compute until
 * the user returns — the documented, chosen trade-off.
 *
 * The wakelock itself lives here rather than in RuntimeForegroundService so
 * that long operations are protected even when no session/service exists and
 * the foreground service is not running (e.g. a bare rootfs download from the
 * Setup screen).
 */
object PowerPolicy {

    const val PREFS_FILE = "power_policy"
    const val KEY_STAY_AWAKE = "stay_awake_sessions"

    /** Hard cap identical to the old service-side lock: 6 h, then re-armed by
        the next reevaluate trigger (user action, op begin/end, service event). */
    const val WAKELOCK_CAP_MS: Long = 6 * 60 * 60 * 1000L

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun stayAwakeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_STAY_AWAKE, false)

    /** Settings > Battery toggle. Re-evaluates immediately so flipping the
        switch has an observable effect without restarting anything. */
    fun setStayAwake(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_STAY_AWAKE, enabled).apply()
        reevaluate(ctx)
    }

    // ---- long-operation markers -------------------------------------------------
    // Long-running app operations wrap their bodies with opBegin/opEnd so the
    // wake lock is justified for exactly the operation's lifetime, including
    // nested operations (an apt install inside an upgrade, etc.).

    private val ops = AtomicInteger(0)

    /** True while any wrapped long operation is in flight. */
    fun opRunning(): Boolean = ops.get() > 0

    /** Context-free core, so JVM unit tests can exercise the counter. */
    fun beginOp() {
        ops.incrementAndGet()
    }

    /** Context-free core; clamps at zero so a stray end() can go negative. */
    fun endOp() {
        ops.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** Wrap a long operation: begin → body → end (even on failure/cancel). */
    fun opBegin(ctx: Context) {
        beginOp()
        reevaluate(ctx)
    }

    fun opEnd(ctx: Context) {
        endOp()
        reevaluate(ctx)
    }

    /** JVM tests only: the op counter is process-global state. */
    internal fun resetForTests() {
        ops.set(0)
    }

    // ---- pure decision (unit-tested in PowerPolicyTest) --------------------------

    /**
     * @param anyServiceRunning a managed guest service process is alive
     * @param longOpRunning    installer download/extract, apt run, clone, backup/restore
     * @param sessionsLive     at least one terminal session is running
     * @param stayAwakePref    user opted in via Settings > Battery
     */
    fun shouldHoldWakeLock(
        anyServiceRunning: Boolean,
        longOpRunning: Boolean,
        sessionsLive: Boolean,
        stayAwakePref: Boolean,
    ): Boolean = anyServiceRunning || longOpRunning || (stayAwakePref && sessionsLive)

    // ---- wake lock plumbing ------------------------------------------------------

    private var lock: PowerManager.WakeLock? = null

    /**
     * Recompute reality and acquire/release accordingly. Cheap and idempotent;
     * safe to call from every lifecycle touchpoint. Callers (existing wiring):
     *  - RuntimeForegroundService.onStartCommand (session/service begin)
     *  - RuntimeForegroundService.stopIfIdle / onDestroy (live work ended)
     *  - PowerPolicy.opBegin/opEnd (long operations)
     *  - PowerPolicy.setStayAwake (Settings toggle)
     */
    fun reevaluate(ctx: Context) {
        val serviceRunning = try {
            com.linuxify.app.srv.ServiceManager.activeCount > 0
        } catch (_: Exception) {
            false
        }
        val sessionsLive = try {
            com.linuxify.app.terminal.TerminalSessionRegistry.hasLiveSessions()
        } catch (_: Exception) {
            false
        }
        val hold = shouldHoldWakeLock(serviceRunning, opRunning(), sessionsLive, stayAwakeEnabled(ctx))

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            lock = null
            return
        }
        if (hold) {
            if (lock == null) {
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linuxify:policy")
                    .apply { setReferenceCounted(false) }
            }
            lock?.let { l ->
                synchronized(l) { if (!l.isHeld) l.acquire(WAKELOCK_CAP_MS) }
            }
        } else {
            lock?.let { l ->
                synchronized(l) { if (l.isHeld) l.release() }
            }
        }
    }
}
