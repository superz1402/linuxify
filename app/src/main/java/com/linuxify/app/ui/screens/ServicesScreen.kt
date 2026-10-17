package com.linuxify.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxify.app.srv.ServiceManager
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.EmptyState
import com.linuxify.app.ui.components.Status
import com.linuxify.app.ui.components.StatusChip
import com.linuxify.app.ui.theme.AppType

/**
 * Local service manager. States come from real process liveness and a real
 * loopback socket probe — never inferred from a Start press.
 */
@Composable
fun ServicesScreen(vm: AppViewModel) {
    val state by vm.servicesState.collectAsStateWithLifecycle()
    var logsFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshServices() }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Services", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Servers run inside the Linux environment, bound to this device (loopback) by default. Android may stop background processes at any time — the app reports that honestly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.services.isEmpty()) {
            EmptyState("No services defined", "A default Python HTTP server is provided for testing. Define more services in a future release or run servers directly in the terminal.")
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(state.services, key = { it.def.id }) { s ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.def.name, style = MaterialTheme.typography.titleMedium)
                            when (s.state) {
                                ServiceManager.State.RUNNING -> StatusChip(Status.RUNNING)
                                ServiceManager.State.STARTING -> StatusChip(Status.STARTING)
                                ServiceManager.State.FAILED -> StatusChip(Status.FAILED)
                                ServiceManager.State.STOPPING -> StatusChip(Status.BUSY)
                                ServiceManager.State.STOPPED -> StatusChip(Status.STOPPED)
                            }
                        }
                        Text(s.def.command, style = AppType.MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.def.port?.let { port ->
                            Text(
                                if (s.portReachable) "127.0.0.1:$port reachable" else "127.0.0.1:$port not reachable yet",
                                style = AppType.MonoSmall,
                                color = if (s.portReachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        s.exitCode?.let {
                            Text(
                                "Exited with code $it",
                                style = AppType.MonoSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.state == ServiceManager.State.STOPPED || s.state == ServiceManager.State.FAILED) {
                                Button(onClick = { vm.startService(s.def) }) { Text("Start") }
                            } else {
                                OutlinedButton(onClick = { vm.stopService(s.def) }) { Text("Stop") }
                            }
                            if (s.recentOutput.isNotEmpty()) {
                                TextButton(onClick = { logsFor = s.def.id }) { Text("Logs") }
                            }
                        }
                    }
                }
            }
        }
    }

    logsFor?.let { id ->
        val s = state.services.firstOrNull { it.def.id == id }
        AlertDialogOnLogs(s) { logsFor = null }
    }
}

@Composable
private fun AlertDialogOnLogs(s: ServiceManager.ServiceRuntime?, onDismiss: () -> Unit) {
    if (s == null) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Logs — ${s.def.name}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .clip(MaterialTheme.shapes.small)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (s.recentOutput.isEmpty()) {
                    Text("No output yet.", style = AppType.MonoSmall)
                }
                s.recentOutput.forEach { line ->
                    Text(line, style = AppType.MonoSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
