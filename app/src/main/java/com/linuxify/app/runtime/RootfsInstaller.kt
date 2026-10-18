package com.linuxify.app.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Staged, resumable, verifiable installer for the Linux root filesystem.
 *
 * Stage markers live in runtime/install.json. Each completed stage is recorded
 * atomically; an interrupted install resumes or cleanly restarts — it can never
 * report a partially extracted rootfs as READY (the ready marker is written only
 * after post-install verification succeeds).
 */
class RootfsInstaller(private val ctx: Context) {

    sealed class InstallState {
        data object NotInstalled : InstallState()
        data object Checking : InstallState()
        data class Downloading(val bytesDone: Long, val bytesTotal: Long) : InstallState()
        data object Verifying : InstallState()
        data class Extracting(val bytesDone: Long) : InstallState()
        data object Configuring : InstallState()
        data object VerifyingInstall : InstallState()
        data class Ready(val distro: String, val version: String) : InstallState()
        data class Failed(val error: RuntimeError, val userMessage: String) : InstallState()
    }

    private val _state = MutableStateFlow<InstallState>(restoreInitial())
    val state: StateFlow<InstallState> = _state

    private val logBuffer = ArrayDeque<String>()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    @Volatile
    var cancelled: Boolean = false
        private set

    private fun appendLog(line: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > 400) logBuffer.removeFirst()
            _log.value = logBuffer.toList()
        }
    }

    private fun restoreInitial(): InstallState {
        return if (isReadyMarkerPresent()) {
            InstallState.Ready(RuntimeMetadata.DISTRO_NAME, RuntimeMetadata.DISTRO_VERSION)
        } else {
            InstallState.NotInstalled
        }
    }

    fun isReadyMarkerPresent(): Boolean {
        val marker = RuntimeDirs.installMarker(ctx)
        if (!marker.exists()) return false
        return try {
            val json = JSONObject(marker.readText())
            json.optBoolean("ready", false) &&
                RuntimeDirs.rootfsDir(ctx).let { it.isDirectory && File(it, "bin/bash").isFile }
        } catch (_: Exception) {
            false
        }
    }

    fun cancel() {
        cancelled = true
    }

    /** Idempotently run the full installation. Safe to call after interruption. */
    suspend fun install(): Result<InstallState.Ready> = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            if (isReadyMarkerPresent()) {
                // A ready marker is only trustworthy while the packaged native
                // runtime is still present (e.g. after an app update). If the
                // binaries vanished, the marker is stale: clear it and reinstall
                // instead of presenting a runtime that cannot spawn anything.
                if (RuntimeDirs.prootAvailable(ctx) && RuntimeDirs.prootLoaderAvailable(ctx)) {
                    val ready = InstallState.Ready(RuntimeMetadata.DISTRO_NAME, RuntimeMetadata.DISTRO_VERSION)
                    _state.value = ready
                    return@withContext Result.success(ready)
                }
                appendLog("install marker present but native runtime binaries are missing — clearing stale marker and reinstalling")
                RuntimeDirs.installMarker(ctx).delete()
            }

            _state.value = InstallState.Checking
            if (!RuntimeDirs.prootAvailable(ctx)) {
                throw RuntimeError.ProotMissing("libproot.so not found in nativeLibraryDir")
            }
            if (!RuntimeDirs.prootLoaderAvailable(ctx)) {
                // Fail here, before any download: without the ELF loader proot
                // cannot start guest programs at all (it execs the loader in
                // place of every guest binary).
                throw RuntimeError.ProotMissing(
                    "libproot-loader.so not found or not executable in nativeLibraryDir",
                )
            }

            // Native dependency preflight BEFORE downloading anything: proves the
            // packaged proot actually links and runs on this device, and self-heals
            // linker-visible dependency-name mismatches via private syslib shims.
            // A broken runtime must fail here, not after a 35 MB download.
            appendLog("checking native runtime…")
            val preflight = LinuxRuntime(ctx).preflightProot { appendLog(it) }
            if (preflight.isFailure) {
                throw preflight.exceptionOrNull() as RuntimeError
            }

            val artifact = RuntimeMetadata.artifactForAbi(android.os.Build.SUPPORTED_ABIS)
                ?: throw RuntimeError.UnsupportedArch(android.os.Build.SUPPORTED_ABIS.joinToString(",").ifEmpty { "unknown" })
            appendLog("ABI: ${artifact.abi}; artifact: ${artifact.fileName}")

            // storage pre-check: download + extracted + margin
            val required = RuntimeMetadata.ROOTFS_APPROX_DOWNLOAD_BYTES +
                RuntimeMetadata.ROOTFS_APPROX_EXTRACTED_BYTES * 2 // staging + final
            val available = RuntimeDirs.availableBytes(ctx)
            if (available < required) {
                throw RuntimeError.InsufficientStorage(available, required)
            }
            appendLog("storage ok: ${available / (1024 * 1024)} MB available")

            val archive = downloadResumable(artifact)

            _state.value = InstallState.Verifying
            appendLog("verifying SHA-256…")
            val actual = Checksum.sha256(archive)
            if (!Checksum.matches(artifact.sha256, actual)) {
                archive.delete()
                throw RuntimeError.Checksum(artifact.sha256, actual)
            }
            appendLog("checksum ok")
            writeStage("verified", artifact)

            val staging = RuntimeDirs.stagingDir(ctx)
            staging.deleteRecursively()
            staging.mkdirs()

            _state.value = InstallState.Extracting(0)
            appendLog("extracting rootfs…")
            val extractor = SafeTarExtractor(staging, onEntry = { })
            archive.inputStream().buffered().use { ins ->
                extractor.extract(ins) { bytes ->
                    _state.value = InstallState.Extracting(bytes)
                    if (cancelled) throw RuntimeError.Cancelled
                }
            }
            // readiness sanity: shell must exist in extracted tree
            if (!File(staging, "bin/bash").isFile) {
                throw RuntimeError.Extraction("bin/bash missing after extraction")
            }
            appendLog("extracted ok")
            writeStage("extracted", artifact)

            _state.value = InstallState.Configuring
            configureUserspace(staging, artifact)
            appendLog("configured")

            // atomic promotion: staging -> rootfs
            val rootfs = RuntimeDirs.rootfsDir(ctx)
            if (rootfs.exists()) {
                // never silently destroy an existing healthy rootfs — but we only get here
                // when the ready marker was absent, i.e. a previous install never completed.
                val backup = File(ctx.filesDir, "rootfs.incomplete.old")
                backup.deleteRecursively()
                rootfs.renameTo(backup)
                rootfs.deleteRecursively()
                backup.deleteRecursively()
            }
            if (!staging.renameTo(rootfs)) {
                throw RuntimeError.Extraction("atomic promotion of staged rootfs failed")
            }
            writeStage("promoted", artifact)

            _state.value = InstallState.VerifyingInstall

            // --- auto self setup: verify the tree the way proot will consume it, ---
            // --- and repair anything the extraction or the filesystem got wrong ---
            appendLog("verifying rootfs integrity…")
            var problems = RootfsIntegrity.verify(rootfs, artifact.abi)
            if (problems.isNotEmpty()) {
                appendLog("integrity: ${problems.size} issue(s) found — auto-repairing (no user action needed)")
                if (!RootfsIntegrity.repair(rootfs, archive, problems) { appendLog(it) }) {
                    throw RuntimeError.Extraction(
                        "rootfs auto-repair could not restore: ${problems.take(5).joinToString(", ")}",
                    )
                }
                problems = RootfsIntegrity.verify(rootfs, artifact.abi)
                if (problems.isNotEmpty()) {
                    throw RuntimeError.Extraction(
                        "rootfs still incomplete after auto-repair: ${problems.take(5).joinToString(", ")}",
                    )
                }
                appendLog("integrity ok after auto-repair")
            } else {
                appendLog("integrity ok (${RootfsIntegrity.criticalPaths(artifact.abi).size} critical paths + full link audit)")
            }

            // Exec-bit self-heal BEFORE the first probe: filesystems and copy
            // paths can drop mode bits, and the loader must be able to map and
            // exec the guest binaries. Fixing this here keeps errors invisible.
            val fixedBits = RootfsIntegrity.ensureExecutableBits(rootfs, artifact.abi) { appendLog(it) }
            if (fixedBits > 0) appendLog("exec permissions restored on $fixedBits file(s)")

            appendLog("verifying command execution…")
            val runtime = LinuxRuntime(ctx)
            verifyExec(runtime, artifact, archive)

            writeReadyMarker(artifact)
            appendLog("install complete")
            val ready = InstallState.Ready(RuntimeMetadata.DISTRO_NAME, RuntimeMetadata.DISTRO_VERSION)
            _state.value = ready
            Result.success(ready)
        } catch (e: RuntimeError) {
            val userMessage = userMessageFor(e)
            appendLog("FAILED: ${e.technical}")
            _state.value = InstallState.Failed(e, userMessage)
            Result.failure(e)
        } catch (e: Exception) {
            val err = RuntimeError.Io(e.message ?: e.javaClass.simpleName)
            appendLog("FAILED: ${err.technical}")
            _state.value = InstallState.Failed(err, "Installation failed unexpectedly. Technical details are available in the log.")
            Result.failure(err)
        }
    }

    /**
     * Exec verification with layered fallbacks and self-healing — the install
     * only fails here when every automatic fix has been attempted.
     *
     * Attempt chain (each retried once after an automatic repair):
     *  1. /bin/bash -lc "uname -s && echo LINUXIFY_OK"   (full shell path)
     *  2. /bin/sh   -c  "uname -s && echo LINUXIFY_OK"   (dash instead of bash)
     *  3. /usr/bin/uname                                  (no shell dependency)
     *
     * Between attempts: linker failures trigger the native shim repair, any
     * other failure triggers the rootfs integrity verify + auto-repair.
     */
    private fun verifyExec(runtime: LinuxRuntime, artifact: RuntimeMetadata.RootfsArtifact, archive: File) {
        data class Attempt(val label: String, val run: () -> LinuxRuntime.ExecResult)

        val attempts = listOf(
            Attempt("bash") { runtime.boundedExec("uname -s && echo LINUXIFY_OK", timeoutMs = 60_000) },
            Attempt("sh") { runtime.boundedExec("uname -s && echo LINUXIFY_OK", timeoutMs = 60_000, shell = "/bin/sh") },
            Attempt("direct uname") { runtime.execInRootfs(listOf("/usr/bin/uname"), timeoutMs = 60_000) },
        )
        var last: LinuxRuntime.ExecResult? = null
        for (attempt in attempts) {
            var res = attempt.run()
            if (passed(res)) {
                appendLog("verification ok via ${attempt.label}: ${res.stdout.trim().lineSequence().firstOrNull() ?: ""}")
                return
            }
            val combined = res.stderr + res.stdout
            if (LinkerDiagnostics.isLinkFailure(combined)) {
                appendLog("linker failure at verification (${attempt.label}) — self-repair and retry")
                runtime.preflightProot { appendLog(it) }
                res = attempt.run()
                if (passed(res)) {
                    appendLog("verification ok via ${attempt.label} after native self-repair")
                    return
                }
            }
            appendLog("probe ${attempt.label} failed: rc=${res.exitCode} err=${res.stderr.trim().take(160)}")
            appendLog("auto-repair: re-verifying rootfs integrity before next attempt")
            val problems = RootfsIntegrity.verify(RuntimeDirs.rootfsDir(ctx), artifact.abi)
            if (problems.isNotEmpty()) {
                appendLog("integrity: ${problems.size} issue(s) — repairing")
                RootfsIntegrity.repair(RuntimeDirs.rootfsDir(ctx), archive, problems) { appendLog(it) }
            } else {
                appendLog("integrity clean — failure is at exec level, trying next fallback")
            }
            // Second self-heal axis: mode bits. Some filesystems/copy paths can
            // leave exec-critical files without +x; fix and retry this attempt.
            val fixed = RootfsIntegrity.ensureExecutableBits(RuntimeDirs.rootfsDir(ctx), artifact.abi) { appendLog(it) }
            if (fixed > 0) {
                appendLog("exec permissions restored on $fixed file(s) — retrying ${attempt.label}")
                res = attempt.run()
                if (passed(res)) {
                    appendLog("verification ok via ${attempt.label} after exec-bit repair")
                    return
                }
            }
            last = res
        }
        val r = last
        throw RuntimeError.Verification(
            "all ${attempts.size} exec probes failed (bash, sh, direct uname). " +
                "last: rc=${r?.exitCode} out=${r?.stdout?.take(200)} err=${r?.stderr?.take(300)}. " +
                execDiagnostics(),
        )
    }

    /**
     * Facts, not guesses, for the final verification error:
     *  - mode/size of the probe binary and the ELF loader as they are on disk
     *  - a DIRECT host execve of the probe binary (no proot). The kernel checks
     *    exec permission BEFORE parsing the ELF, so the errno discriminates:
     *      EACCES  -> filesystem/SELinux denies exec of app-private files;
     *      ENOENT  -> permission checks PASSED (the error comes from the guest
     *                 ELF interpreter path, expected outside proot) — meaning
     *                 the failure was proot-pipeline specific, not permissions.
     */
    private fun execDiagnostics(): String {
        val rootfs = RuntimeDirs.rootfsDir(ctx)
        val uname = File(rootfs, "usr/bin/uname")
        val loader = RuntimeDirs.prootLoaderBinary(ctx)
        fun statLine(f: File): String =
            if (!f.exists()) "${f.name}: ABSENT"
            else "${f.name}: mode=%o size=%d".format(octalMode(f), f.length())
        val direct = if (uname.isFile) {
            try {
                val p = ProcessBuilder(uname.absolutePath).start()
                val finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                p.destroy()
                if (finished) {
                    "direct execve ok (rc=${p.exitValue()}) — permissions allow exec; failure was proot-pipeline specific"
                } else {
                    "direct execve timed out"
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                when {
                    "error=13" in msg || "Permission denied" in msg ->
                        "direct execve: Permission denied — filesystem/SELinux denies exec of app-private files on this device (independent of proot)"
                    "error=2" in msg || "No such file" in msg ->
                        "direct execve reached ELF parsing (ENOENT on the guest interpreter is expected without proot) — exec permissions are fine; failure was proot-pipeline specific"
                    else -> "direct execve failed: ${msg.take(120)}"
                }
            }
        } else {
            "direct execve not attempted: uname absent"
        }
        return "diagnostics: ${statLine(uname)}; ${statLine(loader)}; $direct"
    }

    private fun octalMode(f: File): Int = try {
        val perms = java.nio.file.Files.getPosixFilePermissions(f.toPath())
        var m = 0
        if (java.nio.file.attribute.PosixFilePermission.OWNER_READ in perms) m = m or 0b100_000_000
        if (java.nio.file.attribute.PosixFilePermission.OWNER_WRITE in perms) m = m or 0b010_000_000
        if (java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE in perms) m = m or 0b001_000_000
        if (java.nio.file.attribute.PosixFilePermission.GROUP_READ in perms) m = m or 0b000_100_000
        if (java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE in perms) m = m or 0b000_001_000
        if (java.nio.file.attribute.PosixFilePermission.OTHERS_READ in perms) m = m or 0b000_000_100
        if (java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE in perms) m = m or 0b000_000_001
        m
    } catch (_: Exception) {
        0
    }

    private fun passed(res: LinuxRuntime.ExecResult): Boolean {
        val combined = res.stdout + res.stderr
        return res.exitCode == 0 && (combined.contains("LINUXIFY_OK") || combined.trim().contains("Linux"))
    }

    private fun userMessageFor(e: RuntimeError): String = when (e) {
        is RuntimeError.UnsupportedArch ->
            "This device's CPU architecture (${e.abi}) is not supported by the pinned runtime artifacts."
        is RuntimeError.InsufficientStorage ->
            "Not enough storage: ${e.available / (1024 * 1024)} MB available, ${e.required / (1024 * 1024)} MB needed. Free up space and retry."
        is RuntimeError.Network ->
            "Download failed: ${e.why}. Check your connection and retry — the download resumes where it stopped."
        is RuntimeError.Checksum ->
            "The downloaded files did not match their published checksums and were discarded. Retry the download."
        is RuntimeError.Extraction -> "Extracting the Linux filesystem failed: ${e.why}"
        is RuntimeError.Verification -> "The Linux environment was installed but failed its startup verification: ${e.why}"
        is RuntimeError.ProotMissing -> "A required bundled component is missing from this APK build: ${e.detail}"
        is RuntimeError.NativeDep ->
            "The Linux runtime could not start because a required library (${e.missing}) is not available on this device: ${e.detail}. Install the latest Linuxify build — it ships all required libraries."
        is RuntimeError.Cancelled -> "Installation was cancelled."
        is RuntimeError.Io -> "Unexpected storage error: ${e.why}"
    }

    private fun writeStage(stage: String, artifact: RuntimeMetadata.RootfsArtifact) {
        val marker = RuntimeDirs.installMarker(ctx)
        val json = JSONObject()
            .put("stage", stage)
            .put("abi", artifact.abi)
            .put("rootfs", artifact.fileName)
            .put("sha256", artifact.sha256)
            .put("ts", System.currentTimeMillis())
        marker.writeTextCompat(json.toString())
    }

    private fun writeReadyMarker(artifact: RuntimeMetadata.RootfsArtifact) {
        val marker = RuntimeDirs.installMarker(ctx)
        val json = JSONObject()
            .put("ready", true)
            .put("stage", "ready")
            .put("abi", artifact.abi)
            .put("rootfs", artifact.fileName)
            .put("sha256", artifact.sha256)
            .put("distro", RuntimeMetadata.DISTRO_NAME)
            .put("version", RuntimeMetadata.DISTRO_VERSION)
            .put("ts", System.currentTimeMillis())
        marker.writeTextCompat(json.toString())
    }

    /** Download with HTTP Range resume into a .part file, atomic promote on completion. */
    private suspend fun downloadResumable(artifact: RuntimeMetadata.RootfsArtifact): File {
        val dir = RuntimeDirs.downloadsDir(ctx)
        val part = File(dir, artifact.fileName + ".part")
        val dest = File(dir, artifact.fileName)
        if (dest.isFile && Checksum.matches(artifact.sha256, Checksum.sha256(dest))) {
            appendLog("download already complete (reused)")
            return dest
        }
        val url = URL(RuntimeMetadata.urlFor(artifact))
        var attempt = 0
        while (true) {
            if (cancelled) throw RuntimeError.Cancelled
            // Flaky-network contract: the download MUST self-recover from
            // mid-stream stalls and drops. Range-resume picks up from the last
            // written byte; backoff is exponential (2s,4s,8s,16s,30s,30s,30s)
            // with ±20% jitter so a room full of retrying clients de-synchronizes.
            if (attempt >= 8) throw RuntimeError.Network("gave up after $attempt attempts")
            attempt++
            var conn: HttpURLConnection? = null
            try {
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (part.isFile) {
                    conn.setRequestProperty("Range", "bytes=${part.length()}-")
                }
                conn.connect()
                val code = conn.responseCode
                if (code !in 200..299 && code != 206) {
                    // some servers ignore Range; restart cleanly
                    part.delete()
                    throw IOException("HTTP $code for ${url.host}")
                }
                val resume = code == 206 && part.isFile
                val body = conn.inputStream
                val total = if (conn.contentLengthLong > 0) {
                    conn.contentLengthLong + if (resume) part.length() else 0
                } else -1L

                java.io.FileOutputStream(part, resume).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var readTotal = if (resume) part.length() else 0L
                    while (true) {
                        if (cancelled) throw RuntimeError.Cancelled
                        if (!currentCoroutineContext().isActive) throw RuntimeError.Cancelled
                        val n = body.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        readTotal += n
                        _state.value = InstallState.Downloading(readTotal, total)
                    }
                    out.flush()
                }
                conn.disconnect()
                // atomic promote
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    // rename across same dir should work; fall back to copy
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                return dest
            } catch (e: RuntimeError) {
                throw e
            } catch (e: Exception) {
                runCatching { conn?.disconnect() }
                appendLog("download attempt $attempt failed: ${e.message}; resuming in a moment")
                val backoff = minOf(30_000L, 2_000L * (1L shl (attempt - 1)))
                val jittered = (backoff * 0.8 + backoff * 0.4 * Math.random()).toLong()
                kotlinx.coroutines.delay(jittered)
            }
        }
    }

    /** Minimal, non-interactive configuration of the extracted userspace. */
    private fun configureUserspace(rootfs: File, artifact: RuntimeMetadata.RootfsArtifact) {
        // DNS resolvers (Android has no /etc/resolv.conf for proot guests)
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        resolv.writeTextCompat("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        // os-release sanity for motd/uname-based checks
        val hostInfo = File(rootfs, "etc/linuxify-host.json")
        hostInfo.writeTextCompat(
            JSONObject()
                .put("abi", artifact.abi)
                .put("distro", RuntimeMetadata.DISTRO_NAME)
                .put("version", RuntimeMetadata.DISTRO_VERSION)
                .put("installedAt", System.currentTimeMillis())
                .toString(),
        )

        // workspace directory bound from app projects dir
        File(rootfs, "root").mkdirs()

        // profile + group/marker patches are owned by GuestPatches (single
        // source of truth, shared with the in-place upgrade path in AppViewModel
        // and the per-session self-heal in PtyTerminal).
        val patchSummary = GuestPatches.applyAll(rootfs)
        if (patchSummary == null) {
            // Non-fatal: an unpatched guest costs cosmetic warnings only.
            // Record it so the behavior is diagnosable rather than mysterious.
            val log = File(rootfs, "etc/linuxify-group-patch.txt")
            runCatching { log.writeText("guest patch failed (non-fatal)\n") }
        }
    }

    private fun File.writeTextCompat(text: String) {
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(this)) {
            writeText(text)
            tmp.delete()
        }
    }
}
