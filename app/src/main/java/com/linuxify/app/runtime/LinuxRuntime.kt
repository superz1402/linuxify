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

    /**
     * Environment passed to proot and the guest processes.
     *
     * LD_LIBRARY_PATH puts the private syslib shim directory (symlinks named
     * like the exact sonames the bionic linker requests) ahead of the packaged
     * native lib dir, so self-healed dependency names resolve on every spawn.
     */
    fun buildEnv(): Map<String, String> = mapOf(
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME" to "/root",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "LD_LIBRARY_PATH" to listOf(
            RuntimeDirs.syslibDir(ctx).absolutePath,
            RuntimeDirs.nativeLibDir(ctx).absolutePath,
        ).joinToString(":"),
        "PROOT_TMP_DIR" to RuntimeDirs.prootTmpDir(ctx).absolutePath,
        "PROOT_NO_SECCOMP" to "1",
    )

    /**
     * Verify the packaged proot binary can actually be loaded and executed by
     * the bionic linker, and self-heal when it cannot.
     *
     * The linker resolves DT_NEEDED entries by literal file name and Android
     * only extracts `lib*.so` names from the APK, so a versioned dependency
     * (e.g. `libtalloc.so.2`) can fail to link even though the library ships
     * as `libtalloc.so`. This preflight detects the exact failure from linker
     * stderr, creates a private syslib shim (symlink) mapping the requested
     * name onto the packaged library, and retries — no user interaction,
     * no network. It runs before any rootfs download so a broken runtime
     * fails fast, and it repairs name mismatches automatically.
     *
     * @return success when proot loads (its own exit code is irrelevant);
     *   failure is a [RuntimeError.NativeDep] describing what could not be
     *   resolved, or [RuntimeError.ProotMissing] when the binary is absent.
     */
    fun preflightProot(onLog: ((String) -> Unit)? = null): Result<Unit> {
        val proot = RuntimeDirs.prootBinary(ctx)
        if (!proot.exists()) {
            return Result.failure(RuntimeError.ProotMissing("libproot.so not found in nativeLibraryDir"))
        }
        val libDir = RuntimeDirs.nativeLibDir(ctx)
        val syslib = RuntimeDirs.syslibDir(ctx)
        var lastOutput = ""
        repeat(MAX_PREFLIGHT_ATTEMPTS) { attempt ->
            val res = execDirect(
                listOf(proot.absolutePath, "--version"),
                timeoutMs = 30_000,
            )
            val output = res.stderr + res.stdout
            if (!LinkerDiagnostics.isLinkFailure(output)) {
                // proot itself ran — whatever rc it returned is its own business
                onLog?.invoke("native runtime loadable (proot rc=${res.exitCode})")
                return Result.success(Unit)
            }
            lastOutput = output
            val missing = LinkerDiagnostics.missingLibrary(output)
            if (missing == null) {
                return Result.failure(
                    RuntimeError.NativeDep("unknown", output.lineSequence().firstOrNull()?.take(300) ?: "linker failure"),
                )
            }
            onLog?.invoke("linker cannot resolve $missing — attempting self-repair (attempt ${attempt + 1}/$MAX_PREFLIGHT_ATTEMPTS)")
            val shims = NativeDepRepair.repairMissing(listOf(missing), libDir, syslib)
            if (shims.isEmpty()) {
                return Result.failure(
                    RuntimeError.NativeDep(
                        missing,
                        "no packaged candidate in ${libDir.name} satisfies this library",
                    ),
                )
            }
            onLog?.invoke("self-healed: ${shims.joinToString(", ") { it.name }} shimmed -> retrying")
        }
        return Result.failure(
            RuntimeError.NativeDep(
                LinkerDiagnostics.missingLibrary(lastOutput) ?: "unknown",
                "still failing after $MAX_PREFLIGHT_ATTEMPTS repair attempts",
            ),
        )
    }

    /** Direct (non-rootfs) bounded execution of a native binary, used by preflight. */
    private fun execDirect(argv: List<String>, timeoutMs: Long): ExecResult {
        val pb = ProcessBuilder(argv)
        pb.environment().putAll(buildEnv())
        pb.redirectErrorStream(false)
        val proc: Process = try {
            pb.start()
        } catch (e: Exception) {
            return ExecResult(-1, "", "failed to spawn ${argv.first()}: ${e.message}", false)
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { proc.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } }
        val tErr = Thread { proc.errorStream.bufferedReader().forEachLine { synchronized(err) { err.appendLine(it) } } }
        tOut.isDaemon = true
        tErr.isDaemon = true
        tOut.start()
        tErr.start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return ExecResult(-1, out.toString(), err.toString(), true)
        }
        tOut.join(2000)
        tErr.join(2000)
        return ExecResult(proc.exitValue(), out.toString(), err.toString(), false)
    }

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
        private const val MAX_PREFLIGHT_ATTEMPTS = 3

        fun humanSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }
    }
}
