package com.linuxify.app

import com.linuxify.app.terminal.RunCompletionGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision rules for BEL -> "run completed" notification (alpha.11).
 */
class RunCompletionGateTest {

    @Test
    fun `never notifies while the app is foreground`() {
        assertFalse(RunCompletionGate.shouldNotify(foreground = true, nowMs = 100_000, lastNotifiedMs = 0))
    }

    @Test
    fun `notifies on the first backgrounded bell`() {
        assertTrue(RunCompletionGate.shouldNotify(foreground = false, nowMs = 100_000, lastNotifiedMs = 0))
    }

    @Test
    fun `throttles bursts within the interval`() {
        val first = 100_000L
        val burst = first + RunCompletionGate.MIN_INTERVAL_MS - 1
        assertFalse(RunCompletionGate.shouldNotify(foreground = false, nowMs = burst, lastNotifiedMs = first))
    }

    @Test
    fun `allows the next bell after the interval passes`() {
        val first = 100_000L
        val later = first + RunCompletionGate.MIN_INTERVAL_MS
        assertTrue(RunCompletionGate.shouldNotify(foreground = false, nowMs = later, lastNotifiedMs = first))
    }
}
