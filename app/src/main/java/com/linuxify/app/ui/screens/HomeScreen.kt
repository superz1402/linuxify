package com.linuxify.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxify.app.runtime.CrashReportActivity
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.FactRow
import com.linuxify.app.ui.components.Status
import com.linuxify.app.ui.components.StatusChip

/**
 * Home dashboard. Answers: installed? ready? what can I do next? — using only
 * real runtime data. No decorative charts, no fake activity (master prompt §11.7).
 */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenTerminal: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenPackages: () -> Unit,
) {
    val facts by vm.homeFacts.collectAsStateWithLifecycle()
    val warning by vm.storageWarning.collectAsStateWithLifecycle()
    val services by vm.servicesState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Diagnostic loop-closure: a crash report persisted by the last crash stays
    // reachable after the report screen is gone. If any screen ever crashes
    // again, the exact stack is one tap away here — no adb, no friction.
    var showCrashCard by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showCrashCard = vm.hasCrashReport() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Linuxify",
            style = MaterialTheme.typography.headlineMedium,
        )

        warning?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Storage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        val f = facts
        if (f == null) {
            com.linuxify.app.ui.components.LoadingState("Reading environment status…")
            return@Column
        }

        if (!f.installed) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Linux is not installed yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Install the local Linux environment to start using the terminal, packages, and servers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { vm.startInstall() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Install Linux environment")
                    }
                }
            }
            return@Column
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${f.distro} ${f.version}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    StatusChip(Status.READY)
                }
                FactRow("Architecture", f.abi)
                FactRow("Runtime binaries", if (f.prootOk && f.loaderOk) "ok (proot + ELF loader)" else "missing — reinstall required")
                FactRow("Environment size", LinuxRuntime.humanSize(f.rootfsSizeBytes))
                FactRow("Packages installed", f.installedPackages?.toString() ?: "—")
                FactRow("Free storage", LinuxRuntime.humanSize(f.freeBytes))
                FactRow("Device", f.deviceModel)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onOpenTerminal, modifier = Modifier.weight(1f)) {
                Text("Terminal")
            }
            OutlinedButton(onClick = onOpenProjects, modifier = Modifier.weight(1f)) {
                Text("Projects (${f.projectsCount})")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onOpenPackages, modifier = Modifier.weight(1f)) {
                Text("Packages")
            }
            OutlinedButton(onClick = onOpenServices, modifier = Modifier.weight(1f)) {
                Text("Services")
            }
        }

        val runningServices = services.services.count { it.state == com.linuxify.app.srv.ServiceManager.State.RUNNING || it.state == com.linuxify.app.srv.ServiceManager.State.STARTING }
        if (runningServices > 0) {
            Card(modifier = Modifier.fillMaxWidth(), onClick = onOpenServices) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "$runningServices local service${if (runningServices == 1) "" else "s"} active",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Tap to manage servers and processes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (showCrashCard) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Diagnostics",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "A crash report was saved the last time the app hit an unexpected error.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            context.startActivity(Intent(context, CrashReportActivity::class.java))
                        }) { Text("View report") }
                        TextButton(onClick = {
                            showCrashCard = false
                            vm.dismissCrashReport()
                        }) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}
