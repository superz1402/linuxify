package com.linuxify.app

import com.linuxify.app.proj.ProjectsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the batched git-probe parser (issue #22): ProjectsManager.list()
 * used to spawn proot twice PER project on every refresh; now one guest shell
 * loop emits `<name>\t<branch|->\t<dirty|->` lines and this pure parser turns
 * them into per-project git info. Honest-degradation rules are enforced here:
 * blank/malformed lines are skipped, never guessed into fake data.
 */
class ProjectsGitProbeTest {

    @Test
    fun `parses repo with branch and dirty count`() {
        val out = "alpha\tmain\t3\n"
        val m = ProjectsManager.parseGitProbe(out)
        assertEquals(1, m.size)
        assertEquals("main" to 3, m["alpha"])
    }

    @Test
    fun `parses clean repo as zero dirty`() {
        val m = ProjectsManager.parseGitProbe("clean\tmain\t0\n")
        assertEquals("main" to 0, m["clean"])
    }

    @Test
    fun `parses non-repo dashes as nulls`() {
        val m = ProjectsManager.parseGitProbe("plain\t-\t-\n")
        val p = m.getValue("plain")
        assertNull(p.first)
        assertNull(p.second)
    }

    @Test
    fun `parses branch names containing slashes`() {
        val m = ProjectsManager.parseGitProbe("web\tfeature/login\t12\n")
        assertEquals("feature/login" to 12, m["web"])
    }

    @Test
    fun `parses detached head and unusual branch names`() {
        val m = ProjectsManager.parseGitProbe("detached\thead\t1\npatched\tv0.1.0\t5\n")
        assertEquals("head" to 1, m["detached"])
        assertEquals("v0.1.0" to 5, m["patched"])
    }

    @Test
    fun `multiple projects all parsed`() {
        val out = """
            b\tmain\t0
            a\tdev\t2
            c\t-\t-
        """.trimIndent().replace("\\t", "\t") + "\n"
        val m = ProjectsManager.parseGitProbe(out)
        assertEquals(3, m.size)
        assertEquals("dev" to 2, m["a"])
        assertEquals("main" to 0, m["b"])
        assertNull(m["c"]?.first)
    }

    @Test
    fun `blank and malformed lines are skipped`() {
        val out = "\n   \nbroken-line-no-tabs\n\tonly-two\tcols\n\ngood\tmain\t1\n\n"
        val m = ProjectsManager.parseGitProbe(out)
        assertEquals(1, m.size)
        assertEquals("main" to 1, m["good"])
    }

    @Test
    fun `empty name line is skipped`() {
        val m = ProjectsManager.parseGitProbe("\tmain\t1\nok\tdev\t0\n")
        assertTrue(m.containsKey("ok"))
        assertEquals(1, m.size)
    }

    @Test
    fun `non-numeric dirty count degrades to null`() {
        val m = ProjectsManager.parseGitProbe("odd\tmain\tnot-a-number\n")
        assertEquals("main" to null, m["odd"])
    }

    @Test
    fun `empty output yields empty map`() {
        assertTrue(ProjectsManager.parseGitProbe("").isEmpty())
        assertTrue(ProjectsManager.parseGitProbe("\n\n").isEmpty())
    }

    @Test
    fun `handles CRLF line endings`() {
        val m = ProjectsManager.parseGitProbe("win\tmain\t4\r\nplain\t-\t-\r\n")
        assertEquals("main" to 4, m["win"])
        assertNull(m["plain"]?.first)
    }
}
