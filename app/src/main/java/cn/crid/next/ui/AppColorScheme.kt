package cn.crid.next.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Shared SDR base for application windows and native configuration activities. */
fun cridColorScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFFC8BEFF), onPrimary = Color(0xFF30245E),
        primaryContainer = Color(0xFF493B78), secondary = Color(0xFFB5C5FF),
        background = Color(0xFF121219), surface = Color(0xFF121219),
        surfaceContainerLow = Color(0xFF1C1B25), surfaceContainer = Color(0xFF23222E),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF6650AA), onPrimary = Color.White,
        primaryContainer = Color(0xFFE9E0FF), onPrimaryContainer = Color(0xFF2A194D),
        secondary = Color(0xFF526AB3), background = Color(0xFFFAF9FE),
        surface = Color(0xFFFAF9FE), surfaceContainerLow = Color(0xFFF0EEF8),
        surfaceContainer = Color(0xFFEAE7F3),
    )
}
