package cn.crid.next.ui

import cn.crid.next.core.Course
import cn.crid.next.core.Lesson
import cn.crid.next.core.Occurrence
import cn.crid.next.core.OccurrenceStatus
import cn.crid.next.core.Period
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class TimetableLayoutTest {
    private val periods = listOf(
        Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40"),
        Period(3, "10:00", "10:45"), Period(4, "10:55", "11:40"),
        Period(5, "14:00", "14:45"), Period(6, "14:55", "15:40"),
        Period(7, "16:00", "16:45"), Period(8, "16:55", "17:40"),
        Period(9, "19:00", "19:45"), Period(10, "19:55", "20:40"),
        Period(11, "20:50", "21:35"), Period(12, "21:45", "22:30"),
    )

    @Test fun allSevenColumnsStayInsideNormalViewportAtEveryFontScale() {
        listOf(300f, 360f, 600f, 900f).forEach { viewport ->
            listOf(1f, 1.5f, 2f).forEach { fontScale ->
                val available = viewport - 28f
                val layout = timetableColumns(available, 7, fontScale)
                assertFalse("Unexpected scroll at $viewport dp", layout.scrolls)
                assertTrue(layout.dayWidth > 0f)
                assertEquals(available, layout.dayLeft(6) + layout.dayWidth, .001f)
            }
        }
    }

    @Test fun onlyExtremeNarrowWidthsGetHorizontalScrolling() {
        assertTrue(timetableColumns(271f, 7).scrolls)
        assertFalse(timetableColumns(272f, 7).scrolls)
    }

    @Test fun everyLongerMinuteDurationHasAStrictlyTallerCard() {
        val axis = TimetableAxis(periods, (1..1439).map { MinuteSpan(0, it) })
        val sizes = (1..1439).map { duration -> axis.lessonHeight(MinuteSpan(0, duration)) }
        sizes.zipWithNext().forEachIndexed { index, (short, long) ->
            assertTrue("${index + 2} minutes must be taller than ${index + 1}", long > short)
        }
        val representativeDurations = listOf(1, 5, 30, 45, 90, 165)
        representativeDurations.zipWithNext().forEach { (short, long) ->
            assertTrue(axis.lessonHeight(MinuteSpan(0, long)) > axis.lessonHeight(MinuteSpan(0, short)))
        }
        assertTrue(axis.requiredLessonHeight(MinuteSpan(0, 45)) >= 74f)
        assertTrue(axis.requiredLessonHeight(MinuteSpan(0, 165)) < 265f)
    }

    @Test fun registeredCardsStayStrictlyTallerAfterOneAndTwoDensityPixelRounding() {
        var minute = 60
        val spans = listOf(1, 5, 30, 45, 46, 90, 91, 165).map { duration ->
            MinuteSpan(minute, minute + duration).also { minute += duration + 10 }
        }
        listOf(1f, 2f).forEach { density ->
            val axis = TimetableAxis(emptyList(), spans, .01f, minimumHeightStep = 1f / density + .001f)
            assertPixelHeightsStrictlyIncrease(axis, spans, density)
        }
    }

    @Test fun uniformFallbackAlsoKeepsAVisibleHeightStepAtEachPixelDensity() {
        val short = MinuteSpan(480, 525)
        val long = MinuteSpan(600, 646)
        val nested = (0..5).map { MinuteSpan(480 + it * 8, 481 + it * 8) }
        val spans = nested + listOf(short, long)
        listOf(1f, 2f).forEach { density ->
            val axis = TimetableAxis(periods, spans, .01f, minimumHeightStep = 1f / density + .001f)
            assertTrue(axis.usesUniformFallback)
            assertPixelHeightsStrictlyIncrease(axis, spans, density)
        }
    }

    @Test fun verySmallReadabilitySettingsCannotMakeTheUniformFallbackSubpixel() {
        val spans = listOf(MinuteSpan(480, 525), MinuteSpan(600, 646))
        listOf(1f, 2f).forEach { density ->
            val axis = TimetableAxis(emptyList(), spans, .0001f, minimumLessonHeight = 0f, heightSmoothing = .0001f, minimumHeightStep = 1f / density + .001f)
            assertPixelHeightsStrictlyIncrease(axis, spans, density)
        }
    }

    @Test fun longerCardsStayTallerWhenOtherDaysStretchShorterIntervals() {
        val crowded = MinuteSpan(480, 525)
        val nested = MinuteSpan(500, 520)
        val quiet = MinuteSpan(600, 645)
        val longer = MinuteSpan(840, 930)
        val spans = listOf(crowded, nested, quiet, longer)
        val axis = TimetableAxis(periods, spans)
        assertTrue(axis.lessonHeight(longer) > axis.lessonHeight(crowded))
        assertTrue(axis.lessonHeight(longer) > axis.lessonHeight(quiet))
        spans.forEach { assertCardFits(axis, it) }
        assertTrue(axis.y(nested.start) > axis.y(crowded.start))
        assertTrue(axis.y(nested.end) < axis.y(crowded.end))
    }

    @Test fun continuousLessonsProtectTheirInternalBreaksAndFitAcrossEveryDay() {
        val continuous = MinuteSpan(480, 580) // 08:00 to 09:40, including the 10-minute break.
        val otherDay = MinuteSpan(535, 580)
        val axis = TimetableAxis(periods, listOf(continuous, otherDay))
        assertTrue(axis.lessonHeight(continuous) > axis.lessonHeight(otherDay))
        assertCardFits(axis, continuous)
        assertCardFits(axis, otherDay)
        assertTrue(axis.y(535) - axis.y(525) >= 16f - .001f)
        assertEquals(axis.y(580) - axis.y(535), axis.lessonHeight(otherDay), .001f)
    }

    @Test fun lunchGapsCompressButAllTwelvePeriodLabelsKeepTheirPositions() {
        val axis = TimetableAxis(periods, emptyList())
        assertEquals(480, axis.startMinute)
        assertEquals(1350, axis.endMinute)
        assertEquals(6f, axis.y(840) - axis.y(700), .001f)
        periods.forEach { period ->
            val start = LocalTime.parse(period.start).minuteOfDay()
            val end = LocalTime.parse(period.end).minuteOfDay()
            assertEquals(72f, axis.y(end) - axis.y(start), .001f)
        }
    }

    @Test fun everyMinuteInterpolatesWithinItsOwnAxisSegmentIncludingCompressedGaps() {
        val spans = listOf(MinuteSpan(471, 536), MinuteSpan(623, 699), MinuteSpan(825, 934))
        val axis = TimetableAxis(periods, spans)
        val boundaries = (periods.flatMap {
            listOf(LocalTime.parse(it.start).minuteOfDay(), LocalTime.parse(it.end).minuteOfDay())
        } + spans.flatMap { listOf(it.start, it.end) }).distinct().sorted()
        boundaries.zipWithNext().forEach { (start, end) ->
            val from = axis.y(start)
            val to = axis.y(end)
            for (minute in start..end) {
                val expected = from + (to - from) * (minute - start) / (end - start)
                assertEquals("Minute $minute must stay on its own segment", expected, axis.y(minute), .001f)
            }
        }
        assertEquals(0f, axis.y(boundaries.first() - 1), 0f)
        assertEquals(axis.height, axis.y(boundaries.last() + 1), 0f)
        assertEquals(0f, TimetableAxis(emptyList(), emptyList()).y(600), 0f)
    }

    @Test fun lunchCrossingLessonsStayInsideTheirReservedSpan() {
        val span = MinuteSpan(660, 900)
        val axis = TimetableAxis(periods, listOf(span))
        assertCardFits(axis, span)
        assertTrue(axis.y(840) - axis.y(700) > 6f)
    }

    @Test fun customTimesOutsidePeriodsRemainVisible() {
        val early = MinuteSpan(450, 480)
        val late = MinuteSpan(1350, 1380)
        val axis = TimetableAxis(periods, listOf(early, late))
        assertEquals(450, axis.startMinute)
        assertEquals(1380, axis.endMinute)
        assertCardFits(axis, early)
        assertCardFits(axis, late)
        assertTrue(axis.y(early.start) + axis.lessonHeight(early) <= axis.height)
        assertTrue(axis.y(late.start) + axis.lessonHeight(late) <= axis.height + .001f)
    }

    @Test fun adjacentAndSeparatedCardsNeverCoverTheFollowingLesson() {
        val spans = listOf(MinuteSpan(480, 525), MinuteSpan(525, 615), MinuteSpan(620, 785), MinuteSpan(840, 885))
        val axis = TimetableAxis(periods, spans)
        spans.zipWithNext().forEach { (previous, next) ->
            assertTrue(axis.y(previous.start) + axis.lessonHeight(previous) <= axis.y(next.start) + .001f)
        }
        spans.forEach { assertCardFits(axis, it) }
        (axis.startMinute until axis.endMinute).forEach { minute ->
            assertTrue("Axis must preserve the order at $minute", axis.y(minute + 1) > axis.y(minute))
        }
    }

    @Test fun staggeredOverlapsShareOrderedCoordinatesAndAllHaveEnoughRoom() {
        val spans = listOf(MinuteSpan(480, 525), MinuteSpan(500, 590), MinuteSpan(520, 685), MinuteSpan(590, 635))
        val axis = TimetableAxis(periods, spans)
        spans.forEach { assertCardFits(axis, it) }
        spans.flatMap { listOf(it.start, it.end) }.distinct().sorted().zipWithNext().forEach { (first, second) ->
            assertTrue(axis.y(first) < axis.y(second))
        }
        assertTrue(axis.lessonHeight(spans[2]) > axis.lessonHeight(spans[1]))
        assertTrue(axis.lessonHeight(spans[1]) > axis.lessonHeight(spans[0]))
        assertTrue(axis.lessonHeight(spans[1]) > axis.lessonHeight(spans[3]))
    }

    @Test fun sharedReadableScaleExpandsEveryCardWithoutChangingDurationOrder() {
        val spans = listOf(MinuteSpan(480, 525), MinuteSpan(525, 615), MinuteSpan(620, 785))
        val normal = TimetableAxis(periods, spans)
        val large = TimetableAxis(periods, spans, 3.2f, gapHeight = 12f, minimumLessonHeight = 128f, heightSmoothing = 16f)
        spans.forEach { span ->
            assertEquals(normal.requiredLessonHeight(span) * 2, large.requiredLessonHeight(span), .001f)
            assertTrue(large.lessonHeight(span) > normal.lessonHeight(span))
            assertCardFits(large, span)
        }
    }

    @Test fun timetableAndTodayShareTheDurationReadabilityCurveWithTheirOwnCalibration() {
        val span = MinuteSpan(480, 580)
        val weekly = TimetableAxis(emptyList(), listOf(span))
        assertEquals(160f, weekly.requiredLessonHeight(span), .001f)
        assertEquals(readableCourseHeight(100, 64f, 1.6f, 8f), weekly.requiredLessonHeight(span), .001f)
        assertEquals(84f, readableCourseHeight(100, 76f, .84f, 2f), .05f)
    }

    @Test fun twoPeriodsKeepTheirHeightAndProportionalInternalTicks() {
        val span = MinuteSpan(480, 580)
        val axis = TimetableAxis(periods, listOf(span))
        assertEquals(160f, axis.lessonHeight(span), .001f)
        assertEquals(72f, axis.y(525) - axis.y(480), .001f)
        assertEquals(16f, axis.y(535) - axis.y(525), .001f)
        assertEquals(72f, axis.y(580) - axis.y(535), .001f)
    }

    @Test fun ordinaryMixedWeekKeepsTwoPeriodsCloseToTheirPreviousHeight() {
        val twoPeriods = MinuteSpan(480, 580)
        val separateTwoPeriods = MinuteSpan(840, 940)
        val spans = listOf(MinuteSpan(420, 450), MinuteSpan(480, 525), MinuteSpan(535, 580),
            twoPeriods, separateTwoPeriods, MinuteSpan(1_140, 1_320))
        val axis = TimetableAxis(periods, spans)
        // Both 45-minute cards on other days share the axis and each needs about 2.5 dp extra.
        assertEquals(165.012f, axis.lessonHeight(twoPeriods), .001f)
        assertEquals(160f, axis.lessonHeight(separateTwoPeriods), .001f)
        spans.forEach { assertCardFits(axis, it) }
        listOf(1f, 2f, 3f).forEach { assertPixelHeightsStrictlyIncrease(axis, spans, it) }
    }

    @Test fun mergedConsecutivePeriodsReserveTheWholeDisplayedPythonCard() {
        fun periodOccurrence(start: String, end: String, first: Int, last: Int): Occurrence {
            val item = occurrence("Python", start, end)
            return item.copy(lesson = item.lesson.copy(startPeriod = first, endPeriod = last, startTime = null, endTime = null))
        }
        val first = periodOccurrence("09:50", "11:25", 3, 4)
        val second = periodOccurrence("11:35", "12:35", 5, 6)
        val following = occurrence("Following class", "12:35", "13:20")
        val groups = timetableGroups(listOf(first, second, following))
        assertEquals(2, groups.size)
        val merged = groups.first()
        assertEquals(MinuteSpan(590, 755), merged.span)
        assertEquals(setOf(first, second), merged.occurrences.toSet())
        val axis = TimetableAxis(periods, groups.map { it.span })
        assertCardFits(axis, merged.span)
        assertTrue(axis.y(merged.span.start) + axis.lessonHeight(merged.span) <= axis.y(groups.last().span.start) + .001f)
        assertTrue(axis.lessonHeight(merged.span) > axis.lessonHeight(groups.last().span))
        assertTrue(axis.lessonHeight(merged.span) >= axis.requiredLessonHeight(merged.span) - .001f)
    }

    @Test fun tinyNestedLessonsChooseTheMoreCompactUniformFallback() {
        val shorter = MinuteSpan(480, 525)
        val longer = MinuteSpan(600, 646)
        val nested = (0..5).map { MinuteSpan(480 + it * 8, 481 + it * 8) }
        val spans = nested + listOf(shorter, longer)
        val axis = TimetableAxis(periods, spans)
        assertTrue(axis.usesUniformFallback)
        assertTrue("Fallback must avoid the pathological correction's > 30,000dp axis", axis.height < 10_000f)
        assertTrue(axis.lessonHeight(longer) > axis.lessonHeight(shorter))
        nested.forEach { assertTrue(axis.lessonHeight(shorter) > axis.lessonHeight(it)) }
        spans.forEach { assertCardFits(axis, it) }
    }

    @Test fun overlapsRemainAccessibleInGroupsWithoutOverlappingOtherGroups() {
        val items = listOf(occurrence("A", "08:00", "09:00"), occurrence("B", "08:30", "10:00"), occurrence("C", "10:00", "10:45"))
        val groups = timetableGroups(items)
        assertEquals(listOf(2, 1), groups.map { it.occurrences.size })
        assertEquals(items.toSet(), groups.flatMap { it.occurrences }.toSet())
        assertEquals(groups[0].end, groups[1].start)
        val columns = timetableColumns(336f, 7)
        assertEquals(336f, columns.dayLeft(6) + columns.dayWidth, .001f)
    }

    @Test fun sameCourseInDifferentWeeksBecomesOneCardWithoutConflict() {
        val first = occurrence("Math", "09:00", "10:00", teacher = "Teacher One")
        val second = occurrence("Math", "09:00", "10:00", weeks = listOf(2), teacher = "Teacher Two", statuses = setOf(OccurrenceStatus.OUT_OF_WEEK))
        val group = timetableGroups(listOf(second, first)).single()
        assertEquals(1, group.courses.size)
        assertEquals(2, group.courses.single().occurrences.size)
        assertEquals(listOf(1, 2), group.courses.single().weeks)
        assertEquals("Teacher One", group.representative.lesson.teacher)
        assertEquals(setOf("Teacher One", "Teacher Two"), group.occurrences.map { it.lesson.teacher }.toSet())
        assertFalse(group.hasConflict)
        assertEquals(0, group.conflictCourseCount)
    }

    @Test fun differentCoursesInDifferentWeeksShareAnAccessiblePositionWithoutConflict() {
        val first = occurrence("A", "09:00", "10:00")
        val second = occurrence("B", "09:00", "10:00", weeks = listOf(2), statuses = setOf(OccurrenceStatus.OUT_OF_WEEK))
        val group = timetableGroups(listOf(first, second)).single()
        assertEquals(2, group.courses.size)
        assertEquals(setOf(first, second), group.occurrences.toSet())
        assertFalse(group.hasConflict)
        assertEquals(0, group.conflictCourseCount)
    }

    @Test fun differentCoursesActuallyScheduledInTheSameWeekAndTimeConflict() {
        val group = timetableGroups(listOf(occurrence("A", "09:00", "10:00"), occurrence("B", "09:30", "10:30"))).single()
        assertTrue(group.hasConflict)
        assertEquals(2, group.conflictCourseCount)
    }

    @Test fun otherWeekCoursesDoNotInflateActualConflictCount() {
        val group = timetableGroups(listOf(
            occurrence("A", "09:00", "10:00"),
            occurrence("B", "09:00", "10:00"),
            occurrence("C", "09:00", "10:00", weeks = listOf(2), statuses = setOf(OccurrenceStatus.OUT_OF_WEEK)),
        )).single()
        assertEquals(3, group.courses.size)
        assertTrue(group.hasConflict)
        assertEquals(2, group.conflictCourseCount)
    }

    @Test fun holidayCancellationsAreNotActualConflicts() {
        val group = timetableGroups(listOf(
            occurrence("A", "09:00", "10:00", statuses = setOf(OccurrenceStatus.HOLIDAY)),
            occurrence("B", "09:00", "10:00", statuses = setOf(OccurrenceStatus.HOLIDAY)),
        )).single()
        assertFalse(group.hasConflict)
        assertEquals(0, group.conflictCourseCount)
    }

    @Test fun anOtherWeekBridgeCannotTurnSeparateCurrentLessonsIntoAConflict() {
        val group = timetableGroups(listOf(
            occurrence("A", "08:00", "09:00"),
            occurrence("B", "08:30", "10:00", weeks = listOf(2), statuses = setOf(OccurrenceStatus.OUT_OF_WEEK)),
            occurrence("C", "09:30", "10:30"),
        )).single()
        assertEquals(3, group.courses.size)
        assertFalse(group.hasConflict)
        assertEquals(0, group.conflictCourseCount)
    }

    @Test fun adjacentLessonsDoNotOverlap() {
        val groups = timetableGroups(listOf(occurrence("A", "09:00", "10:00"), occurrence("B", "10:00", "11:00")))
        assertEquals(2, groups.size)
        assertTrue(groups.none { it.hasConflict })
    }

    @Test fun differentDatesNeverFormTheSamePositionOrConflict() {
        val first = occurrence("A", "09:00", "10:00")
        val second = occurrence("B", "09:00", "10:00").copy(date = first.date.plusDays(7), week = 2)
        val groups = timetableGroups(listOf(first, second))
        assertEquals(2, groups.size)
        assertTrue(groups.none { it.hasConflict })
    }

    @Test fun actualDatedAndRecurringLessonsOnTheSameDisplayedDateCanConflict() {
        val recurring = occurrence("A", "09:00", "10:00", statuses = setOf(OccurrenceStatus.MAKEUP))
        val ordinary = occurrence("B", "09:00", "10:00")
        val dated = ordinary.copy(lesson = ordinary.lesson.copy(date = ordinary.date.toString(), weeks = emptyList()), week = 2)
        // A make-up source week can differ from the date's display week; both still occur today.
        val group = timetableGroups(listOf(recurring, dated)).single()
        assertTrue(group.hasConflict)
        assertEquals(2, group.conflictCourseCount)
    }

    @Test fun aLongerOtherWeekRecordNeverExtendsTheCurrentCoursesFortyFiveMinuteTile() {
        val today = occurrence("Math", "09:00", "09:45")
        val other = occurrence("Math", "08:45", "10:15", weeks = listOf(2), statuses = setOf(OccurrenceStatus.OUT_OF_WEEK))
        val group = timetableGroups(listOf(other, today)).single()
        assertEquals(1, group.courses.size)
        assertEquals(today.start, group.start)
        assertEquals(today.end, group.end)
        val axis = TimetableAxis(periods, listOf(group.span))
        assertTrue(axis.lessonHeight(group.span) >= axis.requiredLessonHeight(MinuteSpan(0, 45)) - .001f)
        assertCardFits(axis, group.span)
        assertEquals(setOf(today, other), group.occurrences.toSet())
    }

    @Test fun aLongOtherWeekRecordCannotBridgeTwoSeparateCurrentArrangementsOfTheSameCourse() {
        val morning = occurrence("Math", "09:00", "09:45")
        val later = occurrence("Math", "10:00", "10:45")
        val other = occurrence("Math", "08:45", "11:00", weeks = listOf(2), statuses = setOf(OccurrenceStatus.OUT_OF_WEEK))
        val groups = timetableGroups(listOf(other, later, morning))
        assertEquals(2, groups.size)
        assertEquals(listOf(morning.start, later.start), groups.map { it.start })
        assertEquals(listOf(morning.end, later.end), groups.map { it.end })
        assertEquals(setOf(morning, later, other), groups.flatMap { it.occurrences }.toSet())
        assertTrue(groups.none { it.hasConflict })
    }

    private fun assertPixelHeightsStrictlyIncrease(axis: TimetableAxis, spans: List<MinuteSpan>, density: Float) {
        spans.forEach { shorter ->
            spans.filter { it.end - it.start > shorter.end - shorter.start }.forEach { longer ->
                val shortPixels = (axis.lessonHeight(shorter) * density).roundToInt()
                val longPixels = (axis.lessonHeight(longer) * density).roundToInt()
                assertTrue("$longer must be visibly taller than $shorter at density $density ($longPixels vs $shortPixels)", longPixels > shortPixels)
            }
        }
    }

    private fun assertCardFits(axis: TimetableAxis, span: MinuteSpan) {
        assertEquals("Card $span must end at its real end tick", axis.y(span.end), axis.y(span.start) + axis.lessonHeight(span), .001f)
        assertTrue("Card $span must satisfy its readable minimum", axis.lessonHeight(span) >= axis.requiredLessonHeight(span) - .001f)
    }

    private fun occurrence(
        name: String,
        start: String,
        end: String,
        weeks: List<Int> = listOf(1),
        teacher: String = "",
        statuses: Set<OccurrenceStatus> = emptySet(),
    ): Occurrence {
        val lesson = Lesson(weeks = weeks, startTime = start, endTime = end, teacher = teacher)
        return Occurrence(Course(name = name, lessons = listOf(lesson)), lesson, LocalDate.of(2026, 9, 7), LocalTime.parse(start), LocalTime.parse(end), 1, statuses)
    }
}
