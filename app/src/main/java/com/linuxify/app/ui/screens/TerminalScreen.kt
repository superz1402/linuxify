package com.linuxify.app.ui.screens

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.linuxify.app.R
import com.linuxify.app.terminal.ProcTree
import com.linuxify.app.terminal.PtyTerminal
import com.linuxify.app.terminal.SessionEndCause
import com.linuxify.app.terminal.TerminalPersistence
import com.linuxify.app.terminal.TerminalSessionRegistry
import com.linuxify.app.terminal.TerminalLogShare
import com.linuxify.app.runtime.CrashReporter
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.ErrorState
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/**
 * Termux-style terminal: real PTY session rendered by the vendored Termux
 * emulator view, matching Termux's interaction model:
 *
 *  - Font: Termux's default size (30 in renderer units, which are raw pixels —
 *    the vendored TerminalRenderer feeds textSize straight into Paint without a
 *    density conversion). Pinch to zoom, clamped.
 *  - Keyboard: the view is focusable-in-touch-mode; a tap raises the IME and the
 *    IME is raised automatically when the screen opens. The window resizes with
 *    the IME (imePadding at the activity level), and every resize reaches the
 *    PTY through TerminalView's updateSize, so line wrapping follows the real
 *    visible area.
 *  - Redraws: session output arrives on the main thread (vendored TerminalSession
 *    MainThreadHandler) and is pushed straight into view.onScreenUpdated() — the
 *    same model Termux uses. Composition is never driven by terminal output,
 *    which keeps typing and scrolling free of recomposition overhead.
 *  - Extra keys: Termux's default two rows (ESC / - HOME ↑ END PGUP, then
 *    TAB CTRL ALT ← ↓ → PGDN \\), with CTRL/ALT as sticky modifiers applied to
 *    the next key, exactly like Termux.
 *  - Share log: a small translucent floating button (bottom-right) captures
 *    the full transcript — scrollback AND screen — and opens the Android
 *    share sheet, so the log can be handed to any chat/AI app in two taps.
 *  - History (issue #23): every session's transcript is continuously saved to
 *    disk (TerminalPersistence). Sessions from previous app runs appear after
 *    the live tabs as dimmed, read-only tabs with their start time — nothing
 *    is wiped until the user deletes it. Each history tab offers "New shell
 *    in this directory" and a delete action.
 *  - Close & clear: every live tab carries a ✕ (and the tab row a CLR key).
 *    Closing is SMART: an ended tab closes silently; an IDLE shell (nothing
 *    running under it) closes instantly with no prompt; only a shell with
 *    live work asks once, naming the process count — then the whole tree
 *    (proot, guest, jobs) is SIGKILLed and the on-disk record finalized (the
 *    transcript stays in history; only the tab goes). CLR clears screen +
 *    scrollback of the focused session by feeding ESC[H ESC[2J ESC[3J
 *    straight into the emulator — never the child's stdin, so it is safe
 *    while TUI apps run.
 */
@Composable
fun TerminalScreen(vm: AppViewModel) {
    val context = LocalContext.current
    // Sessions live in the PROCESS-scoped registry, not in composition state:
    // leaving this tab (or backgrounding the whole app) no longer orphans live
    // PTYs, and coming back reattaches to the SAME shell with its scrollback.
    val sessions = TerminalSessionRegistry.sessions
    var selected by remember { mutableIntStateOf(0) }
    val ctrlDown = remember { mutableStateOf(false) }
    val altDown = remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var fontSizePx by remember { mutableIntStateOf(TERMUX_DEFAULT_FONT_SIZE_PX) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    // Load the terminal engine HERE, in a guarded context: a load failure must
    // become an honest on-screen error, not an UnsatisfiedLinkError inside the
    // first layout pass (the classic silent "app keeps stopping").
    val jniError = remember {
        try {
            System.loadLibrary("termux")
            null
        } catch (t: Throwable) {
            CrashReporter.logNonFatal(context, "loadLibrary(termux)", t)
            t
        }
    }
    var spawnError by remember { mutableStateOf<String?>(null) }

    val sessionClient = TerminalSessionRegistry.client

    fun spawnSession(initial: String? = null, workdir: String? = null) {
        try {
            // workdir: null = the plain default (/root); a project "Open
            // terminal" passes its directory through the VM's pending request
            // so the shell starts INSIDE the project (issue #22).
            val s = PtyTerminal.newShellSession(
                context,
                sessionClient,
                workdir = workdir ?: "/root",
                initialCommand = initial,
            )
            // The start directory is persisted with the session record
            // (issue #23) so history can offer "new shell in this directory".
            TerminalSessionRegistry.add(context, s, workdir ?: "/root")
            selected = sessions.size - 1
            spawnError = null
        } catch (t: Throwable) {
            CrashReporter.logNonFatal(context, "spawnSession", t)
            spawnError = t.message ?: t.javaClass.simpleName
        }
    }

    // Terminal history from PREVIOUS app runs (issue #23): loaded once per
    // process start, re-read after delete/clear actions. Ended sessions from
    // THIS process stay in the live list ("(ended)" tabs) until the process
    // exits; their transcripts are already on disk either way.
    var history by remember { mutableStateOf(TerminalPersistence.history()) }

    // Session close/clear (user request): hold the SESSION, not an index —
    // indices shift as tabs open/close; the session object does not.
    var confirmClose by remember { mutableStateOf<TerminalSession?>(null) }

    // Smart close (user request: "even with a normal shell click it asks to
    // close the shell — do it smartly"): the confirmation only appears when
    // REAL WORK is still running under the shell, never for an idle prompt.
    var confirmBusyCount by remember { mutableStateOf(0) }
    var closeCheckInFlight by remember { mutableStateOf(false) }
    val closeScope = rememberCoroutineScope()

    /** The focused session when a LIVE tab is selected (history has no PTY). */
    fun focusedLiveSession(): TerminalSession? =
        if (selected < sessions.size) sessions.getOrNull(selected) else null

    fun closeNow(s: TerminalSession) {
        // Close kills the session's WHOLE process tree (jobs first, tracer
        // last): SIGKILL to proot alone can orphan the guest shell and its
        // background jobs — the dialog's "everything inside it dies" must be
        // literally true. Registry.remove finalizes the on-disk record
        // (transcript survives in history), drops the tab and releases the
        // foreground keep-alive when nothing else is live.
        closeScope.launch {
            // Mark the close as USER-initiated BEFORE the kill: the exit
            // callback arrives asynchronously as SIGKILL (-9) and must not be
            // reported as "killed by Android" (alpha.20 honest-cause work).
            TerminalSessionRegistry.markUserClosed(s)
            if (s.isRunning) {
                withContext(Dispatchers.IO) { ProcTree.killTreeBestEffort(s.getPid()) }
            }
            s.finishIfRunning() // belt & braces for the tracer itself
            TerminalSessionRegistry.remove(s)
            selected = when {
                sessions.isEmpty() -> 0
                // history tabs live AFTER the live region: losing one live tab
                // shifts the whole region left by one
                selected >= sessions.size -> (selected - 1).coerceAtLeast(0)
                else -> selected.coerceIn(0, sessions.size - 1)
            }
        }
    }

    /**
     * Smart close entry point (off-main /proc scan):
     *  - ended tab            -> closes silently
     *  - idle shell (no jobs) -> closes silently — a normal shell NEVER asks
     *  - shell with live work -> one confirmation naming the process count
     */
    fun requestClose(s: TerminalSession?) {
        if (s == null || closeCheckInFlight) return
        if (!s.isRunning) {
            closeNow(s)
            return
        }
        closeCheckInFlight = true
        closeScope.launch {
            val busy = withContext(Dispatchers.IO) {
                runCatching { ProcTree.busyChildrenOf(s.getPid()) }.getOrDefault(0)
            }
            closeCheckInFlight = false
            // The shell may have exited while the tree was being scanned.
            if (busy > 0 && s.isRunning) {
                confirmBusyCount = busy
                confirmClose = s
            } else {
                closeNow(s)
            }
        }
    }

    /** Clear screen + scrollback of the focused session via the emulator itself. */
    fun clearFocused() {
        val emulator = focusedLiveSession()?.emulator ?: return
        emulator.append(CLEAR_SEQUENCE, CLEAR_SEQUENCE.size)
        // Redraws are callback-driven on this screen (not recomposition-driven),
        // so poke the bound view directly.
        sessionClient.boundView?.onScreenUpdated()
    }

    LaunchedEffect(Unit) {
        // A pending "open terminal in project" request spawns a NEW session in
        // that directory even when other sessions exist — it is an explicit
        // user action. A plain visit to the tab keeps the old behavior:
        // reattach to the existing sessions, spawn only when there are none.
        // (With history present, an empty live list is still fine — the user
        // sees their history instead of an empty screen, so no auto-spawn.)
        val requestedWorkdir = vm.consumePendingTerminalWorkdir()
        if (jniError == null && (requestedWorkdir != null || (sessions.isEmpty() && history.isEmpty()))) {
            spawnSession(workdir = requestedWorkdir)
        }
        // ended-session events refresh the tab labels
        sessionClient.onUiInvalidated = { refreshTick += 1 }
    }

    // Raise the keyboard as soon as the view is on screen (Termux does this on
    // activity resume); the post() defers past first layout so focus sticks.
    LaunchedEffect(terminalView) {
        val view = terminalView ?: return@LaunchedEffect
        view.post {
            if (view.requestFocus()) raiseKeyboard(view)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // The composition goes away but the sessions do NOT: they keep
            // running in the registry (kept alive in background by the
            // foreground service). Just unbind the UI hook.
            sessionClient.onUiInvalidated = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermuxTerminalBackground),
    ) {
        when {
            jniError != null -> {
                ErrorState(
                    title = "Terminal engine unavailable",
                    body = "The terminal's native engine (libtermux.so) failed to load: " +
                        "${jniError.message ?: jniError.javaClass.simpleName}. " +
                        "Reinstall the latest Linuxify APK — if this persists, copy the crash report and report it.",
                )
            }
            spawnError != null && sessions.isEmpty() -> {
                ErrorState(
                    title = "Could not start shell",
                    body = "The Linux shell failed to start: $spawnError",
                    actionLabel = "Retry",
                    onAction = { spawnError = null; spawnSession() },
                )
            }
            else -> {
                // Combined tab row (issue #23): live sessions first, then the
                // persisted history of PREVIOUS runs as dimmed read-only tabs.
                SessionTabs(
                    sessions = sessions,
                    history = history,
                    selected = selected,
                    onSelect = { selected = it },
                    onNewSession = { spawnSession() },
                    onCloseTab = ::requestClose,
                    onClear = ::clearFocused,
                    clearEnabled = focusedLiveSession() != null,
                    // reads refreshTick so "(ended)" labels update without polling
                    refreshTick = refreshTick,
                )

                confirmClose?.let { target ->
                    AlertDialog(
                        onDismissRequest = { confirmClose = null },
                        title = { Text("Close this shell?") },
                        text = {
                            Text(
                                "$confirmBusyCount process" + if (confirmBusyCount == 1) " is" else "es are" +
                                    " still running in this session. Closing kills the shell's whole process" +
                                    " tree — background jobs will not survive. Its transcript stays in terminal history.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                closeNow(target)
                                confirmClose = null
                            }) { Text("Close") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmClose = null }) { Text("Cancel") }
                        },
                    )
                }

                val combinedCount = sessions.size + history.size
                when {
                    combinedCount == 0 -> {
                        Surface(Modifier.weight(1f).fillMaxWidth()) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .background(TermuxTerminalBackground)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("No sessions", style = MaterialTheme.typography.titleMedium, color = TermuxForeground)
                                TextButton(onClick = { spawnSession() }) { Text("Start shell") }
                            }
                        }
                    }
                    selected >= sessions.size -> {
                        // Read-only history pane (issue #23): the transcript of a
                        // session from a previous run, loaded lazily from disk.
                        val safeIndex = selected.coerceIn(sessions.size, combinedCount - 1)
                        val rec = history[safeIndex - sessions.size]
                        HistoryPane(
                            record = rec,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onNewShellHere = { spawnSession(workdir = rec.workdir) },
                            onDelete = {
                                TerminalPersistence.deleteHistory(context, rec.id)
                                history = TerminalPersistence.history()
                                selected = (sessions.size - 1).coerceAtLeast(0)
                            },
                        )
                    }
                    else -> {
                    val safeIndex = selected.coerceIn(0, sessions.size - 1)
                    val session = sessions[safeIndex]

                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        // Ended-session banner (alpha.20): when the focused
                        // shell was killed (signal 9 = Android's phantom
                        // process killer / low memory) or crashed, SAY so —
                        // with a one-tap restart and the per-version fixes —
                        // instead of a bare "(ended)" tab. refreshTick is read
                        // to subscribe: the isRunning flip must recompose this.
                        val endTick = refreshTick
                        if (endTick >= 0 && !session.isRunning) {
                            EndedSessionBanner(
                                exitStatus = try {
                                    session.exitStatus
                                } catch (_: Exception) {
                                    -1
                                },
                                workdir = TerminalSessionRegistry.workdirOf(session) ?: "/root",
                                onRestart = {
                                    spawnSession(workdir = TerminalSessionRegistry.workdirOf(session) ?: "/root")
                                },
                            )
                        }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { ctx ->
                                TerminalView(ctx, null).apply {
                                    // Plain View is NOT focusable by default — without this,
                                    // requestFocus() from taps is a no-op and the IME can
                                    // never appear (the alpha.6 "keyboard never opens" bug).
                                    isFocusable = true
                                    isFocusableInTouchMode = true
                                    setTextSize(fontSizePx)
                                    setTerminalViewClient(
                                        LinuxifyTerminalViewClient(
                                            view = this,
                                            initialFontSize = fontSizePx,
                                            ctrlDown = ctrlDown,
                                            altDown = altDown,
                                            onFontResized = { fontSizePx = it },
                                        )
                                    )
                                    attachSession(session)
                                    // Route this session's output callbacks to THIS view.
                                    sessionClient.boundView = this
                                    terminalView = this
                                }
                            },
                            update = { view ->
                                if (view.mTermSession !== session) {
                                    view.attachSession(session)
                                    sessionClient.boundView = view
                                }
                                // No onScreenUpdated() here: redraws are driven by session
                                // callbacks (real-time) instead of recompositions (laggy).
                            },
                            onRelease = { view ->
                                if (sessionClient.boundView === view) sessionClient.boundView = null
                                if (terminalView === view) terminalView = null
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        ShareLogButton(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp),
                            onTap = { shareTranscript(context, session, "sh ${safeIndex + 1}") },
                        )
                    }
                    }
                    }
                }

                // Extra keys belong to a LIVE session — hidden on history tabs.
                if (selected < sessions.size) {
                    ExtraKeys(
                        onKey = { bytes -> sessions.getOrNull(selected)?.write(bytes, 0, bytes.size) },
                        onKeyWithModifiers = { name, bytes ->
                            var payload = bytes
                            // sticky CTRL: control-maps plain chars and CSI sequences (Termux behavior)
                            if (ctrlDown.value) payload = applyCtrl(payload, name)
                            if (altDown.value) payload = byteArrayOf(0x1b) + payload
                            sessions.getOrNull(selected)?.write(payload, 0, payload.size)
                            ctrlDown.value = false
                            altDown.value = false
                        },
                        ctrlDown = ctrlDown.value,
                        altDown = altDown.value,
                        onCtrl = { ctrlDown.value = !ctrlDown.value },
                        onAlt = { altDown.value = !altDown.value },
                    )
                }
            }
        }
    }
}

/** Termux's shipped default; the vendored renderer treats it as raw pixels. */
private const val TERMUX_DEFAULT_FONT_SIZE_PX = 30
private const val MIN_FONT_SIZE_PX = 8
private const val MAX_FONT_SIZE_PX = 64

/** ESC[H (cursor home) + ESC[2J (erase display) + ESC[3J (drop scrollback). */
private val CLEAR_SEQUENCE = byteArrayOf(
    0x1b, '['.code.toByte(), 'H'.code.toByte(),
    0x1b, '['.code.toByte(), '2'.code.toByte(), 'J'.code.toByte(),
    0x1b, '['.code.toByte(), '3'.code.toByte(), 'J'.code.toByte(),
)

private val TermuxTerminalBackground = Color(0xFF000000)
private val TermuxForeground = Color(0xFFE0E0E0)

/** Raise the soft keyboard for [view] (no-op without an IME manager). */
private fun raiseKeyboard(view: View) {
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

/**
 * Capture the full transcript (scrollback + screen) of [session] and open the
 * system share sheet as plain text — one tap, any app (chat, mail, AI client).
 */
private fun shareTranscript(context: Context, session: TerminalSession, sessionLabel: String) {
    val transcript = session.emulator?.screen?.transcriptText
    if (transcript.isNullOrBlank()) {
        Toast.makeText(context, "Terminal log is empty", Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, TerminalLogShare.buildShareText(transcript, sessionLabel))
        putExtra(Intent.EXTRA_TITLE, "Linuxify terminal log")
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share terminal log"))
    }.onFailure {
        CrashReporter.logNonFatal(context, "shareTranscript", it)
        Toast.makeText(context, "No app can accept shared text", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Small translucent floating button pinned to the bottom-right of the terminal
 * area. 44 dp touch target, subdued so live terminal content stays readable
 * underneath.
 */
@Composable
private fun ShareLogButton(modifier: Modifier = Modifier, onTap: () -> Unit) {
    Box(
        modifier = modifier
            .size(44.dp)
            .background(Color(0xB3262626), CircleShape)
            .semantics { contentDescription = "Share terminal log" }
            .clickableNoRipple(onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_share),
            contentDescription = null,
            tint = TermuxKeyForeground,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SessionTabs(
    sessions: List<TerminalSession>,
    history: List<TerminalPersistence.Record>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onNewSession: () -> Unit,
    onCloseTab: (TerminalSession) -> Unit,
    onClear: () -> Unit,
    clearEnabled: Boolean,
    refreshTick: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(TermuxTerminalBackground)
            .padding(horizontal = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        sessions.forEachIndexed { i, s ->
            val tick = refreshTick // read to subscribe: session-finish updates labels
            val label = "sh ${i + 1}" + if (!s.isRunning && tick >= 0) " (ended)" else ""
            // The close affordance is a SIBLING of the tab button, not nested
            // inside it — a normal tab click (switch shell) can never land on
            // the close glyph, so switching tabs never asks anything.
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { onSelect(i) }, modifier = Modifier.height(30.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (i == selected) TermuxForeground else TermuxForeground.copy(alpha = 0.45f),
                    )
                }
                Spacer(Modifier.width(2.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable { onCloseTab(s) }
                        .padding(7.dp)
                        .semantics { contentDescription = "Close sh ${i + 1}" },
                ) {
                    Text(
                        "\u2715",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TermuxForeground.copy(alpha = 0.55f),
                    )
                }
            }
        }
        // History tabs (issue #23): dimmed, labeled with the session start
        // time — visually distinct from live tabs, clearly past sessions.
        val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
        history.forEachIndexed { h, rec ->
            val idx = sessions.size + h
            val label = timeFmt.format(Date(rec.startedMs))
            TextButton(onClick = { onSelect(idx) }, modifier = Modifier.height(30.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (idx == selected) TermuxKeyForeground.copy(alpha = 0.75f) else TermuxForeground.copy(alpha = 0.22f),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onClear,
            enabled = clearEnabled,
            modifier = Modifier.height(30.dp),
        ) {
            Text(
                "CLR",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (clearEnabled) TermuxKeyForeground else TermuxForeground.copy(alpha = 0.25f),
            )
        }
        TextButton(onClick = onNewSession, modifier = Modifier.height(30.dp)) {
            Text("+", style = MaterialTheme.typography.titleMedium, color = TermuxForeground)
        }
    }
}

/**
 * Banner pinned above the terminal when the focused session ended on its own
 * (alpha.20). The vendored emulator already prints "[Process completed
 * (signal 9) - press Enter]" into the scrollback; this tells the user WHO
 * killed the shell and what to do about it, instead of a bare "(ended)" tab.
 */
@Composable
private fun EndedSessionBanner(
    exitStatus: Int,
    workdir: String,
    onRestart: () -> Unit,
) {
    val kind = SessionEndCause.classify(exitStatus)
    var showFixes by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C0B0B))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            SessionEndCause.shortLabel(kind, exitStatus),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = if (kind == SessionEndCause.Kind.CLEAN_EXIT) TermuxKeyForeground else Color(0xFFFF8A80),
        )
        Text(
            SessionEndCause.explanation(kind, exitStatus),
            style = MaterialTheme.typography.bodySmall,
            color = TermuxForeground.copy(alpha = 0.72f),
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRestart) {
                Text(if (workdir != "/root") "Restart shell in $workdir" else "Restart shell")
            }
            if (kind == SessionEndCause.Kind.KILLED_BY_ANDROID || kind == SessionEndCause.Kind.GUEST_CRASH) {
                TextButton(onClick = { showFixes = true }) { Text("Why / fixes") }
            }
        }
    }
    if (showFixes) {
        SessionEndFixesDialog(onDismiss = { showFixes = false })
    }
}

/**
 * The honest explainer for externally killed sessions + the per-version fixes,
 * with one-tap copy of the ADB commands (Android 12/13 have no user-facing
 * toggle — the same mitigation Termux documents for its users).
 */
@Composable
private fun SessionEndFixesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why do shells end on their own?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Android itself killed the shell — Linuxify and the Linux environment did not crash. On Android 12+ " +
                        "the system silently ends apps' background child processes (the \"phantom process killer\") once " +
                        "about 32 exist system-wide, or when one uses heavy CPU while the app is backgrounded. Low " +
                        "memory and OEM battery managers kill the same way.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(SessionEndCause.FIX_ANDROID_14_PLUS, style = MaterialTheme.typography.bodySmall)
                Text(
                    SessionEndCause.FIX_ANDROID_13,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    SessionEndCause.FIX_ANDROID_12,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(SessionEndCause.FIX_GENERAL, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(
                    android.content.ClipData.newPlainText("linuxify-phantom-fix", SessionEndCause.ADB_FIX_COMMANDS),
                )
                Toast.makeText(context, "ADB commands copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy ADB commands") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Read-only transcript pane for a session from a previous run (issue #23).
 * Loads the log lazily (tail-capped at [HISTORY_TAIL_BYTES] — a long-running
 * session's log can grow large); shows the session metadata and offers a new
 * shell in the same directory plus an explicit delete. Nothing here is ever
 * deleted implicitly.
 */
private const val HISTORY_TAIL_BYTES = 512 * 1024

@Composable
private fun HistoryPane(
    record: TerminalPersistence.Record,
    modifier: Modifier = Modifier,
    onNewShellHere: () -> Unit,
    onDelete: () -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    // Lazy disk read: transcript tail for this record id only.
    var text by remember(record.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(record.id) {
        text = withContext(Dispatchers.IO) {
            val f = TerminalPersistence.logFile(com.linuxify.app.LinuxifyApp.instance, record.id)
            readTranscriptTail(f)
        }
    }

    Column(modifier.background(TermuxTerminalBackground)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Terminal history",
                style = MaterialTheme.typography.titleMedium,
                color = TermuxForeground,
            )
            Text(
                listOfNotNull(
                    timeFmt.format(Date(record.startedMs)),
                    record.workdir.takeIf { it != "/root" },
                    when {
                        // alpha.20: the persisted human cause ("killed by Android
                        // (signal 9)") replaces the raw "ended (exit -9)" when known.
                        record.isFinished -> record.endCause ?: "ended (exit ${record.exitCode ?: "unknown"})"
                        else -> "interrupted"
                    },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TermuxForeground.copy(alpha = 0.6f),
            )
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onNewShellHere) { Text("New shell in this directory") }
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        Text(
            text ?: "Loading transcript…",
            style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            color = TermuxForeground.copy(alpha = 0.85f),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Last [maxBytes] of a transcript file, dropped mid-line at the cut. */
private fun readTranscriptTail(file: File, maxBytes: Int = HISTORY_TAIL_BYTES): String {
    if (!file.isFile) return "(transcript not saved)"
    return runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val start = (len - maxBytes).coerceAtLeast(0)
            raf.seek(start)
            val buf = ByteArray((len - start).toInt().coerceAtMost(maxBytes))
            raf.readFully(buf)
            var s = String(buf, Charsets.UTF_8)
            if (start > 0) s = s.lineSequence().drop(1).joinToString("\n")
            s
        }
    }.getOrElse { "(could not read transcript: ${it.message})" }
}

/** Apply sticky CTRL to an extra-key payload, mirroring what a real keyboard does. */
private fun applyCtrl(bytes: ByteArray, keyName: String): ByteArray {
    return when {
        // CSI sequences: insert the 1;5 modifier parameter (e.g. ESC[A -> ESC[1;5A)
        keyName in CTRLABLE_CSI && bytes.size >= 2 && bytes[0] == 0x1b.toByte() && bytes[1] == '['.code.toByte() ->
            byteArrayOf(0x1b, '['.code.toByte()) +
                "1;5".toByteArray(Charsets.US_ASCII) +
                bytes.copyOfRange(2, bytes.size)
        // plain printable chars that map to control codes ('/' and '-' -> US 0x1f)
        bytes.size == 1 && bytes[0].toInt() in 0x20..0x7f -> byteArrayOf((bytes[0].toInt() and 0x1f).toByte())
        else -> bytes
    }
}

private val CTRLABLE_CSI = setOf("UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN")

/**
 * Termux's default extra keys, in Termux's two-row layout. Every key in a row
 * shares equal width, keys write real bytes/escape sequences, and CTRL/ALT are
 * sticky toggles applied to the next key.
 */
@Composable
private fun ExtraKeys(
    onKey: (ByteArray) -> Unit,
    onKeyWithModifiers: (String, ByteArray) -> Unit,
    ctrlDown: Boolean,
    altDown: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TermuxTerminalBackground)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ExtraKey("ESC", Modifier.weight(1f)) { onKeyWithModifiers("ESC", byteArrayOf(0x1b)) }
            ExtraKey("/", Modifier.weight(1f)) { onKeyWithModifiers("/", byteArrayOf('/'.code.toByte())) }
            ExtraKey("-", Modifier.weight(1f)) { onKeyWithModifiers("-", byteArrayOf('-'.code.toByte())) }
            ExtraKey("HOME", Modifier.weight(1f)) { onKeyWithModifiers("HOME", byteArrayOf(0x1b, '['.code.toByte(), 'H'.code.toByte())) }
            ExtraKey("↑", Modifier.weight(1f)) { onKeyWithModifiers("UP", byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())) }
            ExtraKey("END", Modifier.weight(1f)) { onKeyWithModifiers("END", byteArrayOf(0x1b, '['.code.toByte(), 'F'.code.toByte())) }
            ExtraKey("PGUP", Modifier.weight(1f)) { onKeyWithModifiers("PGUP", byteArrayOf(0x1b, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToggleKey("CTRL", ctrlDown, Modifier.weight(1f), onCtrl)
            ToggleKey("ALT", altDown, Modifier.weight(1f), onAlt)
            ExtraKey("TAB", Modifier.weight(1f)) { onKey(byteArrayOf(0x09)) }
            ExtraKey("←", Modifier.weight(1f)) { onKeyWithModifiers("LEFT", byteArrayOf(0x1b, '['.code.toByte(), 'D'.code.toByte())) }
            ExtraKey("↓", Modifier.weight(1f)) { onKeyWithModifiers("DOWN", byteArrayOf(0x1b, '['.code.toByte(), 'B'.code.toByte())) }
            ExtraKey("→", Modifier.weight(1f)) { onKeyWithModifiers("RIGHT", byteArrayOf(0x1b, '['.code.toByte(), 'C'.code.toByte())) }
            ExtraKey("PGDN", Modifier.weight(1f)) { onKeyWithModifiers("PGDN", byteArrayOf(0x1b, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())) }
            ExtraKey("\\", Modifier.weight(1f)) { onKeyWithModifiers("BKSL", byteArrayOf('\\'.code.toByte())) }
        }
    }
}

@Composable
private fun ExtraKey(label: String, modifier: Modifier = Modifier, onTap: () -> Unit) {
    Text(
        label,
        modifier = modifier
            .height(34.dp)
            .background(TermuxKeyBackground, MaterialTheme.shapes.small)
            .clickableNoRipple(onTap)
            .wrapContentSize(Alignment.Center),
        style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        color = TermuxKeyForeground,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ToggleKey(label: String, active: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    Text(
        label,
        modifier = modifier
            .height(34.dp)
            .background(if (active) TermuxKeyActiveBackground else TermuxKeyBackground, MaterialTheme.shapes.small)
            .clickableNoRipple(onTap)
            .wrapContentSize(Alignment.Center),
        style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        color = if (active) TermuxKeyActiveForeground else TermuxKeyForeground,
        textAlign = TextAlign.Center,
    )
}

/** Tap without the ripple/explosion of Material buttons — Termux keys are flat. */
private fun Modifier.clickableNoRipple(onTap: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onTap))

private val TermuxKeyBackground = Color(0xFF2E2E2E)
private val TermuxKeyActiveBackground = Color(0xFF8AB4F8)
private val TermuxKeyActiveForeground = Color(0xFF101010)
private val TermuxKeyForeground = Color(0xFFE0E0E0)

/**
 * TerminalViewClient: required by TerminalView — it calls onEmulatorSet() from
 * updateSize() during the first layout pass, so a view without a client crashes
 * before it ever draws (the alpha.5 crash). Owns the Termux interactions:
 * tap-to-keyboard and pinch-to-zoom, plus the CTRL/ALT sticky state feeding the
 * hardware/IME key pipeline.
 */
private class LinuxifyTerminalViewClient(
    private val view: TerminalView,
    initialFontSize: Int,
    private val ctrlDown: MutableState<Boolean>,
    private val altDown: MutableState<Boolean>,
    private val onFontResized: (Int) -> Unit,
) : TerminalViewClient {

    private var fontSize = initialFontSize

    override fun onScale(scale: Float): Float {
        // Termux-style incremental zoom: the view resets its accumulated factor
        // to whatever we return, so returning 1f makes each callback carry the
        // delta since the last one — multiply it into our own tracked size.
        if (scale > 0f && scale != 1f) {
            val newSize = (fontSize * scale).roundToInt().coerceIn(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX)
            if (newSize != fontSize) {
                fontSize = newSize
                view.setTextSize(newSize)
                onFontResized(newSize)
            }
        }
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        // TerminalView already called requestFocus(); actually raise the IME —
        // without this the terminal cannot receive any typing (alpha.6 bug).
        if (!view.hasFocus()) view.requestFocus()
        raiseKeyboard(view)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = ctrlDown.value

    override fun readAltKey(): Boolean = altDown.value

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) {}

    override fun logWarn(tag: String, message: String) {}

    override fun logInfo(tag: String, message: String) {}

    override fun logDebug(tag: String, message: String) {}

    override fun logVerbose(tag: String, message: String) {}

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}

    override fun logStackTrace(tag: String, e: Exception) {}
}
