package com.linuxify.app.terminal

import android.app.Application
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linuxify.app.runtime.CrashReporter
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * App-scoped owner of terminal sessions, held by [com.linuxify.app.ui.AppViewModel]
 * so session state survives navigation.
 *
 * ROOT-CAUSE FIX (user-reported): sessions used to live in `remember {}` inside
 * TerminalScreen, so *any* navigation — a bottom-bar tap, an accidental touch on
 * Home — destroyed the list, orphaned the running shell, and spawned a duplicate
 * on return. To the user this looked like the app "closing the shell" on normal
 * taps. Sessions now live here, at ViewModel scope: taps and navigation never
 * touch the shell.
 *
 * Smart close policy (deliberate, per user request "do it smartly"):
 *  - A normal tap / navigation            -> never asks anything, never closes.
 *  - Closing an ENDED session             -> instant remove (nothing to kill).
 *  - Closing an IDLE shell (no jobs)      -> instant close, no confirmation.
 *  - Closing a session with live jobs     -> one confirmation naming the count,
 *                                            then the whole process tree dies.
 * Closing kills the full process tree (proot tracer + guest + jobs), because
 * SIGKILL to proot alone can orphan the guest shell.
 */
class TerminalSessionsController(
    private val app: Application,
    private val scope: CoroutineScope,
) {

    // ---- Native engine (guarded, once per process) ----

    @Volatile
    private var engineLoaded = false

    /** Non-null when libtermux.so failed to load — surfaced as an on-screen error. */
    var engineError by mutableStateOf<Throwable?>(null)
        private set

    /**
     * Load the terminal's native engine here, in a guarded context: a load
     * failure must become an honest on-screen error, not an UnsatisfiedLinkError
     * inside the first layout pass (the classic silent "app keeps stopping").
     */
    fun ensureEngineLoaded(): Boolean {
        if (engineLoaded) return true
        synchronized(this) {
            if (engineLoaded) return true
            val err = try {
                System.loadLibrary("termux")
                null
            } catch (t: Throwable) {
                t
            }
            engineError = err
            if (err == null) engineLoaded = true
            return err == null
        }
    }

    // ---- Session state (survives navigation) ----

    val sessions = mutableStateListOf<TerminalSession>()

    /** Index of the visible session; always coerce before use — removals reindex. */
    var selected by mutableIntStateOf(0)

    /**
     * Change signal for emulator-driven UI updates (text, titles, colors,
     * session finish). TerminalSession is not snapshot state, so this counter
     * is what makes the Compose body recompose and repaint the TerminalView.
     */
    var revision by mutableIntStateOf(0)
        private set

    /** Non-null when the last spawn failed and no session exists to show. */
    var spawnError by mutableStateOf<String?>(null)
        private set

    /** Non-null while the "session has running processes" confirmation is up. */
    var closePrompt by mutableStateOf<ClosePrompt?>(null)
        private set

    data class ClosePrompt(val session: TerminalSession, val busyProcs: Int)

    private val client = LinuxifySessionClient { revision++ }

    // ---- Actions ----

    fun spawn(initialCommand: String? = null) {
        if (!ensureEngineLoaded()) return
        try {
            val s = PtyTerminal.newShellSession(app, client, initialCommand = initialCommand)
            sessions.add(s)
            selected = sessions.size - 1
            spawnError = null
        } catch (t: Throwable) {
            CrashReporter.logNonFatal(app, "spawnSession", t)
            spawnError = t.message ?: t.javaClass.simpleName
        }
    }

    /**
     * Smart close entry point. Instant for ended/idle sessions; asks exactly
     * once when real work is still running underneath the shell.
     */
    fun requestClose(session: TerminalSession) {
        val idx = sessions.indexOf(session)
        if (idx < 0) return
        if (!session.isRunning) {
            removeSession(idx)
            return
        }
        scope.launch(Dispatchers.IO) {
            val busy = runCatching { ProcTree.busyChildrenOf(session.getPid()) }.getOrDefault(0)
            if (busy > 0 && sessions.contains(session)) {
                closePrompt = ClosePrompt(session, busy)
            } else {
                killAndRemove(session)
            }
        }
    }

    fun confirmClose() {
        val prompt = closePrompt ?: return
        closePrompt = null
        scope.launch(Dispatchers.IO) { killAndRemove(prompt.session) }
    }

    fun dismissClose() {
        closePrompt = null
    }

    /**
     * Clear the session's screen: Ctrl+L. At an interactive shell this runs
     * readline's clear-screen; inside full-screen programs (vim, less, top)
     * the same byte is the universal redraw key — so clearing is always safe,
     * never injects garbage into the running program.
     */
    fun clear(session: TerminalSession) {
        if (!session.isRunning) return
        runCatching { session.write(byteArrayOf(0x0C), 0, 1) }
            .onFailure { CrashReporter.logNonFatal(app, "clearSession", it) }
    }

    // ---- Internals ----

    private fun killAndRemove(session: TerminalSession) {
        runCatching { killProcessTree(session.getPid()) }
            .onFailure { CrashReporter.logNonFatal(app, "closeSession", it) }
        val idx = sessions.indexOf(session)
        if (idx >= 0) removeSession(idx)
    }

    /** SIGKILL the whole session tree (jobs first, tracer last), best-effort. */
    private fun killProcessTree(rootPid: Int) {
        if (rootPid <= 0) return
        for (pid in ProcTree.subtreeSnapshot(rootPid)) {
            try {
                Os.kill(pid, OsConstants.SIGKILL)
            } catch (_: Throwable) {
                // already gone — fine
            }
        }
    }

    private fun removeSession(idx: Int) {
        sessions.removeAt(idx)
        selected = if (sessions.isEmpty()) 0 else selected.coerceIn(0, sessions.size - 1)
    }
}

/** TerminalSessionClient: feeds emulator-driven UI refreshes and system logging. */
class LinuxifySessionClient(
    private val onChange: () -> Unit,
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        onChange()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onChange()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onChange()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}

    override fun onPasteTextFromClipboard(session: TerminalSession) {}

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {
        onChange()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int = 0

    override fun logError(tag: String, message: String) {}

    override fun logWarn(tag: String, message: String) {}

    override fun logInfo(tag: String, message: String) {}

    override fun logDebug(tag: String, message: String) {}

    override fun logVerbose(tag: String, message: String) {}

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}

    override fun logStackTrace(tag: String, e: Exception) {}
}
