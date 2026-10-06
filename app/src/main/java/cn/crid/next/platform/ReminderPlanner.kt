package cn.crid.next.platform

import cn.crid.next.core.AppState
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.Occurrence
import cn.crid.next.core.ScheduleEngine
import cn.crid.next.core.MakeupMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure planning shared by scheduling and delivery, independently testable without an Android clock. */
internal object ReminderPlanner {
    const val MAX_PENDING = 64

    fun nextDue(state: AppState, calendar: HolidayCalendar, now: Instant, zone: ZoneId): Long? =
        upcomingDues(state, calendar, now, zone).firstOrNull()

    /** Independent alarms survive a missed callback; a fixed cap bounds system resources. */
    fun upcomingDues(state: AppState, calendar: HolidayCalendar, now: Instant, zone: ZoneId): List<Long> =
        upcomingOccurrences(state, calendar, now, zone).keys.toList()

    /** Keep the resolved classes so snapshot creation never has to plan each alarm a second time. */
    fun upcomingOccurrences(state: AppState, calendar: HolidayCalendar, now: Instant, zone: ZoneId): Map<Long, List<Occurrence>> {
        if (!state.settings.remindersEnabled) return emptyMap()
        val semester = state.semester ?: return emptyMap()
        val plan = state.plan ?: return emptyMap()
        val first = maxOf(now.atZone(zone).toLocalDate(), LocalDate.parse(semester.startDate))
        val semesterEnd = LocalDate.parse(semester.endDate)
        if (first.isAfter(semesterEnd)) return emptyMap()
        val weekStart = ScheduleEngine.weekStart(LocalDate.parse(semester.startDate), state.settings.weekStartsSunday)
        val sourceDates = plan.courses.asSequence().flatMap { it.lessons.asSequence() }
            .filterNot { it.unscheduled }.flatMap { lesson ->
                if (lesson.date != null) sequenceOf(LocalDate.parse(lesson.date)) else lesson.weeks.asSequence().mapNotNull { week ->
                    val offset = if (state.settings.weekStartsSunday) lesson.weekday % 7 else lesson.weekday - 1
                    runCatching { weekStart.plusWeeks(week.toLong() - 1L).plusDays(offset.toLong()) }.getOrNull()
                }
            }
        val makeupDates = if (state.settings.holidaysEnabled && state.settings.makeupMode == MakeupMode.ON)
            calendar.days.asSequence().filter { it.workday && it.teachingDate != null }
                .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() } else emptySequence()
        val dates = (sourceDates + makeupDates).filter { !it.isBefore(first) && !it.isAfter(semesterEnd) }
            .distinct().sorted()
        val schedule = ScheduleEngine.prepare(semester, plan, state.settings, calendar)
        val upcoming = mutableMapOf<Long, MutableList<Occurrence>>()
        val nowMillis = now.toEpochMilli()
        for (date in dates) {
            for (occurrence in schedule.occurrences(date)) {
                if (!occurrence.isActual) continue
                val due = trigger(occurrence, state, zone)
                if (due <= nowMillis) continue
                val simultaneous = upcoming[due]
                if (simultaneous != null) simultaneous.add(occurrence)
                else if (upcoming.size < MAX_PENDING) upcoming[due] = mutableListOf(occurrence)
            }
            // Finish the last date so all classes sharing the final alarm are retained.
            if (upcoming.size == MAX_PENDING) break
        }
        return upcoming.toSortedMap()
    }

    fun dueOccurrences(state: AppState, calendar: HolidayCalendar, due: Long, now: Instant, zone: ZoneId): List<Occurrence> {
        if (due <= 0 || due > now.toEpochMilli() || !state.settings.remindersEnabled) return emptyList()
        val semester = state.semester ?: return emptyList()
        val plan = state.plan ?: return emptyList()
        val lessonDate = Instant.ofEpochMilli(due).plusSeconds(offset(state)).atZone(zone).toLocalDate()
        return ScheduleEngine.occurrences(semester, plan, lessonDate, state.settings, calendar)
            .filter { it.isActual && trigger(it, state, zone) == due && it.date.atTime(it.end).atZone(zone).toInstant() > now }
    }

    private fun trigger(occurrence: Occurrence, state: AppState, zone: ZoneId): Long = occurrence.date
        .atTime(occurrence.start).atZone(zone).toInstant().minusSeconds(offset(state)).toEpochMilli()

    private fun offset(state: AppState): Long = state.settings.reminderMinutes.coerceIn(0, 1440) * 60L
}
