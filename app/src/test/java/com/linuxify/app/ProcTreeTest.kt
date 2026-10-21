package com.linuxify.app

import com.linuxify.app.terminal.ProcTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic tests for the smart session close policy's /proc parsing.
 * Covers the real Linux stat layout, hostile comm fields, and tree math.
 */
class ProcTreeTest {

    @Test
    fun `parentOf parses standard stat layout`() {
        // pid (comm) state ppid sessionid ...
        assertEquals(45, ProcTree.parentOf("1234 (bash) S 45 120 120 0 -1 ..."))
        assertEquals(1, ProcTree.parentOf("999 (proot) R 1 0 0 0"))
    }

    @Test
    fun `parentOf survives comm containing spaces and parens`() {
        // comm may contain spaces/parens — the LAST ')' ends comm.
        assertEquals(77, ProcTree.parentOf("42 (gunicorn: m) S 77 1 1 0"))
        assertEquals(77, ProcTree.parentOf("42 (we)ird (name) S 77 1 1 0"))
    }

    @Test
    fun `parentOf rejects malformed input`() {
        assertNull(ProcTree.parentOf("no parens here"))
        assertNull(ProcTree.parentOf("12 (bash) S not-a-number"))
        assertNull(ProcTree.parentOf(""))
    }

    @Test
    fun `countDescendants counts the whole subtree excluding root`() {
        val parents = mapOf(
            1 to 0,
            10 to 1,   // shell spine (direct child of session root 1)
            11 to 10,  // job under the shell -> busy
            110 to 11, // grandchild of the job
            12 to 1,   // second direct child (unusual, still counted)
        )
        // Work under the shell = descendants of root's DIRECT children.
        // busyChildrenOf(1) = descendants(10) + descendants(12) = 2 + 0 = 2
        assertEquals(2, ProcTree.countDescendants(parents, 10) + ProcTree.countDescendants(parents, 12))
    }

    @Test
    fun `countDescendants idle shell has no work processes`() {
        // Session root 1 = proot (tracer); its direct child 2 = the guest bash.
        // Idle shell: nothing runs under bash -> no work processes.
        val parents = mapOf(2 to 1)
        val direct = parents.filterValues { it == 1 }.keys // {2} = the shell itself
        val busy = direct.sumOf { ProcTree.countDescendants(parents, it) }
        assertEquals(0, busy)
    }

    @Test
    fun `countDescendants busy shell counts only jobs under the shell`() {
        // bash(2) forked a job(3), which spawned a helper(30). proot/bash spine excluded.
        val parents = mapOf(2 to 1, 3 to 2, 30 to 3)
        val direct = parents.filterValues { it == 1 }.keys
        val busy = direct.sumOf { ProcTree.countDescendants(parents, it) }
        assertEquals(2, busy) // 3 and 30 — the spine (1, 2) never counts
    }

    @Test
    fun `countDescendants is cycle-safe`() {
        val parents = mapOf(1 to 2, 2 to 1, 3 to 1)
        val n = ProcTree.countDescendants(parents, 1)
        assertTrue(n >= 1) // terminates; exact count depends on traversal, must not hang
    }

    @Test
    fun `countDescendants orphans are ignored`() {
        // pid 99's parent (5) is not in the map — it must not attach to root 1.
        val parents = mapOf(99 to 5)
        assertEquals(0, ProcTree.countDescendants(parents, 1))
    }

    @Test
    fun `subtreeOf includes root and all descendants`() {
        val parents = mapOf(
            2 to 1,    // proot
            3 to 2,    // bash
            30 to 3,   // apt
            300 to 30, // dpkg
        )
        val tree = ProcTree.subtreeOf(parents, 1).toSet()
        assertEquals(setOf(1, 2, 3, 30, 300), tree)
    }

    @Test
    fun `subtreeOf with no children returns just root`() {
        assertEquals(listOf(7), ProcTree.subtreeOf(emptyMap(), 7))
    }
}
