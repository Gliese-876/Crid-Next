package cn.crid.next.export

import cn.crid.next.core.Semester
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ExportUnitsTest {
    private val semester = Semester("test", "Autumn", "2026-09-02", "2027-01-15", 20, emptyList())
    private fun date(value: String) = LocalDate.parse(value)

    @Test fun dailyPagesContainExactlyOneDateEach() {
        val pages = ExportUnits.pages(ExportView.DAY, semester, date("2026-09-02"), date("2026-09-04"))
        assertEquals(3, pages.size)
        assertEquals(listOf(2, 3, 4), pages.map { it.dates.single().dayOfMonth })
        assertTrue(pages.all { it.from == it.to })
    }

    @Test fun weeklySelectionExpandsBothEndpointsToWholeWeeks() {
        val pages = ExportUnits.pages(ExportView.WEEK, semester, date("2026-09-09"), date("2026-09-22"))
        assertEquals(3, pages.size)
        assertEquals(date("2026-09-07"), pages.first().from)
        assertEquals(date("2026-09-27"), pages.last().to)
        assertTrue(pages.all { it.weeks.single().days.size == 7 && it.dates.size == 7 })
    }

    @Test fun monthTilesHaveSevenCellsAndDoNotRepeatCrossMonthDates() {
        val pages = ExportUnits.pages(ExportView.MONTH, semester, date("2026-09-14"), date("2026-10-02"))
        assertEquals(2, pages.size)
        assertEquals(date("2026-09-01"), pages.first().from)
        assertEquals(date("2026-10-31"), pages.last().to)
        assertTrue(pages.all { it.weeks.size in 4..6 && it.weeks.all { week -> week.days.size == 7 } })
        val dates = pages.flatMap { it.dates }
        assertEquals(60, dates.size)
        assertEquals(dates.size, dates.distinct().size)
        assertEquals(date("2026-09-02"), dates.first())
        assertTrue(pages.first().dates.all { it.monthValue == 9 })
        assertTrue(pages.last().dates.all { it.monthValue == 10 })
    }

    @Test fun semesterAlwaysUsesEveryWeekOnOnePageAndBlanksOutsideDates() {
        val unit = ExportUnits.pages(ExportView.SEMESTER, semester, date("2026-10-14"), date("2026-10-15")).single()
        assertEquals(date(semester.startDate), unit.from)
        assertEquals(date(semester.endDate), unit.to)
        assertEquals(ExportUnits.weekCount(semester), unit.weeks.size)
        val expected = generateSequence(unit.from) { it.plusDays(1) }.takeWhile { it <= unit.to }.toList()
        assertEquals(expected, unit.dates)
        assertNull(unit.weeks.first().days.first())
        assertNull(unit.weeks.last().days.last())
    }

    @Test fun sundayWeekStartsDriveBothPickerRangesAndTileBoundaries() {
        val range = ExportUnits.weekRange(semester, 1, ExportUnits.weekCount(semester, true), true)
        assertEquals(date("2026-08-30"), range.first)
        assertEquals(date("2027-01-16"), range.second)
        val units = ExportUnits.pages(ExportView.WEEK, semester, range.first, range.second, true)
        assertEquals(ExportUnits.weekCount(semester, true), units.size)
        assertTrue(units.all { it.from.dayOfWeek.value == 7 && it.weeks.single().start == it.from })
        assertEquals(ExportUnits.pages(ExportView.SEMESTER, semester, range.first, range.second, true).single().dates, units.flatMap { it.dates })
    }

    @Test fun yearBoundaryAndLeapMonthRemainWholeUnits() {
        val leap = semester.copy(startDate = "2027-12-15", endDate = "2028-03-05")
        val pages = ExportUnits.pages(ExportView.MONTH, leap, date("2027-12-15"), date("2028-03-05"))
        assertEquals(4, pages.size)
        assertEquals(29, pages[2].dates.size)
        assertEquals(date("2028-02-29"), pages[2].to)
    }

    @Test fun timelineExpandsForEveryCourseWithoutSplittingOrOverlappingRows() {
        val spans = listOf(ExportTimeline.Span(480, 525, 900f), ExportTimeline.Span(500, 570, 320f), ExportTimeline.Span(570, 615, 440f))
        val axis = ExportTimeline.positions(listOf(480, 500, 525, 570, 615), spans)
        spans.forEach { assertTrue(axis.getValue(it.end) - axis.getValue(it.start) >= it.minimumHeight) }
        assertTrue(axis.values.zipWithNext().all { (a, b) -> b > a })
        assertTrue(axis.getValue(615) > 1300f)
    }

    @Test fun longTitleOnShortLessonCannotOutgrowLongerLessonInAnotherWeek() {
        val first = ExportTimeline.Input(listOf(480, 525, 540, 630), listOf(ExportTimeline.Span(480, 525, 640f), ExportTimeline.Span(540, 630, 100f)))
        val second = ExportTimeline.Input(listOf(480, 570, 600, 645), listOf(ExportTimeline.Span(480, 570, 80f), ExportTimeline.Span(600, 645, 120f)))
        val inputs = listOf(first, second)
        val axes = ExportTimeline.pagePositions(inputs)
        val heights = inputs.flatMapIndexed { index, input -> input.spans.map { span ->
            assertTrue(axes[index].getValue(span.end) - axes[index].getValue(span.start) >= span.minimumHeight)
            (span.end - span.start) to (axes[index].getValue(span.end) - axes[index].getValue(span.start))
        } }
        val shortMaximum = heights.filter { it.first == 45 }.maxOf { it.second }
        val longMinimum = heights.filter { it.first == 90 }.minOf { it.second }
        assertTrue("Duration order is strict across every tile on the page", longMinimum >= shortMaximum + 1f)
    }

    @Test fun timelineKeepsTouchingLessonsOccupiedAndCompressesOnlyEmptyGaps() {
        val boundaries = listOf(450, 480, 500, 525, 570, 600, 630, 690, 750)
        val spans = listOf(ExportTimeline.Span(480, 525, 60f), ExportTimeline.Span(525, 570, 20f),
            ExportTimeline.Span(600, 690, 50f))
        val axis = ExportTimeline.positions(boundaries, spans)
        val expected = listOf(0f, 12f, 25.4f, 83.25f, 114.5f, 126.5f, 146.6f, 199f, 211f)
        boundaries.forEachIndexed { index, minute -> assertEquals("Position at $minute", expected[index], axis.getValue(minute), .0001f) }
    }

    @Test fun duplicateAndNestedLessonsPreserveOccupancyAcrossEmptyTiles() {
        val boundaries = listOf(450, 480, 500, 525, 570, 600)
        val long = ExportTimeline.Span(480, 570, 120f)
        val nested = ExportTimeline.Span(500, 525, 20f)
        val input = ExportTimeline.Input(boundaries, listOf(long, nested))
        val empty = ExportTimeline.Input(emptyList(), emptyList())
        val duplicated = input.copy(spans = listOf(long, nested, nested, long))
        val axes = ExportTimeline.pagePositions(listOf(empty, input, duplicated))
        assertTrue(axes[0].isEmpty())
        assertEquals(axes[1], axes[2])
        assertEquals(12f, axes[1].getValue(480) - axes[1].getValue(450), .0001f)
        assertEquals(12f, axes[1].getValue(600) - axes[1].getValue(570), .0001f)
        for (span in listOf(long, nested)) {
            assertTrue(axes[1].getValue(span.end) - axes[1].getValue(span.start) >= span.minimumHeight)
        }
    }

    @Test fun emptyTeachingPeriodsKeepRoomForAllAxisLabels() {
        val periods = listOf(ExportTimeline.Span(480, 525, 84f), ExportTimeline.Span(535, 580, 84f),
            ExportTimeline.Span(600, 610, 84f))
        val lessons = listOf(ExportTimeline.Span(490, 525, 150f), ExportTimeline.Span(535, 580, 50f))
        val boundaries = (periods + lessons).flatMap { listOf(it.start, it.end) }.distinct().sorted()
        val axis = ExportTimeline.pagePositions(listOf(ExportTimeline.Input(boundaries, lessons, periods))).single()
        periods.forEach { period ->
            assertTrue("Even an unoccupied short period keeps three readable labels",
                axis.getValue(period.end) - axis.getValue(period.start) >= period.minimumHeight - .001f)
        }
        val shortHeight = axis.getValue(525) - axis.getValue(490)
        val longHeight = axis.getValue(580) - axis.getValue(535)
        assertTrue("Duration ordering still applies to courses only", longHeight >= shortHeight + 1f)
        assertEquals("The unoccupied meal break remains compact", 8.4f, axis.getValue(600) - axis.getValue(580), .001f)
    }

    @Test fun protectedPeriodsAloneDoNotAcquireCourseDurationOrdering() {
        val periods = listOf(ExportTimeline.Span(480, 490, 84f), ExportTimeline.Span(600, 645, 84f))
        val boundaries = periods.flatMap { listOf(it.start, it.end) }
        val axis = ExportTimeline.pagePositions(listOf(ExportTimeline.Input(boundaries, emptyList(), periods))).single()
        assertEquals(84f, axis.getValue(490) - axis.getValue(480), .001f)
        assertEquals(84f, axis.getValue(645) - axis.getValue(600), .001f)
        assertEquals(12f, axis.getValue(600) - axis.getValue(490), .001f)
    }

    @Test(expected = IllegalArgumentException::class) fun reversedRangesAreRejected() {
        ExportUnits.pages(ExportView.DAY, semester, date("2026-10-01"), date("2026-09-01"))
    }

    @Test(expected = IllegalArgumentException::class) fun invalidWeekSelectionIsRejected() {
        ExportUnits.weekRange(semester, 0, 2)
    }
}
