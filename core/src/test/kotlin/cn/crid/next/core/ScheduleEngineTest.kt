package cn.crid.next.core

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class ScheduleEngineTest {
    private val semester = Semester(
        id = "semester", name = "秋季", startDate = "2026-09-02", endDate = "2026-10-13", weeks = 6,
        periods = listOf(Period(1, "08:00", "08:45"), Period(2, "09:00", "09:45"), Period(3, "10:00", "10:45")),
    )

    @Test fun `teaching weeks follow the natural week containing the start date`() {
        assertEquals(1, ScheduleEngine.weekNumber(semester, date("2026-09-02")))
        assertEquals(1, ScheduleEngine.weekNumber(semester, date("2026-09-06")))
        assertEquals(2, ScheduleEngine.weekNumber(semester, date("2026-09-07")))
        assertEquals(7, ScheduleEngine.weekNumber(semester, date("2026-10-13")))
        assertNull(ScheduleEngine.weekNumber(semester, date("2026-09-01")))
        assertNull(ScheduleEngine.weekNumber(semester, date("2026-10-14")))
    }

    @Test fun `Sunday week boundary changes the viewing week without altering semester dates`() {
        assertEquals(date("2026-08-31"), ScheduleEngine.weekStart(date("2026-09-02")))
        assertEquals(date("2026-08-30"), ScheduleEngine.weekStart(date("2026-09-02"), true))
        assertEquals(2, ScheduleEngine.weekNumber(semester, date("2026-09-06"), true))
        assertEquals("2026-09-02", semester.startDate)
        assertEquals("2026-10-13", semester.endDate)
        assertEquals(6, semester.weeks)
    }

    @Test fun `the last partial natural week is not truncated by duration weeks`() {
        val short = semester.copy(startDate = "2026-09-02", endDate = "2026-09-08", weeks = 1)
        val plan = plan(lesson(weeks = listOf(2), weekday = 1))
        val occurrences = ScheduleEngine.occurrences(short, plan, date("2026-09-07"), Settings(), HolidayCalendar())
        assertEquals(1, occurrences.size)
        assertTrue(occurrences.single().isActual)
        assertEquals(2, occurrences.single().week)
    }

    @Test fun `dates outside the semester never produce even ghost occurrences`() {
        val plan = plan(lesson(weekday = 2))
        assertTrue(ScheduleEngine.occurrences(semester, plan, date("2026-09-01"), Settings(), HolidayCalendar()).isEmpty())
        assertTrue(ScheduleEngine.occurrences(semester, plan, date("2026-10-20"), Settings(), HolidayCalendar()).isEmpty())
    }

    @Test fun `single day queries outside the semester skip the course index entirely`() {
        val unvisitedCourses = object : AbstractList<Course>() {
            override val size: Int get() = error("Unrelated courses must not be inspected")
            override fun get(index: Int): Course = error("Unrelated courses must not be inspected")
        }
        val source = plan(lesson()).copy(courses = unvisitedCourses)
        for (day in listOf("2026-09-01", "2026-10-14")) {
            assertTrue(ScheduleEngine.occurrences(semester, source, date(day), Settings(), HolidayCalendar()).isEmpty())
        }
        val unrelated = semester.copy(id = "other", startDate = "invalid", endDate = "invalid")
        assertTrue(ScheduleEngine.occurrences(unrelated, source, date("2026-09-07"), Settings(), HolidayCalendar()).isEmpty())
    }

    @Test fun `single lesson explicit times do not inspect the semester period table`() {
        val unvisitedPeriods = object : AbstractList<Period>() {
            override val size: Int get() = error("Explicit times do not need period lookup")
            override fun get(index: Int): Period = error("Explicit times do not need period lookup")
        }
        val explicit = Lesson(startTime = "09:00", endTime = "10:00")
        assertEquals(LocalTime.of(9, 0) to LocalTime.of(10, 0),
            ScheduleEngine.lessonTimes(semester.copy(periods = unvisitedPeriods), explicit))
    }

    @Test fun `single day query does not index weeks for unrelated weekdays or fixed dates`() {
        val unvisitedWeeks = object : AbstractList<Int>() {
            override val size: Int get() = error("Unrelated teaching weeks must not be indexed")
            override fun get(index: Int): Int = error("Unrelated teaching weeks must not be indexed")
        }
        val monday = lesson()
        val source = plan(monday, lesson(weekday = 2).copy(weeks = unvisitedWeeks),
            lesson().copy(date = "2026-09-08", weeks = unvisitedWeeks))
        assertEquals(listOf(monday), occurrences(source, "2026-09-07").map { it.lesson })
    }

    @Test fun `single day and reusable indexes agree across holiday makeup and week settings`() {
        val source = plan(lesson(), lesson(weeks = listOf(3), weekday = 6),
            lesson().copy(date = "2026-09-07"), lesson().copy(date = "2026-09-19"))
        val calendar = HolidayCalendar(days = listOf(
            HolidayDay("2026-09-07", "法定假日", statutory = true),
            HolidayDay("2026-09-19", "补课日", workday = true, teachingDate = "2026-09-07"),
            HolidayDay("2026-09-20", "未确认补课日", workday = true, teachingDate = "2026-08-01"),
        ))
        for (sunday in listOf(false, true)) for (holidays in listOf(false, true)) {
            for (mode in MakeupMode.entries) for (showOtherWeeks in listOf(false, true)) {
                val settings = Settings(weekStartsSunday = sunday, holidaysEnabled = holidays,
                    makeupMode = mode, showOutOfWeek = showOtherWeeks)
                val schedule = ScheduleEngine.prepare(semester, source, settings, calendar)
                for (day in listOf("2026-09-06", "2026-09-07", "2026-09-14", "2026-09-19", "2026-09-20", "2026-09-21")) {
                    assertEquals("$day / $settings", schedule.occurrences(date(day)), occurrences(source, day, settings, calendar))
                }
            }
        }
    }

    @Test fun `period range includes the internal break in its displayed duration`() {
        val occurrence = occurrences(plan(lesson(startPeriod = 1, endPeriod = 2)), "2026-09-07").single()
        assertEquals(LocalTime.of(8, 0), occurrence.start)
        assertEquals(LocalTime.of(9, 45), occurrence.end)
        assertEquals(105L, occurrence.durationMinutes)
    }

    @Test fun `exact start and end times retain their real duration`() {
        val occurrence = occurrences(plan(Lesson(weeks = listOf(2), weekday = 1, startTime = "14:10", endTime = "15:40")), "2026-09-07").single()
        assertEquals(90L, occurrence.durationMinutes)
        assertEquals(LocalTime.of(14, 10), occurrence.start)
    }

    @Test fun `non current week lessons are optional and never actual classes`() {
        val plan = plan(lesson(weeks = listOf(1)))
        val ghost = occurrences(plan, "2026-09-07").single()
        assertEquals(setOf(OccurrenceStatus.OUT_OF_WEEK), ghost.statuses)
        assertFalse(ghost.isActual)
        assertTrue(occurrences(plan, "2026-09-07", Settings(showOutOfWeek = false)).isEmpty())
    }

    @Test fun `specific date lessons appear only on that date despite weekday or ghost setting`() {
        val scheduled = Lesson(date = "2026-09-08", weekday = 1, startTime = "14:00", endTime = "15:00")
        val plan = plan(scheduled)
        assertTrue(occurrences(plan, "2026-09-07").isEmpty())
        assertTrue(occurrences(plan, "2026-09-15").isEmpty())
        val actual = occurrences(plan, "2026-09-08").single()
        assertTrue(actual.isActual)
        assertEquals(date("2026-09-08"), actual.date)
    }

    @Test fun `disabling holidays ignores statutory rest extra rest and makeup mappings`() {
        val plan = plan(lesson(weekday = 1), lesson(weekday = 2, startPeriod = 2, endPeriod = 2))
        val calendar = HolidayCalendar(days = listOf(HolidayDay("2026-09-07", "休假", statutory = true, extraRest = true, workday = true, teachingDate = "2026-09-08")))
        val actual = occurrences(plan, "2026-09-07", Settings(holidaysEnabled = false, makeupMode = MakeupMode.ON), calendar).single()
        assertEquals(1, actual.lesson.weekday)
        assertTrue(actual.statuses.isEmpty())
        assertTrue(actual.isActual)
    }

    @Test fun `all enabled holiday modes mark statutory holidays as no class`() {
        val calendar = HolidayCalendar(days = listOf(HolidayDay("2026-09-07", "法定假日", statutory = true)))
        for (mode in MakeupMode.values()) {
            val occurrence = occurrences(plan(lesson()), "2026-09-07", Settings(makeupMode = mode), calendar).single()
            assertEquals(setOf(OccurrenceStatus.HOLIDAY), occurrence.statuses)
            assertEquals("法定假日", occurrence.holidayName)
            assertFalse(occurrence.isActual)
        }
    }

    @Test fun `extra rest days apply only when a makeup mode is enabled`() {
        val calendar = HolidayCalendar(days = listOf(HolidayDay("2026-09-07", "调休休假", extraRest = true)))
        val plan = plan(lesson())
        assertTrue(occurrences(plan, "2026-09-07", Settings(makeupMode = MakeupMode.OFF), calendar).single().isActual)
        for (mode in listOf(MakeupMode.HOLIDAYS_ONLY, MakeupMode.ON)) {
            assertEquals(setOf(OccurrenceStatus.HOLIDAY), occurrences(plan, "2026-09-07", Settings(makeupMode = mode), calendar).single().statuses)
        }
    }

    @Test fun `no makeup mode preserves lessons already scheduled on a weekend`() {
        val saturday = lesson(weekday = 6)
        val calendar = HolidayCalendar(days = listOf(HolidayDay("2026-09-12", "补班日", workday = true, teachingDate = "2026-09-07")))
        val occurrence = occurrences(plan(saturday), "2026-09-12", Settings(makeupMode = MakeupMode.HOLIDAYS_ONLY), calendar).single()
        assertEquals(saturday, occurrence.lesson)
        assertTrue(occurrence.statuses.isEmpty())
        assertTrue(occurrence.isActual)
    }

    @Test fun `confirmed makeup mapping uses the mapped teaching weekday and week`() {
        val monday = lesson(weeks = listOf(2), weekday = 1)
        val saturday = lesson(weeks = listOf(3), weekday = 6, startPeriod = 2, endPeriod = 2)
        val calendar = HolidayCalendar(days = listOf(HolidayDay("2026-09-19", "补课日", workday = true, teachingDate = "2026-09-07")))
        val occurrence = occurrences(plan(monday, saturday), "2026-09-19", Settings(makeupMode = MakeupMode.ON, showOutOfWeek = false), calendar).single()
        assertEquals(monday, occurrence.lesson)
        assertEquals(date("2026-09-19"), occurrence.date)
        assertEquals(2, occurrence.week)
        assertEquals(setOf(OccurrenceStatus.MAKEUP), occurrence.statuses)
        assertTrue(occurrence.isActual)
    }

    @Test fun `a workday without a confirmed teaching mapping does not invent makeup courses`() {
        val calendar = HolidayCalendar(days = listOf(HolidayDay("2026-09-12", "补班日", workday = true)))
        assertTrue(occurrences(plan(lesson(weekday = 1)), "2026-09-12", Settings(makeupMode = MakeupMode.ON), calendar).isEmpty())
    }

    @Test fun `holiday and out of week statuses are retained together`() {
        val calendar = HolidayCalendar(days = listOf(HolidayDay("2026-09-07", "休假", statutory = true)))
        val occurrence = occurrences(plan(lesson(weeks = listOf(1))), "2026-09-07", Settings(), calendar).single()
        assertEquals(setOf(OccurrenceStatus.OUT_OF_WEEK, OccurrenceStatus.HOLIDAY), occurrence.statuses)
        assertFalse(occurrence.isActual)
    }

    @Test fun `prepared schedule preserves source order across fixed and recurring equal slots`() {
        val day = date("2026-09-07")
        val recurring = lesson().copy(teacher = "Recurring")
        val fixed = recurring.copy(date = day.toString(), weeks = emptyList(), teacher = "Dated")
        for (source in listOf(listOf(fixed, recurring), listOf(recurring, fixed))) {
            val schedule = ScheduleEngine.prepare(semester, plan(*source.toTypedArray()), Settings(), HolidayCalendar())
            assertEquals(source, schedule.occurrences(day).map { it.lesson })
            assertEquals(listOf(recurring), schedule.occurrences(day.plusWeeks(1)).map { it.lesson })
            assertEquals(source, schedule.occurrences(day).map { it.lesson })
        }
    }

    @Test fun `prepared makeup query keeps dated lessons on their own date`() {
        val monday = lesson()
        val fixedMonday = monday.copy(date = "2026-09-07", teacher = "Monday only")
        val fixedSaturday = monday.copy(date = "2026-09-19", teacher = "Saturday only")
        val calendar = HolidayCalendar(days = listOf(HolidayDay("2026-09-19", "补课日", workday = true, teachingDate = "2026-09-07")))
        val schedule = ScheduleEngine.prepare(semester, plan(monday, fixedMonday, fixedSaturday), Settings(makeupMode = MakeupMode.ON), calendar)
        val occurrences = schedule.occurrences(date("2026-09-19"))
        assertEquals(listOf(monday, fixedSaturday), occurrences.map { it.lesson })
        assertEquals(listOf(2, 3), occurrences.map { it.week })
        assertEquals(setOf(OccurrenceStatus.MAKEUP), occurrences.first().statuses)
        assertTrue(occurrences.last().statuses.isEmpty())
    }

    @Test fun `prepared indexes retain first duplicate holiday and period entries`() {
        val duplicatePeriods = semester.copy(periods = semester.periods + Period(1, "15:00", "16:00"))
        val calendar = HolidayCalendar(days = listOf(
            HolidayDay("2026-09-07", "First", statutory = true),
            HolidayDay("2026-09-07", "Second", workday = true),
        ))
        val occurrence = ScheduleEngine.prepare(duplicatePeriods, plan(lesson()), Settings(), calendar).occurrences(date("2026-09-07")).single()
        assertEquals("First", occurrence.holidayName)
        assertEquals(LocalTime.of(8, 0), occurrence.start)
        assertEquals(LocalTime.of(8, 45), occurrence.end)
    }

    @Test fun `prepared schedule skips invalid times and keeps period fallback`() {
        val fallback = lesson().copy(startTime = "invalid", endTime = "08:45:30")
        val invalid = Lesson(weeks = listOf(2), weekday = 1, startTime = "invalid", endTime = "09:30")
        val schedule = ScheduleEngine.prepare(semester, plan(fallback, invalid), Settings(), HolidayCalendar())
        assertEquals(listOf(fallback), schedule.occurrences(date("2026-09-07")).map { it.lesson })
        assertEquals(LocalTime.of(8, 0) to LocalTime.of(8, 45), ScheduleEngine.lessonTimes(semester, fallback))
        assertNull(ScheduleEngine.lessonTimes(semester, invalid))
    }

    @Test fun `cross semester plan remains empty without parsing unrelated semester dates`() {
        val unrelated = semester.copy(id = "other", startDate = "invalid", endDate = "invalid")
        val schedule = ScheduleEngine.prepare(unrelated, plan(lesson()), Settings(weekStartsSunday = true), HolidayCalendar())
        assertTrue(schedule.occurrences(date("2026-09-07")).isEmpty())
    }

    private fun lesson(weeks: List<Int> = listOf(2), weekday: Int = 1, startPeriod: Int = 1, endPeriod: Int = 1) =
        Lesson(weeks = weeks, weekday = weekday, startPeriod = startPeriod, endPeriod = endPeriod)

    private fun plan(vararg lessons: Lesson) = Plan(id = "plan", semesterId = semester.id, name = "课程表", courses = listOf(Course(id = "course", name = "课程", lessons = lessons.toList())))

    private fun occurrences(plan: Plan, day: String, settings: Settings = Settings(), calendar: HolidayCalendar = HolidayCalendar()) =
        ScheduleEngine.occurrences(semester, plan, date(day), settings, calendar)

    private fun date(value: String): LocalDate = LocalDate.parse(value)
}
