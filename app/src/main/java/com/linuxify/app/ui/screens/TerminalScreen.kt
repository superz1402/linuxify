package com.linuxify.app.ui.screens

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.linuxify.app.terminal.PtyTerminal
import com.linuxify.app.runtime.CrashReporter
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.ErrorState
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
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
 */
@Composable
fun TerminalScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val sessions = remember { mutableStateListOf<TerminalSession>() }
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

    val sessionClient = remember {
        LinuxifySessionClient(
            viewProvider = { terminalView },
            onSessionFinishedUi = { refreshTick += 1 },
        )
    }

    fun spawnSession(initial: String? = null) {
        try {
            val s = PtyTerminal.newShellSession(context, sessionClient, initialCommand = initial)
            sessions.add(s)
            selected = sessions.size - 1
            spawnError = null
        } catch (t: Throwable) {
            CrashReporter.logNonFatal(context, "spawnSession", t)
            spawnError = t.message ?: t.javaClass.simpleName
        }
    }

    LaunchedEffect(Unit) {
        if (jniError == null && sessions.isEmpty()) spawnSession()
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
            // Processes keep running by design (master prompt §6.3): closing the terminal
            // view does NOT kill the shell. Honest about app-process death elsewhere.
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
                SessionTabs(
                    sessions = sessions,
                    selected = selected,
                    onSelect = { selected = it },
                    onNewSession = { spawnSession() },
                    // reads refreshTick so "(ended)" labels update without polling
                    refreshTick = refreshTick,
                )

                if (sessions.isEmpty()) {
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
                } else {
                    val safeIndex = selected.coerceIn(0, sessions.size - 1)
                    val session = sessions[safeIndex]

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
                                terminalView = this
                            }
                        },
                        update = { view ->
                            if (view.mTermSession !== session) {
                                view.attachSession(session)
                            }
                            // No onScreenUpdated() here: redraws are driven by session
                            // callbacks (real-time) instead of recompositions (laggy).
                        },
                        onRelease = { terminalView = null },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }

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

/** Termux's shipped default; the vendored renderer treats it as raw pixels. */
private const val TERMUX_DEFAULT_FONT_SIZE_PX = 30
private const val MIN_FONT_SIZE_PX = 8
private const val MAX_FONT_SIZE_PX = 64

private val TermuxTerminalBackground = Color(0xFF000000)
private val TermuxForeground = Color(0xFFE0E0E0)

/** Raise the soft keyboard for [view] (no-op without an IME manager). */
private fun raiseKeyboard(view: View) {
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

@Composable
private fun SessionTabs(
    sessions: List<TerminalSession>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onNewSession: () -> Unit,
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
            TextButton(onClick = { onSelect(i) }, modifier = Modifier.height(30.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (i == selected) TermuxForeground else TermuxForeground.copy(alpha = 0.45f),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onNewSession, modifier = Modifier.height(30.dp)) {
            Text("+", style = MaterialTheme.typography.titleMedium, color = TermuxForeground)
        }
    }
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

/**
 * TerminalSessionClient: pushes emulator output straight into the view (Termux's
 * model — output arrives on the main thread via the session's MainThreadHandler)
 * and feeds UI-only reactions (session finish) back to composition.
 */
private class LinuxifySessionClient(
    private val viewProvider: () -> TerminalView?,
    private val onSessionFinishedUi: () -> Unit,
) : TerminalSessionClient {

    private fun redraw(changedSession: TerminalSession) {
        val view = viewProvider() ?: return
        if (view.mTermSession === changedSession) view.onScreenUpdated()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        redraw(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        redraw(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onSessionFinishedUi()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}

    override fun onPasteTextFromClipboard(session: TerminalSession) {}

    override fun onBell(session: TerminalSession) {}

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
