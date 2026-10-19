package com.linuxify.app.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.InputStream

/**
 * Safe extraction of a .tar.gz rootfs into a destination directory.
 *
 * Security rules (master prompt §4.3):
 *  - reject absolute paths and any path resolving outside the destination
 *  - symlinks/hardlinks are validated: their target must resolve inside the
 *    destination AFTER normalizing `..` segments (a target like
 *    `../lib/x86_64-linux-gnu/ld-linux.so.2` is legitimate inside a rootfs and
 *    MUST be preserved — silently dropping it breaks the ELF interpreter chain
 *    and every guest exec fails with `execve: No such file or directory`)
 *  - device/fifo special entries are skipped (not applicable in a userspace rootfs)
 *  - a hard cap on total extracted size guards against archive bombs
 *
 * Fidelity rules (learned from the on-device install failure v0.1.0-alpha.2):
 *  - every extractable entry must land on disk; anything genuinely unsafe is
 *    recorded in [Result.skipped] so the installer can surface it instead of
 *    shipping a silently incomplete rootfs
 *  - link creation never fails silently: if the filesystem refuses a symlink,
 *    the extractor copies the resolved target content as a fallback
 */
class SafeTarExtractor(
    private val destDir: File,
    private val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    private val onEntry: ((name: String) -> Unit)? = null,
    /**
     * Optional restore filter (issue #23): entries whose sanitized relative
     * path returns true are EXCLUDED from extraction (recorded in Result
     * .skipped as "(excluded by filter)"). Used by backup restore to extract
     * only the scopes the user selected. Never used by the installer.
     */
    private val skipEntry: ((name: String) -> Boolean)? = null,
) {

    data class Result(val entries: Int, val bytes: Long, val skipped: List<String> = emptyList())

    private val skipped = mutableListOf<String>()

    /** Return the sanitized relative path of a tar entry, or null if it must be skipped. */
    fun sanitizePath(name: String): String? {
        var n = name.replace('\\', '/')
        if (n.startsWith("/")) return null // absolute path — reject
        // strip leading ./ segments
        while (n.startsWith("./")) n = n.substring(2)
        if (n.isEmpty() || n == "." || n == "..") return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null // traversal — reject
        return parts.joinToString("/")
    }

    /**
     * Normalize [target] (a symlink or hardlink target, absolute or relative)
     * against the directory containing the link ([linkRel]) and return the
     * canonical rootfs-relative path, or null when it escapes the rootfs.
     *
     * This is the correct security boundary for links: `..` is only dangerous
     * when it walks OUT of the rootfs, never merely by appearing in the text.
     * (usrmerge layouts rely on `../lib/...`-style links for the ELF loaders.)
     */
    fun resolveLinkInsideRootfs(linkRel: String, target: String): String? {
        val base = linkRel.substringBeforeLast('/', "")
            .split('/').filter { it.isNotEmpty() && it != "." }
        var depth = 0
        // absolute targets start from the root, relative ones from the link's dir
        val segments = target.split('/')
        var sawRoot = target.startsWith("/")
        val stack = ArrayDeque<String>()
        if (!sawRoot) base.forEach { stack.addLast(it) }
        for (seg in segments) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    if (stack.isEmpty()) return null // escapes the rootfs — reject
                    stack.removeLast()
                }
                else -> stack.addLast(seg)
            }
        }
        if (stack.isEmpty()) return null // link to "/" itself — nothing to create
        return stack.joinToString("/")
    }

    /**
     * Normalize a HARDLINK target to a rootfs-relative path, or null when it
     * escapes. POSIX tar hardlink targets are relative to the ARCHIVE ROOT
     * (unlike symlink targets, which are relative to the link's directory).
     */
    fun resolveHardlinkInsideRootfs(linkName: String): String? {
        var n = linkName.replace('\\', '/')
        while (n.startsWith("./")) n = n.substring(2)
        n = n.removePrefix("/")
        val stack = ArrayDeque<String>()
        for (seg in n.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast() else return null
                else -> stack.addLast(seg)
            }
        }
        if (stack.isEmpty()) return null
        return stack.joinToString("/")
    }

    private fun resolveInside(rel: String): File {
        val f = File(destDir, rel)
        val canonicalParent = destDir.canonicalFile
        // We construct paths ourselves from sanitized segments, so lexical check suffices,
        // but keep a canonical check as defense-in-depth.
        if (!f.canonicalFile.path.startsWith(canonicalParent.path)) {
            throw RuntimeError.Extraction("path escapes destination: $rel")
        }
        return f
    }

    private data class HardlinkToCopy(val rel: String, val srcRel: String, val mode: Int)

    fun extract(input: InputStream, onProgress: ((bytes: Long) -> Unit)? = null): Result {
        var entries = 0
        var totalBytes = 0L
        skipped.clear()
        val deferredHardlinks = mutableListOf<HardlinkToCopy>()
        destDir.mkdirs()
        if (!destDir.isDirectory) throw RuntimeError.Extraction("destination is not a directory: $destDir")

        TarArchiveInputStream(GzipCompressorInputStream(input.buffered())).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                val rel = sanitizePath(entry.name)
                if (rel == null) {
                    skipped.add("${entry.name} (unsafe path)")
                    continue
                }
                if (skipEntry?.invoke(rel) == true) {
                    skipped.add("$rel (excluded by filter)")
                    // Must still consume the entry payload so the stream stays aligned.
                    if (entry.isFile && entry.size > 0) {
                        var remaining = entry.size
                        val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        while (remaining > 0) {
                            val n = tar.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            remaining -= n
                        }
                    }
                    continue
                }
                onEntry?.invoke(rel)
                val target = resolveInside(rel)

                when {
                    entry.isDirectory -> {
                        if (!target.isDirectory && !target.mkdirs() && !target.isDirectory) {
                            throw RuntimeError.Extraction("cannot create dir: $rel")
                        }
                        RootfsIntegrity.applyTarMode(target, entry.mode, directory = true)
                    }
                    entry.isSymbolicLink -> {
                        val linkTarget = entry.linkName
                        val resolved = resolveLinkInsideRootfs(rel, linkTarget)
                        if (resolved == null) {
                            skipped.add("$rel -> $linkTarget (link escapes rootfs)")
                        } else {
                            target.parentFile?.mkdirs()
                            if (!createSymlink(linkTarget, target)) {
                                // Filesystem refused the symlink (rare on app-private
                                // storage): fall back to materializing the link's
                                // resolved content so the path still works.
                                if (!copyResolvedTargetIfPossible(resolved, target, entry.mode)) {
                                    skipped.add("$rel -> $linkTarget (symlink refused, no copyable target)")
                                }
                            }
                        }
                    }
                    entry.isLink -> {
                        // hardlink: target is archive-root-relative (POSIX); the
                        // source may appear LATER in the archive (ubuntu-base
                        // ships usr/bin/perl5.38.2 as a hardlink to usr/bin/perl,
                        // with perl stored after it), so defer to a second pass.
                        val linkRel = resolveHardlinkInsideRootfs(entry.linkName)
                        if (linkRel == null) {
                            skipped.add("$rel -> ${entry.linkName} (hardlink escapes rootfs)")
                        } else {
                            deferredHardlinks.add(HardlinkToCopy(rel, linkRel, entry.mode))
                        }
                    }
                    entry.isFIFO || entry.isBlockDevice || entry.isCharacterDevice -> {
                        // not applicable — skip silently
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        val written = copyWithLimit(tar, target, entry.size, totalBytes, entry.mode)
                        totalBytes += written
                        if (totalBytes > maxTotalBytes) {
                            throw RuntimeError.Extraction("archive exceeds size cap ($maxTotalBytes bytes)")
                        }
                    }
                }
                entries++
                onProgress?.invoke(totalBytes)
            }
        }
        // second pass: hardlink sources now exist regardless of archive order
        for ((rel, srcRel, mode) in deferredHardlinks) {
            val target = resolveInside(rel)
            val src = resolveInside(srcRel)
            target.parentFile?.mkdirs()
            if (src.isFile) {
                src.copyTo(target, overwrite = true)
                // File.copyTo() does NOT carry permissions: without this the
                // copy lands 0660 and every hardlinked executable (perl,
                // gunzip's uncompress alias) would later fail with EACCES.
                RootfsIntegrity.applyTarMode(target, mode)
            } else {
                skipped.add("$rel -> $srcRel (hardlink source absent)")
            }
        }
        return Result(entries, totalBytes, skipped.toList())
    }

    private fun createSymlink(linkTarget: String, target: File): Boolean = try {
        if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) {
            target.delete()
        }
        java.nio.file.Files.createSymbolicLink(
            target.toPath(),
            java.nio.file.Paths.get(linkTarget),
        )
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Materialize a link whose symlink creation failed by copying the CONTENT
     * of its rootfs-resolved path. Works for relative AND absolute targets
     * because [resolveLinkInsideRootfs] already normalized them. The tar entry
     * mode is applied because File.copyTo() lands the copy with default bits
     * (0660) — a fallback copy of an executable would otherwise be unexecuable.
     */
    private fun copyResolvedTargetIfPossible(resolved: String, target: File, mode: Int): Boolean {
        val src = File(destDir, resolved)
        if (src.isFile) {
            target.parentFile?.mkdirs()
            return try {
                src.copyTo(target, overwrite = true)
                RootfsIntegrity.applyTarMode(target, mode)
                true
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    private fun copyWithLimit(
        input: InputStream,
        target: File,
        entrySize: Long,
        bytesSoFar: Long,
        mode: Int,
    ): Long {
        val remaining = maxTotalBytes - bytesSoFar
        var written = 0L
        target.outputStream().use { out ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (written < entrySize) {
                val want = minOf(buf.size.toLong(), entrySize - written).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                if (written + n > remaining) {
                    throw RuntimeError.Extraction("archive exceeds size cap")
                }
                out.write(buf, 0, n)
                written += n
            }
        }
        // Full mode-bit restoration (tar fidelity): executables keep +x exactly
        // where the tarball has it, non-executables stay non-executable. Owner
        // read/write is forced as a floor so the installer can always repair.
        RootfsIntegrity.applyTarMode(target, mode)
        return written
    }
}
