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
                val ready = InstallState.Ready(RuntimeMetadata.DISTRO_NAME, RuntimeMetadata.DISTRO_VERSION)
                _state.value = ready
                return@withContext Result.success(ready)
            }

            _state.value = InstallState.Checking
            if (!RuntimeDirs.prootAvailable(ctx)) {
                throw RuntimeError.ProotMissing("libproot.so not found in nativeLibraryDir")
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
            appendLog("verifying command execution…")
            val runtime = LinuxRuntime(ctx)
            val probe = runtime.boundedExec("uname -s && echo LINUXIFY_OK", timeoutMs = 60_000)
            val probePassed = probe.exitCode == 0 && probe.stdout.contains("LINUXIFY_OK")
            if (!probePassed && LinkerDiagnostics.isLinkFailure(probe.stderr + probe.stdout)) {
                // Defense in depth: the preflight should already have healed any
                // dependency-name mismatch, but if the linker still choked here,
                // repair once and retry instead of reporting a raw linker error.
                appendLog("linker failure at verification — self-repair and retry")
                runtime.preflightProot { appendLog(it) }
                val retry = runtime.boundedExec("uname -s && echo LINUXIFY_OK", timeoutMs = 60_000)
                if (retry.exitCode != 0 || !retry.stdout.contains("LINUXIFY_OK")) {
                    throw RuntimeError.Verification(
                        "uname probe failed after self-repair: rc=${retry.exitCode} out=${retry.stdout.take(200)} err=${retry.stderr.take(200)}",
                    )
                }
                appendLog("verification ok after self-repair")
            } else if (!probePassed) {
                throw RuntimeError.Verification("uname probe failed: rc=${probe.exitCode} out=${probe.stdout.take(200)} err=${probe.stderr.take(200)}")
            } else {
                appendLog("verification ok: ${probe.stdout.trim().lineSequence().firstOrNull() ?: ""}")
            }

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
            if (attempt >= 5) throw RuntimeError.Network("gave up after $attempt attempts")
            attempt++
            try {
                val conn = url.openConnection() as HttpURLConnection
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
                appendLog("download attempt $attempt failed: ${e.message}; will retry")
                kotlinx.coroutines.delay(1500L * attempt)
            }
        }
    }

    /** Minimal, non-interactive configuration of the extracted userspace. */
    private fun configureUserspace(rootfs: File, artifact: RuntimeMetadata.RootfsArtifact) {
        // DNS resolvers (Android has no /etc/resolv.conf for proot guests)
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        resolv.writeTextCompat("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        // profile with a friendly prompt + env
        val profileDir = File(rootfs, "etc/profile.d")
        profileDir.mkdirs()
        File(profileDir, "linuxify.sh").writeTextCompat(
            """
            # Added by Linuxify (com.linuxify.app)
            export PS1='\u@\h:\w\$ '
            export TMPDIR=/tmp
            export LANG=C.UTF-8
            """.trimIndent() + "\n",
        )

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
