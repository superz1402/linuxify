package com.linuxify.app.terminal

/**
 * Honest classification of WHY a terminal session ended (v0.1.0-alpha.20).
 *
 * User-visible problem this solves: sessions that die on their own used to be
 * surfaced as a bare "(ended)" tab and a "Run completed — exit code -9"
 * notification, which reads as a mysterious crash "for no reason". In reality
 * the exit status tells the whole story: a NEGATIVE status is a signal (the
 * vendored JNI.waitFor contract: "< 0, the signal causing the process to stop,
 * negated"), and the signal identifies the killer:
 *
 *  - 9  (SIGKILL)  -> almost always ANDROID ITSELF: the Android 12+ "phantom
 *    process killer" silently ends apps' forked child processes once a
 *    system-wide budget (~32 processes across ALL apps) is exceeded, or when a
 *    child burns CPU while its app is backgrounded (Google issuetracker
 *    205156966; Termux issue 2366 — the identical "[Process completed
 *    (signal 9)]" reports). The low-memory killer and OEM task managers
 *    (MIUI etc.) produce the same signal. Nothing in Linuxify or the guest
 *    crashed — which is exactly why there was no crash report.
 *  - 4/6/7/11 (SIGILL/ABRT/BUS/SEGV) -> the guest runtime itself (proot,
 *    bash, or a command) crashed. Rare; reportable via the share-log button.
 *  - 1/15 (SIGHUP/SIGTERM) and other signals -> someone asked the process to
 *    terminate (e.g. the app process died and the pty hung up).
 *  - >= 0 -> a normal exit: the user typed exit, or the command finished.
 *
 * A USER-initiated close (tab ✕ / close dialog) also arrives as SIGKILL, so it
 * is marked on the session registry BEFORE we kill — [Kind.USER_CLOSED] comes
 * from that flag, never from the exit status.
 *
 * This object is pure JVM (no Android imports) so the mapping is unit-tested.
 */
object SessionEndCause {

    enum class Kind { USER_CLOSED, CLEAN_EXIT, KILLED_BY_ANDROID, GUEST_CRASH, TERMINATED }

    /**
     * The per-version fixes for externally killed sessions, as exact strings so
     * the Settings card, the terminal banner dialog and the copy button all
     * agree (and tests pin them).
     */
    const val FIX_ANDROID_14_PLUS: String =
        "Android 14 or newer (no computer needed): Settings > Developer options > enable \"Disable child process restrictions\", then reboot."

    const val FIX_ANDROID_13: String =
        "Android 12L / 13 (one ADB command from a computer): adb shell settings put global settings_enable_monitor_phantom_procs false"

    const val FIX_ANDROID_12: String =
        "Android 12 (two ADB commands): adb shell device_config set_sync_disabled_for_tests persistent" +
            " && adb shell device_config put activity_manager max_phantom_processes 2147483647"

    const val FIX_GENERAL: String =
        "Also: run fewer shells/services at once (every shell is proot + bash, every service adds more child processes), " +
            "and exclude Linuxify from the device maker's battery manager if it offers an \"unrestricted\" mode."

    /** Everything the copy button puts on the clipboard, in one block. */
    const val ADB_FIX_COMMANDS: String =
        "# Android 12L/13:\n" +
            "adb shell settings put global settings_enable_monitor_phantom_procs false\n" +
            "\n" +
            "# Android 12:\n" +
            "adb shell device_config set_sync_disabled_for_tests persistent\n" +
            "adb shell device_config put activity_manager max_phantom_processes 2147483647\n" +
            "\n" +
            "# Android 14+: no ADB needed — Developer options > \"Disable child process restrictions\"\n"

    /** Map a TerminalSession exit status to its honest cause. */
    fun classify(exitStatus: Int): Kind = when {
        exitStatus >= 0 -> Kind.CLEAN_EXIT
        -exitStatus == 9 -> Kind.KILLED_BY_ANDROID
        -exitStatus == 4 || -exitStatus == 6 || -exitStatus == 7 || -exitStatus == 11 -> Kind.GUEST_CRASH
        else -> Kind.TERMINATED
    }

    /** One-line label for tabs, history records and the banner headline. */
    fun shortLabel(kind: Kind, exitStatus: Int): String = when (kind) {
        Kind.USER_CLOSED -> "closed by you"
        Kind.CLEAN_EXIT -> if (exitStatus == 0) "clean exit" else "exit $exitStatus"
        Kind.KILLED_BY_ANDROID -> "killed by Android (signal 9)"
        Kind.GUEST_CRASH -> "crashed (signal ${-exitStatus})"
        Kind.TERMINATED -> "terminated (signal ${-exitStatus})"
    }

    /** Full honest explanation shown on the ended-session banner. */
    fun explanation(kind: Kind, exitStatus: Int): String = when (kind) {
        Kind.USER_CLOSED ->
            "You closed this shell. Its transcript stays in terminal history."
        Kind.CLEAN_EXIT ->
            "The shell exited normally (code $exitStatus) — you typed exit, or the command it was running finished. " +
                "The transcript stays in terminal history."
        Kind.KILLED_BY_ANDROID ->
            "Android itself killed this shell (SIGKILL). Nothing inside Linuxify crashed — which is why there is no " +
                "error. On Android 12+ the system silently ends apps' background child processes (\"phantom process " +
                "killer\") once a system-wide budget of roughly 32 is exceeded, or when one uses heavy CPU while the " +
                "app is backgrounded; low memory and OEM battery managers kill the same way. The transcript is saved."
        Kind.GUEST_CRASH ->
            "The guest runtime died with signal ${-exitStatus} (a real crash of proot, the shell, or the command it " +
                "was running). If this repeats with the same command, share the terminal log so it can be traced."
        Kind.TERMINATED ->
            "The shell received signal ${-exitStatus} — a terminate/hangup request rather than a crash (for example " +
                "the app process was killed while the shell ran, and the terminal connection hung up)."
    }

    /**
     * Background notification title + text for a session end the user did NOT
     * cause. USER_CLOSED maps to empty strings — callers must not notify for it.
     */
    fun notification(kind: Kind, exitStatus: Int, label: String): Pair<String, String> = when (kind) {
        Kind.USER_CLOSED -> "" to ""
        Kind.CLEAN_EXIT ->
            "Run completed — $label ended" to
                "Exit code $exitStatus. Tap to open Linuxify."
        Kind.KILLED_BY_ANDROID ->
            "Session killed by Android — $label" to
                "Signal 9: Android ends background child processes when its limits are hit (phantom process killer) " +
                    "or memory is tight. Nothing in Linuxify crashed. The transcript is saved in terminal history."
        Kind.GUEST_CRASH ->
            "$label crashed (signal ${-exitStatus})" to
                "The guest runtime died unexpectedly. The transcript is saved in terminal history — tap to review it."
        Kind.TERMINATED ->
            "$label was terminated (signal ${-exitStatus})" to
                "The shell was asked to terminate. The transcript is saved in terminal history."
    }
}
