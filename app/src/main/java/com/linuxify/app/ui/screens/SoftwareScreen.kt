package com.linuxify.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxify.app.pkg.AptManager
import com.linuxify.app.pkg.Catalog
import com.linuxify.app.pkg.CatalogEntry
import com.linuxify.app.pkg.Ecosystems
import com.linuxify.app.pkg.EnvManager
import com.linuxify.app.pkg.SoftwareManager
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.EmptyState
import com.linuxify.app.ui.components.Status
import com.linuxify.app.ui.components.StatusChip
import com.linuxify.app.ui.theme.AppType

/**
 * Universal Software Management hub.
 *
 * The catalog is data on top of shared ecosystem adapters (APT, npm, pip,
 * checksum-pinned static binaries); user-defined tools use the same core.
 * The terminal stays unrestricted — this screen simplifies installation,
 * it never replaces Linux.
 */
@Composable
fun SoftwareScreen(vm: AppViewModel) {
    val op by vm.software.op.collectAsStateWithLifecycle()
    val aptOp by vm.apt.op.collectAsStateWithLifecycle()
    val envState by vm.env.state.collectAsStateWithLifecycle()
    val customTools by vm.software.customTools.collectAsStateWithLifecycle()
    val installedState by vm.software.installedState.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var showAddDialog by remember { mutableStateOf(false) }
    var aptQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AptManager.SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    val allEntries = remember(customTools) { Catalog.ENTRIES + customTools }
    val filtered = remember(query, category, customTools) { Catalog.search(allEntries, query, category) }
    val aptRunning = aptOp?.state == AptManager.Operation.State.RUNNING
    val busy = (op?.busy == true) || aptRunning || envState.busy

    // Issue #16: probe the STABLE entry set — keying on `filtered` spawned one
    // proot process per search keystroke (and raced overlapping probes). The
    // result map is keyed by binary name, so a superset probe serves every
    // filtered view.
    LaunchedEffect(allEntries) { vm.refreshCatalogState(allEntries) }
    LaunchedEffect(op?.phase) {
        if (op?.phase == SoftwareManager.Phase.DONE || op?.phase == SoftwareManager.Phase.FAILED) {
            vm.refreshCatalogState(allEntries)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Software", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Install anything through the shared core: APT, npm, pip, or checksum-pinned static binaries — with automatic package repair, verification and honest failure reporting. Anything not listed here works in the terminal; the terminal is always unrestricted.",
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
                        enabled = !busy,
                    ) { Text("Repair package manager") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.checkEnvironment() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Check environment") }
                    OutlinedButton(
                        onClick = { vm.upgradePackages() },
                        enabled = !busy && audit != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Update & upgrade") }
                }

                if (envState.steps.isNotEmpty() || envState.log.isNotEmpty()) {
                    EnvLogBox(envState.steps, envState.log.takeLast(30))
                }
            }
        }

        // ---- APT operation (issue #15: upgrade / search-install / repair
        //      feedback is visible here — the old screen never rendered it) ----
        aptOp?.let { a ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(aptKindLabel(a.kind), style = MaterialTheme.typography.titleSmall)
                        StatusChip(
                            when (a.state) {
                                AptManager.Operation.State.RUNNING -> Status.BUSY
                                AptManager.Operation.State.DONE -> Status.READY
                                AptManager.Operation.State.FAILED -> Status.FAILED
                                else -> Status.STOPPED
                            },
                        )
                    }
                    if (a.state == AptManager.Operation.State.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (a.state == AptManager.Operation.State.FAILED && a.exitCode != null) {
                        Text(
                            "failed (exit ${a.exitCode}) — full log below",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (a.output.isNotEmpty()) {
                        EnvLogBox(emptyList(), a.output.takeLast(40))
                    }
                }
            }
        }

        // ---- Current software operation ----
        op?.let { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            o.name ?: "operation",
                            style = AppType.MonoBody,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        o.ecosystem?.let { eco ->
                            Text(
                                eco,
                                style = AppType.MonoSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusChip(
                            when {
                                o.busy -> Status.BUSY
                                o.phase == SoftwareManager.Phase.DONE -> Status.READY
                                o.phase == SoftwareManager.Phase.FAILED -> Status.FAILED
                                else -> Status.STOPPED
                            },
                        )
                    }
                    if (o.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    o.failureHint?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (o.verified.isNotEmpty()) {
                        Text(
                            "Verification: " + o.verified.joinToString("  ") { v ->
                                "${v.binary} ${if (v.ok) "\u2713" else "\u2717"}${v.version?.let { s -> " ($s)" } ?: ""}"
                            },
                            style = AppType.MonoSmall,
                            color = if (o.verified.all { it.ok }) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (o.log.isNotEmpty()) {
                        EnvLogBox(emptyList(), o.log.takeLast(40))
                    }
                }
            }
        }

        // ---- Catalog ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Catalog", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search tools") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Catalog.CATEGORIES.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                if (filtered.isEmpty()) {
                    EmptyState(
                        "Nothing here",
                        "No catalog entry matches. Anything you want can still be installed in the terminal — or add it below as a custom tool.",
                    )
                }

                filtered.take(60).forEach { entry ->
                    CatalogRow(
                        entry = entry,
                        installed = installedState[entry.verifyBinary],
                        busy = busy,
                        onInstall = { vm.installCatalogEntry(entry) },
                        onRemove = { vm.removeCatalogEntry(entry) },
                        onDeleteDefinition = { vm.removeCustomTool(entry.id) },
                    )
                }

                OutlinedButton(
                    onClick = { showAddDialog = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add custom tool (apt / npm / pip)") }
            }
        }

        // ---- Advanced: unrestricted APT ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("All APT packages", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The full Ubuntu archive — search and install anything the catalog does not list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = aptQuery,
                        onValueChange = { aptQuery = it },
                        label = { Text("Search APT") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(
                        onClick = { vm.searchPackages(aptQuery) { r -> results = r; searched = true } },
                        enabled = aptQuery.isNotBlank() && !busy,
                    ) { Text("Search") }
                }
                if (searched && results.isEmpty()) {
                    EmptyState("No results", "Try a different search term, or run Update & upgrade first.")
                }
                results.forEach { hit ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(hit.name, style = AppType.MonoBody)
                            Text(
                                hit.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.installPackages(hit.name) {} }, enabled = !busy) {
                            Text("Install")
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddToolDialog(
            vm = vm,
            busy = busy,
            onClose = { showAddDialog = false },
        )
    }
}

private fun aptKindLabel(kind: AptManager.Operation.Kind): String = when (kind) {
    AptManager.Operation.Kind.UPDATE -> "Index refresh (apt update)"
    AptManager.Operation.Kind.INSTALL -> "APT install"
    AptManager.Operation.Kind.REMOVE -> "APT remove"
    AptManager.Operation.Kind.SEARCH -> "APT search"
    AptManager.Operation.Kind.LIST_INSTALLED -> "Installed packages"
    AptManager.Operation.Kind.REPAIR -> "Package repair"
    AptManager.Operation.Kind.UPGRADE -> "Update \u0026 upgrade (apt upgrade)"
}

@Composable
private fun EnvLogBox(steps: List<EnvManager.StepRecord>, log: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        steps.forEach { s ->
            Text(
                (if (s.ok) "\u2713 " else "\u2717 ") + s.title + (s.note?.let { " ($it)" } ?: ""),
                style = AppType.MonoSmall,
                color = if (s.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        log.forEach { line ->
            Text(line, style = AppType.MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean?,
    busy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onDeleteDefinition: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = AppType.MonoBody)
            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildString {
                    append(entry.category)
                    append(" \u00b7 ")
                    append(Ecosystems.labelOf(entry.specs.first()))
                    append(" \u00b7 ")
                    append(
                        when (installed) {
                            true -> "installed"
                            false -> "not installed"
                            null -> "state unknown"
                        },
                    )
                    if (entry.custom) append(" \u00b7 custom")
                },
                style = AppType.MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (installed == true) {
                OutlinedButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
            } else {
                Button(onClick = onInstall, enabled = !busy) { Text("Install") }
            }
            if (entry.custom && installed != true) {
                TextButton(onClick = onDeleteDefinition, enabled = !busy) { Text("Delete entry") }
            }
        }
    }
}

@Composable
private fun AddToolDialog(vm: AppViewModel, busy: Boolean, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var binary by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var eco by remember { mutableStateOf(Ecosystems.APT) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add custom tool") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Ecosystems.APT, Ecosystems.NPM, Ecosystems.PIP).forEach { e ->
                        FilterChip(
                            selected = eco == e,
                            onClick = { eco = e },
                            label = { Text(e, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = { Text(if (eco == Ecosystems.APT) "APT package" else if (eco == Ecosystems.NPM) "npm package" else "pip package") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = binary,
                    onValueChange = { binary = it },
                    label = { Text("Command it provides (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && name.isNotBlank() && pkg.isNotBlank(),
                onClick = {
                    vm.addCustomTool(name, eco, pkg, binary.ifBlank { null }, description.ifBlank { null }) { entry ->
                        if (entry == null) {
                            error = vm.software.op.value?.failureHint ?: "Could not add the tool."
                        } else {
                            onClose()
                        }
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
