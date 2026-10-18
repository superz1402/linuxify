package com.linuxify.app

import com.linuxify.app.pkg.EnvCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision logic of the Dependency & Package Recovery Manager.
 * The central contract under test: dpkg --configure -a / apt-get --fix-broken
 * run ONLY on real evidence — never blindly.
 */
class EnvManagerTest {

    // ------------------------------------------------------------ profiles

    @Test
    fun `base profile installs curl and ca-certificates only`() {
        assertEquals(listOf("curl", "ca-certificates"), EnvCore.PROFILE_BASE.tools.map { it.pkg })
    }

    @Test
    fun `developer profile installs git nodejs npm`() {
        assertEquals(listOf("git", "nodejs", "npm"), EnvCore.PROFILE_DEV.tools.map { it.pkg })
    }

    @Test
    fun `packagesFor installs only the MISSING tools`() {
        val installed = setOf("curl", "update-ca-certificates", "git")
        assertEquals(listOf("nodejs", "npm"), EnvCore.packagesFor(EnvCore.PROFILE_DEV, installed))
        assertEquals(emptyList<String>(), EnvCore.packagesFor(EnvCore.PROFILE_BASE, installed))
    }

    @Test
    fun `byId resolves both profiles and nothing else`() {
        assertEquals(EnvCore.PROFILE_BASE, EnvCore.byId("base"))
        assertEquals(EnvCore.PROFILE_DEV, EnvCore.byId("dev"))
        assertNull(EnvCore.byId("nope"))
    }

    // ------------------------------------------------------- recovery plan

    @Test
    fun `healthy audit yields an EMPTY plan — never a blind dpkg --configure -a`() {
        assertTrue(EnvCore.recoveryPlan(dpkgInterrupted = false, brokenDeps = false).isEmpty())
    }

    @Test
    fun `interrupted dpkg plans exactly the dpkg reconfigure step`() {
        val plan = EnvCore.recoveryPlan(dpkgInterrupted = true, brokenDeps = false)
        assertEquals(1, plan.size)
        assertEquals(EnvCore.STEP_DPKG_CONFIGURE, plan[0].id)
        assertTrue(plan[0].command.contains("dpkg --configure -a"))
    }

    @Test
    fun `broken deps plan exactly the fix-broken step with IPv4 fallback`() {
        val plan = EnvCore.recoveryPlan(dpkgInterrupted = false, brokenDeps = true)
        assertEquals(1, plan.size)
        assertEquals(EnvCore.STEP_FIX_BROKEN, plan[0].id)
        assertTrue(plan[0].command.contains("--fix-broken"))
        assertTrue(plan[0].command.contains("Acquire::ForceIPv4=true"))
    }

    @Test
    fun `both issues plan dpkg reconfigure BEFORE fix-broken`() {
        val plan = EnvCore.recoveryPlan(dpkgInterrupted = true, brokenDeps = true)
        assertEquals(listOf(EnvCore.STEP_DPKG_CONFIGURE, EnvCore.STEP_FIX_BROKEN), plan.map { it.id })
    }

    @Test
    fun `every recovery step carries a command, a reason and a practical recovery hint`() {
        val plan = EnvCore.recoveryPlan(dpkgInterrupted = true, brokenDeps = true)
        plan.forEach { step ->
            assertTrue(step.command.isNotBlank())
            assertTrue(step.why.isNotBlank())
            assertTrue(step.recoveryHint.isNotBlank())
        }
    }

    // ---------------------------------------------------------- probe parse

    private val fullProbe = listOf(
        "distro=Ubuntu 24.04.5 LTS",
        "arch=arm64",
        "free_mb=12345",
        "net=yes",
        "updates=0",
        "deps=ok",
        "tool:curl=yes",
        "tool:update-ca-certificates=yes",
        "tool:git=yes",
        "tool:node=no",
        "tool:npm=no",
        "done",
    )

    @Test
    fun `probe parses a healthy environment`() {
        val a = EnvCore.parseProbe(fullProbe)
        assertEquals("Ubuntu 24.04.5 LTS", a.distro)
        assertEquals("arm64", a.arch)
        assertEquals(12345L, a.freeStorageMb)
        assertTrue(a.networkOk)
        assertFalse(a.dpkgInterrupted)
        assertFalse(a.brokenDeps)
        assertTrue(a.healthy)
        assertEquals(setOf("curl", "update-ca-certificates", "git"), a.installedBinaries)
    }

    @Test
    fun `probe flags interrupted dpkg from audit output`() {
        val a = EnvCore.parseProbe(
            fullProbe + listOf("audit:The following packages have been unpacked but not yet configured."),
        )
        assertTrue(a.dpkgInterrupted)
        assertFalse(a.healthy)
    }

    @Test
    fun `probe flags interrupted dpkg from leftover updates directory`() {
        val a = EnvCore.parseProbe(fullProbe.map { if (it == "updates=0") "updates=2" else it })
        assertTrue(a.dpkgInterrupted)
    }

    @Test
    fun `probe flags broken dependencies`() {
        val a = EnvCore.parseProbe(fullProbe.map { if (it == "deps=ok") "deps=broken" else it })
        assertTrue(a.brokenDeps)
        assertFalse(a.healthy)
    }

    @Test
    fun `probe treats missing network honestly`() {
        val a = EnvCore.parseProbe(fullProbe.map { if (it == "net=yes") "net=no" else it })
        assertFalse(a.networkOk)
    }

    @Test
    fun `probe with empty output degrades to a null-field audit, not a crash`() {
        val a = EnvCore.parseProbe(emptyList())
        assertNull(a.distro)
        assertNull(a.arch)
        assertNull(a.freeStorageMb)
        assertFalse(a.networkOk)
        assertFalse(a.dpkgInterrupted)
        assertFalse(a.brokenDeps)
        assertTrue(a.installedBinaries.isEmpty())
    }

    @Test
    fun `probe ignores unknown and blank lines`() {
        val a = EnvCore.parseProbe(listOf("", "garbage line", "E: unreadable", *fullProbe.toTypedArray()))
        assertEquals("Ubuntu 24.04.5 LTS", a.distro)
    }

    // ------------------------------------------------------- index + verify

    @Test
    fun `index is stale when no release file exists`() {
        val hour = 60L * 60L * 1000L
        assertTrue(EnvCore.indexIsStale(nowMs = 10_000L, newestReleaseMtimeMs = null))
        assertTrue(EnvCore.indexIsStale(nowMs = 10_000L, newestReleaseMtimeMs = 0L))
        assertFalse(EnvCore.indexIsStale(nowMs = 10_000L + hour, newestReleaseMtimeMs = 10_000L))
        assertTrue(EnvCore.indexIsStale(nowMs = 10_000L + 25 * hour, newestReleaseMtimeMs = 10_000L))
    }

    @Test
    fun `verification passes only when the binary exists AND runs with output`() {
        assertTrue(EnvCore.verifyPassed(exitCode = 0, stdout = "curl 8.5.0\n"))
        assertFalse(EnvCore.verifyPassed(exitCode = 0, stdout = ""))
        assertFalse(EnvCore.verifyPassed(exitCode = 127, stdout = ""))
        assertFalse(EnvCore.verifyPassed(exitCode = 1, stdout = "some error"))
    }

    @Test
    fun `firstVersionLine returns the first non-blank line trimmed`() {
        assertEquals("node v20.15.0", EnvCore.firstVersionLine("\n \nnode v20.15.0\nv20.15.0"))
        assertNull(EnvCore.firstVersionLine("   \n"))
    }

    // ------------------------------------------------------- commands built

    @Test
    fun `apt commands are noninteractive and force IPv4`() {
        assertTrue(EnvCore.updateCommand().contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(EnvCore.updateCommand().contains("Acquire::ForceIPv4=true"))
        val cmd = EnvCore.installCommand(listOf("nodejs", "npm"))
        assertTrue(cmd.contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(cmd.contains("Acquire::ForceIPv4=true"))
        assertTrue(cmd.contains("install -y nodejs npm"))
    }
}
