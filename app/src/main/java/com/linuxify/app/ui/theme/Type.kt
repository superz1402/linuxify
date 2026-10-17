package com.linuxify.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography: platform sans for UI, monospace for technical values
 * (paths, sizes, PIDs, ports, statuses) — a deliberate developer-tool identity.
 */
object AppType {
    val Mono = FontFamily.Monospace

    // mono styles used across runtime-facing UI
    val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)
    val MonoBody = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp)
    val MonoLabel = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
}

val LinuxifyTypography = Typography()
