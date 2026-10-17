package com.linuxify.app.terminal

import android.content.Context
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.RuntimeDirs
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Interactive PTY-backed session factory.
 *
 * TerminalSession (vendored Termux code) forks a child with a real pseudo-terminal via
 * the bundled `libtermux.so` JNI bridge and execs proot, which enters the Linux
 * userspace. The child therefore has real terminal semantics: job control, interactive
 * programs, and ANSI escape handling inside the terminal emulator.
 */
object PtyTerminal {

    /** Create an interactive shell session inside the Linux userspace. */
    fun newShellSession(
        ctx: Context,
        client: TerminalSessionClient,
        workdir: String = "/root",
        initialCommand: String? = null,
    ): TerminalSession {
        val proot = RuntimeDirs.prootBinary(ctx)
        check(proot.exists()) { "proot binary missing — runtime not installed?" }
        val runtime = LinuxRuntime(ctx)

        val argv = mutableListOf(proot.absolutePath)
        argv += runtime.buildProotArgs(workdir)
        argv += "/bin/bash"
        argv += "-l"
        if (initialCommand != null) {
            argv += listOf("-c", initialCommand)
        }

        val env = runtime.buildEnv().map { "${it.key}=${it.value}" }.toTypedArray()
        val cwd = RuntimeDirs.prootTmpDir(ctx).absolutePath

        // transcriptRows: bounded scrollback (2000 lines) — memory safety (master prompt §20.1)
        return TerminalSession(
            proot.absolutePath,
            cwd,
            argv.toTypedArray(),
            env,
            2000,
            client,
        )
    }
}
