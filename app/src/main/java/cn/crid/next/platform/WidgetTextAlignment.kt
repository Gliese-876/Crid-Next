package cn.crid.next.platform

import android.graphics.Rect
import android.widget.TextView
import kotlin.math.roundToInt

/** Call after measuring and laying out the real resource TextView at its displayed width. */
internal fun centerWidgetText(view: TextView, fixedHeight: Boolean = false) {
    val layout = view.layout ?: return
    if (layout.lineCount == 0 || view.height <= 0) return
    val offset = view.baseline - layout.getLineBaseline(0)
    val bounds = Rect()
    var inkTop = Int.MAX_VALUE
    var inkBottom = Int.MIN_VALUE
    val lineCount = minOf(layout.lineCount, view.maxLines.takeIf { it > 0 } ?: layout.lineCount)
    repeat(lineCount) { line ->
        if (offset + layout.getLineBottom(line) <= view.compoundPaddingTop ||
            offset + layout.getLineTop(line) >= view.height - view.compoundPaddingBottom) return@repeat
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        val visible = if (layout.getEllipsisCount(line) > 0) {
            val from = (start + layout.getEllipsisStart(line)).coerceIn(start, end)
            val to = (from + layout.getEllipsisCount(line)).coerceAtMost(end)
            layout.text.subSequence(start, from).toString() + "…" + layout.text.subSequence(to, end)
        } else layout.text.subSequence(start, end).toString()
        val text = visible.replace("\uFEFF", "").trimEnd('\n', '\r')
        if (text.isBlank()) return@repeat
        view.paint.getTextBounds(text, 0, text.length, bounds)
        val baseline = offset + layout.getLineBaseline(line)
        inkTop = minOf(inkTop, baseline + bounds.top)
        inkBottom = maxOf(inkBottom, baseline + bounds.bottom)
    }
    if (inkTop == Int.MAX_VALUE) return
    val shift = (view.height - inkTop - inkBottom) / 2f
    var top = view.paddingTop
    var bottom = view.paddingBottom
    if (fixedHeight) {
        // Center-vertical text moves by this delta without changing its allocated height.
        val delta = shift.roundToInt()
        top += delta
        bottom -= delta
        val nonnegative = maxOf(0, -top, -bottom)
        top += nonnegative
        bottom += nonnegative
    } else {
        // In wrap-content, extra padding also moves the view's midpoint by half that padding.
        val delta = (shift * 2).roundToInt()
        if (delta > 0) top += delta else bottom -= delta
    }
    view.setPadding(view.paddingLeft, top, view.paddingRight, bottom)
}
