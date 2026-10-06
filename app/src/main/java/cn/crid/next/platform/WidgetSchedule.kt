package cn.crid.next.platform

import cn.crid.next.core.AppState
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.Occurrence
import cn.crid.next.core.ScheduleEngine
import cn.crid.next.core.displayCourses
import java.time.LocalDate

/** One immutable input snapshot shared by all widget instances and sizes in a refresh. */
internal class WidgetSchedule(state: AppState, calendar: HolidayCalendar) {
    private val schedule by lazy(LazyThreadSafetyMode.NONE) {
        val semester = state.semester
        val plan = state.plan
        if (semester == null || plan == null) null
        else ScheduleEngine.prepare(semester, plan, state.settings, calendar)
    }
    private val coursesByDate = mutableMapOf<LocalDate, List<Occurrence>>()

    fun courses(date: LocalDate): List<Occurrence> = coursesByDate.getOrPut(date) {
        displayCourses(schedule?.occurrences(date).orEmpty()).map { it.representative }
    }
}
