package com.linuxify.app.ui.theme

import androidx.compose.ui.graphics.Color

// Dark (primary workspace theme) — restrained technical palette
val DarkBackground = Color(0xFF0E1116)
val DarkSurface = Color(0xFF151A21)
val DarkSurfaceHigh = Color(0xFF1C232C)
val DarkSurfaceHighest = Color(0xFF242D38)
val DarkOnBackground = Color(0xFFE3E7EC)
val DarkOnSurface = Color(0xFFD6DCE3)
val DarkOnSurfaceDim = Color(0xFF8A93A6)
val DarkOutline = Color(0xFF303945)
val DarkPrimary = Color(0xFF5CC8D7)      // terminal cyan — restrained accent
val DarkOnPrimary = Color(0xFF06222A)
val DarkPrimaryContainer = Color(0xFF10393F)
val DarkOnPrimaryContainer = Color(0xFFB8ECF4)

// Light theme
val LightBackground = Color(0xFFFAFBFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceHigh = Color(0xFFF0F2F5)
val LightOnBackground = Color(0xFF1A1D22)
val LightOnSurface = Color(0xFF232830)
val LightOnSurfaceDim = Color(0xFF5C6672)
val LightOutline = Color(0xFFD4D9E0)
val LightPrimary = Color(0xFF0E7C8C)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD3F1F6)
val LightOnPrimaryContainer = Color(0xFF06363E)

// Semantic status — fixed hues, consistent across themes (never color-only: always paired with label)
val StatusSuccessDark = Color(0xFF4ADE80)
val StatusSuccessLight = Color(0xFF15803D)
val StatusWarningDark = Color(0xFFFBBF24)
val StatusWarningLight = Color(0xFFB45309)
val StatusErrorDark = Color(0xFFF87171)
val StatusErrorLight = Color(0xFFB91C1C)
val StatusInfoDark = Color(0xFF60A5FA)
val StatusInfoLight = Color(0xFF1D4ED8)
val StatusNeutralDark = Color(0xFF8A93A6)
val StatusNeutralLight = Color(0xFF5C6672)

// Terminal screen background. The foreground palette lives in the terminal
// emulator's color scheme (vendored TerminalColorScheme), not here — the
// TerminalView renders its own glyphs.
val TerminalBackground = Color(0xFF0A0D11)
