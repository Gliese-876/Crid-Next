package cn.crid.next.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Native popup/menu styling, with an interruptible transition and a completed-close callback. */
@Composable
internal fun AnimatedOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    // Keep the transition outside Popup so reversing an unfinished entrance cannot remove it.
    val transition = rememberTransition(state, label = "overflow_menu")
    val progress by transition.animateFloat(
        transitionSpec = {
            if (targetState) spring(dampingRatio = .9f, stiffness = 700f)
            else spring(dampingRatio = 1f, stiffness = 1000f)
        },
        label = "overflow_menu_progress",
    ) { if (it) 1f else 0f }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestClosed by rememberUpdatedState(onClosed)
    var closePending by remember { mutableStateOf(false) }
    SideEffect { if (expanded) closePending = true }
    LaunchedEffect(expanded, state.isIdle, state.currentState, closePending) {
        if (closePending && !expanded && state.isIdle && !state.currentState) {
            closePending = false
            latestClosed()
        }
    }

    val margin = 8.dp
    val marginPixels = with(LocalDensity.current) { margin.roundToPx() }
    val origin = remember { mutableStateOf(TransformOrigin.Center) }
    val positionProvider = remember(marginPixels) {
        OverflowMenuPositionProvider(marginPixels) { origin.value = it }
    }
    val scrollState = rememberScrollState()
    if (expanded || state.currentState || state.targetState || !state.isIdle) {
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = { if (expanded) latestDismiss() },
            properties = PopupProperties(focusable = true),
        ) {
            // Popup supplies the current window's available constraints, including small windows.
            // The menu keeps its native item sizing and scrolls instead of extending off screen.
            BoxWithConstraints {
                Surface(
                    modifier = modifier
                        .widthIn(max = (maxWidth - margin * 2).coerceAtLeast(1.dp))
                        .heightIn(max = (maxHeight - margin * 2).coerceAtLeast(1.dp))
                        .graphicsLayer {
                            val scale = .88f + .12f * progress
                            scaleX = scale
                            scaleY = scale
                            // Become a solid surface early and keep that surface until the final
                            // part of its return, with no separate fade animation or delayed tail.
                            alpha = (progress / .35f).coerceIn(0f, 1f)
                            transformOrigin = origin.value
                        },
                    shape = MenuDefaults.shape,
                    color = MenuDefaults.containerColor,
                    tonalElevation = MenuDefaults.TonalElevation,
                    shadowElevation = MenuDefaults.ShadowElevation,
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp)
                            .width(IntrinsicSize.Max)
                            .verticalScroll(scrollState),
                        content = content,
                    )
                }
            }
        }
    }
}

/** Prefer the anchor's lower trailing corner, then the space above, and stay inside the window. */
internal class OverflowMenuPositionProvider(
    private val margin: Int,
    private val onOrigin: (TransformOrigin) -> Unit = {},
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        fun fit(value: Int, size: Int, window: Int): Int {
            val edge = margin.coerceIn(0, ((window - size) / 2).coerceAtLeast(0))
            return value.coerceIn(edge, (window - size - edge).coerceAtLeast(edge))
        }
        val desiredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val below = anchorBounds.bottom
        val above = anchorBounds.top - popupContentSize.height
        val desiredY = when {
            below + popupContentSize.height <= windowSize.height - margin -> below
            above >= margin -> above
            windowSize.height - below >= anchorBounds.top -> below
            else -> above
        }
        val x = fit(desiredX, popupContentSize.width, windowSize.width)
        val y = fit(desiredY, popupContentSize.height, windowSize.height)
        // The trigger can sit just outside the popup. Keep its actual center as the pivot so
        // both edges move toward that control instead of shrinking around a detached menu edge.
        val pivotX = (anchorBounds.center.x - x).toFloat() / popupContentSize.width.coerceAtLeast(1)
        val pivotY = (anchorBounds.center.y - y).toFloat() / popupContentSize.height.coerceAtLeast(1)
        onOrigin(TransformOrigin(pivotX, pivotY))
        return IntOffset(x, y)
    }
}
