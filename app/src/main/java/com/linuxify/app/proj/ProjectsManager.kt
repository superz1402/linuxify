package com.linuxify.app.proj

import android.content.Context
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
        projectsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.map { dir ->
                val branch = gitBranch(dir)
                val dirty = if (branch != null) gitDirtyCount(dir) else null
                Project(dir.name, dir, branch, dirty)
            }
            ?: emptyList()
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

    suspend fun gitStatus(project: Project): String? = withContext(Dispatchers.IO) {
        if (project.gitBranch == null) return@withContext null
        val runtime = LinuxRuntime(ctx)
        val res = runtime.boundedExec("git status --short | head -20", timeoutMs = 30_000)
        res.stdout.trim().ifEmpty { null }
    }

    private suspend fun gitBranch(dir: File): String? = withContext(Dispatchers.IO) {
        val runtime = LinuxRuntime(ctx)
        val rel = dir.absolutePath.removePrefix(RuntimeDirs.projectsDir(ctx).absolutePath)
        val res = runtime.boundedExec("git -C /root/projects$rel rev-parse --abbrev-ref HEAD 2>/dev/null", timeoutMs = 20_000)
        res.stdout.trim().ifEmpty { null }
    }

    private suspend fun gitDirtyCount(dir: File): Int? = withContext(Dispatchers.IO) {
        val runtime = LinuxRuntime(ctx)
        val rel = dir.absolutePath.removePrefix(RuntimeDirs.projectsDir(ctx).absolutePath)
        val res = runtime.boundedExec("git -C /root/projects$rel status --porcelain 2>/dev/null | wc -l", timeoutMs = 20_000)
        res.stdout.trim().toIntOrNull()
    }
}
