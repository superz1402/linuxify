package com.linuxify.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exit-status → honest-cause mapping (alpha.20). These tests pin the exact
 * contract the UI copy and notifications are built on:
 * a NEGATIVE exit status is a signal (vendored JNI.waitFor), and signal 9 is
 * reported as "killed by Android" — the phantom process killer / low-memory
 * killer signature users saw as "ended for no reason".
 */
class SessionEndCauseTest {

    @Test
    fun `non-negative status is a clean exit`() {
        assertEquals(SessionEndCause.Kind.CLEAN_EXIT, SessionEndCause.classify(0))
        assertEquals(SessionEndCause.Kind.CLEAN_EXIT, SessionEndCause.classify(1))
        assertEquals(SessionEndCause.Kind.CLEAN_EXIT, SessionEndCause.classify(130))
    }

    @Test
    fun `signal 9 is killed by Android`() {
        assertEquals(SessionEndCause.Kind.KILLED_BY_ANDROID, SessionEndCause.classify(-9))
    }

    @Test
    fun `hardware and abort signals are a guest crash`() {
        assertEquals(SessionEndCause.Kind.GUEST_CRASH, SessionEndCause.classify(-4)) // SIGILL
        assertEquals(SessionEndCause.Kind.GUEST_CRASH, SessionEndCause.classify(-6)) // SIGABRT
        assertEquals(SessionEndCause.Kind.GUEST_CRASH, SessionEndCause.classify(-7)) // SIGBUS
        assertEquals(SessionEndCause.Kind.GUEST_CRASH, SessionEndCause.classify(-11)) // SIGSEGV
    }

    @Test
    fun `hangup and terminate are terminated not crashed`() {
        assertEquals(SessionEndCause.Kind.TERMINATED, SessionEndCause.classify(-1)) // SIGHUP
        assertEquals(SessionEndCause.Kind.TERMINATED, SessionEndCause.classify(-15)) // SIGTERM
    }

    @Test
    fun `labels carry the signal number`() {
        assertTrue(SessionEndCause.shortLabel(SessionEndCause.Kind.KILLED_BY_ANDROID, -9).contains("signal 9"))
        assertTrue(SessionEndCause.shortLabel(SessionEndCause.Kind.GUEST_CRASH, -11).contains("signal 11"))
        assertTrue(SessionEndCause.shortLabel(SessionEndCause.Kind.TERMINATED, -15).contains("signal 15"))
        assertEquals("clean exit", SessionEndCause.shortLabel(SessionEndCause.Kind.CLEAN_EXIT, 0))
        assertEquals("exit 3", SessionEndCause.shortLabel(SessionEndCause.Kind.CLEAN_EXIT, 3))
        assertEquals("closed by you", SessionEndCause.shortLabel(SessionEndCause.Kind.USER_CLOSED, -9))
    }

    @Test
    fun `signal 9 explanation says Android killed it and nothing crashed`() {
        val text = SessionEndCause.explanation(SessionEndCause.Kind.KILLED_BY_ANDROID, -9)
        assertTrue(text.contains("Android"))
        assertTrue(text.contains("phantom process killer"))
        assertTrue(text.contains("did not crash") || text.contains("Nothing inside Linuxify crashed"))
    }

    @Test
    fun `every notification for non-user ends is non-blank`() {
        for (kind in SessionEndCause.Kind.entries) {
            val (title, text) = SessionEndCause.notification(kind, -9, "sh 1")
            if (kind == SessionEndCause.Kind.USER_CLOSED) {
                // user closes are NEVER notified
                assertTrue(title.isEmpty() && text.isEmpty())
            } else {
                assertTrue("title for $kind", title.isNotBlank())
                assertTrue("text for $kind", text.isNotBlank())
            }
        }
    }

    @Test
    fun `signal 9 notification names the real killer`() {
        val (title, text) = SessionEndCause.notification(SessionEndCause.Kind.KILLED_BY_ANDROID, -9, "sh 2")
        assertTrue(title.contains("killed by Android"))
        assertTrue(title.contains("sh 2"))
        assertTrue(text.contains("phantom process killer"))
    }

    @Test
    fun `adb fix covers all three android generations`() {
        val fix = SessionEndCause.ADB_FIX_COMMANDS
        assertTrue(fix.contains("settings_enable_monitor_phantom_procs")) // 12L/13
        assertTrue(fix.contains("max_phantom_processes")) // 12
        assertTrue(fix.contains("Disable child process restrictions")) // 14+
        assertTrue(SessionEndCause.FIX_ANDROID_14_PLUS.contains("Developer options"))
        assertTrue(SessionEndCause.FIX_ANDROID_12.contains("device_config"))
    }
}
