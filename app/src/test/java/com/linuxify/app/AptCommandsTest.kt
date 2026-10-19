package com.linuxify.app

import com.linuxify.app.pkg.AptCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #13 — APT search never matched ordinary names because Regex.escape
 * emits PCRE \Q…\E, which glibc ERE (apt-cache's engine) reads as literal
 * "Q…E". AptCommands must produce ERE-safe quoting instead.
 */
class AptCommandsTest {

    // ------------------------------------------------------------- ereQuote

    @Test
    fun `ordinary names pass through except ERE metacharacters`() {
        assertEquals("htop", AptCommands.ereQuote("htop"))
        // '+' IS an ERE metacharacter — it must be escaped even in package names.
        assertEquals("libstdc\\+\\+6", AptCommands.ereQuote("libstdc++6"))
    }

    @Test
    fun `dot and plus are backslash-escaped for ERE`() {
        assertEquals("node\\.js", AptCommands.ereQuote("node.js"))
        assertEquals("g\\+\\+", AptCommands.ereQuote("g++"))
    }

    @Test
    fun `escaped pattern still matches the original literal through a regex engine`() {
        // java.util.regex shares the backslash-literal convention for these
        // escapes with glibc ERE, so this guards the escaping itself.
        val pattern = Regex(AptCommands.ereQuote("node.js+"))
        assertTrue(pattern.containsMatchIn("node.js+"))
        assertFalse(pattern.containsMatchIn("nodeXjsX"))
    }

    @Test
    fun `every ERE metacharacter gets escaped`() {
        val metas = ".*+?^()|[]{}\\"
        assertEquals(
            metas.map { "\\" + it }.joinToString(""),
            AptCommands.ereQuote(metas),
        )
    }

    // -------------------------------------------------------- searchCommand

    @Test
    fun `search command embeds the quoted term`() {
        val cmd = AptCommands.searchCommand("htop")!!
        assertTrue(cmd.startsWith("apt-cache search --names-only '"))
        assertTrue(cmd.contains("^htop|htop"))
        assertTrue(cmd.endsWith("' 2>/dev/null | head -40"))
    }

    @Test
    fun `search command escapes ERE metacharacters in the term`() {
        val cmd = AptCommands.searchCommand("node.js+")!!
        assertTrue(cmd.contains("node\\.js\\+"))
        assertFalse(cmd.contains("node.js+"))
    }

    @Test
    fun `search command strips shell metacharacters from user input`() {
        // Assert on the surviving TERM, not the whole command — the fixed
        // suffix legitimately contains "/" (2>/dev/null | head -40).
        assertEquals("foorm-rf", AptCommands.safeSearchTerm("foo; rm -rf /"))
        val cmd = AptCommands.searchCommand("foo; rm -rf /")!!
        assertFalse(cmd.contains("rm -rf"))
        assertTrue(cmd.contains("foorm-rf"))
    }

    @Test
    fun `empty or fully-stripped search terms yield no command`() {
        assertNull(AptCommands.searchCommand(""))
        assertNull(AptCommands.searchCommand("   "))
        assertNull(AptCommands.searchCommand(";;;"))
        assertNull(AptCommands.searchCommand("|&"))
    }

    // ---------------------------------------------------- hasInstalledStatus

    @Test
    fun `dpkg status output is recognized when installed`() {
        val out = """
            Package: htop
            Status: install ok installed
            Version: 3.3.0-4build1
        """.trimIndent()
        assertTrue(AptCommands.hasInstalledStatus(out))
    }

    @Test
    fun `dpkg status output is rejected when removed or absent`() {
        assertFalse(AptCommands.hasInstalledStatus("Package: htop\nStatus: deinstall ok config-files\n"))
        assertFalse(AptCommands.hasInstalledStatus(""))
        assertFalse(AptCommands.hasInstalledStatus("dpkg-query: no packages found matching nope"))
    }
}
