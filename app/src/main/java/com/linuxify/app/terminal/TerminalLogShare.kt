package com.linuxify.app.terminal

/**
 * Builds the plain-text payload for the terminal "share log" action.
 *
 * Pure and unit-testable on the JVM: the Compose side only captures the
 * transcript from the vendored emulator buffer and hands it here.
 *
 * Design notes:
 *  - A one-line factual header tells the recipient (typically an AI chat)
 *    WHAT the text is — an Ubuntu userland running under proot on Android —
 *    so a bare paste still gets useful context without the sender explaining.
 *  - Android binder limits intent payloads to ~1 MB; a runaway command can
 *    scroll hundreds of thousands of lines. We cap and keep the MOST RECENT
 *    characters (the tail is where a crash usually lives), with an honest
 *    truncation notice instead of a silent cut.
 */
object TerminalLogShare {

    /** Safe under Android's binder transaction limits (intent payloads ~1 MB). */
    const val MAX_SHARE_CHARS: Int = 500_000

    fun buildShareText(transcript: String, sessionLabel: String): String {
        val header =
            "[Linuxify terminal log — Ubuntu userland via proot on Android, session $sessionLabel]"
        if (transcript.length <= MAX_SHARE_CHARS) {
            return "$header\n$transcript"
        }
        return "$header\n" +
            "[log truncated to the most recent ${MAX_SHARE_CHARS} characters]\n" +
            transcript.takeLast(MAX_SHARE_CHARS)
    }
}
