package cn.crid.next.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import android.view.View
import android.view.WindowManager

private data class ModalEnvironment(val widthDp:Int,val heightDp:Int,val rotation:Int)
private fun modalEnvironment(view:View)=ModalEnvironment(
    view.resources.configuration.screenWidthDp,view.resources.configuration.screenHeightDp,view.display?.rotation ?: 0,
)
private fun screenRect(windowBounds:Rect,view:View):Rect {
    val screen=IntArray(2);val window=IntArray(2)
    view.getLocationOnScreen(screen);view.getLocationInWindow(window)
    return windowBounds.translate(Offset((screen[0]-window[0]).toFloat(),(screen[1]-window[1]).toFloat()))
}

private data class ModalSource(val bounds:Rect,val environment:ModalEnvironment)

/** A trigger records a screen-space snapshot when clicked, before the destination changes layout. */
@Stable
internal class ModalOrigin internal constructor() {
    private var latest by mutableStateOf<ModalSource?>(null)
    private var captured by mutableStateOf<ModalSource?>(null)
    fun capture() { captured=latest }
    fun clear() { captured=null }
    internal fun update(bounds:Rect,view:View) {
        latest=if(bounds.width>0f && bounds.height>0f)ModalSource(bounds,modalEnvironment(view))else null
    }
    internal fun detach() {latest=null}
    internal fun snapshot():Rect?=captured?.bounds
    internal fun isValid(view:View,widthDp:Int,heightDp:Int):Boolean=captured?.let { source ->
        source.environment==ModalEnvironment(widthDp,heightDp,view.display?.rotation ?: 0) && latest?.environment==source.environment &&
            latest?.bounds?.overlaps(source.bounds)==true
    } ?: false
}

@Composable
internal fun rememberModalOrigin():ModalOrigin=remember {ModalOrigin()}

internal fun Modifier.modalOrigin(origin:ModalOrigin):Modifier=composed {
    val view=LocalView.current
    DisposableEffect(origin) {onDispose {origin.detach()}}
    onGloballyPositioned {coordinates -> origin.update(screenRect(coordinates.boundsInWindow(),view),view)}
}

/** Spatial springs are shared by navigation and modal surfaces, without a library upgrade. */
internal object AppMotion {
    fun <T> pageSpring(): SpringSpec<T> = spring(dampingRatio = .9f, stiffness = 380f)
    // A full-screen surface has no room to overshoot: a bouncy finish clips against the window.
    fun <T> enterSpring(fullScreen: Boolean = false): SpringSpec<T> =
        spring(dampingRatio = if (fullScreen) Spring.DampingRatioNoBouncy else .86f, stiffness = 420f)
    fun <T> exitSpring(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 650f)
}

/** Defers removal, navigation, and destructive work until the modal has finished closing. */
internal class AppDialogScope internal constructor(
    val dismiss: () -> Unit,
    val finish: (() -> Unit) -> Unit,
)

@Composable
internal fun AnimatedAppDialog(
    onDismissRequest: () -> Unit,
    fullScreen: Boolean = false,
    dismissEnabled: Boolean = true,
    properties: DialogProperties = DialogProperties(),
    backNavigatesContent: Boolean = false,
    onContentBack: () -> Unit = {},
    origin: ModalOrigin? = null,
    content: @Composable (AppDialogScope) -> Unit,
) {
    // The source belongs to the launching window. A Dialog installs its own Android composition
    // locals, so inspect the caller's current environment before crossing that window boundary.
    val sourceConfiguration=LocalConfiguration.current
    val arrival = remember { Animatable(0f) }
    val predictiveBack = remember { Animatable(0f) }
    val motionScope = rememberCoroutineScope()
    val sourceBounds = remember { origin?.snapshot() }
    var destinationBounds by remember { mutableStateOf<Rect?>(null) }
    var exitAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestEnabled by rememberUpdatedState(dismissEnabled)
    val dialogScope = remember {
        AppDialogScope(
            dismiss = { if (latestEnabled && exitAction == null) exitAction = { latestDismiss() } },
            finish = { action -> if (exitAction == null) exitAction = action },
        )
    }
    LaunchedEffect(exitAction,destinationBounds!=null) {
        if(destinationBounds==null)return@LaunchedEffect
        val action = exitAction
        // Let Animatable interrupt its own previous animation so it captures the current
        // velocity first. Cancelling the effect's animation would reset that velocity to zero.
        motionScope.launch {
            if (action == null) arrival.animateTo(1f, AppMotion.enterSpring(fullScreen))
            else {
                arrival.animateTo(0f, AppMotion.exitSpring())
                action()
            }
        }
    }
    Dialog(
        onDismissRequest = dialogScope.dismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = properties.dismissOnClickOutside && dismissEnabled,
            securePolicy = properties.securePolicy,
            // A screen-sized transparent host lets a small modal travel to a trigger outside its
            // final bounds without the platform clipping its animation to a floating window.
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        WindowColorProvider {
        MatchDialogSystemBars(dimmedBackground = !fullScreen)
        val view = LocalView.current
        val validSource = if(origin?.isValid(view,sourceConfiguration.screenWidthDp,sourceConfiguration.screenHeightDp)==true)sourceBounds else null
        val window = remember(view) {
            generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        }
        val scope = rememberCoroutineScope()
        val latestContentBack by rememberUpdatedState(onContentBack)
        val latestNavigatesContent by rememberUpdatedState(backNavigatesContent)
        val callback = remember(window) {
            object : OnBackPressedCallback(true) {
                override fun handleOnBackStarted(backEvent: BackEventCompat) {
                    if (!latestNavigatesContent) scope.launch { predictiveBack.snapTo(backEvent.progress) }
                }
                override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                    if (!latestNavigatesContent) scope.launch { predictiveBack.snapTo(backEvent.progress) }
                }
                override fun handleOnBackCancelled() {
                    scope.launch { predictiveBack.animateTo(0f, AppMotion.pageSpring()) }
                }
                override fun handleOnBackPressed() {
                    if (latestNavigatesContent) latestContentBack() else dialogScope.dismiss()
                }
            }
        }
        SideEffect { callback.isEnabled = dismissEnabled && exitAction == null }
        DisposableEffect(window, callback, fullScreen) {
            // Register with this Dialog's dispatcher, never the underlying Activity's dispatcher.
            // ComponentDialog exposes its dispatcher through the Window callback.
            val owner = window?.callback as? OnBackPressedDispatcherOwner
            owner?.onBackPressedDispatcher?.addCallback(callback)
            window?.setWindowAnimations(0)
            // Full-screen pages reveal their launching surface during spatial motion. Keep the
            // platform's background dim only for floating dialogs, before either starts drawing.
            if (fullScreen) window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            else window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            onDispose { callback.remove() }
        }
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            if(!fullScreen)Spacer(Modifier.matchParentSize().pointerInput(dismissEnabled,exitAction,properties.dismissOnClickOutside) {
                detectTapGestures {if(dismissEnabled && properties.dismissOnClickOutside)dialogScope.dismiss()}
            })
        Box(
            modifier = (if (fullScreen) Modifier.fillMaxSize() else Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .padding(horizontal=if(properties.usePlatformDefaultWidth)24.dp else 0.dp))
                .onGloballyPositioned {coordinates -> destinationBounds=screenRect(coordinates.boundsInWindow(),view)}
                .pointerInput(Unit) {}
        ) {
        Box(
            modifier = Modifier
                .pointerInput(exitAction != null) {
                if (exitAction != null) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }.graphicsLayer {
                val back = predictiveBack.value.coerceIn(0f, 1f)
                val progress = arrival.value * (1f-back*.18f)
                val source=validSource
                val destination=destinationBounds
                val restScale = if(source!=null && destination!=null)
                    minOf(source.width/size.width,source.height/size.height).coerceIn(.08f,.8f)
                    else if(fullScreen).94f else .88f
                val scale = restScale + (1f - restScale) * progress
                scaleX = scale
                scaleY = scale
                val offset=if(source!=null && destination!=null)source.center-destination.center else Offset.Zero
                translationX=offset.x*(1f-progress)
                translationY=offset.y*(1f-progress)
                alpha=(progress/.22f).coerceIn(0f,1f)
                // Keep the visible corner radius substantial while the surface moves. A radius
                // expressed directly in this layer would shrink again with scaleX/scaleY.
                // Full-screen surfaces blend to square only near their settled edge-to-edge pose;
                // the same progress reverses this blend for close and predictive-back gestures.
                val rounding=(1f-progress.coerceIn(0f,1f)).div(.18f).coerceIn(0f,1f)
                val roundness=if(fullScreen)1f-(1f-rounding)*(1f-rounding)else 1f
                val visibleRadius=28.dp.toPx()*roundness
                shape=RoundedCornerShape((visibleRadius/scale.coerceAtLeast(.01f)).toDp())
                clip=true
            },
            contentAlignment = Alignment.Center,
        ) { content(dialogScope) }
        }
        }
        }
    }
}

/** Material dialog spacing and typography, with the same spatial lifecycle as full-screen pages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnimatedAppAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable (AppDialogScope) -> Unit,
    dismissButton: (@Composable (AppDialogScope) -> Unit)? = null,
    origin: ModalOrigin? = null,
) {
    AnimatedAppDialog(onDismissRequest,origin=origin) { motion ->
        Surface(
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(24.dp)) {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.weight(1f, fill = false)) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                }
                Spacer(Modifier.height(24.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dismissButton?.invoke(motion)
                    confirmButton(motion)
                }
            }
        }
    }
}
