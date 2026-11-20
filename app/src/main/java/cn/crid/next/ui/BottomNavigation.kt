package cn.crid.next.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import kotlin.math.floor
import kotlin.math.roundToInt

/** One indicator shares the pager's spring on taps and follows the finger during a page drag. */
@Composable
internal fun CridBottomNavigation(
    destinations: List<Pair<String, String>>,
    selectedRoute: String,
    pagePosition: () -> Float,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    dark: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val routes = destinations.map { it.first }
    val glass = backdrop != null
    val anchors = remember(routes, glass) { mutableStateMapOf<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val frame = if (backdrop != null) {
        Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .liquidGlassSurface(backdrop, CircleShape, dark)
            .glassTouchLight(dark)
    } else Modifier.background(scheme.surface)
    Box(modifier.fillMaxWidth().then(frame).testTag("bottom_navigation")
        .onGloballyPositioned { origin = it.positionInRoot() }) {
        if (routes.isNotEmpty() && routes.all { it in anchors }) {
            val tab = anchors.getValue(routes.first())
            val indicatorWidth = if (glass) with(density) { (tab.width.toDp() - 4.dp).coerceAtLeast(1.dp) } else 64.dp
            val indicatorHeight = if (glass) with(density) { (tab.height.toDp() - 14.dp).coerceAtLeast(1.dp) } else 32.dp
            val halfWidth = with(density) { indicatorWidth.toPx() / 2f }
            val halfHeight = with(density) { indicatorHeight.toPx() / 2f }
            // Box otherwise places its small child at TopStart before this physical offset;
            // anchor it to the physical left as well so RTL does not add a second translation.
            Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset {
                val position = pagePosition().takeIf { it.isFinite() }
                    ?.coerceIn(0f, routes.lastIndex.toFloat())
                    ?: routes.indexOf(selectedRoute).coerceAtLeast(0).toFloat()
                val first = floor(position).toInt()
                val next = (first + 1).coerceAtMost(routes.lastIndex)
                val start = anchors.getValue(routes[first]).center
                val end = anchors.getValue(routes[next]).center
                val center = start + (end - start) * (position - first)
                // Measured coordinates are physical, so do not mirror the offset a second time in RTL.
                IntOffset((center.x - origin.x - halfWidth).roundToInt(),
                    (center.y - origin.y - halfHeight).roundToInt())
            }.requiredSize(indicatorWidth, indicatorHeight).then(
                if (glass) Modifier.glassNavigationIndicator(scheme.primary, dark)
                else Modifier.background(scheme.primaryContainer, CircleShape),
            )
                .testTag("bottom_navigation_indicator").semantics { hideFromAccessibility() })
        }
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp,
            windowInsets = if (backdrop == null) NavigationBarDefaults.windowInsets else WindowInsets(0, 0, 0, 0),
        ) {
            val colors = if (glass) NavigationBarItemDefaults.colors(
                selectedIconColor = scheme.onSurface, unselectedIconColor = scheme.onSurface,
                selectedTextColor = scheme.onSurface, unselectedTextColor = scheme.onSurface,
                indicatorColor = Color.Transparent,
            ) else NavigationBarItemDefaults.colors(
                selectedIconColor = scheme.primary, unselectedIconColor = scheme.onSurfaceVariant,
                indicatorColor = Color.Transparent,
            )
            destinations.forEach { (route, label) ->
                val interactionSource = if (glass) remember(route) { MutableInteractionSource() } else null
                val response = if (interactionSource != null) Modifier.glassItemPress(interactionSource) else Modifier
                val itemAnchor = if (glass) Modifier.onGloballyPositioned { anchors[route] = it.boundsInRoot() } else Modifier
                // The regular theme's indicator remains centered on the measured icon.
                val iconAnchor = if (glass) Modifier else Modifier.onGloballyPositioned { anchors[route] = it.boundsInRoot() }
                NavigationBarItem(
                    selected = selectedRoute == route,
                    onClick = { onNavigate(route) },
                    modifier = Modifier.testTag("nav_$route").then(itemAnchor),
                    interactionSource = interactionSource,
                    icon = {
                        Box(Modifier.testTag("nav_icon_$route").then(iconAnchor).then(response)) {
                            NavGlyph(route, LocalContentColor.current)
                        }
                    },
                    label = { Text(label, modifier = response, maxLines = 2, textAlign = TextAlign.Center) },
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun Modifier.glassItemPress(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .92f else 1f,
        spring(dampingRatio = .72f, stiffness = 450f), label = "glass_tab_press")
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

@Composable
internal fun GlassSideNavigationItem(route: String, label: String, selected: Boolean, expanded: Boolean, dark: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val response = Modifier.glassItemPress(interaction)
    val selection = if (selected) Modifier.glassNavigationIndicator(scheme.primary, dark) else Modifier
    val frame = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("nav_$route").clip(CircleShape)
        .then(selection).selectable(selected, interaction, LocalIndication.current, role = Role.Tab, onClick = onClick)
        .padding(horizontal = if (expanded) 14.dp else 4.dp, vertical = 8.dp)
    if (expanded) {
        Row(frame, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(response) { NavGlyph(route, scheme.onSurface) }
            Text(label, modifier = response, color = scheme.onSurface, maxLines = 2)
        }
    } else {
        Column(frame, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            Box(response) { NavGlyph(route, scheme.onSurface) }
            Text(label, modifier = response, color = scheme.onSurface, style = MaterialTheme.typography.labelMedium,
                maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun Modifier.glassTouchLight(dark: Boolean, cornerRadius: Dp? = null): Modifier {
    val touch = remember { mutableStateOf(Offset.Zero) }
    var touching by remember { mutableStateOf(false) }
    val intensity = animateFloatAsState(if (touching) 1f else 0f, AppMotion.pageSpring(), label = "glass_touch_light")
    return pointerInput(Unit) {
        // Watch the gesture without consuming changes, so item ripples and navigation still own it.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touch.value = down.position
            touching = true
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    touch.value = change.position
                }
            } finally {
                touching = false
            }
        }
    }.drawWithCache {
        val corner = CornerRadius(cornerRadius?.toPx()?.coerceAtMost(size.minDimension / 2f) ?: size.minDimension / 2f)
        val capsule = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), corner)) }
        val radius = size.minDimension * 1.4f
        val light = if (dark) Color(0xFFC4D6FF) else Color.White
        val glow = Brush.radialGradient(
            colors = listOf(light.copy(alpha = if (dark) .26f else .54f), light.copy(alpha = 0f)),
            center = Offset.Zero, radius = radius,
        )
        val rim = Brush.radialGradient(
            colors = listOf(light.copy(alpha = if (dark) .68f else .94f), light.copy(alpha = 0f)),
            center = Offset.Zero, radius = radius,
        )
        val rimWidth = 1.dp.toPx()
        val rimStroke = Stroke(rimWidth)
        onDrawBehind {
            val alpha = intensity.value
            if (alpha > .003f) {
                val point = touch.value
                val x = point.x.coerceIn(0f, size.width)
                val y = point.y.coerceIn(0f, size.height)
                clipPath(capsule) {
                    translate(x, y) {
                        drawCircle(glow, radius, Offset.Zero, alpha = alpha)
                        drawRoundRect(rim, topLeft = Offset(rimWidth / 2f - x, rimWidth / 2f - y),
                            size = Size(size.width - rimWidth, size.height - rimWidth),
                            cornerRadius = corner, alpha = alpha, style = rimStroke)
                    }
                }
            }
        }
    }
}

private fun Modifier.glassNavigationIndicator(primary: Color, dark: Boolean): Modifier = drawWithCache {
    val tint = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) .17f else .75f),
        .40f to primary.copy(alpha = if (dark) .24f else .11f),
        1f to primary.copy(alpha = .16f),
    )
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) .52f else .90f),
        .56f to Color.White.copy(alpha = if (dark) .11f else .36f),
        1f to primary.copy(alpha = if (dark) .34f else .26f),
    )
    val stroke = .8.dp.toPx()
    val corner = CornerRadius(size.minDimension / 2f)
    val rimSize = Size(size.width - stroke, size.height - stroke)
    val rimInset = Offset(stroke / 2f, stroke / 2f)
    val rimCorner = CornerRadius((size.minDimension - stroke) / 2f)
    val rimStroke = Stroke(stroke)
    onDrawBehind {
        drawRoundRect(tint, cornerRadius = corner)
        drawRoundRect(rim, topLeft = rimInset, size = rimSize, cornerRadius = rimCorner, style = rimStroke)
    }
}
