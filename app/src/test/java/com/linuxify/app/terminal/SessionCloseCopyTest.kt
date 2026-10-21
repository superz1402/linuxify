package com.linuxify.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the close-confirmation copy — including the case the old inline Compose
 * string got wrong: with Kotlin precedence, `"... process" + if (n == 1) " is"
 * else "es are" + rest` bound the whole warning into the else branch, so a
 * single running process (the most common case) produced a truncated
 * "1 process is" with all guidance dropped.
 */
class SessionCloseCopyTest {

    @Test
    fun `exactly one process - full sentence with singular noun`() {
        val body = SessionCloseCopy.confirmBody(1)
        assertTrue(body.startsWith("1 process is still running in this session."))
        // the guidance that used to vanish must be present
        assertTrue(body.contains("whole process tree"))
        assertTrue(body.contains("background jobs will not survive"))
        assertTrue(body.contains("terminal history"))
    }

    @Test
    fun `multiple processes - plural noun with count`() {
        val body = SessionCloseCopy.confirmBody(3)
        assertTrue(body.startsWith("3 processes are still running in this session."))
        assertFalse(body.contains("3 process is"))
        assertTrue(body.contains("whole process tree"))
    }

    @Test
    fun `zero processes still renders a coherent sentence`() {
        // Not reachable through the smart-close policy (0 closes instantly),
        // but the builder must still not emit a broken sentence.
        val body = SessionCloseCopy.confirmBody(0)
        assertTrue(body.startsWith("0 processes are still running in this session."))
    }
}
