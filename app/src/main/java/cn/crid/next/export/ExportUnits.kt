package cn.crid.next.export

import cn.crid.next.core.ScheduleEngine
import cn.crid.next.core.Semester
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** A page is always one complete selected calendar unit. Null days leave tile cells empty. */
data class ExportWeek(val start: LocalDate, val days: List<LocalDate?>)
data class ExportUnit(val from: LocalDate, val to: LocalDate, val weeks: List<ExportWeek>) {
    val dates: List<LocalDate> get() = weeks.flatMap { it.days }.filterNotNull()
}

object ExportUnits {
    fun weekCount(semester: Semester, weekStartsSunday: Boolean = false): Int =
        (ChronoUnit.WEEKS.between(
            ScheduleEngine.weekStart(LocalDate.parse(semester.startDate), weekStartsSunday),
            ScheduleEngine.weekStart(LocalDate.parse(semester.endDate), weekStartsSunday)) + 1).toInt()

    fun weekRange(semester: Semester, firstWeek: Int, lastWeek: Int, weekStartsSunday: Boolean = false): Pair<LocalDate, LocalDate> {
        require(firstWeek in 1..weekCount(semester, weekStartsSunday) && lastWeek in firstWeek..weekCount(semester, weekStartsSunday))
        val first = ScheduleEngine.weekStart(LocalDate.parse(semester.startDate), weekStartsSunday)
        return first.plusWeeks(firstWeek - 1L) to first.plusWeeks(lastWeek - 1L).plusDays(6)
    }

    fun pages(view: ExportView, semester: Semester, from: LocalDate, to: LocalDate, weekStartsSunday: Boolean = false): List<ExportUnit> {
        require(to >= from)
        require(ChronoUnit.DAYS.between(from, to) < 732)
        val semesterFrom = LocalDate.parse(semester.startDate)
        val semesterTo = LocalDate.parse(semester.endDate)
        fun unit(first: LocalDate, last: LocalDate, day: Boolean = false): ExportUnit {
            val weeks = if (day) listOf(ExportWeek(first, listOf(first.takeIf { it in semesterFrom..semesterTo }))) else
                generateSequence(ScheduleEngine.weekStart(first, weekStartsSunday)) { it.plusWeeks(1) }
                    .takeWhile { it <= last }.map { start ->
                        ExportWeek(start, (0L..6L).map { offset -> start.plusDays(offset).takeIf { it in first..last && it in semesterFrom..semesterTo } })
                    }.toList()
            return ExportUnit(first, last, weeks)
        }
        return when (view) {
            ExportView.DAY -> generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.map { unit(it, it, true) }.toList()
            ExportView.WEEK -> generateSequence(ScheduleEngine.weekStart(from, weekStartsSunday)) { it.plusWeeks(1) }
                .takeWhile { it <= to }.map { unit(it, it.plusDays(6)) }.toList()
            ExportView.MONTH -> generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(to) }
                .map { unit(it.atDay(1), it.atEndOfMonth()) }.toList()
            ExportView.SEMESTER -> listOf(unit(semesterFrom, semesterTo))
        }
    }
}

/** The same increasing clock axis is used by every day in a week tile. */
internal object ExportTimeline {
    data class Span(val start: Int, val end: Int, val minimumHeight: Float)
    data class Input(val boundaries: List<Int>, val spans: List<Span>, val protectedSpans: List<Span> = emptyList())

    fun positions(boundaries: List<Int>, spans: List<Span>, pixelsPerMinute: Float = .42f): Map<Int, Float> =
        pagePositions(listOf(Input(boundaries, spans)), pixelsPerMinute).single()

    /** Correct all tiles together: even cards in different weeks obey strict duration order. */
    fun pagePositions(timelines: List<Input>, pixelsPerMinute: Float = .42f): List<Map<Int, Float>> {
        require(pixelsPerMinute > 0 && pixelsPerMinute.isFinite())
        // Adjacent boundaries and their occupancy are reused by all three layout passes.
        val intervals = timelines.map { it.boundaries.zipWithNext() }
        timelines.forEachIndexed { index, (boundaries, spans, protectedSpans) ->
            require(intervals[index].all { (first, last) -> first < last })
            val minutes = boundaries.toHashSet()
            require((spans + protectedSpans).all { it.start < it.end && it.start in minutes && it.end in minutes && it.minimumHeight >= 0 && it.minimumHeight.isFinite() })
        }
        val occupied = timelines.mapIndexed { index, input ->
            val changes = HashMap<Int, Int>()
            input.spans.forEach { span ->
                changes[span.start] = (changes[span.start] ?: 0) + 1
                changes[span.end] = (changes[span.end] ?: 0) - 1
            }
            var active = 0
            BooleanArray(intervals[index].size) { interval ->
                active += changes[intervals[index][interval].first] ?: 0
                active > 0
            }
        }
        // Period labels reserve readable space even when no lesson occupies that period.
        // Only course spans participate in duration ordering; the period grid is a baseline.
        val protectedRates = timelines.mapIndexed { index, input -> intervals[index].map { (first, last) ->
            input.protectedSpans.asSequence().filter { it.start < last && it.end > first }
                .maxOfOrNull { it.minimumHeight.toDouble() / (it.end - it.start) } ?: 0.0
        } }
        fun ordinary(first: Int, last: Int, occupied: Boolean, protectedRate: Double): Double {
            val natural = maxOf(3.0, (last - first) * maxOf(pixelsPerMinute.toDouble(), protectedRate))
            return if (occupied || protectedRate > 0) natural else minOf(12.0, natural)
        }
        val base = timelines.mapIndexed { index, input ->
            val ending = input.spans.groupBy { it.end }
            if (input.boundaries.isEmpty()) emptyMap() else linkedMapOf(input.boundaries.first() to 0.0).apply {
                intervals[index].forEachIndexed { interval, (first, last) ->
                    var y = getValue(first) + ordinary(first, last, occupied[index][interval], protectedRates[index][interval])
                    ending[last]?.forEach { y = maxOf(y, getValue(it.start) + it.minimumHeight) }
                    put(last, y)
                }
            }
        }
        val ranges = timelines.flatMapIndexed { index, input -> input.spans.map { span ->
            (span.end - span.start) to (base[index].getValue(span.end) - base[index].getValue(span.start))
        } }.groupBy({ it.first }, { it.second }).toSortedMap().map { (duration, heights) -> Triple(duration, heights.min(), heights.max()) }
        // A margin above one pixel survives Float conversion and raster rounding.
        val margin = maxOf(1.25, (ranges.maxOfOrNull { it.third } ?: 0.0) * .00001)
        var correction = 0.0
        ranges.forEachIndexed { index, shorter ->
            for (longerIndex in index + 1 until ranges.size) {
                val longer = ranges[longerIndex]
                correction = maxOf(correction, (shorter.third - longer.second + margin) / (longer.first - shorter.first))
            }
        }
        val uniformScale = maxOf(margin, pixelsPerMinute.toDouble(),
            timelines.flatMap { it.spans + it.protectedSpans }.maxOfOrNull { it.minimumHeight.toDouble() / (it.end - it.start) } ?: 0.0)
        val corrected = intervals.mapIndexed { index, gaps -> gaps.mapIndexed { interval, (first, last) ->
            base[index].getValue(last) - base[index].getValue(first) + if (occupied[index][interval]) correction * (last - first) else 0.0
        } }
        val uniform = intervals.mapIndexed { index, gaps -> gaps.mapIndexed { interval, (first, last) ->
            if (occupied[index][interval]) uniformScale * (last - first) else ordinary(first, last, false, protectedRates[index][interval])
        } }
        // Both options obey every constraint. Prefer the smaller complete canvas.
        val heights = if (uniform.sumOf { it.sum() } < corrected.sumOf { it.sum() }) uniform else corrected
        return timelines.mapIndexed { index, input ->
            var y = 0.0
            input.boundaries.mapIndexed { boundary, minute ->
                if (boundary > 0) y += heights[index][boundary - 1]
                minute to y.toFloat()
            }.toMap()
        }
    }
}
