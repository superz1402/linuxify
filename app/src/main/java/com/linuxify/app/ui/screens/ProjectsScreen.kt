package com.linuxify.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.EmptyState
import com.linuxify.app.ui.theme.AppType

/** Project manager: real directories, real git state (branch + dirty count), real clone output. */
@Composable
fun ProjectsScreen(
    vm: AppViewModel,
    onOpenTerminalIn: (String) -> Unit,
    onBrowseFiles: () -> Unit,
) {
    val state by vm.projectsState.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshProjects() }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Projects",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).padding(top = 8.dp),
            )
            OutlinedButton(onClick = { showClone = true }) { Text("Clone") }
            Button(onClick = { showCreate = true }) { Text("New") }
        }

        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (state.loading && state.projects.isEmpty()) {
            Text(
                "Loading projects…",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (state.projects.isEmpty()) {
            EmptyState(
                title = "No projects yet",
                body = "Create an empty project or clone a Git repository. Projects are stored in app-private storage and bound into the Linux environment at /root/projects.",
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(state.projects, key = { it.name }) { p ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    p.gitBranch?.let { "branch: $it" },
                                    p.dirtyCount?.takeIf { it > 0 }?.let { "$it changed" },
                                    if (p.gitBranch == null) "not a git repo" else null,
                                ).joinToString(" · "),
                                style = AppType.MonoSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "/root/projects/${p.name}",
                                style = AppType.MonoSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onOpenTerminalIn(p.name) }) { Text("Open terminal") }
                                TextButton(onClick = onBrowseFiles) { Text("Browse files") }
                            }
                        }
                    }
                }
            }
        }

        if (state.busy) {
            Text(
                "Cloning — real git output:",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
            ) {
                state.log.takeLast(12).forEach {
                    Text(it, style = AppType.MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New project") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    supportingText = { Text(vm.projects.validateName(name) ?: "Letters, digits, dot, dash, underscore") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.createProject(name)
                    showCreate = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancel") }
            },
        )
    }

    if (showClone) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showClone = false },
            title = { Text("Clone repository") },
            text = {
                Column {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("HTTPS Git URL") },
                    )
                    Text(
                        "Cloned with real git inside the Linux environment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.cloneProject(url)
                    showClone = false
                }) { Text("Clone") }
            },
            dismissButton = {
                TextButton(onClick = { showClone = false }) { Text("Cancel") }
            },
        )
    }
}
