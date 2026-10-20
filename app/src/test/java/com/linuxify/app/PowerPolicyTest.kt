package com.linuxify.app

import com.linuxify.app.runtime.PowerPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Battery policy decision matrix (alpha.17 battery audit).
 *
 * Pure JVM: exercises [PowerPolicy.shouldHoldWakeLock] and the long-op marker
 * counter. Context-bound plumbing ([PowerPolicy.reevaluate]) is deliberately
 * NOT tested here — it needs a real PowerManager.
 *
 * The regression these tests pin: an idle shell session must NOT justify a
 * wake lock by itself. Before alpha.17 the foreground service held an
 * unconditional partial wake lock for up to 6 h whenever any session existed.
 */
class PowerPolicyTest {

    @Before
    fun resetOpCounter() {
        PowerPolicy.resetForTests()
    }

    @Test
    fun `nothing live holds nothing`() {
        assertFalse(PowerPolicy.shouldHoldWakeLock(anyServiceRunning = false, longOpRunning = false, sessionsLive = false, stayAwakePref = false))
    }

    @Test
    fun `idle shell session alone does NOT hold a wake lock`() {
        // THE battery fix: sessions existed before, and the old service locked
        // the CPU for 6 h for exactly this state.
        assertFalse(PowerPolicy.shouldHoldWakeLock(anyServiceRunning = false, longOpRunning = false, sessionsLive = true, stayAwakePref = false))
    }

    @Test
    fun `session with user opt-in holds`() {
        assertTrue(PowerPolicy.shouldHoldWakeLock(anyServiceRunning = false, longOpRunning = false, sessionsLive = true, stayAwakePref = true))
    }

    @Test
    fun `managed service running holds even without sessions`() {
        assertTrue(PowerPolicy.shouldHoldWakeLock(anyServiceRunning = true, longOpRunning = false, sessionsLive = false, stayAwakePref = false))
    }

    @Test
    fun `long operation holds even without sessions`() {
        // Rootfs download from the Setup screen: no session, no service — the
        // CPU must still not Doze-sleep mid-download.
        assertTrue(PowerPolicy.shouldHoldWakeLock(anyServiceRunning = false, longOpRunning = true, sessionsLive = false, stayAwakePref = false))
    }

    @Test
    fun `opt-in without a live session does not hold`() {
        // The pref alone is never enough — it only amplifies live sessions.
        assertFalse(PowerPolicy.shouldHoldWakeLock(anyServiceRunning = false, longOpRunning = false, sessionsLive = false, stayAwakePref = true))
    }

    @Test
    fun `everything live holds regardless`() {
        assertTrue(PowerPolicy.shouldHoldWakeLock(anyServiceRunning = true, longOpRunning = true, sessionsLive = true, stayAwakePref = true))
    }

    @Test
    fun `op marker begin and end returns to idle`() {
        assertFalse(PowerPolicy.opRunning())
        PowerPolicy.beginOp()
        assertTrue(PowerPolicy.opRunning())
        PowerPolicy.endOp()
        assertFalse(PowerPolicy.opRunning())
    }

    @Test
    fun `nested op markers hold until the last one ends`() {
        PowerPolicy.beginOp()
        PowerPolicy.beginOp()
        PowerPolicy.endOp()
        assertTrue("inner end must not release the outer op", PowerPolicy.opRunning())
        PowerPolicy.endOp()
        assertFalse(PowerPolicy.opRunning())
    }

    @Test
    fun `stray op end clamps at zero`() {
        PowerPolicy.endOp()
        PowerPolicy.endOp()
        assertFalse(PowerPolicy.opRunning())
        // and the counter still works after the clamp
        PowerPolicy.beginOp()
        assertTrue(PowerPolicy.opRunning())
        PowerPolicy.endOp()
        assertFalse(PowerPolicy.opRunning())
    }
}
