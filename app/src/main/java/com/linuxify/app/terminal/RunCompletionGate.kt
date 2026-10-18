package com.linuxify.app.terminal

/**
 * Pure decision logic for turning a terminal BEL into a "run completed"
 * notification. Kept free of Android types so it is unit-testable on the JVM.
 *
 * Rules:
 *  - foreground app: never notify (the user is looking at the terminal);
 *  - backgrounded: notify, but throttle bursts (bash evaluates PROMPT_COMMAND
 *    before EVERY prompt — a session start plus a quick command can deliver
 *    several bells in rapid succession; without the throttle each one would
 *    buzz the user's pocket for a shell they left seconds ago).
 */
object RunCompletionGate {

    /** Minimum spacing between two bell notifications, in milliseconds. */
    const val MIN_INTERVAL_MS: Long = 3_000

    fun shouldNotify(foreground: Boolean, nowMs: Long, lastNotifiedMs: Long): Boolean {
        if (foreground) return false
        return nowMs - lastNotifiedMs >= MIN_INTERVAL_MS
    }
}
