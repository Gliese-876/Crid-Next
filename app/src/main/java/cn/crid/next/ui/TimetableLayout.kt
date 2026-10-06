package cn.crid.next.ui

import cn.crid.next.core.Occurrence
import cn.crid.next.core.Period
import cn.crid.next.core.DisplayCourse
import cn.crid.next.core.displayCourses
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p

internal data class MinuteSpan(val start: Int, val end: Int) {
    init { require(end > start) }
}

internal fun LocalTime.minuteOfDay() = toSecondOfDay() / 60

/** A smooth readable minimum that quickly approaches a time-proportional height. */
internal fun readableCourseHeight(durationMinutes: Int, minimumHeight: Float, pixelsPerMinute: Float, smoothing: Float): Float {
    require(durationMinutes > 0)
    require(minimumHeight.isFinite() && minimumHeight >= 0)
    require(pixelsPerMinute.isFinite() && pixelsPerMinute > 0)
    require(smoothing.isFinite() && smoothing > 0)
    val linear = durationMinutes * pixelsPerMinute.toDouble()
    val minimum = minimumHeight.toDouble()
    // Equivalent to minimum + smoothing * ln(1 + exp((linear - minimum) / smoothing)),
    // without overflow on long lessons or underflow caused by subtracting large values.
    return (maxOf(linear, minimum) + smoothing * ln1p(exp(-abs((linear - minimum) / smoothing)))).toFloat()
}

/**
 * One axis for every day, with enough space for each final display group's readable card.
 *
 * Both card endpoints stay on their real-time ticks. First, forward constraints reserve
 * readable card heights. A shared time-proportional correction then makes every longer
 * registered span strictly taller than every shorter one, including on different days.
 * Pass the spans produced by timetableGroups(), after same-course lessons are combined.
 */
internal class TimetableAxis(
    periods: List<Period>,
    lessons: List<MinuteSpan>,
    val pixelsPerMinute: Float = 1.6f,
    gapHeight: Float = 6f,
    val minimumLessonHeight: Float = 64f,
    val heightSmoothing: Float = 8f,
    val minimumHeightStep: Float = 1f,
) {
    private val protectedSpans = periods.map { MinuteSpan(LocalTime.parse(it.start).minuteOfDay(), LocalTime.parse(it.end).minuteOfDay()) } + lessons
    private val boundaries = protectedSpans.flatMap { listOf(it.start, it.end) }.distinct().sorted()
    private data class Segment(val start: Int, val end: Int, val y: Float, val height: Float)
    private val segments: List<Segment>
    val startMinute = boundaries.firstOrNull() ?: 480
    val endMinute = boundaries.lastOrNull() ?: 480
    val height: Float
    val usesUniformFallback: Boolean

    init {
        require(pixelsPerMinute.isFinite() && pixelsPerMinute > 0)
        require(gapHeight.isFinite() && gapHeight >= 0)
        require(minimumLessonHeight.isFinite() && minimumLessonHeight >= 0)
        require(heightSmoothing.isFinite() && heightSmoothing > 0)
        require(minimumHeightStep.isFinite() && minimumHeightStep > 0)
        val indices = boundaries.withIndex().associate { it.value to it.index }
        val distinctLessons = lessons.distinct()
        val intervals = boundaries.zipWithNext()
        val occupiedByLesson = intervals.map { (start, end) -> lessons.any { it.start < end && it.end > start } }
        val ordinaryHeights = intervals.map { (start, end) ->
            val occupied = protectedSpans.any { it.start < end && it.end > start }
            val naturalHeight = (end - start) * pixelsPerMinute.toDouble()
            if (occupied) naturalHeight else minOf(naturalHeight, gapHeight.toDouble())
        }
        // Edges only point forward in real time. The longest path gives the smallest
        // shared coordinates satisfying both ordinary spacing and every card's height.
        val cardsEndingAt = distinctLessons.groupBy { indices.getValue(it.end) }
        val positions = DoubleArray(boundaries.size)
        intervals.forEachIndexed { index, _ ->
            var nextY = positions[index] + ordinaryHeights[index]
            cardsEndingAt[index + 1].orEmpty().forEach { span ->
                nextY = maxOf(nextY, positions[indices.getValue(span.start)] + requiredLessonHeight(span))
            }
            positions[index + 1] = nextY
        }

        val durationRanges = distinctLessons.groupBy { it.end - it.start }.toSortedMap().map { (duration, spans) ->
            val heights = spans.map { positions[indices.getValue(it.end)] - positions[indices.getValue(it.start)] }
            Triple(duration, heights.min(), heights.max())
        }
        // Every registered lesson protects its entire interval, so adding lambda to
        // occupied minutes adds exactly lambda * duration to that card. A small margin
        // survives Float rounding and final pixel rounding for near-equal durations.
        // The UI supplies one physical pixel in dp, plus a small rounding allowance.
        val margin = maxOf(minimumHeightStep.toDouble(), (durationRanges.maxOfOrNull { it.third } ?: 0.0) * .00001)
        var correction = 0.0
        durationRanges.forEachIndexed { index, shorter ->
            for (otherIndex in index + 1 until durationRanges.size) {
                val longer = durationRanges[otherIndex]
                correction = maxOf(correction, (shorter.third - longer.second + margin) / (longer.first - shorter.first))
            }
        }
        val correctedHeights = intervals.mapIndexed { index, (start, end) ->
            positions[index + 1] - positions[index] + if (occupiedByLesson[index]) correction * (end - start) else 0.0
        }

        // Adversarial nested, almost-equal spans can demand a large correction. A
        // uniform coefficient is always feasible; prefer it when it produces a shorter
        // whole axis. It still respects every readable minimum and strict duration order.
        val uniformScale = maxOf(pixelsPerMinute.toDouble(), minimumHeightStep.toDouble(), distinctLessons.maxOfOrNull {
            requiredLessonHeight(it).toDouble() / (it.end - it.start)
        } ?: 0.0)
        val uniformHeights = intervals.mapIndexed { index, (start, end) ->
            if (occupiedByLesson[index]) uniformScale * (end - start) else ordinaryHeights[index]
        }
        usesUniformFallback = uniformHeights.sum() < correctedHeights.sum()
        val finalHeights = if (usesUniformFallback) uniformHeights else correctedHeights
        var top = 0.0
        segments = intervals.mapIndexed { index, (start, end) ->
            Segment(start, end, top.toFloat(), finalHeights[index].toFloat()).also { top += finalHeights[index] }
        }
        height = top.toFloat()
    }

    fun y(minute: Int): Float {
        if (minute <= startMinute) return 0f
        if (minute >= endMinute) return height
        val boundary = boundaries.binarySearch(minute)
        val segment = segments[if (boundary >= 0) boundary else -boundary - 2]
        return segment.y + (minute - segment.start).toFloat() / (segment.end - segment.start) * segment.height
    }

    /** Readability constraint: unlike a fixed minimum clamp, every added minute increases it. */
    fun requiredLessonHeight(span: MinuteSpan): Float =
        readableCourseHeight(span.end - span.start, minimumLessonHeight, pixelsPerMinute, heightSmoothing)

    /** Render with this exact height so the card bottom remains aligned with its end tick. */
    fun lessonHeight(span: MinuteSpan): Float = y(span.end) - y(span.start)
}

internal data class TimetableColumns(val contentWidth: Float, val timeWidth: Float, val dayWidth: Float, val gap: Float, val scrolls: Boolean) {
    fun dayLeft(index: Int) = timeWidth + gap + index * (dayWidth + gap)
}

/** No lane count or font-size decision can expand a normal-width weekly grid. */
internal fun timetableColumns(availableWidth: Float, days: Int, fontScale: Float = 1f): TimetableColumns {
    require(days > 0)
    val scrolls = availableWidth < 272f // 300dp viewport, with the screen's 14dp side padding.
    val width = if (scrolls) 272f else availableWidth
    val gap = if (days == 1) 6f else 3f
    val timeWidth = (42f * fontScale.coerceIn(1f, 1.6f)).coerceAtMost(width / 3)
    return TimetableColumns(width, timeWidth, (width - timeWidth - gap * days) / days, gap, scrolls)
}

internal class TimetableGroup(val courses: List<DisplayCourse>) {
    val occurrences: List<Occurrence> = courses.flatMap { it.occurrences }
    val representative: Occurrence = courses.minWith(
        compareBy<DisplayCourse> { !it.isActual }
            .thenBy { cn.crid.next.core.OccurrenceStatus.OUT_OF_WEEK in it.representative.statuses }
            .thenBy { it.representative.start }
    ).representative
    val start: LocalTime = courses.minOf { it.representative.start }
    val end: LocalTime = courses.maxOf { it.representative.end }
    val span = MinuteSpan(start.minuteOfDay(), end.minuteOfDay())

    /** Geometric stacking alone is not a conflict: both lessons must really occur. */
    private val conflictingCourses: Set<DisplayCourse> = buildSet {
        courses.forEachIndexed { index, course ->
            for (otherIndex in index + 1 until courses.size) {
                val other = courses[otherIndex]
                val overlaps = course.occurrences.any { first ->
                    first.isActual && other.occurrences.any { second ->
                        second.isActual && first.date == second.date &&
                            first.start < second.end && second.start < first.end
                    }
                }
                if (overlaps) {
                    add(course)
                    add(other)
                }
            }
        }
    }
    val hasConflict: Boolean = conflictingCourses.isNotEmpty()
    val conflictCourseCount: Int = conflictingCourses.size
}

/** Shared positions stay selectable without widening the grid; only actual overlaps conflict. */
internal fun timetableGroups(items: List<Occurrence>): List<TimetableGroup> {
    val groups = mutableListOf<MutableList<DisplayCourse>>()
    var groupEnd: LocalTime? = null
    displayCourses(items).forEach { course ->
        val item = course.representative
        val previous = groups.lastOrNull()
        if (previous != null && item.date == previous.first().representative.date && item.start < requireNotNull(groupEnd)) {
            previous += course
            groupEnd = maxOf(requireNotNull(groupEnd), item.end)
        } else {
            groups += mutableListOf(course)
            groupEnd = item.end
        }
    }
    return groups.map(::TimetableGroup)
}
