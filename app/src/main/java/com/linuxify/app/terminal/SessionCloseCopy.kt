package com.linuxify.app.terminal

/**
 * User-facing copy for the smart session-close confirmation.
 *
 * Pure and unit-tested on purpose: the previous inline Compose string had a
 * Kotlin precedence bug — `"... process" + if (n == 1) " is" else "es are" +
 * rest` binds the ENTIRE rest of the sentence into the `else` branch, so when
 * exactly ONE process was running (the most common case — one `top`, one
 * `apt install`) the dialog collapsed to a truncated "1 process is" with all
 * of the actual warning dropped.
 */
object SessionCloseCopy {

    /** Body text for the "Close this shell?" confirmation dialog. */
    fun confirmBody(busyCount: Int): String {
        val noun = if (busyCount == 1) "1 process is" else "$busyCount processes are"
        return "$noun still running in this session. Closing kills the shell's whole process " +
            "tree — background jobs will not survive. Its transcript stays in terminal history."
    }
}
