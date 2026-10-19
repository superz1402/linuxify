package com.linuxify.app.proj

import android.content.Context
import com.linuxify.app.runtime.CrashReporter
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.RuntimeDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Project management: directories under the app projects dir, bound into the
 * userspace at /root/projects. Real git operations run inside the userspace.
 */
class ProjectsManager(private val ctx: Context) {

    data class Project(
        val name: String,
        val dir: File,
        val gitBranch: String? = null,
        val dirtyCount: Int? = null,
    )

    private val projectsDir: File get() = RuntimeDirs.projectsDir(ctx)

    suspend fun list(): List<Project> = withContext(Dispatchers.IO) {
        val dirs = projectsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: return@withContext emptyList()
        if (dirs.isEmpty()) return@withContext emptyList()
        // ONE proot spawn probes every project (issue #22): the previous code
        // spawned twice per project (branch + dirty count) on every refresh.
        // A failed/blocked probe degrades to "no git info" for all projects —
        // the same display the old per-dir failure path produced.
        val probe = runCatching { batchGitProbe() }
            .onFailure { CrashReporter.logNonFatal(ctx, "projects.batchGitProbe", it) }
            .getOrDefault(emptyMap())
        dirs.map { dir ->
            val info = probe[dir.name]
            Project(dir.name, dir, info?.first, info?.second)
        }
    }

    /**
     * Probe branch + dirty count for every project in ONE guest shell loop.
     * Output: "<name>\t<branch|->\t<dirty|->" per line, parsed by
     * [Companion.parseGitProbe] (pure, unit-tested).
     */
    private suspend fun batchGitProbe(): Map<String, Pair<String?, Int?>> =
        withContext(Dispatchers.IO) {
            // Kotlin raw-string gotcha (see SESSION_STATE.md): "\$" is NOT an
            // escape here — every literal shell dollar is ${'$'}.
            val script = """
                for d in /root/projects/*/; do
                  n=${'$'}(basename "${'$'}d")
                  b=${'$'}(git -C "${'$'}d" rev-parse --abbrev-ref HEAD 2>/dev/null)
                  if [ -n "${'$'}b" ]; then
                    c=${'$'}(git -C "${'$'}d" status --porcelain 2>/dev/null | wc -l)
                    printf '%s\t%s\t%s\n' "${'$'}n" "${'$'}b" "${'$'}c"
                  else
                    printf '%s\t-\t-\n' "${'$'}n"
                  fi
                done
            """.trimIndent()
            val res = LinuxRuntime(ctx).boundedExec(script, timeoutMs = 60_000)
            if (res.exitCode != 0) emptyMap() else parseGitProbe(res.stdout)
        }

    fun validateName(name: String): String? = when {
        name.isBlank() -> "Name cannot be empty"
        name.length > 64 -> "Name too long (max 64)"
        !Regex("^[A-Za-z0-9._-]+$").matches(name) ->
            "Use letters, digits, dot, dash, underscore only"
        else -> null
    }

    fun create(name: String): Result<File> {
        val err = validateName(name)
        if (err != null) return Result.failure(IllegalArgumentException(err))
        val dir = File(projectsDir, name)
        if (dir.exists()) return Result.failure(IllegalStateException("A project with this name already exists"))
        return try {
            if (dir.mkdirs()) Result.success(dir) else Result.failure(IllegalStateException("Could not create directory"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Clone a Git repository inside the userspace (real git, real output). */
    suspend fun clone(url: String, onOutput: (String) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        val safeUrl = url.trim()
        if (!Regex("^https://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+$").matches(safeUrl)) {
            return@withContext Result.failure(IllegalArgumentException("Only HTTPS URLs are supported"))
        }
        val name = safeUrl.substringAfterLast('/').removeSuffix(".git")
            .replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "repo" }
        val runtime = LinuxRuntime(ctx)
        val target = "${'$'}HOME/projects/$name"
        val res = runtime.boundedExec(
            "git clone ${'"'}$safeUrl${'"'} $target 2>&1",
            timeoutMs = 10 * 60_000,
            onOutput = onOutput,
        )
        if (res.exitCode == 0 && File(projectsDir, name).isDirectory) {
            Result.success(name)
        } else {
            Result.failure(IllegalStateException(res.stdout.ifBlank { res.stderr }.take(400)))
        }
    }

    companion object {
        /**
         * Parse the batched git-probe output (issue #22).
         *
         * Expected line: `<name>\t<branch|->\t<dirty|->`. Blank lines and
         * malformed lines are skipped (honest degradation — a missing entry
         * simply means "no git info" for that project). Pure function, JVM
         * unit-tested; the caller maps unknown names to a project with no
         * git info rather than guessing.
         */
        fun parseGitProbe(output: String): Map<String, Pair<String?, Int?>> {
            val result = mutableMapOf<String, Pair<String?, Int?>>()
            output.lineSequence()
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) return@forEach
                    val name = parts[0].trim()
                    if (name.isEmpty()) return@forEach
                    val branch = parts[1].trim().takeIf { it != "-" && it.isNotEmpty() }
                    val dirty = parts[2].trim().takeIf { it != "-" && it.isNotEmpty() }?.toIntOrNull()
                    result[name] = branch to dirty
                }
            return result
        }
    }
}
