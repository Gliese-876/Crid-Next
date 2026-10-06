package cn.crid.next.platform

import cn.crid.next.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WidgetScheduleTest {
    private val today = LocalDate.parse("2026-10-12")
    private val semester = Semester("s", "Fall", "2026-10-12", "2026-10-31", 3,
        listOf(Period(1, "08:00", "08:45"), Period(2, "09:00", "09:45")))
    private val lessons = (1..2).map { period ->
        Lesson(weeks = listOf(1), weekday = 1, startPeriod = period, endPeriod = period, location = "A101")
    }
    private val plan = Plan("p", "s", "Plan", listOf(Course("c", "Class", lessons = lessons)))
    private val state = AppState(listOf(semester), listOf(plan), "s", "p")

    @Test fun repeatedWidgetVariantsReuseTheSameGroupedDay() {
        val schedule = WidgetSchedule(state, HolidayCalendar())
        val courses = schedule.courses(today)
        assertEquals(1, courses.size)
        assertEquals(LocalTime.of(8, 0), courses.single().start)
        assertEquals(LocalTime.of(9, 45), courses.single().end)
        assertSame(courses, schedule.courses(today))
        val tomorrow = schedule.courses(today.plusDays(1))
        assertTrue(tomorrow.isEmpty())
        assertSame(tomorrow, schedule.courses(today.plusDays(1)))
    }

    @Test fun nextRefreshUsesChangedHolidayAndPlanWithoutReusingOldCourses() {
        val original = WidgetSchedule(state, HolidayCalendar()).courses(today).single()
        val holiday = HolidayCalendar(days = listOf(HolidayDay(today.toString(), "Rest", statutory = true)))
        val holidayCourses = WidgetSchedule(state, holiday).courses(today)
        assertTrue(original.isActual)
        assertFalse(holidayCourses.single().isActual)
        val editedPlan = plan.copy(courses = listOf(plan.courses.single().copy(
            lessons = lessons.map { it.copy(location = "B202") })))
        val edited = WidgetSchedule(state.copy(plans = listOf(editedPlan)), HolidayCalendar()).courses(today).single()
        assertEquals("A101", original.lesson.location)
        assertEquals("B202", edited.lesson.location)
        assertTrue(WidgetSchedule(state.copy(plans = emptyList()), HolidayCalendar()).courses(today).isEmpty())
    }
}
