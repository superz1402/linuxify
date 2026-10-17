package com.linuxify.app.runtime

import android.content.Context
import java.io.File

/**
 * Canonical filesystem layout for the Linuxify runtime.
 *
 * Everything lives in app-private internal storage:
 *  - runtime/   installer state, logs, staging
 *  - rootfs/    the Linux root filesystem (created by the installer)
 *  - projects/  user projects (bound into the userspace at /root/projects)
 *  - downloads/ staging area for rootfs archives (.part files, atomic promote)
 */
object RuntimeDirs {

    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "runtime").apply { mkdirs() }
    fun downloadsDir(ctx: Context): File = File(ctx.filesDir, "downloads").apply { mkdirs() }
    fun stagingDir(ctx: Context): File = File(ctx.filesDir, "rootfs.staging")
    fun rootfsDir(ctx: Context): File = File(ctx.filesDir, "rootfs")
    fun projectsDir(ctx: Context): File = File(ctx.filesDir, "projects").apply { mkdirs() }
    fun logsDir(ctx: Context): File = File(runtimeDir(ctx), "logs").apply { mkdirs() }
    fun prootTmpDir(ctx: Context): File = File(runtimeDir(ctx), "proot-tmp").apply { mkdirs() }

    /**
     * Private directory holding linker "shims": symlinks named exactly like
     * the sonames the bionic linker requests (e.g. `libtalloc.so.2`) pointing
     * at the packaged `lib*.so` equivalents. Prepended to LD_LIBRARY_PATH by
     * [LinuxRuntime.buildEnv]. Created on demand by [NativeDepRepair].
     */
    fun syslibDir(ctx: Context): File = File(runtimeDir(ctx), "syslib").apply { mkdirs() }

    /** Directory containing the packaged native executables (proot). */
    fun nativeLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    fun prootBinary(ctx: Context): File = File(nativeLibDir(ctx), "libproot.so")

    /** True when the packaged proot executable exists and is executable. */
    fun prootAvailable(ctx: Context): Boolean {
        val p = prootBinary(ctx)
        return p.exists() && p.canExecute()
    }

    fun installMarker(ctx: Context): File = File(runtimeDir(ctx), "install.json")

    fun sizeRecursively(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(file)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isFile) total += f.length()
            else f.listFiles()?.let { stack.addAll(it) }
        }
        return total
    }

    fun availableBytes(ctx: Context): Long = ctx.filesDir.usableSpace
}
