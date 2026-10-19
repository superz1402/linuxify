package com.linuxify.app.pkg

import android.content.Context
import com.linuxify.app.runtime.LinuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * APT coordination inside the Linux userspace.
 *
 * Guarantees (master prompt §7.2):
 *  - never two conflicting APT operations concurrently (shared Mutex)
 *  - real exit status and real output — never synthetic progress
 *  - non-interactive flags so operations never hang on prompts
 */
class AptManager(private val ctx: Context) {

    data class Operation(
        val kind: Kind,
        val state: State,
        val output: List<String> = emptyList(),
        val exitCode: Int? = null,
    ) {
        enum class Kind { UPDATE, INSTALL, REMOVE, SEARCH, LIST_INSTALLED, REPAIR, UPGRADE }
        enum class State { IDLE, RUNNING, DONE, FAILED }
    }

    private val lock = Mutex()
    private val _op = MutableStateFlow<Operation?>(null)
    val op: StateFlow<Operation?> = _op

    private val outputBuffer = ArrayDeque<String>()

    private fun pushLine(line: String) {
        synchronized(outputBuffer) {
            outputBuffer.addLast(line)
            while (outputBuffer.size > 300) outputBuffer.removeFirst()
            _op.value = _op.value?.copy(output = outputBuffer.toList())
        }
    }

    private suspend fun run(kind: Operation.Kind, command: String): Operation =
        withContext(Dispatchers.IO) {
            lock.withLock {
                outputBuffer.clear()
                _op.value = Operation(kind, Operation.State.RUNNING)
                val runtime = LinuxRuntime(ctx)
                val result = runtime.boundedExec(
                    command,
                    timeoutMs = 15 * 60_000,
                    onOutput = { line -> pushLine(line) },
                )
                val state = if (result.exitCode == 0) Operation.State.DONE else Operation.State.FAILED
                if (result.stderr.isNotBlank()) {
                    result.stderr.lines().forEach { if (it.isNotBlank()) pushLine("err: $it") }
                }
                if (result.timedOut) pushLine("operation timed out")
                val finished = Operation(kind, state, outputBuffer.toList(), result.exitCode)
                _op.value = finished
                finished
            }
        }

    suspend fun refreshIndex(): Operation =
        run(Operation.Kind.UPDATE, "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true update -y")

    suspend fun install(vararg packages: String): Operation =
        run(Operation.Kind.INSTALL, "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true install -y ${packages.joinToString(" ")}")

    /**
     * Package recovery: finish interrupted dpkg configurations. Only ever
     * invoked by EnvManager's recovery plan — never blindly on launch.
     */
    suspend fun repairDpkg(): Operation =
        run(Operation.Kind.REPAIR, "DEBIAN_FRONTEND=noninteractive dpkg --configure -a")

    /**
     * Package recovery: repair broken dependencies (IPv4 fallback so flaky
     * networks cannot block the repair).
     */
    suspend fun fixBroken(): Operation =
        run(Operation.Kind.REPAIR, "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true --fix-broken install -y")

    /**
     * One-tap upgrade of installed packages (IPv4 fallback, noninteractive).
     * Pair with refreshIndex() first — the callers do.
     */
    suspend fun fullUpgrade(): Operation =
        run(Operation.Kind.UPGRADE, "DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceIPv4=true upgrade -y")

    suspend fun remove(vararg packages: String): Operation =
        run(Operation.Kind.REMOVE, "DEBIAN_FRONTEND=noninteractive apt-get remove -y ${packages.joinToString(" ")}")

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val command = AptCommands.searchCommand(query) ?: return@withContext emptyList()
        val runtime = LinuxRuntime(ctx)
        val res = runtime.boundedExec(command, timeoutMs = 60_000)
        res.stdout.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val idx = line.indexOf(' ')
                if (idx <= 0) null
                else SearchHit(line.substring(0, idx), line.substring(idx + 1).trim())
            }
    }

    /**
     * True when dpkg reports "Status: install ok installed" for [pkg]. Executed
     * as an explicit argv (no shell) so a hostile package name cannot inject
     * commands — the old Regex.escape-built shell string was neither shell- nor
     * ERE-safe and always returned false (issue #13).
     */
    suspend fun isInstalled(pkg: String): Boolean = withContext(Dispatchers.IO) {
        val runtime = LinuxRuntime(ctx)
        val res = runtime.execInRootfs(listOf("dpkg", "-s", pkg), timeoutMs = 30_000)
        res.exitCode == 0 && AptCommands.hasInstalledStatus(res.stdout)
    }

    suspend fun installedCount(): Int? = withContext(Dispatchers.IO) {
        val runtime = LinuxRuntime(ctx)
        val res = runtime.boundedExec("dpkg -l 2>/dev/null | grep -c '^ii'", timeoutMs = 60_000)
        res.stdout.trim().toIntOrNull()
    }

    data class SearchHit(val name: String, val description: String)
}

/**
 * Pure command construction for APT (JVM-unit-tested).
 *
 * Issue #13: [Regex.escape] emits PCRE \\Q…\\E quoting, which glibc ERE
 * (apt-cache's regex engine) treats as literal "Q…E" — ordinary package names
 * never matched and search always came back empty. ERE quoting must use
 * backslash escapes for the ERE metacharacters instead.
 */
object AptCommands {

    /** Characters special to POSIX ERE (glibc regcomp REG_EXTENDED). */
    private const val ERE_SPECIALS = ".[]*+?^$|(){}\\"

    /**
     * Quote [term] for embedding in an ERE: every ERE metacharacter gets a
     * backslash (a defined literal escape in ERE); ordinary characters pass
     * through unchanged.
     */
    fun ereQuote(term: String): String = buildString {
        for (c in term) {
            if (c in ERE_SPECIALS) append('\\')
            append(c)
        }
    }

    /** Keep only characters apt package names can contain; drops shell metacharacters. */
    fun safeSearchTerm(raw: String): String = raw.replace(Regex("[^A-Za-z0-9.+-]"), "")

    /** Full apt-cache command for a raw UI query, or null when nothing searchable remains. */
    fun searchCommand(rawTerm: String): String? {
        val safe = safeSearchTerm(rawTerm)
        if (safe.isEmpty()) return null
        val quoted = ereQuote(safe)
        return "apt-cache search --names-only '^" + quoted + "|" + quoted + "' 2>/dev/null | head -40"
    }

    /** dpkg -s output says the package is fully installed. */
    fun hasInstalledStatus(dpkgStatusStdout: String): Boolean =
        dpkgStatusStdout.lineSequence().any { it.trim() == "Status: install ok installed" }
}
