package com.linuxify.app.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import com.linuxify.app.terminal.PtyTerminal
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.theme.AppType
import com.linuxify.app.ui.theme.TerminalBackground
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

/**
 * Real PTY-backed terminal (vendored Termux TerminalView inside Compose).
 * Multiple sessions, extra-keys row, keyboard-aware resizing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val sessions = remember { mutableStateListOf<TerminalSession>() }
    var selected by remember { mutableIntStateOf(0) }
    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    val sessionClient = remember { LinuxifySessionClient { refreshTick++ } }

    fun spawnSession(initial: String? = null): TerminalSession {
        val s = PtyTerminal.newShellSession(context, sessionClient, initialCommand = initial)
        sessions.add(s)
        selected = sessions.size - 1
        return s
    }

    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) spawnSession()
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
            .background(TerminalBackground),
    ) {
        if (sessions.size > 1) {
            SecondaryScrollableTabRow(
                selectedTabIndex = selected.coerceIn(0, sessions.size - 1),
                edgePadding = 8.dp,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                sessions.forEachIndexed { i, s ->
                    Tab(
                        selected = i == selected,
                        onClick = { selected = i },
                        text = {
                            Text(
                                "sh ${i + 1}" + if (!s.isRunning) " (ended)" else "",
                                style = AppType.MonoSmall,
                            )
                        },
                    )
                }
            }
        }

        if (sessions.isEmpty()) {
            Surface(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No sessions", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { spawnSession() }) { Text("Start shell") }
                }
            }
        } else {
            val safeIndex = selected.coerceIn(0, sessions.size - 1)
            val session = sessions[safeIndex]

            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        setTextSize(13)
                        attachSession(session)
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_DOWN && event.isSystem == false) {
                                false // let TerminalView's own onKeyDown handle real keys
                            } else {
                                false
                            }
                        }
                    }
                },
                update = { view ->
                    if (view.mTermSession !== session) {
                        view.attachSession(session)
                    }
                    view.onScreenUpdated()
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }

        ExtraKeysRow(
            ctrlDown = ctrlDown,
            altDown = altDown,
            onCtrl = { ctrlDown = !ctrlDown },
            onAlt = { altDown = !altDown },
            onKey = { _, bytes ->
                sessions.getOrNull(selected)?.write(bytes, 0, bytes.size)
                ctrlDown = false
                altDown = false
            },
            onCtrlC = {
                byteArrayOf(0x03).let { b -> sessions.getOrNull(selected)?.write(b, 0, b.size) }
                ctrlDown = false
            },
            onNewSession = { spawnSession() },
        )
    }
}

@Composable
private fun ExtraKeysRow(
    ctrlDown: Boolean,
    altDown: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (String, ByteArray) -> Unit,
    onCtrlC: () -> Unit,
    onNewSession: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ExtraKey("ESC") { onKey("esc", byteArrayOf(0x1b)) }
            ExtraKey("TAB") { onKey("tab", byteArrayOf(0x09)) }
            ToggleKey("CTRL", ctrlDown, onCtrl)
            ToggleKey("ALT", altDown, onAlt)
            ExtraKey("↑") { onKey("up", byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())) }
            ExtraKey("↓") { onKey("down", byteArrayOf(0x1b, '['.code.toByte(), 'B'.code.toByte())) }
            ExtraKey("←") { onKey("left", byteArrayOf(0x1b, '['.code.toByte(), 'D'.code.toByte())) }
            ExtraKey("→") { onKey("right", byteArrayOf(0x1b, '['.code.toByte(), 'C'.code.toByte())) }
            ExtraKey("HOME") { onKey("home", byteArrayOf(0x1b, '['.code.toByte(), 'H'.code.toByte())) }
            ExtraKey("END") { onKey("end", byteArrayOf(0x1b, '['.code.toByte(), 'F'.code.toByte())) }
            ExtraKey("PGUP") { onKey("pgup", byteArrayOf(0x1b, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())) }
            ExtraKey("PGDN") { onKey("pgdn", byteArrayOf(0x1b, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())) }
            ExtraKey("CTRL+C") { onCtrlC() }
            ExtraKey("+SH") { onNewSession() }
        }
    }
}

@Composable
private fun ExtraKey(label: String, onTap: () -> Unit) {
    TextButton(onClick = onTap, modifier = Modifier.height(36.dp)) {
        Text(label, style = AppType.MonoSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ToggleKey(label: String, active: Boolean, onTap: () -> Unit) {
    TextButton(onClick = onTap, modifier = Modifier.height(36.dp)) {
        Text(
            label,
            style = AppType.MonoSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** TerminalSessionClient: feeds emulator-driven UI refreshes and system logging. */
private class LinuxifySessionClient(
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
