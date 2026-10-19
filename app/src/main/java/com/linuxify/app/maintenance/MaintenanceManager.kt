package com.linuxify.app.maintenance

import android.content.Context
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.RuntimeDirs
import java.io.File

/**
 * Cache & maintenance operations (issue #23).
 *
 * Every clear operation follows the same contract: [report] first (cheap,
 * shows the user exactly which directories and how many bytes are involved),
 * then an explicit user confirmation, then [clear] of the confirmed items —
 * nothing is deleted implicitly and every delete is contents-only (the parent
 * directories themselves are preserved; apt/npm/pip recreate their own
 * structure, so removing contents is always sufficient and safer).
 */
class MaintenanceManager(private val ctx: Context) {

    /** One clearable location. [target] is always a DIRECTORY; clearing removes its contents. */
    data class CacheItem(
        val label: String,
        val target: File,
        val bytes: Long,
        val blockedReason: String? = null, // non-null = shown but not clearable now
    )

    data class Report(val items: List<CacheItem>) {
        val totalBytes: Long get() = items.filter { it.blockedReason == null }.sumOf { it.bytes }
        val hasClearable: Boolean get() = items.any { it.blockedReason == null && it.bytes > 0 }
    }

    // ---- per-project -------------------------------------------------------

    /** Cache dirs inside ONE project (real dirs only — see CacheTargets). */
    fun projectReport(projectName: String): Report {
        val dir = File(RuntimeDirs.projectsDir(ctx), projectName)
        val items = CacheTargets.findProjectCaches(dir).map { f ->
            CacheItem(
                label = f.relativeTo(dir).path,
                target = f,
                bytes = CacheTargets.sizeOfDirSafe(f),
            )
        }
        return Report(items)
    }

    /** Delete the given per-project cache dirs (contents AND the dir itself). */
    fun clearProjectCaches(projectName: String, dirs: List<File>): Long {
        val root = RuntimeDirs.projectsDir(ctx).canonicalFile
        var freed = 0L
        for (d in dirs) {
            // Defense in depth: only directories inside THIS project are legal.
            if (!d.canonicalFile.path.startsWith(File(root, projectName).canonicalFile.path + File.separator)) continue
            if (!d.isDirectory) continue
            freed += CacheTargets.sizeOfDirSafe(d)
            if (!d.deleteRecursively()) d.delete()
        }
        return freed
    }

    // ---- whole system -------------------------------------------------------

    /**
     * System-wide cache report. Guards are passed by the caller (AppViewModel):
     *  - [liveSessions]: proot temp must not be emptied while sessions run;
     *  - [installerBusy]: download/staging leftovers belong to a running install.
     */
    fun systemReport(liveSessions: Boolean, installerBusy: Boolean): Report {
        val rootfs = RuntimeDirs.rootfsDir(ctx)
        val items = mutableListOf<CacheItem>()

        fun dirItem(label: String, dir: File?, blocked: String? = null): CacheItem {
            val f = dir ?: return CacheItem(label, File("/nonexistent"), 0, "unavailable")
            val real = if (f.exists()) f else null
            return CacheItem(
                label = label,
                target = f,
                bytes = real?.let { CacheTargets.sizeOfDirSafe(it) } ?: 0L,
                blockedReason = blocked,
            )
        }

        // 1. APT package archives (.deb downloads) — always safe.
        items += dirItem("APT package archives (.deb)", File(rootfs, "var/cache/apt/archives"))

        // 2. Guest tool caches: /root/.cache (pip, fontconfig, …) + npm's own cache.
        items += dirItem("Guest tool caches (/root/.cache)", File(rootfs, "root/.cache"))
        items += dirItem("npm cache (/root/.npm/_cacache)", File(rootfs, "root/.npm/_cacache"))

        // 3. App-side system-managed cache — always safe.
        items += dirItem("App cache (system-managed)", ctx.cacheDir)

        // 4. proot temp — blocked while a terminal session is live.
        items += dirItem(
            "Runtime temp (proot)",
            RuntimeDirs.prootTmpDir(ctx),
            blocked = if (liveSessions) "close running terminal sessions first" else null,
        )

        // 5. Stale download/staging leftovers — blocked during an install.
        val installBlocked = if (installerBusy) "an install is running" else null
        items += dirItem("Stale rootfs downloads", RuntimeDirs.downloadsDir(ctx), installBlocked)
        items += dirItem("Install staging", RuntimeDirs.stagingDir(ctx), installBlocked)

        return Report(items.filter { it.bytes > 0 || it.blockedReason != null })
    }

    /**
     * Clear the given system items (contents-only). Skips anything whose
     * target is not inside a directory we own. Returns freed bytes.
     */
    fun clearSystem(items: List<CacheItem>): Long {
        val allowedRoots = listOf(
            RuntimeDirs.rootfsDir(ctx).canonicalFile,
            RuntimeDirs.runtimeDir(ctx).canonicalFile,
            RuntimeDirs.downloadsDir(ctx).canonicalFile,
            RuntimeDirs.stagingDir(ctx).canonicalFile,
            ctx.cacheDir.canonicalFile,
        )
        var freed = 0L
        for (item in items) {
            if (item.blockedReason != null) continue
            val t = item.target
            if (!t.isDirectory) continue
            val canon = t.canonicalFile
            if (allowedRoots.none { root -> canon == root || canon.path.startsWith(root.path + File.separator) }) continue
            t.listFiles()?.forEach { child ->
                freed += CacheTargets.sizeOfDirSafe(child)
                if (!child.deleteRecursively()) child.delete()
            }
        }
        return freed
    }

    fun human(bytes: Long): String = LinuxRuntime.humanSize(bytes)
}
