package com.linuxify.app.terminal

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Minimal /proc-based process-tree inspection used for SMART session close.
 *
 * Why this exists: a terminal session's process tree always contains fixed
 * infrastructure — the session root is proot (the tracer), and proot's direct
 * child is the guest shell itself. Counting *all* descendants would therefore
 * always report a "busy" session (proot + bash are always there), which is
 * exactly the nagging behavior users complain about: closing a NORMAL, idle
 * shell should never ask anything. The meaningful question is narrower: does
 * the shell have any *work* running beneath it (foreground jobs, background
 * jobs, servers started from that shell)?
 *
 * The resulting policy, enforced by TerminalScreen.requestClose:
 *   - ended session        -> remove instantly, no prompt
 *   - idle shell (no jobs) -> close instantly, no prompt (a "normal shell" never nags)
 *   - shell with live jobs -> confirm once, naming the process count
 */
object ProcTree {

    /**
     * Parse the parent PID from a `/proc/<pid>/stat` payload.
     *
     * Layout: `pid (comm) state ppid ...`. The comm field is wrapped in
     * parentheses and may itself contain spaces or parentheses, so the state
     * field starts after the LAST ')' — this keeps the parse robust for
     * names like `gunicorn: m`. Returns null when unparsable.
     */
    fun parentOf(stat: String): Int? {
        val closeParen = stat.lastIndexOf(')')
        if (closeParen < 0) return null
        val fields = stat.substring(closeParen + 1).trim().split(' ').filter { it.isNotEmpty() }
        // fields[0] = state, fields[1] = ppid
        return fields.getOrNull(1)?.toIntOrNull()
    }

    /**
     * Count processes strictly descending from [root] (root excluded) in the
     * given pid -> ppid snapshot. Handles orphans (parent not in map) and is
     * cycle-safe.
     */
    fun countDescendants(parents: Map<Int, Int>, root: Int): Int {
        val childrenOf = HashMap<Int, MutableList<Int>>()
        for ((pid, ppid) in parents) {
            childrenOf.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        var count = 0
        val seen = HashSet<Int>()
        val queue = ArrayDeque(childrenOf[root].orEmpty())
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!seen.add(pid)) continue
            count++
            childrenOf[pid]?.let(queue::addAll)
        }
        return count
    }

    /**
     * Snapshot /proc and count live *work* processes under [rootPid]: the
     * descendants of root's direct children (i.e. grandchildren and below of
     * the session root). The fixed proot->shell spine is deliberately not
     * counted — see the class doc.
     *
     * Best-effort: /proc entries vanish as processes exit; any read failure
     * contributes 0 rather than failing the close action.
     */
    fun busyChildrenOf(rootPid: Int): Int {
        if (rootPid <= 0) return 0
        return try {
            val parents = HashMap<Int, Int>()
            snapshotProc(parents)
            parents.filterValues { it == rootPid }.keys.sumOf { child ->
                countDescendants(parents, child)
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * All pids in root's subtree INCLUDING root itself (fresh /proc snapshot).
     * Used for closing a session: every process in the tree must die, not just
     * the tracer — SIGKILL to proot alone can orphan the guest shell and its
     * background jobs.
     */
    fun killTreeBestEffort(rootPid: Int) {
        if (rootPid <= 0) return
        val tree = try {
            val parents = HashMap<Int, Int>()
            snapshotProc(parents)
            subtreeOf(parents, rootPid)
        } catch (_: Exception) {
            listOf(rootPid)
        }
        for (pid in tree) {
            try {
                Os.kill(pid, OsConstants.SIGKILL)
            } catch (_: Throwable) {
                // already gone — fine
            }
        }
    }

    /** All pids in root's subtree INCLUDING root, from the given snapshot. */
    private fun subtreeOf(parents: Map<Int, Int>, root: Int): List<Int> {
        val childrenOf = HashMap<Int, MutableList<Int>>()
        for ((pid, ppid) in parents) {
            childrenOf.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val out = mutableListOf(root)
        val seen = HashSet<Int>()
        val queue = ArrayDeque(childrenOf[root].orEmpty())
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!seen.add(pid)) continue
            out.add(pid)
            childrenOf[pid]?.let(queue::addAll)
        }
        return out
    }

    private fun snapshotProc(parents: MutableMap<Int, Int>) {
        val entries = File("/proc").listFiles() ?: return
        for (dir in entries) {
            val pid = dir.name.toIntOrNull() ?: continue
            val stat = try {
                File(dir, "stat").readText()
            } catch (_: Exception) {
                continue // process exited mid-scan; skip
            }
            val ppid = parentOf(stat) ?: continue
            parents[pid] = ppid
        }
    }
}
