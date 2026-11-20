package cn.crid.next.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat

internal val LocalButtonHapticsEnabled = staticCompositionLocalOf { false }

/** Keep feedback on the action so disabled controls and cancelled taps stay quiet. */
@Composable
internal fun hapticClick(action: () -> Unit): () -> Unit {
    val feedback = rememberButtonFeedback() ?: return action
    return remember(feedback, action) { { feedback(); action() } }
}

@Composable
internal fun hapticToggle(action: (Boolean) -> Unit): (Boolean) -> Unit {
    val feedback = rememberButtonFeedback() ?: return action
    return remember(feedback, action) { { checked -> feedback(); action(checked) } }
}

@Composable
private fun rememberButtonFeedback(): (() -> Unit)? {
    if (!LocalButtonHapticsEnabled.current) return null
    val view = LocalView.current
    return remember(view) { { ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.VIRTUAL_KEY) } }
}
