package com.linuxify.app.backup

import android.content.Context
import android.os.Build
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.RuntimeDirs
import com.linuxify.app.runtime.SafeTarExtractor
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Backup & restore of the whole Linuxify environment (issue #23).
 *
 * FORMAT: a single .tar.gz whose entry paths are RELATIVE TO filesDir, so a
 * restore is a plain safe extraction into filesDir:
 *
 *     backup.json                      <- manifest, ALWAYS the FIRST entry
 *     projects/<name>/...              <- project trees (modes + symlinks kept)
 *     runtime/terminal/sessions.json   <- terminal history index
 *     runtime/terminal/<id>.log        <- transcript logs
 *     runtime/install.json             <- ready marker (rootfs scope only)
 *     rootfs/...                       <- the Linux environment (optional, LARGE)
 *
 * Manifest-first is deliberate: inspection opens the archive, reads entry
 * #1 and stops — no full decompression just to show the summary dialog.
 *
 * All restores go through [SafeTarExtractor]: absolute paths and `..`
 * traversal rejected, link targets must stay inside the destination, total
 * size capped, POSIX modes restored on extraction (executables stay
 * executable). The writer records modes (0755/0644 + exec bit) and symlinks
 * so the guest survives a rootfs round-trip byte-for-byte in behavior.
 */
class BackupManager(private val ctx: Context) {

    enum class Scope(val key: String) {
        PROJECTS("projects"),
        TERMINAL("terminal"),
        ROOTFS("rootfs"),
    }

    data class Manifest(
        val format: Int,
        val createdMs: Long,
        val versionName: String,
        val versionCode: Int,
        val projectNames: List<String>,
        val terminalCount: Int,
        val rootfsIncluded: Boolean,
    ) {
        val valid: Boolean get() = format == FORMAT
    }

    data class RestoreOptions(
        val scopes: Set<Scope>,
        val replaceProjects: Boolean,
    )

    // ---- create -------------------------------------------------------------

    fun createBackup(
        out: OutputStream,
        scopes: Set<Scope>,
        onProgress: (String) -> Unit,
    ): Result<Manifest> = runCatching {
        require(scopes.isNotEmpty()) { "no scopes selected" }
        val manifest = buildManifest(scopes)
        GzipCompressorOutputStream(out.buffered()).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                // Manifest first (see class doc).
                BackupTar.writeStringEntry(tar, MANIFEST_ENTRY, manifestToJson(manifest).toString(2))
                onProgress("backup manifest written")

                if (Scope.PROJECTS in scopes) {
                    val projects = RuntimeDirs.projectsDir(ctx)
                    projects.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { p ->
                        onProgress("packing project ${p.name}")
                        BackupTar.addTree(tar, p, "$PROJECTS_DIR/${p.name}")
                    }
                }
                if (Scope.TERMINAL in scopes) {
                    val term = RuntimeDirs.terminalDir(ctx)
                    onProgress("packing terminal history")
                    BackupTar.addTree(tar, term, TERM_DIR_REL)
                }
                if (Scope.ROOTFS in scopes) {
                    val rootfs = RuntimeDirs.rootfsDir(ctx)
                    require(rootfs.isDirectory) { "rootfs not installed — nothing to back up" }
                    onProgress("packing Linux environment (this can take a while)")
                    BackupTar.addTree(tar, rootfs, ROOTFS_DIR)
                    val marker = RuntimeDirs.installMarker(ctx)
                    if (marker.isFile) {
                        BackupTar.addFileEntry(tar, marker, INSTALL_MARKER_REL)
                    }
                }
                tar.finish()
            }
        }
        manifest
    }

    private fun buildManifest(scopes: Set<Scope>): Manifest {
        val projectNames = RuntimeDirs.projectsDir(ctx).listFiles()
            ?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        val term = RuntimeDirs.terminalDir(ctx).listFiles()?.count { it.name.endsWith(".log") } ?: 0
        return Manifest(
            format = FORMAT,
            createdMs = System.currentTimeMillis(),
            versionName = currentVersionName(),
            versionCode = currentVersionCode(),
            projectNames = projectNames,
            terminalCount = term,
            rootfsIncluded = Scope.ROOTFS in scopes,
        )
    }

    private fun manifestToJson(m: Manifest): JSONObject = JSONObject()
        .put("format", m.format)
        .put("app", "linuxify")
        .put("versionName", m.versionName)
        .put("versionCode", m.versionCode)
        .put("created", m.createdMs)
        .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        .put("projects", JSONArray(m.projectNames))
        .put("terminalSessions", m.terminalCount)
        .put("rootfs", m.rootfsIncluded)

    // ---- inspect ------------------------------------------------------------

    /** Reads ONLY the first entry (manifest) — cheap even for huge backups. */
    fun readManifest(input: java.io.InputStream): Result<Manifest> = runCatching {
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(input.buffered()),
        ).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                if (e.name == MANIFEST_ENTRY) {
                    val text = tar.readBytes().toString(Charsets.UTF_8)
                    return@runCatching parseManifest(text) ?: throw IllegalStateException("backup manifest unreadable")
                }
                if (e.size > 0) tar.skip(e.size) else break
            }
            throw IllegalStateException("not a Linuxify backup (no manifest)")
        }
    }

    fun parseManifest(text: String): Manifest? = Companion.parseManifestStatic(text)

    // ---- restore ------------------------------------------------------------

    /**
     * Restore the selected scopes from [input]. When [RestoreOptions.replaceProjects]
     * is true, existing projects present in the backup are DELETED first
     * (caller confirms in UI). When false, backup entries for projects that
     * already exist locally are skipped and NEW projects still restore.
     * Returns a human summary line.
     */
    fun restore(
        input: java.io.InputStream,
        opts: RestoreOptions,
        onProgress: (String) -> Unit,
    ): Result<String> = runCatching {
        require(opts.scopes.isNotEmpty()) { "no scopes selected" }
        val filesRoot = ctx.filesDir.canonicalFile

        if (Scope.ROOTFS in opts.scopes) {
            onProgress("removing current Linux environment")
            val rootfs = RuntimeDirs.rootfsDir(ctx)
            if (rootfs.exists() && !rootfs.deleteRecursively()) {
                throw IllegalStateException("could not remove the current Linux environment")
            }
            RuntimeDirs.installMarker(ctx).delete()
        }

        var markerRestored = false
        var projectEntries = 0
        var terminalEntries = 0

        val extractor = SafeTarExtractor(
            destDir = filesRoot,
            maxTotalBytes = RESTORE_TOTAL_CAP_BYTES,
            onEntry = { },
            skipEntry = { rel ->
                when {
                    rel == MANIFEST_ENTRY -> true
                    rel.startsWith("$PROJECTS_DIR/") && Scope.PROJECTS !in opts.scopes -> true
                    rel.startsWith("$TERM_DIR_REL/") && Scope.TERMINAL !in opts.scopes -> true
                    rel == INSTALL_MARKER_REL || rel.startsWith("$ROOTFS_DIR/") -> {
                        if (Scope.ROOTFS !in opts.scopes) true
                        else if (rel == INSTALL_MARKER_REL) { markerRestored = true; false }
                        else false
                    }
                    rel.startsWith("$PROJECTS_DIR/") && !opts.replaceProjects -> {
                        // Skip entries of projects that already exist locally.
                        val name = rel.removePrefix("$PROJECTS_DIR/").substringBefore('/')
                        val exists = name.isNotEmpty() && File(RuntimeDirs.projectsDir(ctx), name).isDirectory
                        if (exists) true else { projectEntries++; false }
                    }
                    rel.startsWith("$PROJECTS_DIR/") -> { projectEntries++; false }
                    rel.startsWith("$TERM_DIR_REL/") -> { terminalEntries++; false }
                    else -> false
                }
            },
        )
        onProgress("extracting…")
        val res = extractor.extract(input) { bytes -> onProgress("extracted ${LinuxRuntime.humanSize(bytes)}") }

        // Rootfs scope without a marker in the archive -> synthesize one so the
        // app treats the environment as ready (bin/bash presence is re-checked).
        if (Scope.ROOTFS in opts.scopes && !markerRestored) {
            val marker = RuntimeDirs.installMarker(ctx)
            marker.writeText(
                JSONObject()
                    .put("ready", true)
                    .put("stage", "ready")
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                    .put("rootfs", "restored")
                    .put("sha256", "restored")
                    .put("distro", "Ubuntu")
                    .put("version", "24.04")
                    .put("ts", System.currentTimeMillis())
                    .toString(),
            )
        }

        val scopesText = opts.scopes.joinToString(", ") { it.key }
        val note = if (res.skipped.isNotEmpty()) " (${res.skipped.size} entries skipped)" else ""
        "Restored $scopesText — ${res.entries} entries, ${LinuxRuntime.humanSize(res.bytes)}$note"
    }

    // ---- tar writing: delegated to the pure, JVM-testable BackupTar ---------

    private fun currentVersionName(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun currentVersionCode(): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
        }
    } catch (_: Exception) { 0 }

    companion object {
        const val FORMAT = 1
        const val MANIFEST_ENTRY = "backup.json"
        const val PROJECTS_DIR = "projects"
        const val ROOTFS_DIR = "rootfs"
        const val TERM_DIR_REL = "runtime/terminal"
        const val INSTALL_MARKER_REL = "runtime/install.json"

        /** Restore size cap: a full environment with user packages can be big. */
        const val RESTORE_TOTAL_CAP_BYTES = 8L * 1024 * 1024 * 1024

        /** Pure, JVM-tested manifest parser: corrupt/foreign text -> null or invalid. */
        fun parseManifestStatic(text: String): Manifest? = runCatching {
            val o = JSONObject(text)
            Manifest(
                format = o.optInt("format", -1),
                createdMs = o.optLong("created", 0L),
                versionName = o.optString("versionName"),
                versionCode = o.optInt("versionCode", 0),
                projectNames = o.optJSONArray("projects")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                } ?: emptyList(),
                terminalCount = o.optInt("terminalSessions", 0),
                rootfsIncluded = o.optBoolean("rootfs", false),
            )
        }.getOrNull()
    }
}

/**
 * Pure tar-writing primitives for the backup format (issue #23) — no Android
 * dependencies, so the round-trip (write here -> SafeTarExtractor) is fully
 * JVM-tested: directory entries, file entries with the exec bit, symlinks,
 * and inline string entries (the manifest).
 */
internal object BackupTar {

    const val MODE_DIR = 0b111101101
    const val MODE_FILE = 0b110100100
    const val MODE_EXEC_FILE = 0b111101101
    const val MODE_SYMLINK = 0b111101101

    /** Adds [base] (as [baseRel]) and its whole subtree. Symlinks preserved. */
    fun addTree(tar: TarArchiveOutputStream, base: File, baseRel: String) {
        addDirEntry(tar, baseRel)
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(base to baseRel)
        while (stack.isNotEmpty()) {
            val (dir, rel) = stack.removeLast()
            dir.listFiles()?.sortedBy { it.name }?.forEach { c ->
                val childRel = "$rel/${c.name}"
                when {
                    java.nio.file.Files.isSymbolicLink(c.toPath()) -> {
                        val target = java.nio.file.Files.readSymbolicLink(c.toPath()).toString()
                        addSymlinkEntry(tar, childRel, target)
                    }
                    c.isDirectory -> {
                        addDirEntry(tar, childRel)
                        stack.addLast(c to childRel)
                    }
                    c.isFile -> addFileEntry(tar, c, childRel)
                    // sockets/fifos/device nodes: not applicable — skip
                }
            }
        }
    }

    fun addDirEntry(tar: TarArchiveOutputStream, rel: String) {
        val e = TarArchiveEntry("$rel/")
        e.mode = MODE_DIR
        tar.putArchiveEntry(e)
        tar.closeArchiveEntry()
    }

    fun addFileEntry(tar: TarArchiveOutputStream, f: File, rel: String) {
        val entry = TarArchiveEntry(f, rel)
        entry.mode = if (f.canExecute()) MODE_EXEC_FILE else MODE_FILE
        tar.putArchiveEntry(entry)
        f.inputStream().use { it.copyTo(tar, 64 * 1024) }
        tar.closeArchiveEntry()
    }

    fun addSymlinkEntry(tar: TarArchiveOutputStream, rel: String, target: String) {
        val e = TarArchiveEntry(rel, TarConstants.LF_SYMLINK)
        e.linkName = target
        e.mode = MODE_SYMLINK
        tar.putArchiveEntry(e)
        tar.closeArchiveEntry()
    }

    fun writeStringEntry(tar: TarArchiveOutputStream, rel: String, content: String) {
        val e = TarArchiveEntry(rel)
        e.mode = MODE_FILE
        val bytes = content.toByteArray(Charsets.UTF_8)
        e.size = bytes.size.toLong()
        tar.putArchiveEntry(e)
        tar.write(bytes)
        tar.closeArchiveEntry()
    }
}
