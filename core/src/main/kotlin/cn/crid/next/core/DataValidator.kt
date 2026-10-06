package cn.crid.next.core

import java.time.LocalDate
import java.time.LocalTime

/** Validation is shared by local parsers, model-assisted parsing and import transactions. */
object DataValidator {
    const val MAX_COURSES = 500
    const val MAX_LESSONS = 3000
    const val MAX_WEEK_ENTRIES = 366

    fun validateWorkload(courses: List<Course>): List<String> = buildList {
        if (courses.size > MAX_COURSES) add("一次最多处理 $MAX_COURSES 门课程，请拆分课表文件")
        if (courses.sumOf { it.lessons.size.toLong() } > MAX_LESSONS) add("一个方案最多处理 $MAX_LESSONS 项授课安排，请拆分课表文件")
        if (courses.any { course -> course.lessons.any { it.weeks.size > MAX_WEEK_ENTRIES } }) add("单项授课安排最多包含 $MAX_WEEK_ENTRIES 个周次，请检查课表文件")
        if (courses.any { it.name.length > 300 || it.credits.length > 100 || it.extra.size > 100 || it.extra.any { field -> field.key.length > 100 || field.value.length > 8000 } || it.lessons.any { lesson -> lesson.location.length > 1000 || lesson.teacher.length > 1000 || lesson.note.length > 8000 } })
            add("部分课程文字过长，请精简课程名称或附加信息后再导入")
    }

    fun validateSemester(semester: Semester): List<String> = buildList {
        if (semester.id.isBlank()) add("学期标识不能为空")
        if (semester.name.isBlank()) add("请填写学期名称")
        runCatching { SemesterDates.resolve(semester.startDate, semester.endDate, semester.weeks) }
            .exceptionOrNull()?.let { add(it.message ?: "学期日期无效") }
        if (semester.periods.isEmpty()) add("请设置作息时间")
        val periods = semester.periods.sortedBy { it.number }
        if (periods.map { it.number } != (1..periods.size).toList()) add("作息节次编号必须从 1 开始连续且不重复")
        var previousEnd: LocalTime? = null
        periods.forEach { period ->
            val start = parseTime(period.start)
            val end = parseTime(period.end)
            if (start == null || end == null || !start.isBefore(end)) add("第 ${period.number} 节的起止时间无效")
            else {
                if (previousEnd?.isAfter(start) == true) add("第 ${period.number} 节与上一节的时间重叠")
                previousEnd = end
            }
        }
    }.distinct()

    fun validateCourses(courses: List<Course>, semester: Semester? = null): List<String> {
        val workloadErrors = validateWorkload(courses)
        if (workloadErrors.isNotEmpty()) return workloadErrors
        return buildList {
        if (courses.isEmpty()) add("没有可导入的课程")
        val first = semester?.startDate?.let(::parseDate)
        val last = semester?.endDate?.let(::parseDate)
        val maxWeek = if (semester != null && first != null && last != null && !first.isAfter(last))
            maxOf(ScheduleEngine.weekNumber(semester, last, false) ?: 0, ScheduleEngine.weekNumber(semester, last, true) ?: 0) else null
        courses.forEachIndexed { index, course ->
            val label = course.name.trim().ifBlank { "第 ${index + 1} 门课程" }
            if (courseKey(course.name).isBlank()) add("第 ${index + 1} 门课程缺少课程名")
            if (course.lessons.isEmpty()) add("$label：缺少可确定的授课安排")
            course.lessons.forEachIndexed { lessonIndex, lesson ->
                val prefix = "$label（安排 ${lessonIndex + 1}）"
                if (lesson.unscheduled) {
                    if (lesson.weekday != 0 || lesson.date != null || lesson.startPeriod != null || lesson.endPeriod != null || lesson.startTime != null || lesson.endTime != null)
                        add("$prefix：待排课安排不能同时指定星期、日期、节次或时间")
                } else if (lesson.weekday !in 1..7) add("$prefix：星期必须在 1 至 7 之间")
                if (lesson.date == null && lesson.weeks.isEmpty()) add("$prefix：缺少授课周次或具体日期")
                if (lesson.weeks.any { it < 1 }) add("$prefix：周次必须为正整数")
                if (maxWeek != null && lesson.weeks.any { it > maxWeek }) add("$prefix：授课周次超出目标学期，请调整目标学期或授课安排")
                lesson.date?.let { value ->
                    val date = parseDate(value)
                    if (date == null) add("$prefix：授课日期无效")
                    else if ((first != null && date.isBefore(first)) || (last != null && date.isAfter(last)))
                        add("$prefix：授课日期超出目标学期")
                }
                val hasPeriods = lesson.startPeriod != null || lesson.endPeriod != null
                val hasTimes = lesson.startTime != null || lesson.endTime != null
                if (!lesson.unscheduled && !hasPeriods && !hasTimes) add("$prefix：缺少节次或起止时间")
                if (hasPeriods) {
                    val start = lesson.startPeriod
                    val end = lesson.endPeriod
                    if (start == null || end == null || start < 1 || end < start) add("$prefix：起止节次无效")
                    else if (semester != null && (start..end).any { number -> semester.periods.none { it.number == number } })
                        add("$prefix：节次无法对应目标学期作息，请先调整作息")
                }
                if (hasTimes) {
                    val start = lesson.startTime?.let(::parseTime)
                    val end = lesson.endTime?.let(::parseTime)
                    if (start == null || end == null || !start.isBefore(end)) add("$prefix：起止时间无效")
                    else if (hasPeriods && semester != null) {
                        val periodStart = semester.periods.find { it.number == lesson.startPeriod }?.start?.let(::parseTime)
                        val periodEnd = semester.periods.find { it.number == lesson.endPeriod }?.end?.let(::parseTime)
                        if ((periodStart != null && periodStart != start) || (periodEnd != null && periodEnd != end))
                            add("$prefix：指定时间与目标学期节次时间不一致，请确认后保留一种时间表达")
                    }
                }
            }
        }
        }.distinct()
    }

    internal fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }
        .getOrNull()?.takeIf { it.second == 0 && it.nano == 0 }
    internal fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
}
