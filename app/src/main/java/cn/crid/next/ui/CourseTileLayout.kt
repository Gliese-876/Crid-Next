package cn.crid.next.ui

internal data class CourseTileLines(val title: Int, val location: Int, val teacher: Int)

/** Name and room share the available space before a teacher is added to the card. */
internal fun courseTileLines(
    titleLines: Int,
    locationLines: Int,
    teacherLines: Int,
    titleLineHeight: Float,
    detailLineHeight: Float,
    availableHeight: Float,
    spacing: Float = 3f,
): CourseTileLines {
    val desired = intArrayOf(titleLines.coerceAtLeast(1), locationLines.coerceAtLeast(0))
    val lines = intArrayOf(1, if (locationLines > 0) 1 else 0)
    val heights = floatArrayOf(titleLineHeight, detailLineHeight)
    var remaining = availableHeight - titleLineHeight - lines[1] * (detailLineHeight + spacing)
    var grew: Boolean
    do {
        grew = false
        lines.indices.forEach { index ->
            if (lines[index] < desired[index] && remaining >= heights[index]) {
                lines[index]++
                remaining -= heights[index]
                grew = true
            }
        }
    } while (grew)
    var teachers = 0
    if (lines.contentEquals(desired) && teacherLines > 0 && remaining >= detailLineHeight + spacing) {
        remaining -= spacing
        while (teachers < teacherLines && remaining >= detailLineHeight) {
            teachers++
            remaining -= detailLineHeight
        }
    }
    return CourseTileLines(lines[0], lines[1], teachers)
}
