package com.linuxify.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxify.app.pkg.AptManager
import com.linuxify.app.pkg.EnvCore
import com.linuxify.app.pkg.EnvManager
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.EmptyState
import com.linuxify.app.ui.components.Status
import com.linuxify.app.ui.components.StatusChip
import com.linuxify.app.ui.theme.AppType

/**
 * Real APT package management inside the Linux userspace. Live real output only;
 * a shared lock prevents concurrent conflicting apt operations.
 *
 * Top section: the Dependency & Package Recovery Manager — environment audit
 * (distro/arch/storage/network/package health), repair-only-when-inconsistent,
 * BASE/DEVELOPER tool profiles, and per-tool install verification.
 */
@Composable
fun PackagesScreen(vm: AppViewModel) {
    val op by vm.apt.op.collectAsStateWithLifecycle()
    val envState by vm.env.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AptManager.SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val aptRunning = op?.state == AptManager.Operation.State.RUNNING

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Packages", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Installs and removes run the real APT inside the Linux environment. Only one package operation runs at a time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- Environment (Dependency & Package Recovery Manager) ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Environment", style = MaterialTheme.typography.titleMedium)
                    StatusChip(
                        when {
                            envState.busy -> Status.BUSY
                            envState.phase == EnvManager.Phase.FAILED -> Status.FAILED
                            envState.audit?.healthy == true -> Status.READY
                            else -> Status.STOPPED
                        },
                    )
                }

                val audit = envState.audit
                if (audit == null) {
                    Text(
                        "Check audits the environment: Ubuntu version, architecture, free storage, network, package-manager health and installed tools.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        buildString {
                            append(audit.distro ?: "unknown distro")
                            if (audit.arch != null) append(" \u00b7 ").append(audit.arch)
                            if (audit.freeStorageMb != null) append(" \u00b7 ").append(audit.freeStorageMb).append(" MB free")
                            append(" \u00b7 network ").append(if (audit.networkOk) "ok" else "unavailable")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (audit != null && !audit.healthy) {
                    Text(
                        envState.failureHint
                            ?: "Package manager is inconsistent — repair it before installing anything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { vm.repairPackageManager() },
                        enabled = !envState.busy && !aptRunning,
                    ) { Text("Repair package manager") }
                }

                EnvCore.PROFILES.forEach { profile ->
                    val missing = audit?.let { EnvCore.packagesFor(profile, it.installedBinaries) } ?: emptyList()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(profile.label, style = AppType.MonoBody)
                            Text(
                                profile.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                when {
                                    audit == null -> "tools not probed yet"
                                    missing.isEmpty() -> "all tools installed"
                                    else -> "missing: ${missing.joinToString(" ")}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (audit == null) MaterialTheme.colorScheme.onSurfaceVariant
                                else if (missing.isEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(
                            onClick = { vm.installEnvProfile(profile.id) },
                            enabled = !envState.busy && !aptRunning,
                        ) {
                            Text(if (missing.isEmpty()) "Reinstall" else "Install")
                        }
                    }
                }

                if (envState.verified.isNotEmpty()) {
                    Text(
                        "Verification: " + envState.verified.joinToString("  ") { v ->
                            "${v.binary} ${if (v.ok) "\u2713" else "\u2717"}${v.version?.let { " ($it)" } ?: ""}"
                        },
                        style = AppType.MonoSmall,
                        color = if (envState.verified.all { it.ok }) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }

                if (envState.phase == EnvManager.Phase.FAILED && envState.failureHint != null && audit?.healthy != false) {
                    Text(
                        envState.failureHint.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedButton(
                    onClick = { vm.checkEnvironment() },
                    enabled = !envState.busy && !aptRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Check environment") }

                if (envState.steps.isNotEmpty() || envState.log.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        envState.steps.forEach { s ->
                            Text(
                                (if (s.ok) "\u2713 " else "\u2717 ") + s.title + (s.note?.let { " ($it)" } ?: ""),
                                style = AppType.MonoSmall,
                                color = if (s.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                        envState.log.takeLast(40).forEach { line ->
                            Text(line, style = AppType.MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search packages") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                onClick = {
                    vm.searchPackages(query) { results = it; searched = true }
                },
                enabled = query.isNotBlank() && op?.state != AptManager.Operation.State.RUNNING,
            ) { Text("Search") }
        }

        op?.let { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (o.kind) {
                                AptManager.Operation.Kind.UPDATE -> "apt-get update"
                                AptManager.Operation.Kind.INSTALL -> "apt-get install"
                                AptManager.Operation.Kind.REMOVE -> "apt-get remove"
                                AptManager.Operation.Kind.SEARCH -> "apt-cache search"
                                AptManager.Operation.Kind.LIST_INSTALLED -> "list installed"
                                AptManager.Operation.Kind.REPAIR -> "package repair"
                            },
                            style = AppType.MonoBody,
                        )
                        when (o.state) {
                            AptManager.Operation.State.RUNNING -> StatusChip(Status.BUSY)
                            AptManager.Operation.State.DONE -> StatusChip(Status.READY)
                            AptManager.Operation.State.FAILED -> StatusChip(Status.FAILED)
                            AptManager.Operation.State.IDLE -> StatusChip(Status.STOPPED)
                        }
                    }
                    if (o.state == AptManager.Operation.State.RUNNING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    o.exitCode?.let {
                        Text(
                            if (o.state == AptManager.Operation.State.DONE) "exit 0" else "exit $it",
                            style = AppType.MonoSmall,
                            color = if (o.state == AptManager.Operation.State.DONE) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (o.output.isNotEmpty()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            o.output.takeLast(60).forEach { line ->
                                Text(line, style = AppType.MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (searched && results.isEmpty()) {
            EmptyState("No results", "Try a different search term, or run apt-get update first.")
        }

        results.forEach { hit ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(hit.name, style = AppType.MonoBody)
                    Text(
                        hit.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val running = op?.state == AptManager.Operation.State.RUNNING
                TextButton(
                    onClick = { vm.installPackages(hit.name) {} },
                    enabled = !running,
                ) { Text("Install") }
            }
        }

        OutlinedButton(
            onClick = { vm.refreshPackageIndex() },
            enabled = op?.state != AptManager.Operation.State.RUNNING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Update package index (apt-get update)") }
    }
}
