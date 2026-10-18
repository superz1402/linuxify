package com.linuxify.app

import com.linuxify.app.runtime.GuestGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the guest /etc/group patch that silences Ubuntu's
 * "cannot find name for group ID" prompt warnings (bash.bashrc runs $(groups)).
 */
class GuestGroupsTest {

    @Test
    fun `appends missing runtime gids`() {
        val existing = "root:x:0:\ndaemon:x:1:\nbin:x:2:\n"
        val appended = GuestGroups.missingEntries(existing, listOf(0, 1023, 3003))
        assertEquals(listOf("linuxify-g1023:x:1023:", "linuxify-g3003:x:3003:"), appended)
    }

    @Test
    fun `is idempotent - no duplicates on second run`() {
        val afterFirst = "root:x:0:\nlinuxify-g3003:x:3003:\n"
        val appended = GuestGroups.missingEntries(afterFirst, listOf(0, 3003))
        assertTrue(appended.isEmpty())
    }

    @Test
    fun `handles file without trailing newline`() {
        val existing = "root:x:0:" // no trailing newline
        val appended = GuestGroups.missingEntries(existing, listOf(5))
        assertEquals(listOf("linuxify-g5:x:5:"), appended)
    }

    @Test
    fun `ignores malformed lines`() {
        val existing = "root:x:0:\nnot-a-group-line\nweird:x:notanumber:\n"
        val appended = GuestGroups.missingEntries(existing, listOf(0, 7))
        assertEquals(listOf("linuxify-g7:x:7:"), appended)
    }

    @Test
    fun `rejects negative gids`() {
        val appended = GuestGroups.missingEntries("root:x:0:\n", listOf(-1, 0))
        assertTrue(appended.isEmpty())
    }

    @Test
    fun `null content behaves like empty file`() {
        val appended = GuestGroups.missingEntries(null, listOf(0, 3003))
        assertEquals(listOf("linuxify-g0:x:0:", "linuxify-g3003:x:3003:"), appended)
    }

    @Test
    fun `ensureRuntimeGroups skips non-rootfs directory`() {
        val dir = Files.createTempDirectory("empty").toFile()
        // no bin/ inside -> not a rootfs -> must be a no-op and never create etc/
        val result = GuestGroups.ensureRuntimeGroupsInGuest(dir)
        assertEquals(0, result)
        assertTrue(!File(dir, "etc").exists())
    }

    @Test
    fun `ensureRuntimeGroups appends at least the well-known floor to a real rootfs`() {
        val dir = Files.createTempDirectory("rootfs").toFile()
        File(dir, "bin").mkdirs()
        File(dir, "etc").mkdirs()
        File(dir, "etc/group").writeText("root:x:0:\n")
        val result = GuestGroups.ensureRuntimeGroupsInGuest(dir)
        // Since alpha.11 runtimeGids() always contains the well-known Android
        // floor (3001-3004, 9997) — even if every proc read fails. On the JVM it
        // also picks up the host's own gids from /proc. Device-independent
        // assertion: the two universal groups MUST end up named.
        assertTrue("expected gids appended, got $result", result != null && result >= 2)
        val text = File(dir, "etc/group").readText()
        assertTrue(text.contains("linuxify-g3003:x:3003:"))
        assertTrue(text.contains("linuxify-g9997:x:9997:"))
    }

    @Test
    fun `parses procs-groups one-gid-per-line format`() {
        val parsed = GuestGroups.parseProcGroups("0\n1001\n3003\n\njunk\n9997")
        assertEquals(setOf(0, 1001, 3003, 9997), parsed)
    }

    @Test
    fun `parses proc-status Groups line format`() {
        val status = """
            Uid:        10495   10495   10495   10495
            Gid:        10495   10495   10495   10495
            Groups:     3003 9997 20495 50495
            VmPeak:        12345 kB
        """.trimIndent()
        val parsed = GuestGroups.parseStatusGroups(status)
        assertEquals(setOf(3003, 9997, 20495, 50495), parsed)
    }

    @Test
    fun `status parser returns empty for missing Groups line`() {
        assertTrue(GuestGroups.parseStatusGroups("Name: init\nUid:\t0\t0\t0\t0\n").isEmpty())
        assertTrue(GuestGroups.parseStatusGroups(null).isEmpty())
    }

    @Test
    fun `runtimeGids always contains the universal android groups`() {
        val gids = GuestGroups.runtimeGids()
        assertTrue("3003 (inet) must always be named", 3003 in gids)
        assertTrue("9997 (everyone) must always be named", 9997 in gids)
    }
}
