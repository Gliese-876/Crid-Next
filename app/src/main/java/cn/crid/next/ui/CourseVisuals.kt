package cn.crid.next.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import cn.crid.next.core.Course
import cn.crid.next.core.CourseColors

internal data class CourseTilePalette(
    val fill: Color,
    val content: Color,
    val outline: Color,
    val hatch: Color,
)

@Composable
internal fun courseTilePalette(course: Course, outOfWeek: Boolean, holiday: Boolean): CourseTilePalette {
    val surface = MaterialTheme.colorScheme.surface
    return remember(course.color, course.name, surface, outOfWeek, holiday) {
        val swatch = CourseColors.swatch(course.color.takeIf { it != 0 } ?: CourseColors.colorFor(course.name),
            dark = surface.luminance() < .4f, outOfWeek = outOfWeek, holiday = holiday, surface = surface.toArgb())
        val hatch = Color(swatch.hatch)
        CourseTilePalette(Color(swatch.fill), Color(swatch.content), Color(swatch.outline), hatch.copy(alpha = hatch.alpha * .6f))
    }
}

internal fun courseTileFill(palette: CourseTilePalette, highlight: Float = 0f): Color =
    lerp(palette.fill, palette.outline, highlight.coerceIn(0f, 1f) * .08f)

/** Animation invalidates drawing only; text measurement and the course composition stay untouched. */
@Composable
internal fun Modifier.courseTileBackground(palette: CourseTilePalette, highlight: State<Float>,
    radius: Dp, outOfWeek: Boolean, holiday: Boolean): Modifier {
    val wideColor = currentWindowColorState().active
    val density = LocalDensity.current
    val outline = remember(outOfWeek, density.density) {
        if (outOfWeek) with(density) { Stroke(1.2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))) } else null
    }
    return drawWithCache {
        val fillBrush = wideColorBrushFor(courseTileFill(palette, highlight.value), wideColor)
        val corner = CornerRadius(radius.toPx())
        onDrawBehind {
            drawRect(fillBrush)
            if (holiday) drawHolidayHatch(palette.hatch)
            if (outline != null) drawRoundRect(palette.outline, cornerRadius = corner, style = outline)
        }
    }
}

/** Clip at the call site when a rounded tile, rather than a full date column, is shaded. */
internal fun DrawScope.drawHolidayHatch(color: Color) {
    val spacing = 13.dp.toPx()
    val stroke = 3.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stroke)
        x += spacing
    }
}
