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
 *  - symlinks/hardlinks are validated: their target must stay inside the destination
 *  - device/fifo special entries are skipped (not applicable in a userspace rootfs)
 *  - a hard cap on total extracted size guards against archive bombs
 */
class SafeTarExtractor(
    private val destDir: File,
    private val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    private val onEntry: ((name: String) -> Unit)? = null,
) {

    data class Result(val entries: Int, val bytes: Long)

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

    fun extract(input: InputStream, onProgress: ((bytes: Long) -> Unit)? = null): Result {
        var entries = 0
        var totalBytes = 0L
        destDir.mkdirs()
        if (!destDir.isDirectory) throw RuntimeError.Extraction("destination is not a directory: $destDir")

        TarArchiveInputStream(GzipCompressorInputStream(input.buffered())).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                val rel = sanitizePath(entry.name) ?: continue
                onEntry?.invoke(rel)
                val target = resolveInside(rel)

                when {
                    entry.isDirectory -> {
                        if (!target.isDirectory && !target.mkdirs() && !target.isDirectory) {
                            throw RuntimeError.Extraction("cannot create dir: $rel")
                        }
                    }
                    entry.isSymbolicLink -> {
                        val linkTarget = entry.linkName
                        val resolved = safeResolveLink(rel, linkTarget)
                        if (resolved != null) {
                            target.parentFile?.mkdirs()
                            if (!createSymlink(linkTarget, target)) {
                                // If symlink creation fails (filesystem support), fall back
                                // to copying the target content when possible.
                                copyLinkTargetIfPossible(linkTarget, target)
                            }
                        }
                    }
                    entry.isLink -> {
                        // hardlink: resolve inside rootfs and copy content
                        val linkRel = sanitizePath(entry.linkName)
                        if (linkRel != null) {
                            val src = resolveInside(linkRel)
                            target.parentFile?.mkdirs()
                            if (src.isFile) src.copyTo(target, overwrite = true)
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
        return Result(entries, totalBytes)
    }

    private fun safeResolveLink(linkRel: String, target: String): String? {
        // Validate that the link target cannot escape the rootfs via combined path
        val base = linkRel.substringBeforeLast('/', "")
        val combined = if (target.startsWith("/")) {
            target.removePrefix("/")
        } else if (base.isEmpty()) target else "$base/$target"
        val sanitized = sanitizePath(combined) ?: return null
        // reject .. after resolution through segments
        if (sanitized.split('/').any { it == ".." }) return null
        return sanitized
    }

    private fun createSymlink(linkTarget: String, target: File): Boolean = try {
        if (target.exists() || target.toString().let { java.nio.file.Files.isSymbolicLink(java.nio.file.Path.of(it)) }) {
            target.delete()
        }
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of(target.path),
            java.nio.file.Path.of(linkTarget),
        )
        true
    } catch (_: Exception) {
        false
    }

    private fun copyLinkTargetIfPossible(linkTarget: String, target: File) {
        if (!linkTarget.startsWith("/")) return
        // Absolute link target inside the rootfs (common: /usr/bin -> bin)
        val src = File(destDir, linkTarget.removePrefix("/"))
        if (src.isFile) {
            target.parentFile?.mkdirs()
            src.copyTo(target, overwrite = true)
        }
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
        // best-effort permission restore (tar mode bits): any execute bit -> executable
        if (mode and 0b001001001 != 0) {
            target.setExecutable(true, false)
        }
        return written
    }
}
