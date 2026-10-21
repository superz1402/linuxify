package com.linuxify.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxify.app.backup.BackupManager
import com.linuxify.app.maintenance.MaintenanceManager
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.PowerPolicy
import com.linuxify.app.runtime.RuntimeDirs
import com.linuxify.app.terminal.SessionEndCause
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.FactRow
import com.linuxify.app.ui.theme.AppType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings: real storage facts, safe maintenance actions, backup & restore
 * (issue #23), and a danger zone with explicit, separately-confirmed
 * destructive actions.
 *
 * Every cache action is REPORT-then-CONFIRM: tapping a button computes the
 * exact list of locations + bytes first; a dialog shows them; only explicit
 * confirmation deletes. Blocked items (live sessions, running install) are
 * shown with their reason and are not deletable until the blocker clears.
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val facts by vm.homeFacts.collectAsStateWithLifecycle()
    val backupState by vm.backupState.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetTyped by remember { mutableStateOf("") }
    var confirmClearTermHist by remember { mutableStateOf(false) }

    // cache report/confirm flow (system-wide)
    var cacheReport by remember { mutableStateOf<MaintenanceManager.Report?>(null) }

    // backup/restore flow (issue #23)
    var includeRootfs by remember { mutableStateOf(false) }
    var restoreManifest by remember { mutableStateOf<BackupManager.Manifest?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var restoreProjects by remember { mutableStateOf(true) }
    var restoreTerminal by remember { mutableStateOf(true) }
    var restoreRootfs by remember { mutableStateOf(false) }
    var restoreReplaceProjects by remember { mutableStateOf(false) }

    // Battery policy toggle (alpha.17 battery audit): default OFF — the CPU is
    // allowed to sleep when only idle shells exist.
    val appCtx = LocalContext.current
    var stayAwake by remember { mutableStateOf(PowerPolicy.stayAwakeEnabled(appCtx)) }

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { uri -> if (uri != null) vm.createBackup(uri, includeRootfs) }

    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            restoreUri = uri
            vm.inspectBackup(
                uri,
                onReady = { restoreManifest = it },
                onError = { message = "Not a valid backup: $it" },
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Storage", style = MaterialTheme.typography.titleMedium)
                facts?.let { f ->
                    FactRow("Linux environment", LinuxRuntime.humanSize(f.rootfsSizeBytes))
                    FactRow("Projects (${f.projectsCount})", LinuxRuntime.humanSize(f.projectsSizeBytes))
                    FactRow("Terminal history", LinuxRuntime.humanSize(f.terminalLogsBytes))
                    FactRow("App cache", LinuxRuntime.humanSize(f.appCacheBytes))
                    FactRow("Free on device", LinuxRuntime.humanSize(f.freeBytes))
                }
                Text(
                    "Everything lives in app-private storage and survives restarts and reboots. Only explicit actions below (or uninstalling the app) delete data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { vm.systemCacheReport { cacheReport = it } },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Clear caches (whole system)…") }
                // Terminal history is transcript logs of finished sessions —
                // live sessions and their records are never touched.
                OutlinedButton(onClick = { confirmClearTermHist = true }) {
                    Text("Clear terminal history…")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Battery", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = stayAwake,
                        onCheckedChange = { on ->
                            stayAwake = on
                            PowerPolicy.setStayAwake(appCtx, on)
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Keep CPU awake while shells run", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Off (recommended): when the screen turns off the CPU may sleep unless a managed service is running or something is downloading/installing — background shells pause, zero extra drain. " +
                        "On: background shell sessions keep computing at full speed (Termux's optional wake-lock behavior) at a real battery cost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // alpha.20: honest guidance for the #1 "is the app broken?" report —
        // shells that die on their own are Android's phantom process killer,
        // not a crash. The fix is a device setting, so surface it here too.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Session stability", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If shells ever end on their own — the tab flips to \"(ended)\" and the transcript shows " +
                        "[Process completed (signal 9)] — Android killed the shell, not a Linuxify crash. On Android 12+ " +
                        "the system silently ends apps' background child processes (\"phantom process killer\") once about " +
                        "32 exist system-wide, or when one uses heavy CPU while the app is backgrounded; low memory and " +
                        "OEM battery managers kill the same way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "The fixes, per Android version:",
                    style = MaterialTheme.typography.labelLarge,
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
                Text(SessionEndCause.FIX_GENERAL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    val cm = appCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("linuxify-phantom-fix", SessionEndCause.ADB_FIX_COMMANDS),
                    )
                    android.widget.Toast.makeText(appCtx, "ADB commands copied", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Copy ADB fix commands") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Saves your projects, terminal history and (optionally) the whole Linux environment into one .tar.gz file you choose with the system picker. Restore brings it back — e.g. after reinstalling the app or moving to a new device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeRootfs, onCheckedChange = { includeRootfs = it })
                    Text(
                        "Include Linux environment (large, slow)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                            createBackupLauncher.launch("linuxify-backup-$stamp.tar.gz")
                        },
                        enabled = !backupState.busy,
                    ) { Text("Create backup") }
                    OutlinedButton(
                        onClick = { openBackupLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream")) },
                        enabled = !backupState.busy,
                    ) { Text("Restore backup") }
                }
                backupState.progress?.takeIf { backupState.busy }?.let {
                    Text(it, style = AppType.MonoSmall, color = MaterialTheme.colorScheme.primary)
                }
                backupState.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About this environment", style = MaterialTheme.typography.titleMedium)
                FactRow("Distro", "Ubuntu 24.04.5 LTS (noble)")
                FactRow("Runtime", "proot (no root, no kernel isolation)")
                FactRow("Terminal", "Termux terminal emulator (Apache-2.0)")
                FactRow("Package manager", "APT")
                Text(
                    "Uninstalling Linuxify deletes the Linux environment and all user files — export a backup first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Danger zone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(
                    "Reset deletes the entire Linux environment, including all files created inside it (projects under the app's Projects directory are kept).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { confirmReset = true }) {
                    Text("Reset Linux environment", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Text(
            "Linuxify ${com.linuxify.app.BuildConfig.VERSION_NAME} · GPLv3 · terminal components Apache-2.0 (jackpal/Termux) · proot GPLv3",
            style = AppType.MonoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ---- system cache report/confirm dialog ----
    cacheReport?.let { report ->
        AlertDialog(
            onDismissRequest = { cacheReport = null },
            title = { Text("Clear system caches?") },
            text = {
                Column {
                    if (report.items.isEmpty()) {
                        Text("Nothing to clear right now — caches are empty.")
                    }
                    report.items.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                "${item.label} — ${LinuxRuntime.humanSize(item.bytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        item.blockedReason?.let {
                            Text(
                                "  blocked: $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (report.hasClearable) {
                        Text(
                            "Total clearable: ${LinuxRuntime.humanSize(report.totalBytes)}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = report.hasClearable,
                    onClick = {
                        val items = report.items.filter { it.blockedReason == null }
                        cacheReport = null
                        vm.clearSystemCaches(items) { message = it }
                    },
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { cacheReport = null }) { Text("Cancel") } },
        )
    }

    // ---- clear terminal history confirm dialog ----
    if (confirmClearTermHist) {
        AlertDialog(
            onDismissRequest = { confirmClearTermHist = false },
            title = { Text("Clear terminal history?") },
            text = {
                Text(
                    "Deletes the transcripts and records of all FINISHED terminal sessions " +
                        "(the \"Terminal history\" storage shown above). Shells that are " +
                        "running right now keep running and are not affected.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearTermHist = false
                        vm.clearTerminalHistory { message = it }
                    },
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClearTermHist = false }) { Text("Cancel") } },
        )
    }

    // ---- restore options dialog ----
    val manifest = restoreManifest
    val uri = restoreUri
    if (manifest != null && uri != null) {
        AlertDialog(
            onDismissRequest = { restoreManifest = null; restoreUri = null },
            title = { Text("Restore backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val createdText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(manifest.createdMs))
                    Text(
                        "Created $createdText by Linuxify ${manifest.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = restoreProjects,
                            onCheckedChange = { restoreProjects = it },
                            enabled = manifest.projectNames.isNotEmpty(),
                        )
                        Text("Projects (${manifest.projectNames.size})", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = restoreTerminal,
                            onCheckedChange = { restoreTerminal = it },
                            enabled = manifest.terminalCount > 0,
                        )
                        Text("Terminal history (${manifest.terminalCount})", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = restoreRootfs,
                            onCheckedChange = { restoreRootfs = it },
                            enabled = manifest.rootfsIncluded,
                        )
                        Text("Linux environment", style = MaterialTheme.typography.bodySmall)
                    }
                    if (manifest.rootfsIncluded) {
                        Text(
                            "Restoring the Linux environment REPLACES the current one entirely (services and terminals are stopped).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = restoreReplaceProjects,
                            onCheckedChange = { restoreReplaceProjects = it },
                            enabled = restoreProjects,
                        )
                        Text(
                            "Overwrite existing projects with the same name",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!restoreReplaceProjects) {
                        Text(
                            "Existing projects keep their current files; only new projects are added.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val scopes = buildSet {
                            if (restoreProjects) add(BackupManager.Scope.PROJECTS)
                            if (restoreTerminal) add(BackupManager.Scope.TERMINAL)
                            if (restoreRootfs && manifest.rootfsIncluded) add(BackupManager.Scope.ROOTFS)
                        }
                        restoreManifest = null
                        restoreUri = null
                        if (scopes.isEmpty()) {
                            message = "Nothing selected to restore."
                        } else {
                            vm.restoreBackup(uri, scopes, restoreReplaceProjects)
                        }
                    },
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { restoreManifest = null; restoreUri = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false; resetTyped = "" },
            title = { Text("Reset Linux environment?") },
            text = {
                Column {
                    Text("This deletes the Linux filesystem and everything inside it. This cannot be undone.")
                    Text(
                        "Type RESET to confirm.",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = resetTyped,
                        onValueChange = { resetTyped = it },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmReset = false
                        val ctx = vm.getApplication<android.app.Application>()
                        RuntimeDirs.installMarker(ctx).delete()
                        RuntimeDirs.rootfsDir(ctx).deleteRecursively()
                        RuntimeDirs.stagingDir(ctx).deleteRecursively()
                        RuntimeDirs.downloadsDir(ctx).deleteRecursively()
                        message = "Linux environment removed. Use Home → Install to set it up again."
                        vm.refreshHomeFacts()
                        resetTyped = ""
                    },
                    enabled = resetTyped == "RESET",
                ) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
