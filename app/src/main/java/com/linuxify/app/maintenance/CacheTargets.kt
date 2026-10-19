package com.linuxify.app.maintenance

import java.io.File

/**
 * Pure cache-discovery logic (issue #23) — no Android dependencies, JVM-tested.
 *
 * Per-project scope: well-known cache directories that are always safe to
 * delete. Anything NOT in these lists is never touched: no `build`, `dist`,
 * `target` or other output dirs (they are work products, not caches), and no
 * virtual-environment or dependency trees as a whole (`.venv`, `vendor`,
 * `node_modules` minus its `.cache` — removing them would force full
 * reinstalls, which is NOT "clearing cache").
 *
 * Safety rules, enforced in one place and unit-tested:
 *  - `.git` is never descended into (its object store must stay intact);
 *  - symbolic links are never followed, never deleted — only REAL directories
 *    inside the project are candidates;
 *  - matching is by exact directory name (or exact nested relative path for
 *    the nested list), anywhere in the tree up to [MAX_WALK_DEPTH].
 */
object CacheTargets {

    /** Cache directory names matched anywhere inside a project. */
    val PROJECT_CACHE_DIR_NAMES = setOf(
        "__pycache__",
        ".pytest_cache",
        ".mypy_cache",
        ".ruff_cache",
        ".ipynb_checkpoints",
        ".cache",
        ".gradle",
        ".javacache",
        ".scala-build-cache",
    )

    /** Nested caches matched by their path relative to the project root. */
    val PROJECT_NESTED_CACHE_DIRS = setOf(
        "node_modules/.cache",
        ".next/cache",
    )

    /** Walk depth cap — deep nesting is repo content, not caches. */
    const val MAX_WALK_DEPTH = 8

    /**
     * Enumerate cache directories under [projectDir]. Returns real directories
     * sorted by relative path. A matched cache dir is returned whole — its own
     * subtree is not walked further.
     */
    fun findProjectCaches(projectDir: File): List<File> {
        if (!projectDir.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        val stack = ArrayDeque<Pair<File, String>>() // dir, path relative to project root
        stack.addLast(projectDir to "")
        while (stack.isNotEmpty()) {
            val (dir, rel) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (c in children) {
                // Never follow or delete symlinks — a symlink named ".cache"
                // pointing outside the project is not ours to remove.
                if (java.nio.file.Files.isSymbolicLink(c.toPath())) continue
                if (!c.isDirectory) continue
                val childRel = if (rel.isEmpty()) c.name else "$rel/${c.name}"
                if (childRel == ".git" || childRel.startsWith(".git/")) continue
                val depth = childRel.count { it == '/' } + 1
                if (depth > MAX_WALK_DEPTH) continue
                val hit = c.name in PROJECT_CACHE_DIR_NAMES || childRel in PROJECT_NESTED_CACHE_DIRS
                if (hit) {
                    out.add(c)
                    continue // matched whole — do not descend
                }
                stack.addLast(c to childRel)
            }
        }
        return out.sortedBy { it.relativeTo(projectDir).path }
    }

    /**
     * Symlink-safe recursive size of [dir]. Symlinks are counted as zero (a
     * cache dir containing a symlink loop or a symlink to a huge external
     * tree must not inflate the estimate).
     */
    fun sizeOfDirSafe(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
            if (f.isFile) {
                total += f.length()
            } else if (f.isDirectory) {
                f.listFiles()?.let { stack.addAll(it) }
            }
        }
        return total
    }
}
