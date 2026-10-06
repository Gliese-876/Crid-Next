package cn.crid.next.ui

import android.graphics.Color as NativeColor
import android.graphics.ColorSpace as NativeColorSpace
import android.graphics.LinearGradient as NativeLinearGradient
import android.graphics.Shader as NativeShader
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.colorspace.ColorSpaces

/** Convert coordinates, rather than relabeling sRGB primaries and making the soft fills more vivid. */
internal fun nativeDisplayP3Color(color: Color): Long {
    val p3 = color.convert(ColorSpaces.DisplayP3)
    return NativeColor.pack(p3.red, p3.green, p3.blue, p3.alpha,
        NativeColorSpace.get(NativeColorSpace.Named.DISPLAY_P3))
}

/** Native long-color stops keep P3 coordinates intact. */
internal fun wideColorBrushFor(color: Color, wideColorSupported: Boolean): Brush {
    if (!wideColorSupported) return SolidColor(color)
    val packed = nativeDisplayP3Color(color)
    return ShaderBrush(NativeLinearGradient(0f, 0f, 1f, 0f,
        longArrayOf(packed, packed), null, NativeShader.TileMode.CLAMP))
}

/** Each brush follows the color capability of its own window. */
@Composable
internal fun wideColorBrush(color: Color): Brush {
    val supported = currentWindowColorState().active
    return remember(color, supported) { wideColorBrushFor(color, supported) }
}

/** Uses Foundation's original shape and clipping path, adding no outline or extra surface. */
@Composable
internal fun Modifier.wideColorBackground(color: Color, shape: Shape = RectangleShape, alpha: Float = 1f): Modifier =
    background(brush = wideColorBrush(color), shape = shape, alpha = alpha)
