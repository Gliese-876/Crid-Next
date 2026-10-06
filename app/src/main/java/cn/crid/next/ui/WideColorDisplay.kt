package cn.crid.next.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.WeakHashMap

internal data class WideColorCapabilities(
    val validDisplay: Boolean = false,
    val hardwareAccelerated: Boolean = false,
    val wideColorRendering: Boolean = false,
    val wideGamutDisplay: Boolean = false,
) {
    val supported: Boolean get() = validDisplay && hardwareAccelerated && wideColorRendering && wideGamutDisplay
}

internal data class WideColorDisplayState(
    val supported: Boolean = false,
    val foreground: Boolean = true,
    val displayId: Int? = null,
) {
    val active: Boolean get() = supported && foreground
}

internal val LocalWideColorDisplay = staticCompositionLocalOf { WideColorDisplayState() }
private val LocalColorWindowView = staticCompositionLocalOf<View?> { null }

@Composable
private fun rememberWideColorCapabilities(view: View): WideColorCapabilities {
    val owner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    var capability by remember { mutableStateOf(WideColorCapabilities()) }
    DisposableEffect(view, owner, configuration.colorMode) {
        val manager = view.context.getSystemService(DisplayManager::class.java)
        var observedDisplay: Display? = null
        var alive = true
        fun refresh() {
            if (!alive) return
            val display = view.display
            observedDisplay = display
            capability = try {
                WideColorCapabilities(display?.isValid == true, view.isHardwareAccelerated,
                    view.resources.configuration.isScreenWideColorGamut, display?.isWideColorGamut == true)
            } catch (_: RuntimeException) { WideColorCapabilities() }
        }
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refresh()
            override fun onDisplayRemoved(displayId: Int) = refresh()
            override fun onDisplayChanged(displayId: Int) = refresh()
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = refresh()
            override fun onViewDetachedFromWindow(v: View) { capability = WideColorCapabilities() }
        }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (observedDisplay?.displayId != view.display?.displayId || capability.hardwareAccelerated != view.isHardwareAccelerated) refresh()
        }
        val lifecycleListener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) refresh()
        }
        manager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        view.addOnAttachStateChangeListener(attachListener)
        view.addOnLayoutChangeListener(layoutListener)
        owner.lifecycle.addObserver(lifecycleListener)
        refresh()
        onDispose {
            alive = false
            manager.unregisterDisplayListener(displayListener)
            view.removeOnAttachStateChangeListener(attachListener)
            view.removeOnLayoutChangeListener(layoutListener)
            owner.lifecycle.removeObserver(lifecycleListener)
        }
    }
    return capability
}

/** Use the display's wider gamut automatically, while retaining ordinary interface brightness. */
@Composable
internal fun WideColorDisplayProvider(content: @Composable () -> Unit) {
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val capability = rememberWideColorCapabilities(view)
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) foreground = true
            if (event == Lifecycle.Event.ON_STOP) foreground = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val state = WideColorDisplayState(capability.supported, foreground, view.display?.displayId)
    MatchWideColorWindow(view.context.activityWindow(), state, observeWindowDisplay = false)
    CompositionLocalProvider(LocalWideColorDisplay provides state, LocalColorWindowView provides view, content = content)
}

/** A modal may be hosted on another display, so its brushes follow that window's capability. */
@Composable
internal fun WindowColorProvider(content: @Composable () -> Unit) {
    val inherited = LocalWideColorDisplay.current
    val view = LocalView.current
    val capability = rememberWideColorCapabilities(view)
    val local = inherited.copy(supported = capability.supported, displayId = view.display?.displayId)
    CompositionLocalProvider(LocalWideColorDisplay provides local, LocalColorWindowView provides view, content = content)
}

@Composable
internal fun currentWindowColorState(): WideColorDisplayState = LocalWideColorDisplay.current

private fun Context.activityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> if (baseContext !== this) baseContext.activityWindow() else null
    else -> null
}

/** Nested system-bar helpers share one window; the first lease saves its original color mode. */
internal class WideColorWindowLease(private val window: Window) {
    private class Record(val previousMode: Int, var references: Int = 0)
    companion object { private val records = WeakHashMap<Window, Record>() }
    private val record = records.getOrPut(window) { Record(window.colorMode) }.also { it.references++ }
    private var released = false
    fun apply(state: WideColorDisplayState) {
        if (released) return
        val mode = if (state.active) ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT else ActivityInfo.COLOR_MODE_DEFAULT
        if (window.colorMode != mode) window.colorMode = mode
    }
    fun restore() {
        if (released) return
        released = true
        if (--record.references == 0) {
            records.remove(window)
            window.colorMode = record.previousMode
        }
    }
}

private class WideColorLeaseHolder(var lease: WideColorWindowLease? = null)

@Composable
internal fun MatchWideColorWindow(window: Window?, state: WideColorDisplayState = LocalWideColorDisplay.current, observeWindowDisplay: Boolean = true) {
    val windowView = LocalView.current
    val effective = if (observeWindowDisplay && LocalColorWindowView.current !== windowView) {
        state.copy(supported = rememberWideColorCapabilities(windowView).supported)
    } else state
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(effective)
    val holder = remember(window) { WideColorLeaseHolder() }
    DisposableEffect(window, owner) {
        holder.lease = window?.let(::WideColorWindowLease)?.also { it.apply(effective) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) holder.lease?.apply(latest.copy(foreground = false))
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) holder.lease?.apply(latest.copy(foreground = true))
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); holder.lease?.restore(); holder.lease = null }
    }
    SideEffect { holder.lease?.apply(effective) }
}
