package com.linuxify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.linuxify.app.terminal.TerminalSessionsController
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.ErrorState
import com.linuxify.app.ui.theme.AppType
import com.linuxify.app.ui.theme.TerminalBackground
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * Real PTY-backed terminal (vendored Termux TerminalView inside Compose).
 *
 * All session state lives in [TerminalSessionsController] (ViewModel scope):
 * navigating away and back — or any tap anywhere in the UI — never closes,
 * resets, or duplicates the shell. Closing a session only happens through the
 * explicit Close action, and even then the controller asks only when real
 * work is running under that shell (see its smart-close policy).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: AppViewModel) {
    val ts = vm.terminalSessions
    val sessions = ts.sessions
    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }

    // First entry spawns the shell; re-entry after navigation must NOT spawn
    // again — sessions persist in the controller, so an empty list here means
    // the user closed them all (or the very first visit).
    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) ts.spawn()
    }

    // Emulator-driven changes (output, titles, colors, session finish) bump
    // ts.revision. Reading it as a composition key makes this body recompose,
    // which re-invokes the AndroidView update block below (repaint + the
    // running/ended badges in the header).
    LaunchedEffect(ts.revision) { /* repaint sync — see class doc */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground),
    ) {
        when {
            ts.engineError != null -> {
                ErrorState(
                    title = "Terminal engine unavailable",
                    body = "The terminal's native engine (libtermux.so) failed to load: " +
                        "${ts.engineError?.message ?: ts.engineError?.javaClass?.simpleName}. " +
                        "Reinstall the latest Linuxify APK — if this persists, copy the crash report and report it.",
                )
            }
            ts.spawnError != null && sessions.isEmpty() -> {
                ErrorState(
                    title = "Could not start shell",
                    body = "The Linux shell failed to start: ${ts.spawnError}",
                    actionLabel = "Retry",
                    onAction = { ts.spawn() },
                )
            }
            else -> {
                if (sessions.isNotEmpty()) SessionHeader(ts)

                if (sessions.isEmpty()) {
                    Surface(Modifier.weight(1f).fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("No sessions", style = MaterialTheme.typography.titleMedium)
                            Text("All sessions were closed. Nothing is running in the background of this screen.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { ts.spawn() }) { Text("Start shell") }
                        }
                    }
                } else {
                    val safeIndex = ts.selected.coerceIn(0, sessions.size - 1)
                    val session = sessions[safeIndex]

                    AndroidView(
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                setTextSize(13)
                                attachSession(session)
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

                    ExtraKeysRow(
                        ctrlDown = ctrlDown,
                        altDown = altDown,
                        onCtrl = { ctrlDown = !ctrlDown },
                        onAlt = { altDown = !altDown },
                        onKey = { _, bytes ->
                            sessions.getOrNull(ts.selected)?.write(bytes, 0, bytes.size)
                            ctrlDown = false
                            altDown = false
                        },
                        onCtrlC = {
                            byteArrayOf(0x03).let { b -> sessions.getOrNull(ts.selected)?.write(b, 0, b.size) }
                            ctrlDown = false
                        },
                    )
                }
            }
        }
    }

    ts.closePrompt?.let { prompt ->
        CloseSessionDialog(prompt, ts)
    }
}

/** Session switcher (with per-tab close) + session actions for the visible session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionHeader(ts: TerminalSessionsController) {
    val sessions = ts.sessions
    if (sessions.size > 1) {
        SecondaryScrollableTabRow(
            selectedTabIndex = ts.selected.coerceIn(0, sessions.size - 1),
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            sessions.forEachIndexed { i, s ->
                Tab(
                    selected = i == ts.selected,
                    onClick = { ts.selected = i },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "sh ${i + 1}" + if (!s.isRunning) " (ended)" else "",
                                style = AppType.MonoSmall,
                            )
                            // Per-session close: hits only this tab's 14dp glyph,
                            // never the tab itself (descendant consumes the tap).
                            Box(
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(22.dp)
                                    .clickable { ts.requestClose(s) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Close sh ${i + 1}",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
    SessionActionBar(ts)
}

@Composable
private fun SessionActionBar(ts: TerminalSessionsController) {
    val sessions = ts.sessions
    val safeIndex = ts.selected.coerceIn(0, sessions.size - 1)
    val session = sessions.getOrNull(safeIndex) ?: return
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sessions.size == 1) {
                Text(
                    "sh 1" + if (!session.isRunning) " (ended)" else "",
                    style = AppType.MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { ts.spawn() }) {
                Icon(Icons.Filled.Add, contentDescription = "New session")
            }
            IconButton(
                onClick = { ts.clear(session) },
                enabled = session.isRunning,
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear screen")
            }
            IconButton(onClick = { ts.requestClose(session) }) {
                Icon(Icons.Filled.Close, contentDescription = "Close session")
            }
        }
    }
}

@Composable
private fun CloseSessionDialog(
    prompt: TerminalSessionsController.ClosePrompt,
    ts: TerminalSessionsController,
) {
    val idx = ts.sessions.indexOf(prompt.session)
    val label = if (idx >= 0) "sh ${idx + 1}" else "this session"
    AlertDialog(
        onDismissRequest = { ts.dismissClose() },
        title = { Text("Close $label?") },
        text = {
            Text(
                "${prompt.busyProcs} process" + if (prompt.busyProcs == 1) " is" else "es are" +
                    " still running in $label. Closing kills the session's whole process tree — " +
                    "unsaved work in that session is lost."
            )
        },
        confirmButton = {
            TextButton(onClick = { ts.confirmClose() }) { Text("Close anyway") }
        },
        dismissButton = {
            TextButton(onClick = { ts.dismissClose() }) { Text("Cancel") }
        },
    )
}

@Composable
private fun ExtraKeysRow(
    ctrlDown: Boolean,
    altDown: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (String, ByteArray) -> Unit,
    onCtrlC: () -> Unit,
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
