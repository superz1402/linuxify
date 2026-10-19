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

/**
 * Dependency & Package Recovery Manager.
 *
 * Design contract (user directive, 2026-10):
 *  - BASE environment (curl, ca-certificates) ships by default; DEVELOPER tools
 *    (git, Node.js, npm) are an optional profile installed on demand.
 *  - The package manager is repaired ONLY when actually inconsistent —
 *    `dpkg --configure -a` must never run blindly on every launch.
 *  - Every apt operation that touches the network uses the IPv4 fallback
 *    (`-o Acquire::ForceIPv4=true`) so flaky/IPv6-broken connections recover.
 *  - An installation is only "done" when each tool's executable exists AND runs.
 *  - Failures stop the flow and surface the failed step plus a practical
 *    recovery command — never a silent continue.
 *
 * Pure decision logic lives in [EnvCore] (JVM-unit-tested); this class only
 * executes the plan against the guest and reports honest results.
 *
 * Concurrency: all apt/dpkg WRITES are serialized through the shared
 * [AptManager] mutex (the same instance the rest of the app uses, passed in by
 * AppViewModel). [EnvManager] holds its own lock for the multi-step flow.
 */
class EnvManager(
    private val ctx: Context,
    private val apt: AptManager,
) {

    // ---------------------------------------------------------------- state

    enum class Phase { IDLE, AUDITING, RECOVERING, UPDATING, INSTALLING, VERIFYING, DONE, FAILED }

    data class StepRecord(val id: String, val title: String, val ok: Boolean, val note: String? = null)

    data class VerifyResult(
        val binary: String,
        val ok: Boolean,
        val version: String? = null,
        val exitCode: Int? = null,
    )

    data class EnvState(
        val phase: Phase = Phase.IDLE,
        val profileLabel: String? = null,
        val audit: EnvCore.EnvAudit? = null,
        val steps: List<StepRecord> = emptyList(),
        val log: List<String> = emptyList(),
        val failureHint: String? = null,
        val verified: List<VerifyResult> = emptyList(),
    ) {
        val busy: Boolean get() = phase in setOf(Phase.AUDITING, Phase.RECOVERING, Phase.UPDATING, Phase.INSTALLING, Phase.VERIFYING)
    }

    private val lock = Mutex()
    private val _state = MutableStateFlow(EnvState(Phase.IDLE))
    val state: StateFlow<EnvState> = _state

    private val logBuffer = ArrayDeque<String>()

    private fun pushLog(line: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > 300) logBuffer.removeFirst()
            _state.value = _state.value.copy(log = logBuffer.toList())
        }
    }

    private fun setPhase(phase: Phase) {
        _state.value = _state.value.copy(phase = phase)
    }

    private val runtime: LinuxRuntime get() = LinuxRuntime(ctx)

    // ------------------------------------------------------------ probe exec

    /**
     * ONE proot spawn gathers the entire audit (probe spawn cost on device is
     * ~1s each, so a combined script matters). Output protocol:
     *   distro=<pretty name>  arch=<dpkg arch>  free_mb=<int>  net=yes|no
     *   updates=<count>       deps=ok|broken    tool:<bin>=yes|no
     *   audit:<dpkg --audit line>...             done
     */
    private val PROBE_SCRIPT = listOf(
        "echo \"distro=\$(grep -E '^PRETTY_NAME=' /etc/os-release 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\"')\"",
        "echo \"arch=\$(dpkg --print-architecture 2>/dev/null)\"",
        "echo \"free_mb=\$(df -Pm / 2>/dev/null | awk 'NR==2{print \$4}')\"",
        "if getent hosts deb.debian.org >/dev/null 2>&1; then echo net=yes; else echo net=no; fi",
        "echo \"updates=\$(ls /var/lib/dpkg/updates 2>/dev/null | grep -c .)\"",
        "dpkg --audit 2>/dev/null | sed 's/^/audit:/'",
        "if DEBIAN_FRONTEND=noninteractive apt-get check >/dev/null 2>&1; then echo deps=ok; else echo deps=broken; fi",
        "for b in ${EnvCore.PROBED_BINARIES.joinToString(" ")}; do command -v \$b >/dev/null 2>&1 && echo \"tool:\$b=yes\" || echo \"tool:\$b=no\"; done",
        "echo done",
    ).joinToString("\n")

    /** Audit-only pass (no writes). Safe to call from the UI at any time. */
    suspend fun checkEnvironment(): EnvCore.EnvAudit? = lock.withLock {
        if (!guardRootfs()) return@withLock null
        withContext(Dispatchers.IO) {
            reset("Environment check")
            setPhase(Phase.AUDITING)
            val audit = runProbe()
            _state.value = _state.value.copy(audit = audit)
            if (audit == null) {
                fail("Could not probe the Linux environment. Is the environment installed?", Phase.FAILED)
            } else {
                pushLog(describeHealth(audit))
                setPhase(if (audit.healthy) Phase.DONE else Phase.FAILED)
                if (!audit.healthy) {
                    _state.value = _state.value.copy(failureHint = recoveryHintText(audit))
                }
            }
            audit
        }
    }

    /**
     * Reusable one-shot audit WITHOUT touching the Environment card state —
     * other managers (SoftwareManager) probe the same way without fighting
     * over this card's UI. Returns null when the rootfs is not ready.
     */
    suspend fun rawAudit(): EnvCore.EnvAudit? {
        if (!runtime.rootfsReady()) return null
        return withContext(Dispatchers.IO) { runProbe() }
    }

    /**
     * Health-only pass used BEFORE any other install path (search-screen
     * installs): repairs the package manager when actually inconsistent.
     * @return true when healthy (or repaired), false with failureHint set.
     */
    suspend fun ensureHealthyOnly(): Boolean = lock.withLock {
        if (!guardRootfs()) return@withLock false
        withContext(Dispatchers.IO) {
            reset("Package health")
            setPhase(Phase.AUDITING)
            val audit = runProbe()
            if (audit == null) {
                fail("Could not probe the Linux environment (install may still work).", Phase.FAILED)
                return@withContext false
            }
            _state.value = _state.value.copy(audit = audit)
            if (audit.healthy) {
                pushLog("package manager healthy")
                setPhase(Phase.DONE)
                return@withContext true
            }
            if (!runRecovery(audit)) return@withContext false
            setPhase(Phase.DONE)
            true
        }
    }

    /** Install a named profile (base/developer) with recovery + verification. */
    suspend fun installProfile(profileId: String) {
        val profile = EnvCore.byId(profileId) ?: return
        lock.withLock {
            if (!guardRootfs()) return
            withContext(Dispatchers.IO) {
                reset(profile.label)
                setPhase(Phase.AUDITING)
                val audit = runProbe()
                if (audit == null) {
                    fail("Could not probe the Linux environment before installing ${profile.label}.", Phase.FAILED)
                    return@withContext
                }
                _state.value = _state.value.copy(audit = audit)

                // 1. Safe recovery — repair the package manager BEFORE installs.
                if (!audit.healthy && !runRecovery(audit)) return@withContext

                // 2. Index freshness — stale/absent lists make installs 404.
                val pkgs = EnvCore.packagesFor(profile, audit.installedBinaries)
                if (pkgs.isEmpty()) {
                    pushLog("${profile.label}: nothing to install (all tools already present)")
                } else {
                    if (indexIsStale()) {
                        setPhase(Phase.UPDATING)
                        pushLog("package index is stale or missing — refreshing")
                        val upd = apt.refreshIndex()
                        if (!recordAptStep("apt-update", "Refresh package index", upd)) return@withContext
                    }
                    // 3. Install only the MISSING dependencies of this profile.
                    setPhase(Phase.INSTALLING)
                    pushLog("installing: ${pkgs.joinToString(" ")}")
                    var inst = apt.install(*pkgs.toTypedArray())
                    if (!recordAptStep("apt-install", "Install ${pkgs.joinToString(" ")}", inst, fatal = false)) {
                        // One bounded recovery attempt: fix-broken, then retry once.
                        pushLog("install failed — running dependency repair, then retrying once")
                        val fix = apt.fixBroken()
                        if (!recordAptStep("apt-fix", "Repair broken dependencies", fix, fatal = false)) return@withContext
                        setPhase(Phase.INSTALLING)
                        inst = apt.install(*pkgs.toTypedArray())
                        if (!recordAptStep("apt-install-retry", "Retry install ${pkgs.joinToString(" ")}", inst)) return@withContext
                    }
                }

                // 4. Verification — each tool must exist AND run.
                setPhase(Phase.VERIFYING)
                val results = verifyProfile(profile)
                _state.value = _state.value.copy(verified = results)
                val failed = results.filter { !it.ok }
                if (failed.isEmpty()) {
                    pushLog("${profile.label}: verified — ${results.joinToString(", ") { "${it.binary} ${it.version ?: "ok"}" }}")
                    setPhase(Phase.DONE)
                } else {
                    fail(
                        "Installed but verification failed for: ${failed.joinToString { it.binary }}. " +
                            "Recovery: open a terminal and run 'DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true --fix-broken install -y', then retry.",
                        Phase.FAILED,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- internals

    private fun guardRootfs(): Boolean {
        return if (runtime.rootfsReady()) true else {
            fail("The Linux environment is not installed yet. Install it from the Home screen first.", Phase.FAILED)
            false
        }
    }

    private fun reset(label: String) {
        synchronized(logBuffer) { logBuffer.clear() }
        _state.value = EnvState(Phase.IDLE, profileLabel = label)
    }

    private fun fail(hint: String, phase: Phase = Phase.FAILED) {
        pushLog("FAILED: $hint")
        _state.value = _state.value.copy(phase = phase, failureHint = hint)
        CrashReporter.logNonFatal(ctx, "EnvManager", IllegalStateException(hint))
    }

    private fun runProbe(): EnvCore.EnvAudit? = try {
        val res = runtime.boundedExec(PROBE_SCRIPT, timeoutMs = 120_000, onOutput = { line -> pushLog(line) })
        if (res.timedOut) {
            pushLog("environment probe timed out")
            null
        } else {
            EnvCore.parseProbe(res.stdout.lines())
        }
    } catch (e: Exception) {
        CrashReporter.logNonFatal(ctx, "EnvManager.probe", e)
        null
    }

    /** Runs the recovery plan steps in order; every failure is fatal-with-hint. */
    private suspend fun runRecovery(audit: EnvCore.EnvAudit): Boolean {
        val plan = EnvCore.recoveryPlan(audit.dpkgInterrupted, audit.brokenDeps)
        if (plan.isEmpty()) return true
        setPhase(Phase.RECOVERING)
        pushLog("package manager needs repair: " + plan.joinToString("; ") { it.why })
        plan.forEach { step ->
            val op = when (step.id) {
                EnvCore.STEP_DPKG_CONFIGURE -> apt.repairDpkg()
                EnvCore.STEP_FIX_BROKEN -> apt.fixBroken()
                else -> null
            }
            if (op == null || !recordAptStep(step.id, step.title, op)) return false
        }
        pushLog("package manager repaired")
        return true
    }

    /** Records an AptManager operation as a step; returns success. */
    private fun recordAptStep(id: String, title: String, op: AptManager.Operation, fatal: Boolean = true): Boolean {
        val ok = op.state == AptManager.Operation.State.DONE
        val note = if (ok) "exit 0" else "exit ${op.exitCode}" + if (op.output.isNotEmpty()) " — ${op.output.lastOrNull() ?: ""}" else ""
        _state.value = _state.value.copy(steps = _state.value.steps + StepRecord(id, title, ok, note))
        if (!ok) {
            val recovery = when (id) {
                EnvCore.STEP_DPKG_CONFIGURE ->
                    "In a terminal session run: DEBIAN_FRONTEND=noninteractive dpkg --configure -a — if it fails again, share the terminal log via the share button."
                EnvCore.STEP_FIX_BROKEN ->
                    "Check free storage and network, then in a terminal run: DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true --fix-broken install -y"
                else ->
                    "Check network and free storage, then retry from the Software screen. Recovery in a terminal: DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true update -y && apt-get -o Acquire::ForceIPv4=true --fix-broken install -y"
            }
            if (fatal) fail("Step failed: $title ($note). $recovery")
            else pushLog("step failed (continuing): $title ($note)")
        }
        return ok
    }

    private suspend fun verifyProfile(profile: EnvCore.EnvProfile): List<VerifyResult> = profile.tools.map { tool ->
        val res = runCatching {
            runtime.execInRootfs(listOf(tool.binary, tool.versionArg), timeoutMs = 60_000)
        }.getOrElse {
            CrashReporter.logNonFatal(ctx, "EnvManager.verify(${tool.binary})", it)
            LinuxRuntime.ExecResult(-1, "", it.message ?: "exec failed", false)
        }
        val ok = EnvCore.verifyPassed(res.exitCode, res.stdout)
        VerifyResult(tool.binary, ok, EnvCore.firstVersionLine(res.stdout), res.exitCode)
    }

    private fun indexIsStale(): Boolean {
        val lists = File(RuntimeDirs.rootfsDir(ctx), "var/lib/apt/lists")
        val newest = lists.listFiles()
            ?.filter { it.name.endsWith("InRelease") || it.name.endsWith("Release") }
            ?.maxOfOrNull { it.lastModified() }
        return EnvCore.indexIsStale(System.currentTimeMillis(), newest)
    }

    private fun describeHealth(a: EnvCore.EnvAudit): String = when {
        a.healthy -> "environment healthy: ${a.distro ?: "unknown distro"} ${a.arch ?: ""}, ${a.freeStorageMb ?: "?"} MB free"
        else -> "environment issues: " + listOf(
            if (a.dpkgInterrupted) "interrupted dpkg operation" else null,
            if (a.brokenDeps) "broken package dependencies" else null,
            if (!a.networkOk) "no network (DNS)" else null,
        ).filterNotNull().joinToString(", ")
    }

    private fun recoveryHintText(a: EnvCore.EnvAudit): String = when {
        a.dpkgInterrupted && a.brokenDeps ->
            "Package database is inconsistent. Software > Environment > Repair package manager, or in a terminal: DEBIAN_FRONTEND=noninteractive dpkg --configure -a && apt-get -o Acquire::ForceIPv4=true --fix-broken install -y"
        a.dpkgInterrupted ->
            "An earlier dpkg run was interrupted. Software > Environment > Repair package manager, or in a terminal: DEBIAN_FRONTEND=noninteractive dpkg --configure -a"
        a.brokenDeps ->
            "Package dependencies are broken. Software > Environment > Repair package manager, or in a terminal: DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true --fix-broken install -y"
        else -> "Environment needs attention."
    }
}

/**
 * Pure, JVM-testable core of the Dependency & Package Recovery Manager:
 * profiles, audit parsing, plan building, verification rules. No Android types.
 */
object EnvCore {

    data class ToolSpec(val pkg: String, val binary: String, val versionArg: String = "--version")

    data class EnvProfile(
        val id: String,
        val label: String,
        val description: String,
        val tools: List<ToolSpec>,
    )

    /** Binaries probed by the combined audit script (order matters for tests). */
    val PROBED_BINARIES: List<String> = listOf("curl", "update-ca-certificates", "git", "node", "npm")

    val PROFILE_BASE = EnvProfile(
        id = "base",
        label = "Base environment",
        description = "curl + HTTPS certificate trust — required by tool installers and scripts. Installed automatically after a fresh environment install.",
        tools = listOf(
            ToolSpec(pkg = "curl", binary = "curl"),
            ToolSpec(pkg = "ca-certificates", binary = "update-ca-certificates"),
        ),
    )

    val PROFILE_DEV = EnvProfile(
        id = "dev",
        label = "Developer tools",
        description = "git, Node.js and npm — for JavaScript/Node development and tools such as OpenCode.",
        tools = listOf(
            ToolSpec(pkg = "git", binary = "git"),
            ToolSpec(pkg = "nodejs", binary = "node"),
            ToolSpec(pkg = "npm", binary = "npm"),
        ),
    )

    val PROFILES: List<EnvProfile> = listOf(PROFILE_BASE, PROFILE_DEV)

    fun byId(id: String): EnvProfile? = PROFILES.firstOrNull { it.id == id }

    /** Step ids understood by [EnvManager]. */
    const val STEP_DPKG_CONFIGURE = "dpkg-configure"
    const val STEP_FIX_BROKEN = "fix-broken"

    data class EnvStep(
        val id: String,
        val title: String,
        val command: String,
        val why: String,
        val recoveryHint: String,
    )

    data class EnvAudit(
        val distro: String? = null,
        val arch: String? = null,
        val freeStorageMb: Long? = null,
        val networkOk: Boolean = false,
        val dpkgInterrupted: Boolean = false,
        val brokenDeps: Boolean = false,
        val installedBinaries: Set<String> = emptySet(),
    ) {
        val healthy: Boolean get() = !dpkgInterrupted && !brokenDeps
    }

    /** Only the packages the audit says are missing — never reinstall blindly. */
    fun missingTools(profile: EnvProfile, installedBinaries: Collection<String>): List<ToolSpec> =
        profile.tools.filter { it.binary !in installedBinaries }

    fun packagesFor(profile: EnvProfile, installedBinaries: Collection<String>): List<String> =
        missingTools(profile, installedBinaries).map { it.pkg }

    /**
     * Recovery plan — the heart of "repair only when needed". A healthy audit
     * yields an EMPTY plan: `dpkg --configure -a` never runs blindly.
     */
    fun recoveryPlan(dpkgInterrupted: Boolean, brokenDeps: Boolean): List<EnvStep> {
        val steps = mutableListOf<EnvStep>()
        if (dpkgInterrupted) {
            steps += EnvStep(
                id = STEP_DPKG_CONFIGURE,
                title = "Finish interrupted package configuration (dpkg --configure -a)",
                command = "DEBIAN_FRONTEND=noninteractive dpkg --configure -a",
                why = "an earlier dpkg run was interrupted",
                recoveryHint = "run 'DEBIAN_FRONTEND=noninteractive dpkg --configure -a' in a terminal and inspect its output",
            )
        }
        if (brokenDeps) {
            steps += EnvStep(
                id = STEP_FIX_BROKEN,
                title = "Repair broken dependencies (apt-get --fix-broken install)",
                command = "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true --fix-broken install -y",
                why = "package dependencies are broken",
                recoveryHint = "check free storage and network, then run 'DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true --fix-broken install -y' in a terminal",
            )
        }
        return steps
    }

    fun updateCommand(): String =
        "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true update -y"

    fun installCommand(pkgs: Collection<String>): String =
        "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true install -y ${pkgs.joinToString(" ")}"

    /**
     * The apt lists are stale when no InRelease/Release file exists or the
     * newest one is older than [maxAgeHours]. Fresh-index installs avoid the
     * classic "404 Not Found / Hash Sum mismatch" class of failures.
     */
    fun indexIsStale(nowMs: Long, newestReleaseMtimeMs: Long?, maxAgeHours: Long = 24): Boolean {
        if (newestReleaseMtimeMs == null || newestReleaseMtimeMs <= 0L) return true
        val ageMs = nowMs - newestReleaseMtimeMs
        return ageMs > maxAgeHours * 60L * 60L * 1000L
    }

    /** Verification: the executable must exist AND run without error. */
    fun verifyPassed(exitCode: Int, stdout: String): Boolean =
        exitCode == 0 && stdout.isNotBlank()

    fun firstVersionLine(stdout: String): String? =
        stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)

    /**
     * Parses the combined probe output. Unknown/blank lines are ignored; the
     * audit is honest about what it could NOT read (fields stay null/false).
     */
    fun parseProbe(lines: List<String>): EnvAudit {
        var distro: String? = null
        var arch: String? = null
        var freeMb: Long? = null
        var net: Boolean? = null
        var updates: Int? = null
        var deps: Boolean = false
        var auditInterrupted: Boolean = false
        val tools = mutableSetOf<String>()
        lines.forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("distro=") -> distro = line.removePrefix("distro=").trim().ifEmpty { null }
                line.startsWith("arch=") -> arch = line.removePrefix("arch=").trim().ifEmpty { null }
                line.startsWith("free_mb=") -> freeMb = line.removePrefix("free_mb=").trim().toLongOrNull()
                line == "net=yes" -> net = true
                line == "net=no" -> net = false
                line.startsWith("updates=") -> updates = line.removePrefix("updates=").trim().toIntOrNull()
                line.startsWith("audit:") -> if (line.removePrefix("audit:").isNotBlank()) auditInterrupted = true
                line == "deps=broken" -> deps = true
                line.startsWith("tool:") -> {
                    val body = line.removePrefix("tool:")
                    val name = body.substringBefore("=").trim()
                    val present = body.substringAfter("=", "") == "yes"
                    if (name.isNotEmpty() && present) tools += name
                }
            }
        }
        return EnvAudit(
            distro = distro,
            arch = arch,
            freeStorageMb = freeMb,
            networkOk = net ?: false,
            dpkgInterrupted = auditInterrupted || (updates ?: 0) > 0,
            brokenDeps = deps,
            installedBinaries = tools,
        )
    }
}
