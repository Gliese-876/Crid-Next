package cn.crid.next.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.*
import org.junit.Test

class CompleteSemesterWeeksTest {
    @Test fun mondayWeeksResolveExactlyFromEachPairOfFields() {
        assertAllPairs("2026-09-07", "2026-10-04", 4, false)
        assertAllPairs("2026-09-07", "2026-09-13", 1, false)
    }

    @Test fun sundayWeeksResolveExactlyFromEachPairOfFields() {
        assertAllPairs("2026-09-06", "2026-10-03", 4, true)
        assertAllPairs("2026-09-06", "2026-09-12", 1, true)
    }

    @Test fun completeWeeksCrossYearsAndLeapDayWithoutRemainders() {
        assertAllPairs("2026-12-28", "2027-01-10", 2, false)
        assertAllPairs("2026-12-27", "2027-01-09", 2, true)
        assertAllPairs("2028-02-28", "2028-03-12", 2, false)
        assertAllPairs("2028-02-27", "2028-03-11", 2, true)
    }

    @Test fun everyNonBoundaryWeekdayIsRejectedIncludingSevenDayButMisalignedRanges() {
        for (sunday in listOf(false, true)) {
            val first = LocalDate.parse(if (sunday) "2026-09-06" else "2026-09-07")
            for (offset in 1L..6L) {
                val incorrect = first.plusDays(offset)
                rejects { SemesterDates.resolve(incorrect.toString(), null, 1, sunday) }
                rejects { SemesterDates.resolve(null, incorrect.plusDays(6).toString(), 1, sunday) }
                rejects { SemesterDates.resolve(incorrect.toString(), incorrect.plusDays(6).toString(), 1, sunday) }
            }
        }
        assertEquals(DayOfWeek.MONDAY, SemesterDates.startWeekday(false))
        assertEquals(DayOfWeek.SUNDAY, SemesterDates.endWeekday(false))
        assertEquals(DayOfWeek.SUNDAY, SemesterDates.startWeekday(true))
        assertEquals(DayOfWeek.SATURDAY, SemesterDates.endWeekday(true))
    }

    @Test fun aThirdFieldMustAgreeWithTheExactNumberOfWholeWeeks() {
        rejects { SemesterDates.resolve("2026-09-07", "2026-09-20", 1, false) }
        rejects { SemesterDates.resolve("2026-09-06", "2026-09-19", 3, true) }
        rejects { SemesterDates.resolve("2026-09-07", "2026-09-19", 2, false) }
        rejects { SemesterDates.resolve("2026-09-06", "2026-09-20", 2, true) }
        rejects { SemesterDates.resolve("2026-09-07", null, 0, false) }
        rejects { SemesterDates.resolve("2026-09-14", "2026-09-13", null, false) }
        rejects { SemesterDates.resolve("2026-02-30", null, 2, false) }
        rejects { SemesterDates.resolve("2026-09-07", null, null, false) }
    }

    @Test fun bothPoliciesKeepTheLastTwoEditsAuthoritative() {
        for (sunday in listOf(false, true)) {
            val start = LocalDate.parse(if (sunday) "2026-09-06" else "2026-09-07")
            val initial = SemesterEditor(weekStartsSunday = sunday).editStart(start.toString()).editWeeks(2)
            assertEquals(start.plusDays(13).toString(), initial.end)
            val endEdit = initial.editEnd(start.plusDays(20).toString())
            assertEquals(SemesterField.START, endEdit.derivedField)
            assertEquals(start.plusDays(7).toString(), endEdit.start)
            val startEdit = endEdit.editStart(start.toString())
            assertEquals(SemesterField.WEEKS, startEdit.derivedField)
            assertEquals(3, startEdit.weeks)
            assertEquals(start.toString(), startEdit.start)
            assertEquals(start.plusDays(20).toString(), startEdit.end)
            assertEquals(2, initial.weeks)
        }
    }

    @Test fun openingLegacyPartialWeeksKeepsValuesAndRequiresAnExplicitCorrection() {
        val legacy = SemesterEditor("2026-09-08", "2026-09-20", 2, weekStartsSunday = false)
        assertEquals("2026-09-08", legacy.start)
        assertEquals("2026-09-20", legacy.end)
        assertNull(legacy.result)
        assertEquals(listOf("起始日请选择周一"), legacy.errors)
        val corrected = legacy.editStart("2026-09-07")
        assertEquals(SemesterDates.Result("2026-09-07", "2026-09-20", 2), corrected.result)
        assertEquals("2026-09-08", legacy.start)
        // Existing files retain their inclusive partial-week behavior until explicitly edited.
        assertEquals(2, SemesterDates.resolve(legacy.start, legacy.end, legacy.weeks).weeks)
    }

    @Test fun changingTheEditorPolicyDoesNotMoveHistoricalDates() {
        val monday = SemesterEditor("2026-09-07", "2026-09-20", 2, weekStartsSunday = false)
        val sunday = monday.copy(weekStartsSunday = true)
        assertNull(sunday.result)
        assertEquals(monday.start, sunday.start)
        assertEquals(monday.end, sunday.end)
        assertEquals(monday.weeks, sunday.weeks)
        val corrected = sunday.editStart("2026-09-06").editEnd("2026-09-19")
        assertEquals(SemesterDates.Result("2026-09-06", "2026-09-19", 2), corrected.result)
    }

    @Test fun invalidEditsCannotReuseAnEarlierSaveableResult() {
        val initial = SemesterEditor(weekStartsSunday = false).editStart("2026-09-07").editWeeks(2)
        assertNull(initial.editEnd("2026-09-19").result)
        assertNull(initial.editStart("2026-09-08").result)
        assertNull(initial.editWeeks(0).result)
        assertNotNull(initial.result)
    }

    @Test fun theFinalMondayOfASundayStartedFourWeekTermIsTeachingWeekFour() {
        val dates = SemesterDates.resolve("2026-09-06", null, 4, true)
        val semester = Semester("sunday-term", "Autumn", dates.startDate, dates.endDate, dates.weeks,
            listOf(Period(1, "08:00", "08:45")))
        val course = Course(name = "Final week seminar", lessons = listOf(
            Lesson(weeks = listOf(4), weekday = 1, startPeriod = 1, endPeriod = 1)))
        val plan = Plan(semesterId = semester.id, name = "My timetable", courses = listOf(course))
        val occurrences = ScheduleEngine.occurrences(semester, plan, LocalDate.of(2026, 9, 28),
            Settings(weekStartsSunday = true, holidaysEnabled = false), HolidayCalendar())
        assertEquals(1, occurrences.size)
        assertTrue(occurrences.single().isActual)
        assertEquals(4, occurrences.single().week)
    }

    private fun assertAllPairs(start: String, end: String, weeks: Int, sunday: Boolean) {
        val expected = SemesterDates.Result(start, end, weeks)
        assertEquals(expected, SemesterDates.resolve(start, end, weeks, sunday))
        assertEquals(expected, SemesterDates.resolve(start, end, null, sunday))
        assertEquals(expected, SemesterDates.resolve(start, null, weeks, sunday))
        assertEquals(expected, SemesterDates.resolve(null, end, weeks, sunday))
        assertEquals(weeks * 7L, ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1)
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected invalid semester boundaries to be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
