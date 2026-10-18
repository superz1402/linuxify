package com.linuxify.app

import com.linuxify.app.terminal.TerminalLogShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLogShareTest {

    @Test
    fun `small transcript is prefixed with an environment header and kept intact`() {
        val transcript = "root@localhost:~# uname -a\nLinux localhost 6.6.30\n"
        val out = TerminalLogShare.buildShareText(transcript, "sh 1")

        assertTrue(out.startsWith("[Linuxify terminal log"))
        assertTrue(out.contains("proot on Android"))
        assertTrue(out.contains("session sh 1"))
        assertTrue(out.endsWith(transcript))
    }

    @Test
    fun `oversized transcript is truncated from the FRONT, keeping the most recent tail`() {
        val head = "OLD_JUNK_START".padEnd(600_000, 'x')
        val tail = "NEW_CRASH_LINE_42"
        val transcript = head + tail

        val out = TerminalLogShare.buildShareText(transcript, "sh 2")

        assertTrue(out.contains("truncated"))
        assertTrue(out.contains("session sh 2"))
        // the tail survives — that's where the interesting output lives
        assertTrue(out.endsWith(tail))
        // header + notice + exactly MAX_SHARE_CHARS of body
        assertEquals(
            TerminalLogShare.MAX_SHARE_CHARS,
            out.length - out.indexOf("[log truncated") - "[log truncated to the most recent 500000 characters]\n".length,
        )
        // the ancient head is dropped
        assertTrue(!out.contains("OLD_JUNK_START"))
    }

    @Test
    fun `exactly-at-cap transcript is NOT truncated`() {
        val transcript = "y".repeat(TerminalLogShare.MAX_SHARE_CHARS)
        val out = TerminalLogShare.buildShareText(transcript, "sh 3")
        assertTrue(!out.contains("truncated"))
        assertTrue(out.endsWith(transcript))
    }
}
