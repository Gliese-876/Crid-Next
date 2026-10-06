package cn.crid.next.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Shared by the application, widgets, notifications and visual export. */
object ScheduleEngine {
    fun weekStart(date: LocalDate, weekStartsSunday: Boolean = false): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(if (weekStartsSunday) DayOfWeek.SUNDAY else DayOfWeek.MONDAY))

    fun weekNumber(semester: Semester, date: LocalDate, weekStartsSunday: Boolean = false): Int? {
        val first = LocalDate.parse(semester.startDate)
        if (date.isBefore(first) || date.isAfter(LocalDate.parse(semester.endDate))) return null
        return (ChronoUnit.DAYS.between(weekStart(first, weekStartsSunday), weekStart(date, weekStartsSunday)) / 7L + 1L).toInt()
    }

    /** Reuse one prepared snapshot when displaying or scanning more than one day. */
    fun prepare(semester: Semester, plan: Plan, settings: Settings, calendar: HolidayCalendar): PreparedSchedule =
        PreparedSchedule(semester, plan, settings, calendar)

    fun occurrences(semester: Semester, plan: Plan, date: LocalDate, settings: Settings, calendar: HolidayCalendar): List<Occurrence> {
        if (plan.semesterId != semester.id || weekNumber(semester, date, settings.weekStartsSunday) == null) return emptyList()
        return PreparedSchedule(semester, plan, settings, calendar, date).occurrences(date)
    }

    fun lessonTimes(semester: Semester, lesson: Lesson): Pair<LocalTime, LocalTime>? {
        if (lesson.unscheduled) return null
        val start = lesson.startTime?.let(DataValidator::parseTime)
            ?: semester.periods.firstOrNull { it.number == lesson.startPeriod }?.start?.let(DataValidator::parseTime)
            ?: return null
        val end = lesson.endTime?.let(DataValidator::parseTime)
            ?: semester.periods.firstOrNull { it.number == lesson.endPeriod }?.end?.let(DataValidator::parseTime)
            ?: return null
        if (!start.isBefore(end)) return null
        return start to end
    }
}

/** An immutable schedule index. A new instance picks up changes to courses, settings or holidays. */
class PreparedSchedule internal constructor(
    semester: Semester, plan: Plan, private val settings: Settings, calendar: HolidayCalendar,
    private val queryDate: LocalDate? = null,
) {
    private val matchesSemester = plan.semesterId == semester.id
    private val first = if (matchesSemester) LocalDate.parse(semester.startDate) else LocalDate.MIN
    private val last = if (matchesSemester) LocalDate.parse(semester.endDate) else LocalDate.MIN
    private val firstWeek = if (matchesSemester) ScheduleEngine.weekStart(first, settings.weekStartsSunday) else first
    private val holidays = buildMap {
        if (settings.holidaysEnabled) calendar.days.forEach { if (it.date !in this) put(it.date, it) }
    }
    private val queryDay = queryDate?.let(::resolveDay)
    private val recurring = mutableMapOf<Int, MutableList<ScheduledLesson>>()
    private val dated = mutableMapOf<String, MutableList<ScheduledLesson>>()

    private data class ScheduledLesson(
        val course: Course, val lesson: Lesson, val start: LocalTime, val end: LocalTime,
        val courseKey: String, val order: Int, val weeks: Collection<Int>,
    )

    private data class TeachingDay(
        val dateText: String, val displayWeek: Int, val sourceWeek: Int, val weekday: Int,
        val isMakeup: Boolean, val onHoliday: Boolean, val holidayName: String?,
    )

    init {
        if (matchesSemester && (queryDate == null || queryDay != null)) {
            val times = LessonTimeResolver(semester)
            var order = 0
            plan.courses.forEach { course ->
                var key: String? = null
                course.lessons.forEach lessonLoop@ { lesson ->
                    // A one-day request only resolves records relevant to its teaching date.
                    if (queryDay != null) {
                        val matchesDay = lesson.date?.let { it == queryDay.dateText } ?: (lesson.weekday == queryDay.weekday)
                        if (!matchesDay) return@lessonLoop
                    }
                    val time = times.resolve(lesson)
                    if (time != null) {
                        val name = key ?: courseKey(course.name).also { key = it }
                        val weeks = if (queryDate == null) lesson.weeks.toSet() else lesson.weeks
                        val record = ScheduledLesson(course, lesson, time.first, time.second, name, order++, weeks)
                        if (lesson.date != null) dated.getOrPut(lesson.date) { mutableListOf() }.add(record)
                        else recurring.getOrPut(lesson.weekday) { mutableListOf() }.add(record)
                    }
                }
            }
            recurring.values.forEach { it.sortWith(scheduledOrder) }
            dated.values.forEach { it.sortWith(scheduledOrder) }
        }
    }

    private fun weekNumber(date: LocalDate): Int? =
        if (!matchesSemester || date.isBefore(first) || date.isAfter(last)) null
        else (ChronoUnit.DAYS.between(firstWeek, date) / 7L + 1L).toInt()

    private fun resolveDay(date: LocalDate): TeachingDay? {
        val displayWeek = weekNumber(date) ?: return null
        val dateText = date.toString()
        val day = holidays[dateText]
        val onHoliday = day?.let { it.statutory || (it.extraRest && settings.makeupMode != MakeupMode.OFF) } == true
        val teachingDate = if (settings.holidaysEnabled && settings.makeupMode == MakeupMode.ON && day?.workday == true)
            day.teachingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } else null
        val mappedWeek = teachingDate?.let(::weekNumber)
        // Unconfirmed or out-of-semester mappings never invent make-up classes.
        val sourceDate = teachingDate.takeIf { mappedWeek != null } ?: date
        return TeachingDay(dateText, displayWeek, mappedWeek ?: displayWeek, sourceDate.dayOfWeek.value,
            sourceDate != date, onHoliday, day?.name.takeIf { onHoliday })
    }

    fun occurrences(date: LocalDate): List<Occurrence> {
        val day = (if (queryDate == null) resolveDay(date) else queryDay?.takeIf { date == queryDate }) ?: return emptyList()
        val weekly = recurring[day.weekday].orEmpty()
        val fixed = dated[day.dateText].orEmpty()
        // Both inputs are already ordered. Merge them while preserving source order for equal slots.
        var weeklyIndex = 0
        var fixedIndex = 0
        return buildList(weekly.size + fixed.size) {
            while (weeklyIndex < weekly.size || fixedIndex < fixed.size) {
                val useWeekly = fixedIndex == fixed.size || (weeklyIndex < weekly.size &&
                    scheduledOrder.compare(weekly[weeklyIndex], fixed[fixedIndex]) <= 0)
                val record = if (useWeekly) weekly[weeklyIndex++] else fixed[fixedIndex++]
                val outOfWeek = useWeekly && day.sourceWeek !in record.weeks
                if (outOfWeek && !settings.showOutOfWeek) continue
                val statuses = buildSet {
                    if (outOfWeek) add(OccurrenceStatus.OUT_OF_WEEK)
                    if (day.onHoliday) add(OccurrenceStatus.HOLIDAY)
                    if (day.isMakeup && useWeekly) add(OccurrenceStatus.MAKEUP)
                }
                add(Occurrence(record.course, record.lesson, date, record.start, record.end,
                    if (useWeekly) day.sourceWeek else day.displayWeek, statuses, day.holidayName))
            }
        }
    }

    private companion object {
        val scheduledOrder = compareBy<ScheduledLesson> { it.start }.thenBy { it.end }.thenBy { it.courseKey }.thenBy { it.order }
    }
}

/** Resolve period numbers and clock strings once per batch, retaining the first duplicate period. */
internal class LessonTimeResolver(semester: Semester) {
    private val periods = buildMap { semester.periods.forEach { if (it.number !in this) put(it.number, it) } }
    private val parsedTimes = mutableMapOf<String, LocalTime?>()

    private fun parse(value: String): LocalTime? {
        if (value !in parsedTimes) parsedTimes[value] = DataValidator.parseTime(value)
        return parsedTimes[value]
    }

    fun resolve(lesson: Lesson): Pair<LocalTime, LocalTime>? {
        if (lesson.unscheduled) return null
        val start = lesson.startTime?.let(::parse)
            ?: periods[lesson.startPeriod]?.start?.let(::parse)
            ?: return null
        val end = lesson.endTime?.let(::parse)
            ?: periods[lesson.endPeriod]?.end?.let(::parse)
            ?: return null
        if (!start.isBefore(end)) return null
        return start to end
    }
}
