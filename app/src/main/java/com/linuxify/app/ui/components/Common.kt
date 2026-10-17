package com.linuxify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linuxify.app.ui.theme.AppType

/** Semantic status used across runtime UI. Never color-only: dot + label. */
enum class Status(val label: String) {
    READY("Ready"),
    RUNNING("Running"),
    STARTING("Starting"),
    STOPPED("Stopped"),
    FAILED("Failed"),
    BUSY("Busy"),
    INFO("Info"),
}

@Composable
fun statusColor(status: Status, dark: Boolean): Color = when (status) {
    Status.READY, Status.RUNNING -> if (dark) com.linuxify.app.ui.theme.StatusSuccessDark else com.linuxify.app.ui.theme.StatusSuccessLight
    Status.STARTING, Status.BUSY -> if (dark) com.linuxify.app.ui.theme.StatusWarningDark else com.linuxify.app.ui.theme.StatusWarningLight
    Status.FAILED -> if (dark) com.linuxify.app.ui.theme.StatusErrorDark else com.linuxify.app.ui.theme.StatusErrorLight
    Status.STOPPED -> if (dark) com.linuxify.app.ui.theme.StatusNeutralDark else com.linuxify.app.ui.theme.StatusNeutralLight
    Status.INFO -> if (dark) com.linuxify.app.ui.theme.StatusInfoDark else com.linuxify.app.ui.theme.StatusInfoLight
}

@Composable
fun StatusChip(status: Status, modifier: Modifier = Modifier) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val color = statusColor(status, dark)
    Row(
        modifier = modifier.semantics { contentDescription = "Status: ${status.label}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            status.label,
            style = AppType.MonoLabel,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Compact key/value row used for runtime facts (distro, arch, storage…). */
@Composable
fun FactRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = AppType.MonoBody,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            androidx.compose.material3.OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
