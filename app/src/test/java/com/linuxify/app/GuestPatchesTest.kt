package com.linuxify.app

import com.linuxify.app.runtime.GuestPatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the idempotent in-place guest patches (alpha.11):
 *  - /etc/group runtime-gid resolution,
 *  - /root/.sudo_as_admin_successful marker (kills bash.bashrc's $(groups)),
 *  - /etc/profile.d/linuxify.sh with the bell-on-prompt hook.
 */
class GuestPatchesTest {

    @Before
    fun resetMemo() {
        GuestPatches.resetMemoForTest()
    }

    private fun makeFakeRootfs(): File {
        val dir = Files.createTempDirectory("linuxify-rootfs").toFile()
        File(dir, "bin").mkdirs()
        File(dir, "etc").mkdirs()
        File(dir, "etc/group").writeText("root:x:0:\ndaemon:x:1:\n")
        return dir
    }

    @Test
    fun `applyAll patches groups marker and profile on a real rootfs`() {
        val rootfs = makeFakeRootfs()
        val summary = GuestPatches.applyAll(rootfs)
        assertNotNull("applyAll must succeed on a real-looking rootfs", summary)

        val group = File(rootfs, "etc/group").readText()
        // the universal floor must ALWAYS be named — this is the layer that
        // rescues devices where proc reads come back empty (alpha.7 lesson)
        assertTrue(group.contains("linuxify-g3003:x:3003:"))
        assertTrue(group.contains("linuxify-g9997:x:9997:"))
        // pre-existing entries untouched
        assertTrue(group.contains("root:x:0:"))

        assertTrue(File(rootfs, "root/.sudo_as_admin_successful").exists())

        val profile = File(rootfs, "etc/profile.d/linuxify.sh").readText()
        assertTrue(profile.contains("PS1='\\u@\\h:\\w\\\$ '"))
        assertTrue(profile.contains("PROMPT_COMMAND"))
        assertTrue(profile.contains("printf '\\a'"))
        // interactive-only guard so non-interactive boundedExec shells skip the hook
        assertTrue(profile.contains("case \"\$-\" in *i*)"))
    }

    @Test
    fun `applyAll is idempotent - no duplicate group lines on second run`() {
        val rootfs = makeFakeRootfs()
        val first = GuestPatches.applyAll(rootfs)
        val second = GuestPatches.applyAll(rootfs)
        assertNotNull(first)
        assertNotNull(second)
        val count = File(rootfs, "etc/group").readText()
            .lineSequence()
            .count { it.startsWith("linuxify-g3003:") }
        assertEquals(1, count)
        // profile rewritten identically, not appended
        assertEquals(1, File(rootfs, "etc/profile.d/linuxify.sh").readText()
            .lineSequence().count { it.contains("PROMPT_COMMAND") })
    }

    @Test
    fun `applyAll returns null for a non-rootfs directory`() {
        val dir = Files.createTempDirectory("empty").toFile()
        val summary = GuestPatches.applyAll(dir)
        assertEquals(null, summary)
        // and it must NOT have created fragments
        assertFalse(File(dir, "root/.sudo_as_admin_successful").exists())
        assertFalse(File(dir, "etc").exists())
    }

    @Test
    fun `applyAll memoizes success per rootfs path`() {
        val rootfs = makeFakeRootfs()
        GuestPatches.applyAll(rootfs)
        val second = GuestPatches.applyAll(rootfs)
        assertEquals("already patched (memoized)", second)
    }

    @Test
    fun `profile hook survives a pre-existing profile from older alphas`() {
        val rootfs = makeFakeRootfs()
        // simulate an alpha.7-era profile WITHOUT the bell hook
        File(rootfs, "etc/profile.d").mkdirs()
        File(rootfs, "etc/profile.d/linuxify.sh").writeText(
            "# Added by Linuxify (com.linuxify.app)\nexport PS1='\\u@\\h:\\w\\\$ '\nexport TMPDIR=/tmp\nexport LANG=C.UTF-8\n",
        )
        GuestPatches.applyAll(rootfs)
        // upgraded in place: hook present, no duplicated PS1 lines
        val profile = File(rootfs, "etc/profile.d/linuxify.sh").readText()
        assertTrue(profile.contains("PROMPT_COMMAND"))
        assertEquals(1, profile.lineSequence().count { it.contains("export PS1=") })
    }
}
