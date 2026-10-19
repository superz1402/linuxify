package com.linuxify.app.pkg

import android.content.Context
import com.linuxify.app.runtime.CrashReporter
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.RuntimeDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Universal Software Management System — the shared core.
 *
 * Any [CatalogEntry] (built-in OR user-defined) flows through the same path:
 * audit → resolve ecosystem → auto-provision prerequisites → install via the
 * adapter → verify the executable runs → report failures with actionable
 * recovery hints. One operation at a time; all apt writes serialize through
 * the SHARED AptManager instance.
 *
 * The terminal stays unrestricted: this manager simplifies installation, it
 * never replaces the shell.
 */
class SoftwareManager(
    private val ctx: Context,
    private val apt: AptManager,
    private val env: EnvManager,
) {

    enum class Phase { IDLE, PREPARING, INSTALLING, VERIFYING, DONE, FAILED }

    data class SoftwareOp(
        val entryId: String? = null,
        val name: String? = null,
        val ecosystem: String? = null,
        val phase: Phase = Phase.IDLE,
        val log: List<String> = emptyList(),
        val failureHint: String? = null,
        val verified: List<EnvManager.VerifyResult> = emptyList(),
    ) {
        val busy: Boolean get() = phase in setOf(Phase.PREPARING, Phase.INSTALLING, Phase.VERIFYING)
    }

    private val lock = Mutex()
    private val _op = MutableStateFlow<SoftwareOp?>(null)
    val op: StateFlow<SoftwareOp?> = _op

    private val _customTools = MutableStateFlow<List<CatalogEntry>>(emptyList())
    val customTools: StateFlow<List<CatalogEntry>> = _customTools

    /** binary → present, for catalog UI state (one combined probe per refresh). */
    private val _installedState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val installedState: StateFlow<Map<String, Boolean>> = _installedState

    private val logBuffer = ArrayDeque<String>()

    private fun pushLog(line: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > 300) logBuffer.removeFirst()
            _op.value = _op.value?.copy(log = logBuffer.toList())
        }
    }

    private fun setPhase(phase: Phase) {
        _op.value = _op.value?.copy(phase = phase)
    }

    private fun fail(hint: String): Boolean {
        pushLog("FAILED: $hint")
        _op.value = _op.value?.copy(phase = Phase.FAILED, failureHint = hint)
        CrashReporter.logNonFatal(ctx, "SoftwareManager", IllegalStateException(hint))
        return false
    }

    private val runtime: LinuxRuntime get() = LinuxRuntime(ctx)

    init {
        loadCustomTools()
    }

    // ------------------------------------------------------------ custom tools

    private fun customFile(): File = File(ctx.filesDir, "custom_tools.json")

    @Synchronized
    fun loadCustomTools(): List<CatalogEntry> {
        val loaded = runCatching {
            if (customFile().exists()) CustomTools.fromJson(customFile().readText()) else emptyList()
        }.getOrDefault(emptyList())
        _customTools.value = loaded
        return loaded
    }

    @Synchronized
    fun addCustomTool(
        rawName: String,
        ecosystem: String,
        rawPkg: String,
        rawBinary: String?,
        rawDescription: String?,
    ): CatalogEntry? {
        val entry = CustomTools.buildEntry(rawName, ecosystem, rawPkg, rawBinary, rawDescription) { msg ->
            fail(msg)
        }
        if (entry == null) return null
        if (_customTools.value.any { it.id == entry.id }) {
            fail("A tool named '${entry.id}' already exists.")
            return null
        }
        val all = CustomTools.merge(_customTools.value, listOf(entry))
        val ok = runCatching {
            customFile().writeText(CustomTools.toJson(all))
            _customTools.value = all
            true
        }.getOrDefault(false)
        if (!ok) {
            fail("Could not save the custom tool.")
            return null
        }
        pushLog("custom tool added: ${entry.name} (${Ecosystems.idOf(entry.specs.first())} — verify: ${entry.verifyBinary})")
        return entry
    }

    @Synchronized
    fun removeCustomTool(id: String): Boolean {
        val remaining = _customTools.value.filterNot { it.id == id }
        val ok = runCatching {
            customFile().writeText(CustomTools.toJson(remaining))
            _customTools.value = remaining
            true
        }.getOrDefault(false)
        if (ok) pushLog("custom tool definition removed: $id (installed software is untouched — use Remove to uninstall)")
        return ok
    }

    // ---------------------------------------------------------- catalog state

    /** One proot spawn probes every binary the visible catalog shows. */
    suspend fun refreshInstalledState(entries: List<CatalogEntry>) {
        val bins = entries.map { it.verifyBinary }.distinct()
        if (bins.isEmpty()) return
        withContext(Dispatchers.IO) {
            val res = runCatching {
                runtime.boundedExec(Ecosystems.installedProbeScript(bins), timeoutMs = 120_000)
            }.getOrElse {
                CrashReporter.logNonFatal(ctx, "SoftwareManager.refreshInstalledState", it)
                return@withContext
            }
            _installedState.value = Ecosystems.parseInstalledProbe(res.stdout.lines())
        }
    }

    // ------------------------------------------------------------- install

    suspend fun install(entry: CatalogEntry) {
        lock.withLock {
            if (!runtime.rootfsReady()) {
                _op.value = SoftwareOp(entry.id, entry.name, phase = Phase.FAILED, failureHint = "The Linux environment is not installed yet.")
                return
            }
            synchronized(logBuffer) { logBuffer.clear() }
            _op.value = SoftwareOp(entry.id, entry.name, phase = Phase.PREPARING)

            val audit = env.rawAudit()
            if (audit == null) {
                fail("Could not probe the Linux environment. Try again, or run the command in a terminal.")
                return
            }
            val spec = Ecosystems.resolveSpec(entry.specs, audit)
            if (spec == null) {
                fail("No compatible installation path for ${entry.name} on architecture ${audit.arch ?: "unknown"}.")
                return
            }
            _op.value = _op.value?.copy(ecosystem = Ecosystems.idOf(spec))
            pushLog("${entry.name}: installing via ${Ecosystems.labelOf(spec)}")

            val ok = when (spec) {
                is InstallSpec.Apt -> installViaApt(spec)
                is InstallSpec.Npm -> installViaNpm(spec)
                is InstallSpec.Pip -> installViaPip(spec)
                is InstallSpec.Binary -> installViaBinary(spec)
            }
            if (!ok) return

            setPhase(Phase.VERIFYING)
            val result = verifyEntry(entry)
            _op.value = _op.value?.copy(verified = listOf(result))
            if (result.ok) {
                pushLog("${entry.name} ${result.version ?: ""} verified — command: ${entry.verifyBinary}")
                setPhase(Phase.DONE)
            } else {
                // Issue #19: report the REAL exit code — the version line is
                // output, not an exit status, and a 0-exit with blank output
                // must not be called "nonzero".
                fail(
                    "Installed but '${entry.verifyBinary}' did not run (exit ${result.exitCode ?: "exec failed"}). " +
                        (result.version?.let { "Last output: $it. " } ?: "") +
                        "Recovery: try again once; if it persists, install it manually in a terminal (the terminal is always unrestricted).",
                )
            }
        }
    }

    private suspend fun installViaApt(spec: InstallSpec.Apt): Boolean {
        if (!env.ensureHealthyOnly()) {
            return fail(env.state.value.failureHint ?: "Package manager is inconsistent — repair it first (Packages > Repair package manager).")
        }
        setPhase(Phase.INSTALLING)
        val op = apt.install(*spec.packages.toTypedArray())
        val ok = op.state == AptManager.Operation.State.DONE
        if (!ok) {
            return fail(
                "APT install failed (exit ${op.exitCode}). Last output: ${op.output.lastOrNull() ?: "none"}. " +
                    "Recovery: check network and free storage, then retry; the full log is in the APT operation card above.",
            )
        }
        pushLog("APT: ${spec.packages.joinToString(" ")} installed")
        return true
    }

    private suspend fun installViaNpm(spec: InstallSpec.Npm): Boolean {
        if (!Ecosystems.npmAvailable(env.rawAudit())) {
            setPhase(Phase.PREPARING)
            pushLog("npm not found — installing the Node.js toolchain (Developer profile) first")
            env.installProfile(EnvCore.PROFILE_DEV.id)
            if (env.state.value.phase != EnvManager.Phase.DONE) {
                return fail(
                    "Node.js toolchain install did not complete (${env.state.value.failureHint ?: "see Environment card"}). " +
                        "npm tools need Node.js — fix the Developer profile, then retry.",
                )
            }
        }
        setPhase(Phase.INSTALLING)
        val res = runtime.boundedExec(Ecosystems.npmInstallCommand(spec.pkg), timeoutMs = 15 * 60_000, onOutput = { line -> pushLog(line) })
        if (res.exitCode != 0) {
            return fail(
                "npm install failed (exit ${res.exitCode}). ${res.stderr.lineSequence().lastOrNull { it.isNotBlank() } ?: ""} " +
                    "Recovery: run 'npm install -g ${spec.pkg}' in a terminal to see the full error.",
            )
        }
        pushLog("npm: ${spec.pkg} installed globally")
        return true
    }

    private suspend fun installViaPip(spec: InstallSpec.Pip): Boolean {
        val audit = env.rawAudit()
        if (audit == null || !Ecosystems.pipAvailable(audit)) {
            setPhase(Phase.PREPARING)
            pushLog("pip3 not found — installing python3-pip first")
            if (!env.ensureHealthyOnly()) {
                return fail(env.state.value.failureHint ?: "Package manager is inconsistent — repair it first.")
            }
            val op = apt.install("python3-pip")
            if (op.state != AptManager.Operation.State.DONE) {
                return fail("python3-pip install failed (exit ${op.exitCode}). Recovery: check network/storage, then retry.")
            }
        }
        setPhase(Phase.INSTALLING)
        val res = runtime.boundedExec(Ecosystems.pipInstallCommand(spec.pkg), timeoutMs = 15 * 60_000, onOutput = { line -> pushLog(line) })
        if (res.exitCode != 0) {
            return fail(
                "pip install failed (exit ${res.exitCode}). ${res.stderr.lineSequence().lastOrNull { it.isNotBlank() } ?: ""} " +
                    "Recovery: run 'pip3 install --break-system-packages ${spec.pkg}' in a terminal to see the full error.",
            )
        }
        pushLog("pip: ${spec.pkg} installed")
        return true
    }

    private suspend fun installViaBinary(spec: InstallSpec.Binary): Boolean {
        setPhase(Phase.INSTALLING)
        val target = File(RuntimeDirs.rootfsDir(ctx), "usr/local/bin/${spec.binaryName}")
        val rootfs = RuntimeDirs.rootfsDir(ctx).canonicalFile
        if (!target.canonicalFile.path.startsWith(rootfs.path)) {
            return fail("Refusing to install outside the environment root (path check failed).")
        }
        val tmp = File(ctx.cacheDir, "${spec.binaryName}.download")
        pushLog("downloading ${spec.binaryName} ${spec.version} (checksum-pinned)")
        val ok = withContext(Dispatchers.IO) {
            BinaryDownloader.download(spec.url, spec.sha256, tmp, onLog = { pushLog(it) })
        }
        val file = ok.getOrNull()
        if (file == null) {
            tmp.delete()
            val e = ok.exceptionOrNull()
            return fail(
                "Download failed: ${e?.message ?: "unknown error"}. Recovery: check the network and retry; " +
                    "if the checksum never matches, the pinned upstream release may have moved — report it via the share-log button.",
            )
        }
        return runCatching {
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            file.delete()
            pushLog("installed to /usr/local/bin/${spec.binaryName} (sha256 verified)")
            true
        }.getOrElse {
            CrashReporter.logNonFatal(ctx, "SoftwareManager.binaryInstall", it)
            fail("Could not place the binary: ${it.message}. Recovery: retry; if it persists, free up storage.")
        }
    }

    // -------------------------------------------------------------- remove

    suspend fun remove(entry: CatalogEntry) {
        lock.withLock {
            if (!runtime.rootfsReady()) return
            synchronized(logBuffer) { logBuffer.clear() }
            _op.value = SoftwareOp(entry.id, entry.name, phase = Phase.INSTALLING)
            val audit = env.rawAudit()
            val spec = Ecosystems.resolveSpec(entry.specs, audit)
            if (spec == null) {
                fail("No compatible ecosystem detected for removal.")
                return
            }
            _op.value = _op.value?.copy(ecosystem = Ecosystems.idOf(spec))
            val ok = when (spec) {
                is InstallSpec.Apt -> {
                    val op = apt.remove(*spec.packages.toTypedArray())
                    op.state == AptManager.Operation.State.DONE
                }
                is InstallSpec.Npm -> {
                    val res = runtime.boundedExec(Ecosystems.npmUninstallCommand(spec.pkg), timeoutMs = 5 * 60_000, onOutput = { line -> pushLog(line) })
                    res.exitCode == 0
                }
                is InstallSpec.Pip -> {
                    val res = runtime.boundedExec(Ecosystems.pipUninstallCommand(spec.pkg), timeoutMs = 5 * 60_000, onOutput = { line -> pushLog(line) })
                    res.exitCode == 0
                }
                is InstallSpec.Binary -> {
                    val target = File(RuntimeDirs.rootfsDir(ctx), "usr/local/bin/${spec.binaryName}")
                    // Idempotent: an already-absent binary is a successful removal
                    // (File.delete() returns false for absent files — issue #18).
                    runCatching {
                        target.delete()
                        !target.exists()
                    }.getOrDefault(false)
                }
            }
            if (ok) {
                pushLog("${entry.name} removed")
                setPhase(Phase.DONE)
            } else {
                fail("Removal failed. Recovery: remove it manually in a terminal (the terminal is always unrestricted).")
            }
        }
    }

    private suspend fun verifyEntry(entry: CatalogEntry): EnvManager.VerifyResult {
        val res = runCatching {
            runtime.execInRootfs(listOf(entry.verifyBinary, entry.verifyArg), timeoutMs = 60_000)
        }.getOrElse {
            CrashReporter.logNonFatal(ctx, "SoftwareManager.verify(${entry.verifyBinary})", it)
            LinuxRuntime.ExecResult(-1, "", it.message ?: "exec failed", false)
        }
        val ok = EnvCore.verifyPassed(res.exitCode, res.stdout)
        return EnvManager.VerifyResult(entry.verifyBinary, ok, EnvCore.firstVersionLine(res.stdout), res.exitCode)
    }
}

/**
 * Small-file downloader with retries + sha256 pinning. Range resume is not
 * needed at catalog-binary sizes; three attempts with backoff absorb flaky
 * networks (same philosophy as the rootfs download, scaled down).
 */
object BinaryDownloader {

    const val MAX_BYTES: Long = 128L * 1024 * 1024

    fun download(url: String, expectedSha256: String, dest: File, attempts: Int = 3, onLog: ((String) -> Unit)? = null): Result<File> {
        var lastError: String = "unknown"
        for (attempt in 0 until attempts) {
            if (attempt > 0) {
                val backoff = 2_000L shl (attempt - 1)
                onLog?.invoke("retrying in ${backoff / 1000}s (attempt ${attempt + 1}/$attempts)")
                Thread.sleep(backoff)
            }
            try {
                onLog?.invoke("GET $url")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) {
                    lastError = "HTTP ${conn.responseCode}"
                    conn.disconnect()
                    continue
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IllegalStateException("file exceeds ${MAX_BYTES / 1024 / 1024} MB limit")
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expectedSha256.lowercase()) {
                    dest.delete()
                    lastError = "sha256 mismatch (got $actual, expected $expectedSha256)"
                    onLog?.invoke(lastError)
                    continue
                }
                onLog?.invoke("downloaded ${LinuxRuntime.humanSize(total)}, sha256 verified")
                return Result.success(dest)
            } catch (e: Exception) {
                dest.delete()
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return Result.failure(IllegalStateException(lastError))
    }
}
