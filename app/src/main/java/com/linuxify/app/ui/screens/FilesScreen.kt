package com.linuxify.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.components.EmptyState
import com.linuxify.app.ui.theme.AppType
import java.io.File

/**
 * Linux-aware file browser over the rootfs home directory (/root).
 * Direct filesystem access (app-private storage) — no shell required for browsing.
 */
@Composable
fun FilesScreen(vm: AppViewModel) {
    var currentRel by remember { mutableStateOf("/root") }
    var showHidden by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var viewText by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val dirs = LinuxRuntime(vm.getApplication<android.app.Application>()).listDir(currentRel)
    val visible = dirs.filter { showHidden || !it.name.startsWith(".") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            if (currentRel != "/root") {
                IconButton(onClick = {
                    currentRel = currentRel.substringBeforeLast('/', "").ifEmpty { "/root" }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
            }
            Text(
                currentRel,
                style = AppType.MonoBody,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showHidden = !showHidden }) {
                Icon(
                    if (showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (showHidden) "Hide hidden files" else "Show hidden files",
                )
            }
            IconButton(onClick = { showMkdir = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (visible.isEmpty()) {
            EmptyState("Empty directory", "This directory has no visible entries.")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.absolutePath }) { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                when {
                                    f.isDirectory -> {
                                        error = null
                                        currentRel = currentRel.trimEnd('/') + "/" + f.name
                                    }
                                    f.isFile && f.length() < 512 * 1024 -> {
                                        viewText = try {
                                            f.readText().take(200_000)
                                        } catch (e: Exception) {
                                            "Cannot read file: ${e.message}"
                                        }
                                        viewing = f
                                    }
                                    else -> error = "File is not a directory and too large to preview."
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.name + if (f.isDirectory) "/" else "",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                if (f.isDirectory) "directory" else LinuxRuntime.humanSize(f.length()),
                                style = AppType.MonoSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { confirmDelete = f }) { Text("Delete") }
                    }
                }
            }
        }
    }

    viewing?.let { f ->
        AlertDialog(
            onDismissRequest = { viewing = null; viewText = null },
            title = { Text(f.name, style = AppType.MonoBody) },
            text = {
                LazyColumn {
                    items(viewText?.lines() ?: emptyList()) { line ->
                        Text(line, style = AppType.MonoSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewing = null; viewText = null }) { Text("Close") }
            },
        )
    }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${f.name}?") },
            text = {
                Text(
                    if (f.isDirectory) "This deletes the directory and everything inside it. This cannot be undone."
                    else "This permanently deletes the file. This cannot be undone.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.deleteTextFileSafely(currentRel.trimEnd('/') + "/" + f.name) { r ->
                        r.fold(
                            onSuccess = { },
                            onFailure = { error = it.message },
                        )
                    }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (showMkdir) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextFieldForMkdir(name) { name = it }
            },
            confirmButton = {
                Button(onClick = {
                    val safe = name.filter { it.isLetterOrDigit() || it in "._- " }.trim()
                    if (safe.isEmpty()) {
                        error = "Invalid folder name"
                    } else {
                        val f = File(currentRel, safe)
                        val rootfs = com.linuxify.app.runtime.RuntimeDirs.rootfsDir(
                            vm.getApplication<android.app.Application>(),
                        ).canonicalFile
                        if (!f.canonicalFile.path.startsWith(rootfs.path)) {
                            error = "Invalid path"
                        } else {
                            f.mkdirs()
                        }
                    }
                    showMkdir = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OutlinedTextFieldForMkdir(value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Folder name") },
    )
}
