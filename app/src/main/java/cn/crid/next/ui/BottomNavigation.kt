package cn.crid.next.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
) {
    val scheme = MaterialTheme.colorScheme
    val routes = destinations.map { it.first }
    val centers = remember(routes) { mutableStateMapOf<String, Offset>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val halfWidth = with(density) { 32.dp.toPx() }
    val halfHeight = with(density) { 16.dp.toPx() }
    Box(modifier.fillMaxWidth().background(scheme.surface).testTag("bottom_navigation")
        .onGloballyPositioned { origin = it.positionInRoot() }) {
        if (routes.isNotEmpty() && routes.all { it in centers }) {
            // Box otherwise places its small child at TopStart before this physical offset;
            // anchor it to the physical left as well so RTL does not add a second translation.
            Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset {
                val position = pagePosition().takeIf { it.isFinite() }
                    ?.coerceIn(0f, routes.lastIndex.toFloat())
                    ?: routes.indexOf(selectedRoute).coerceAtLeast(0).toFloat()
                val first = floor(position).toInt()
                val next = (first + 1).coerceAtMost(routes.lastIndex)
                val start = centers.getValue(routes[first])
                val end = centers.getValue(routes[next])
                val center = start + (end - start) * (position - first)
                // Measured coordinates are physical, so do not mirror the offset a second time in RTL.
                IntOffset((center.x - origin.x - halfWidth).roundToInt(),
                    (center.y - origin.y - halfHeight).roundToInt())
            }.requiredSize(64.dp, 32.dp).background(scheme.primaryContainer, CircleShape)
                .testTag("bottom_navigation_indicator").semantics { hideFromAccessibility() })
        }
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
            destinations.forEach { (route, label) ->
                NavigationBarItem(
                    selected = selectedRoute == route,
                    onClick = { onNavigate(route) },
                    modifier = Modifier.testTag("nav_$route"),
                    icon = {
                        // Native item measurement includes insets and wrapped labels. Follow its
                        // actual icon center instead of assuming four equal screen-space columns.
                        Box(Modifier.testTag("nav_icon_$route").onGloballyPositioned {
                            centers[route] = it.boundsInRoot().center
                        }) {
                            NavGlyph(route, LocalContentColor.current)
                        }
                    },
                    label = { Text(label, maxLines = 2, textAlign = TextAlign.Center) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = scheme.primary,
                        unselectedIconColor = scheme.onSurfaceVariant,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}
