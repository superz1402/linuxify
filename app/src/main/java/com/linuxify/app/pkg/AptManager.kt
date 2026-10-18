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
        enum class Kind { UPDATE, INSTALL, REMOVE, SEARCH, LIST_INSTALLED, REPAIR }
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

    suspend fun remove(vararg packages: String): Operation =
        run(Operation.Kind.REMOVE, "DEBIAN_FRONTEND=noninteractive apt-get remove -y ${packages.joinToString(" ")}")

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val safe = query.replace(Regex("[^A-Za-z0-9.+-]"), "")
        if (safe.isEmpty()) return@withContext emptyList()
        val runtime = LinuxRuntime(ctx)
        val res = runtime.boundedExec("apt-cache search --names-only '^${Regex.escape(safe)}|${Regex.escape(safe)}' 2>/dev/null | head -40", timeoutMs = 60_000)
        res.stdout.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val idx = line.indexOf(' ')
                if (idx <= 0) null
                else SearchHit(line.substring(0, idx), line.substring(idx + 1).trim())
            }
    }

    suspend fun isInstalled(pkg: String): Boolean = withContext(Dispatchers.IO) {
        val runtime = LinuxRuntime(ctx)
        val res = runtime.boundedExec("dpkg -s ${Regex.escape(pkg)} 2>/dev/null | grep -q '^Status: install ok installed'", timeoutMs = 30_000)
        res.exitCode == 0
    }

    suspend fun installedCount(): Int? = withContext(Dispatchers.IO) {
        val runtime = LinuxRuntime(ctx)
        val res = runtime.boundedExec("dpkg -l 2>/dev/null | grep -c '^ii'", timeoutMs = 60_000)
        res.stdout.trim().toIntOrNull()
    }

    data class SearchHit(val name: String, val description: String)
}
