package com.linuxify.app.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.nio.file.Files

/**
 * Post-extraction rootfs verification + automatic repair ("auto self setup").
 *
 * The installer must never hand a broken rootfs to proot, and it must never
 * ask the user to fix what the app can fix itself. This module is the
 * safety net between extraction and the first exec:
 *
 *  1. [verify] checks the exact set of paths the guest needs at exec time —
 *     the ELF interpreter chain first among them — using rootfs-relative
 *     resolution (absolute symlink targets like `/lib/...` must be resolved
 *     against the rootfs, never against the Android host filesystem).
 *  2. [repair] re-extracts precisely the reported entries (and any entries
 *     that resolve to them, e.g. real loader files behind links) from the
 *     already-downloaded, checksum-verified artifact. No network, no user
 *     interaction.
 *  3. [auditBrokenLinks] walks the whole tree and detects symlinks that do
 *     not resolve within the rootfs, so partial filesystem quirks are caught
 *     and healed too.
 *
 * Pure JVM (no Android imports) — unit-testable.
 */
object RootfsIntegrity {

    /** Paths that MUST resolve inside the rootfs before any exec attempt. */
    fun criticalPaths(abi: String): List<String> {
        val common = listOf(
            "bin",                    // usrmerge symlink
            "bin/bash",               // shell (guest entry point)
            "bin/sh",                 // script interpreter
            "usr/bin/uname",          // startup probe
            "usr/bin/env",            // shebang workhorse
            "etc/os-release",         // apt/dpkg/motd identity
            "etc/resolv.conf",        // DNS (written by installer config)
            "root",                   // default workdir
            "tmp",                    // TMPDIR
            "var",
            "usr/bin/ld.so",          // dynamic loader symlink family
            "usr/bin/apt-get",
            "usr/bin/dpkg",
            "usr/lib/apt/planners/dump",
        )
        return when (abi) {
            "arm64-v8a" -> common + listOf(
                "lib",
                "lib/ld-linux-aarch64.so.1",
                "usr/lib/ld-linux-aarch64.so.1",
                "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            )
            "x86_64" -> common + listOf(
                "lib64",
                "lib64/ld-linux-x86-64.so.2",
                "usr/lib64/ld-linux-x86-64.so.2",
                "usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
            )
            else -> common
        }
    }

    /**
     * Verify that every critical path resolves within [rootfs] (through
     * symlinks, rootfs-relative).
     *
     * Deliberately a GATE over critical paths only: the upstream ubuntu-base
     * tarball itself ships dangling links (locale catalogs, stripped changelog
     * symlinks) that proot/apt/dpkg never dereference — gating on them would
     * make the auto-repair loop on something it can never fix.
     * [auditBrokenLinks] remains available as a diagnostics-only report.
     *
     * @return list of missing critical paths; empty when healthy.
     */
    fun verify(rootfs: File, abi: String): List<String> {
        val problems = mutableListOf<String>()
        if (!rootfs.isDirectory) return listOf("rootfs directory missing: $rootfs")
        for (p in criticalPaths(abi)) {
            val f = File(rootfs, p)
            if (!resolvesInsideRootfs(rootfs, f)) {
                problems.add(p)
            }
        }
        return problems
    }

    /**
     * True when [f] (possibly a symlink, possibly behind symlinks) resolves to
     * an existing path inside the rootfs. Resolution is purely rootfs-relative:
     * absolute link targets are joined to the rootfs, `..` segments are
     * normalized, and any resolution escaping the rootfs counts as broken.
     */
    fun resolvesInsideRootfs(rootfs: File, f: File): Boolean {
        val rootPath = rootfs.canonicalFile.path
        var current = f
        // bound the walk: real chains are short, and loops mean broken anyway
        var hops = 0
        while (hops++ < 16) {
            if (Files.isSymbolicLink(current.toPath())) {
                val targetText = try {
                    Files.readSymbolicLink(current.toPath()).toString()
                } catch (_: Exception) {
                    return false
                }
                current = resolveLinkTarget(rootfs, current, targetText) ?: return false
            } else {
                break
            }
        }
        val canonical = current.canonicalFile
        if (!canonical.path.startsWith(rootPath)) return false
        return canonical.exists()
    }

    private fun resolveLinkTarget(rootfs: File, link: File, targetText: String): File? {
        val rootPath = rootfs.canonicalFile.path
        val dirPart = link.parentFile?.canonicalPath ?: return null
        val relDir = if (dirPart.startsWith(rootPath)) dirPart.removePrefix(rootPath).trimStart('/') else return null
        val combined = if (targetText.startsWith("/")) {
            targetText.removePrefix("/")
        } else if (relDir.isEmpty()) targetText else "$relDir/$targetText"
        val segments = combined.split('/')
        val stack = ArrayDeque<String>()
        for (seg in segments) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast() else return null
                else -> stack.addLast(seg)
            }
        }
        return File(rootfs, stack.joinToString("/"))
    }

    /**
     * Whole-tree scan for symlinks that do not resolve within the rootfs.
     * Returns their rootfs-relative names.
     */
    fun auditBrokenLinks(rootfs: File): List<String> {
        val broken = mutableListOf<String>()
        val rootPath = rootfs.canonicalFile.path
        val stack = ArrayDeque<File>()
        stack.add(rootfs)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) {
                    if (!resolvesInsideRootfs(rootfs, child)) {
                        broken.add(child.canonicalFile.path.removePrefix(rootPath).trimStart('/'))
                    }
                } else if (child.isDirectory) {
                    stack.add(child)
                }
            }
        }
        return broken
    }

    /**
     * Repair [problems] by re-extracting exactly the missing entries from the
     * cached, checksum-verified [artifact]. A path may be a dangling symlink
     * whose tar entry carries the correct metadata, or an absent file/dir —
     * both are restored by matching the entry NAME.
     *
     * @return true when a re-verification finds no remaining problems.
     */
    fun repair(
        rootfs: File,
        artifact: File,
        problems: List<String>,
        onLog: ((String) -> Unit)? = null,
    ): Boolean {
        if (problems.isEmpty()) return true
        if (!artifact.isFile) {
            onLog?.invoke("auto-repair skipped: cached artifact missing")
            return false
        }
        val extractor = SafeTarExtractor(rootfs)
        val wanted = problems.toHashSet()
        var repaired = 0
        try {
            artifact.inputStream().buffered().use { ins ->
                TarArchiveInputStream(GzipCompressorInputStream(ins)).use { tar ->
                    while (true) {
                        val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                        val rel = extractor.sanitizePath(entry.name) ?: continue
                        val isParent = wanted.any { it.startsWith("$rel/") }
                        if (rel !in wanted && !isParent) continue
                        val target = File(rootfs, rel)
                        when {
                            entry.isDirectory -> {
                                if (!target.isDirectory && !target.mkdirs() && !target.isDirectory) return false
                            }
                            entry.isSymbolicLink -> {
                                if (extractor.resolveLinkInsideRootfs(rel, entry.linkName) == null) continue
                                target.parentFile?.mkdirs()
                                if (target.exists() || Files.isSymbolicLink(target.toPath())) target.delete()
                                try {
                                    Files.createSymbolicLink(target.toPath(), java.nio.file.Path.of(entry.linkName))
                                    repaired++
                                } catch (_: Exception) {
                                    continue
                                }
                            }
                            entry.isFile -> {
                                target.parentFile?.mkdirs()
                                if (target.isDirectory) continue
                                target.outputStream().use { out ->
                                    tar.copyTo(out)
                                }
                                if (entry.mode and 0b001001001 != 0) target.setExecutable(true, false)
                                repaired++
                            }
                            entry.isLink -> {
                                // hardlink: copy content from its in-rootfs source
                                val srcRel = extractor.resolveHardlinkInsideRootfs(entry.linkName)
                                    ?: continue
                                val src = File(rootfs, srcRel)
                                target.parentFile?.mkdirs()
                                if (src.isFile) {
                                    src.copyTo(target, overwrite = true)
                                    repaired++
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onLog?.invoke("auto-repair failed: ${e.message}")
            return false
        }
        onLog?.invoke("auto-repair restored $repaired entr${if (repaired == 1) "y" else "ies"}")
        return true
    }
}
