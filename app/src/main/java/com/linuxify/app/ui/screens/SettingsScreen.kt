package com.linuxify.app.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.RuntimeDirs
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.FactRow
import com.linuxify.app.ui.theme.AppType

/**
 * Settings: real storage facts, safe maintenance actions, and a danger zone
 * with explicit, separately-confirmed destructive actions.
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val facts by vm.homeFacts.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetTyped by remember { mutableStateOf("") }

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
                    FactRow("Free on device", LinuxRuntime.humanSize(f.freeBytes))
                }
                OutlinedButton(
                    onClick = { vm.clearAptCaches { message = it } },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Clear cached package archives") }
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
                    "Data lives in app-private storage. Uninstalling Linuxify deletes the Linux environment and all user files — export anything important first.",
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
            "Linuxify 0.1.0-alpha.1 · GPLv3 · terminal components Apache-2.0 (jackpal/Termux) · proot GPLv3",
            style = AppType.MonoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
