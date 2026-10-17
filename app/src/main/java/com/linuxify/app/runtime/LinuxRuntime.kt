package com.linuxify.app.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bridge to the Linux userspace: builds proot invocations and performs bounded
 * noninteractive execution. Interactive PTY sessions are handled by PtyTerminal
 * on top of the vendored Termux JNI bridge.
 */
class LinuxRuntime(private val ctx: Context) {

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    fun rootfsReady(): Boolean = RootfsInstaller(ctx).isReadyMarkerPresent()

    /**
     * proot argument vector for entering the userspace.
     *
     *  - `-0` fake root (apt and dpkg require uid 0 semantics)
     *  - `--link2symlink` hardlink emulation for Android filesystems
     *  - `--kill-on-exit` tracees die with proot (reliable service stop)
     *  - binds /dev /proc /sys from Android
     *  - projects dir bound at /root/projects
     */
    fun buildProotArgs(workdir: String = "/root"): List<String> {
        val rootfs = RuntimeDirs.rootfsDir(ctx)
        val projects = RuntimeDirs.projectsDir(ctx)
        val args = mutableListOf(
            "-0",
            "--link2symlink",
            "--kill-on-exit",
            "-r", rootfs.absolutePath,
            "-w", workdir,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
        )
        if (projects.isDirectory) {
            args += listOf("-b", "${projects.absolutePath}:/root/projects")
        }
        return args
    }

    /** Environment passed to proot and the guest processes. */
    fun buildEnv(): Map<String, String> = mapOf(
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME" to "/root",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "LD_LIBRARY_PATH" to RuntimeDirs.nativeLibDir(ctx).absolutePath,
        "PROOT_TMP_DIR" to RuntimeDirs.prootTmpDir(ctx).absolutePath,
        "PROOT_NO_SECCOMP" to "1",
    )

    /**
     * Bounded noninteractive execution inside the userspace.
     * Uses ProcessBuilder (not a PTY) — for apt ops, probes, and tool runs.
     */
    fun boundedExec(
        command: String,
        workdir: String = "/root",
        timeoutMs: Long = 120_000,
        onOutput: ((line: String) -> Unit)? = null,
    ): ExecResult {
        val proot = RuntimeDirs.prootBinary(ctx)
        require(proot.exists()) { "proot binary missing" }
        val argv = mutableListOf(
            proot.absolutePath,
        )
        argv += buildProotArgs(workdir)
        argv += listOf("/bin/bash", "-lc", command)

        val pb = ProcessBuilder(argv)
        val env = pb.environment()
        env.putAll(buildEnv())
        pb.redirectErrorStream(false)

        val proc: Process
        try {
            proc = pb.start()
        } catch (e: Exception) {
            return ExecResult(-1, "", "failed to spawn proot: ${e.message}", false)
        }

        val outBuilder = StringBuilder()
        val errBuilder = StringBuilder()
        val outThread = Thread {
            proc.inputStream.bufferedReader().forEachLine { line ->
                synchronized(outBuilder) { outBuilder.appendLine(line) }
                onOutput?.invoke(line)
            }
        }
        val errThread = Thread {
            proc.errorStream.bufferedReader().forEachLine { line ->
                synchronized(errBuilder) { errBuilder.appendLine(line) }
            }
        }
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return ExecResult(-1, outBuilder.toString(), errBuilder.toString(), true)
        }
        outThread.join(2000)
        errThread.join(2000)
        val out = synchronized(outBuilder) { outBuilder.toString() }
        val err = synchronized(errBuilder) { errBuilder.toString() }
        return ExecResult(proc.exitValue(), out, err, false)
    }

    /** List a directory inside the rootfs directly from app-private storage (no proot needed). */
    fun listDir(relative: String): List<File> {
        val rootfs = RuntimeDirs.rootfsDir(ctx)
        val dir = File(rootfs, relative.trimStart('/'))
        val canonicalRoot = rootfs.canonicalFile
        if (!dir.canonicalFile.path.startsWith(canonicalRoot.path)) return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    fun relativePathOf(file: File): String? {
        val rootfs = RuntimeDirs.rootfsDir(ctx).canonicalFile
        val c = file.canonicalFile
        if (!c.path.startsWith(rootfs.path)) return null
        return c.path.removePrefix(rootfs.path).ifEmpty { "/" }
    }

    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }
    }
}
