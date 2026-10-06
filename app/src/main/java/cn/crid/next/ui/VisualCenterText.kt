package cn.crid.next.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import kotlin.math.roundToInt

private val InkTop = HorizontalAlignmentLine { first, second -> minOf(first, second) }
private val InkBottom = HorizontalAlignmentLine { first, second -> maxOf(first, second) }

/** Align a text group by its visible glyphs; non-text controls retain their geometric center. */
internal fun RowScope.alignByVisualCenter(modifier: Modifier = Modifier): Modifier = modifier.alignBy { measured ->
    val top = measured[InkTop]
    val bottom = measured[InkBottom]
    if (top == AlignmentLine.Unspecified || bottom == AlignmentLine.Unspecified) measured.measuredHeight / 2
    else ((top + bottom) / 2f).roundToInt()
}

/** Center a label inside an existing native control slot without changing that slot's size. */
internal fun Modifier.centerVisualText(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val top = placeable[InkTop]
    val bottom = placeable[InkBottom]
    val offset = if (top == AlignmentLine.Unspecified || bottom == AlignmentLine.Unspecified) 0
        else ((placeable.height - top - bottom) / 2f).roundToInt()
    // Inherited alignment lines follow the child's placement, including the native baselines.
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, offset) }
}

/**
 * Native Material Text with invisible glyph-bound alignment lines. Its own onTextLayout supplies
 * the actual first and last rendered line ranges; text layout, placement and baselines are unchanged.
 * Columns and native buttons propagate the combined bounds to alignByVisualCenter automatically.
 */
@Composable
internal fun VisualCenterText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val effectiveStyle = LocalTextStyle.current.merge(style).merge(
        color = color, fontSize = fontSize, fontWeight = fontWeight,
        textAlign = textAlign ?: TextAlign.Unspecified, lineHeight = lineHeight,
    )
    val typeface by LocalFontFamilyResolver.current.resolve(
        effectiveStyle.fontFamily, effectiveStyle.fontWeight ?: FontWeight.Normal,
        effectiveStyle.fontStyle ?: FontStyle.Normal, effectiveStyle.fontSynthesis ?: FontSynthesis.All,
    )
    val textSize = with(LocalDensity.current) { effectiveStyle.fontSize.toPx() }
    val ink = remember(text, typeface, textSize) {
        TextInkBounds(text, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface as Typeface
            this.textSize = textSize
        })
    }
    Text(text, modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val lines: Map<AlignmentLine, Int> = if (ink.hasInk) mapOf(
            InkTop to (placeable[FirstBaseline] + ink.topOffset).roundToInt(),
            InkBottom to (placeable[LastBaseline] + ink.bottomOffset).roundToInt(),
        ) else emptyMap()
        layout(placeable.width, placeable.height, lines) { placeable.placeRelative(0, 0) }
    }, style = effectiveStyle, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
        onTextLayout = { result -> ink.update(result); onTextLayout?.invoke(result) })
}

private class TextInkBounds(private val text: String, private val paint: Paint) {
    private val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
    var hasInk by mutableStateOf(text.isNotBlank())
        private set
    var topOffset by mutableFloatStateOf(bounds.top.toFloat())
        private set
    var bottomOffset by mutableFloatStateOf(bounds.bottom.toFloat())
        private set

    // These values are read only during measurement, so changed wrapping requests remeasurement.
    fun update(layout: TextLayoutResult) {
        fun hasVisibleInk(line: Int) =
            layout.getLineStart(line) < layout.getLineEnd(line, visibleEnd = true) || layout.isLineEllipsized(line)
        var first = 0
        while (first < layout.lineCount && !hasVisibleInk(first)) first++
        hasInk = first < layout.lineCount
        if (!hasInk) return
        var last = layout.lineCount - 1
        while (last > first && !hasVisibleInk(last)) last--
        measureLine(layout, first)
        topOffset = layout.getLineBaseline(first) + bounds.top - layout.firstBaseline
        if (last != first) measureLine(layout, last)
        bottomOffset = layout.getLineBaseline(last) + bounds.bottom - layout.lastBaseline
    }

    private fun measureLine(layout: TextLayoutResult, line: Int) {
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line, visibleEnd = true)
        if (layout.isLineEllipsized(line)) {
            val visible = text.substring(start, end) + "…"
            paint.getTextBounds(visible, 0, visible.length, bounds)
        } else paint.getTextBounds(text, start, end, bounds)
    }
}
