package cn.crid.next.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

// Scrollable pages keep their final item clear of the floating navigation at the end of a scroll.
internal val LocalFloatingNavigationPadding = staticCompositionLocalOf { 0.dp }
internal val LocalLiquidGlassDark = staticCompositionLocalOf<Boolean?> { null }

@Composable
internal fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Capture the static background or the strip of scrolling content under the bottom bar. */
@Stable
internal class GlassBackdrop(private val layer: GraphicsLayer) : Backdrop {
    private var sourceCoordinates by mutableStateOf<LayoutCoordinates?>(null)

    override val isCoordinatesDependent = true

    fun capture(modifier: Modifier, bottomStrip: Dp? = null): Modifier = modifier
        .onGloballyPositioned { sourceCoordinates = it }
        .drawWithContent {
            if (bottomStrip == null) {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            } else {
                drawContent()
                val height = minOf(size.height.toInt(), bottomStrip.roundToPx())
                val top = size.height.toInt() - height
                layer.topLeft = IntOffset(0, top)
                layer.clip = true
                layer.record(IntSize(size.width.toInt(), height)) {
                    translate(top = -top.toFloat()) { this@drawWithContent.drawContent() }
                }
            }
        }

    // The navigation surface shares the source's layout root and has no layer transform.
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val source = sourceCoordinates?.takeIf { it.isAttached } ?: return
        val target = coordinates?.takeIf { it.isAttached } ?: return
        val offset = source.localPositionOf(target, Offset.Zero)
        translate(-offset.x, -offset.y) { drawLayer(layer) }
    }
}

internal fun Modifier.liquidGlassBackground(base: Color, dark: Boolean) = drawWithCache {
    val violet = Brush.radialGradient(
        colors = listOf(if (dark) Color(0xFF342951) else Color(0xFFE4DBFB), Color.Transparent),
        center = Offset(size.width * .08f, size.height * .1f),
        radius = maxOf(size.width, size.height) * .65f,
    )
    val blue = Brush.radialGradient(
        colors = listOf(if (dark) Color(0xFF1D3549) else Color(0xFFD7E9FA), Color.Transparent),
        center = Offset(size.width * .91f, size.height * .87f),
        radius = maxOf(size.width, size.height) * .58f,
    )
    onDrawBehind {
        drawRect(base)
        drawRect(violet)
        drawRect(blue)
    }
}

internal fun Modifier.liquidGlassSurface(backdrop: Backdrop, shape: CornerBasedShape, dark: Boolean): Modifier {
    val tint = if (dark) Color(0xFF182136).copy(alpha = .67f) else Color(0xFFF9FAFF).copy(alpha = .67f)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(5.dp.toPx())
            // Backdrop leaves this step out on Android 12 and 12L.
            lens(15.dp.toPx(), 29.dp.toPx(), depthEffect = true, chromaticAberration = true)
        },
        highlight = { Highlight(width = .65.dp, alpha = if (dark) .7f else .9f) },
        shadow = { Shadow(radius = 18.dp, offset = DpOffset(0.dp, 4.dp), color = Color(0xFF17203A).copy(alpha = if (dark) .4f else .22f)) },
        onDrawSurface = { drawRect(tint) },
    )
}

@Composable
internal fun PageToolbarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = LocalLiquidGlassDark.current
    if (dark == null) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        return
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val restFill = if (dark) Color(0xFFB9C9FF).copy(alpha = .11f) else Color.White.copy(alpha = .48f)
    val flash = if (dark) Color(0xFFD8E5FF) else Color.White
    val progress = animateFloatAsState(if (pressed) 1f else 0f, AppMotion.enterSpring(), label = "glass_toolbar_press")
    val surface = modifier.drawWithCache {
        val inset = 3.dp.toPx()
        val topLeft = Offset(inset, inset)
        val bounds = Size(size.width - inset * 2f, size.height - inset * 2f)
        val corner = CornerRadius(bounds.minDimension / 2f)
        val rim = Brush.linearGradient(
            .0f to flash.copy(alpha = if (dark) .55f else .95f),
            .50f to flash.copy(alpha = .08f),
            1f to flash.copy(alpha = if (dark) .38f else .66f),
        )
        val stroke = Stroke(1.dp.toPx())
        val shade = Color(0xFF131729).copy(alpha = if (dark) .19f else .06f)
        val shadowOffset = Offset(0f, 1.5.dp.toPx())
        onDrawBehind {
            val pressure = progress.value.coerceIn(0f, 1f)
            val opacity = if (enabled) 1f else .45f
            scale(scaleX = 1f - .05f * pressure, scaleY = 1f - .09f * pressure) {
                drawRoundRect(shade, topLeft + shadowOffset, bounds, corner, alpha = opacity * (1f - pressure))
                drawRoundRect(restFill, topLeft, bounds, corner, alpha = opacity)
                if (pressure > 0f) drawRoundRect(flash, topLeft, bounds, corner, alpha = .26f * pressure)
                drawRoundRect(rim, topLeft, bounds, corner, alpha = opacity, style = stroke)
            }
        }
    }
    IconButton(onClick = onClick, modifier = surface, enabled = enabled, interactionSource = interaction, content = content)
}
