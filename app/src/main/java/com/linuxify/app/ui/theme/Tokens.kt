package com.linuxify.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens (from the maintainer's UI research — Your-Files-Redesign /
 * tabit-android pattern): semantic aliases, never arbitrary dp in screens.
 */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    // component sizing
    val touchTarget: Dp = 48.dp
    val extraKeyHeight: Dp = 36.dp
}

/** Motion durations (tabit-android convention: 120/250/350/500ms). */
object Motion {
    const val FAST_MS = 120
    const val NORMAL_MS = 250
    const val SLOW_MS = 350
}
