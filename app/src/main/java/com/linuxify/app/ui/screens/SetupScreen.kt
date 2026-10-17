package com.linuxify.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxify.app.runtime.LinuxRuntime
import com.linuxify.app.runtime.RuntimeDirs
import com.linuxify.app.runtime.RuntimeMetadata
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.theme.AppType

/**
 * First-run setup + installer. One screen, three real states:
 *  1) intro with concrete facts (what/where/size/limits)
 *  2) staged progress driven by the installer state machine (real bytes)
 *  3) failure with typed user message + retry / expandable technical log
 */
@Composable
fun SetupScreen(vm: AppViewModel, onInstalled: () -> Unit) {
    val state by vm.installer.state.collectAsStateWithLifecycle()
    val log by vm.installer.log.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (val s = state) {
            is com.linuxify.app.runtime.RootfsInstaller.InstallState.Ready -> {
                // Brief transition state; nav effect handles movement.
                onInstalled()
            }
            is com.linuxify.app.runtime.RootfsInstaller.InstallState.Failed -> FailedView(s, vm, log)
            is com.linuxify.app.runtime.RootfsInstaller.InstallState.Downloading,
            is com.linuxify.app.runtime.RootfsInstaller.InstallState.Extracting,
            com.linuxify.app.runtime.RootfsInstaller.InstallState.Checking,
            com.linuxify.app.runtime.RootfsInstaller.InstallState.Verifying,
            com.linuxify.app.runtime.RootfsInstaller.InstallState.Configuring,
            com.linuxify.app.runtime.RootfsInstaller.InstallState.VerifyingInstall,
            -> ProgressView(s, vm, log)
            com.linuxify.app.runtime.RootfsInstaller.InstallState.NotInstalled -> IntroView(vm)
        }
    }
}

@Composable
private fun IntroView(vm: AppViewModel) {
    val free = RuntimeDirs.availableBytes(vm.getApplication<android.app.Application>())
    Spacer(Modifier.height(24.dp))
    Text(
        "Linuxify",
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        "A local Linux environment on this device",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Fact(
            "What gets installed",
            "${RuntimeMetadata.DISTRO_NAME} ${RuntimeMetadata.DISTRO_VERSION} userspace running via proot — real Linux commands, real packages.",
        )
        Fact(
            "Where it lives",
            "App-private storage (only Linuxify can access it). User files survive app restarts and reboots.",
        )
        Fact(
            "Download size",
            "≈${LinuxRuntime.humanSize(RuntimeMetadata.ROOTFS_APPROX_DOWNLOAD_BYTES)} now; the environment grows as you install packages (base ≈${LinuxRuntime.humanSize(RuntimeMetadata.ROOTFS_APPROX_EXTRACTED_BYTES)}).",
        )
        Fact(
            "Available on device",
            "${LinuxRuntime.humanSize(free)} free",
        )
        Fact(
            "Requires",
            "Internet once for the download. No root, no Docker, no Termux, no account.",
        )
        Fact(
            "Honest limitations",
            "No kernel-level isolation; Android may stop background processes; servers are device-local by default.",
        )
    }

    Button(
        onClick = { vm.startInstall() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Install Linux environment")
    }
}

@Composable
private fun Fact(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProgressView(
    state: com.linuxify.app.runtime.RootfsInstaller.InstallState,
    vm: AppViewModel,
    log: List<String>,
) {
    var showLog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Setting up your Linux environment",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        val (label, fraction) = when (val s = state) {
            is com.linuxify.app.runtime.RootfsInstaller.InstallState.Downloading ->
                "Downloading ${LinuxRuntime.humanSize(s.bytesDone)}" +
                    (s.bytesTotal.takeIf { it > 0 }?.let { " of ${LinuxRuntime.humanSize(it)}" } ?: "") to
                    (s.bytesTotal.takeIf { it > 0 }?.let { s.bytesDone.toFloat() / it })
            is com.linuxify.app.runtime.RootfsInstaller.InstallState.Extracting ->
                "Extracting ${LinuxRuntime.humanSize(s.bytesDone)}…" to null
            com.linuxify.app.runtime.RootfsInstaller.InstallState.Checking -> "Checking device…" to null
            com.linuxify.app.runtime.RootfsInstaller.InstallState.Verifying -> "Verifying checksums…" to null
            com.linuxify.app.runtime.RootfsInstaller.InstallState.Configuring -> "Configuring Linux…" to null
            com.linuxify.app.runtime.RootfsInstaller.InstallState.VerifyingInstall -> "Verifying installation…" to null
            else -> "Working…" to null
        }

        Text(label, style = AppType.MonoBody)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        TextButton(onClick = { showLog = !showLog }) {
            Text(if (showLog) "Hide technical log" else "Show technical log")
        }
        if (showLog) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .animateContentSize(),
            ) {
                log.forEach { line ->
                    Text(line, style = AppType.MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        OutlinedButton(onClick = { vm.installer.cancel() }, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun FailedView(
    s: com.linuxify.app.runtime.RootfsInstaller.InstallState.Failed,
    vm: AppViewModel,
    log: List<String>,
) {
    var showLog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Installation failed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
        Text(s.userMessage, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { showLog = !showLog }) {
            Text(if (showLog) "Hide technical log" else "Show technical log")
        }
        if (showLog) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                log.forEach { line ->
                    Text(line, style = AppType.MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.startInstall() }, modifier = Modifier.weight(1f)) {
                Text("Retry")
            }
        }
    }
    Box {}
}
