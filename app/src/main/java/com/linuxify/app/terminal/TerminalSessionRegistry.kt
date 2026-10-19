package com.linuxify.app.terminal

import android.content.Context
import com.linuxify.app.LinuxifyApp
import com.linuxify.app.runtime.CrashReporter
import com.linuxify.app.runtime.RuntimeForegroundService
import com.linuxify.app.runtime.RuntimeNotifier
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

/**
 * Process-scoped holder for terminal sessions.
 *
 * Previously sessions lived in `remember {}` inside the composable — leaving
 * the terminal tab orphaned live PTY processes (unreachable but still running,
 * and a NEW session spawned on return). Now sessions live here for the whole
 * process lifetime, which is what makes real background running possible:
 * background the app (process kept alive by RuntimeForegroundService), come
 * back, and the same shell is still there with its scrollback intact.
 *
 * Also owns the single shared TerminalSessionClient — one client serves all
 * sessions (Termux's model); callbacks carry the session, redraws are routed
 * to whichever TerminalView is currently bound and showing that session.
 */
object TerminalSessionRegistry {

    val sessions = java.util.concurrent.CopyOnWriteArrayList<TerminalSession>()

    val client: LinuxifySessionClient by lazy { LinuxifySessionClient(LinuxifyApp.instance) }

    /**
     * Adds the session AND registers it with [TerminalPersistence] (issue #23):
     * every session gets an on-disk record + transcript log under
     * filesDir/runtime/terminal, updated continuously while it runs.
     * [workdir] is the guest path the shell was spawned in — remembered so
     * history can offer "start a shell in the same directory".
     */
    fun add(ctx: Context, session: TerminalSession, workdir: String = "/root") {
        sessions.add(session)
        TerminalPersistence.register(ctx, session, workdir)
        // A live session exists — the process must survive backgrounding.
        RuntimeForegroundService.start(ctx)
    }

    fun hasLiveSessions(): Boolean = sessions.any { it.isRunning }

    /** Removes an ended session from the list (closes its tab). */
    fun remove(session: TerminalSession) {
        TerminalPersistence.forget(LinuxifyApp.instance, session)
        sessions.remove(session)
        RuntimeForegroundService.stopIfIdle(LinuxifyApp.instance)
    }

    fun stopEverythingAndRelease() {
        sessions.forEach { s ->
            runCatching {
                val exit = try { if (s.isRunning) null else s.exitStatus } catch (_: Exception) { null }
                TerminalPersistence.finalizeSession(LinuxifyApp.instance, s, exit)
                s.finishIfRunning()
            }
        }
        sessions.clear()
        RuntimeForegroundService.stopIfIdle(LinuxifyApp.instance)
    }
}

/**
 * The single TerminalSessionClient for all sessions:
 *  - pushes emulator output straight into the bound view (Termux's model —
 *    output arrives on the main thread via the session's MainThreadHandler);
 *  - clipboard plumbing for the long-press toolbar Copy/Paste items;
 *  - on session exit: posts a "run completed" notification when the app is in
 *    the background and releases the keep-alive when nothing else is live.
 */
class LinuxifySessionClient(private val context: Context) : TerminalSessionClient {

    /** The TerminalView currently on screen, if any. Set/cleared by TerminalScreen. */
    @Volatile
    var boundView: TerminalView? = null

    /** UI hook (tab labels etc.) — set by the composition, cleared on dispose. */
    @Volatile
    var onUiInvalidated: (() -> Unit)? = null

    private fun redraw(changedSession: TerminalSession) {
        val view = boundView ?: return
        if (view.mTermSession === changedSession) view.onScreenUpdated()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        // Persistence (issue #23): flag the transcript as changed — the 10s
        // sweep / ON_STOP flush does the actual disk write (throttled).
        TerminalPersistence.markDirty(changedSession)
        redraw(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        redraw(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val idx = TerminalSessionRegistry.sessions.indexOf(finishedSession)
        val label = if (idx >= 0) "sh ${idx + 1}" else "shell"
        val code = try {
            finishedSession.exitStatus
        } catch (_: Exception) {
            -1
        }
        // Persistence (issue #23): final transcript snapshot + record close.
        TerminalPersistence.finalizeSession(context, finishedSession, code)
        RuntimeNotifier.runCompleted(
            context,
            title = "Run completed — $label ended",
            text = "Exit code $code. Tap to open Linuxify.",
        )
        // Release the keep-alive if this was the last live work. Ended sessions
        // stay in the list so the user can still read their output.
        RuntimeForegroundService.stopIfIdle(context)
        onUiInvalidated?.invoke()
    }

    /**
     * The vendored Termux selection toolbar hands selected text here — without a
     * real clipboard write the Copy item is a silent no-op (alpha.7 bug report).
     * Android 13+ shows its own "copied" overlay on setPrimaryClip; older OSes
     * get a plain toast like Termux's.
     */
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        if (text.isEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("linuxify-terminal", text))
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(context, "Text copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The Paste toolbar item routes here; read the system clipboard and feed it
     * through TerminalEmulator.paste (bracketed paste + escape stripping), the
     * same path TerminalView's own paste uses.
     */
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        val paste = clip.getItemAt(0)?.coerceToText(context) ?: return
        if (!android.text.TextUtils.isEmpty(paste)) {
            session.emulator?.paste(paste.toString())
        }
    }

    /**
     * BEL while the app is backgrounded = "the prompt returned" = a command the
     * user left running has finished. The guest profile installs a
     * PROMPT_COMMAND that prints '\a' right before every prompt (see
     * GuestPatches.PROFILE_SH); the vendored emulator routes BEL to this
     * callback. In the foreground the user is watching — never notify.
     */
    override fun onBell(session: TerminalSession) {
        val now = System.currentTimeMillis()
        if (!RunCompletionGate.shouldNotify(
                foreground = com.linuxify.app.LinuxifyApp.isInForeground,
                nowMs = now,
                lastNotifiedMs = lastBellNotifiedMs,
            )
        ) return
        lastBellNotifiedMs = now
        val idx = TerminalSessionRegistry.sessions.indexOf(session)
        val label = if (idx >= 0) "sh ${idx + 1}" else "shell"
        RuntimeNotifier.runCompleted(
            context,
            title = "Command finished — $label is ready",
            text = "The prompt returned while you were away. Tap to return to Linuxify.",
        )
    }

    private var lastBellNotifiedMs = 0L

    override fun onColorsChanged(session: TerminalSession) {
        redraw(session)
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
