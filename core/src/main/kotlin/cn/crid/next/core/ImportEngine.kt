package cn.crid.next.core

/** A preview is pure, and apply returns one complete replacement state or throws before mutation. */
object ImportEngine {
    fun preview(result: ParseResult, semester: Semester, current: Plan?, mode: ImportMode): ImportPreview {
        val warnings = result.warnings.toMutableList()
        val inputLimits = DataValidator.validateWorkload(result.courses) +
            if (mode == ImportMode.MERGE && current != null) DataValidator.validateWorkload(current.courses) else emptyList()
        if (inputLimits.isNotEmpty()) return ImportPreview(emptyList(), warnings, (result.errors + result.unresolved + inputLimits).distinct(), 0, 0)
        val errors = (result.errors + result.unresolved + DataValidator.validateSemester(semester) + DataValidator.validateCourses(result.courses, semester)).toMutableList()
        if (mode != ImportMode.NEW && current == null) errors += "请选择要合并或替换的方案"
        if (mode != ImportMode.NEW && current != null && current.semesterId != semester.id) errors += "当前方案不属于目标学期"
        result.sourceSemester?.takeIf { it.isNotBlank() }?.let { source ->
            val sourceTerm = identifyTerm(source)
            val targetTerm = identifyTerm(semester.name) ?: DataValidator.parseDate(semester.startDate)?.let { it.year to (if (it.monthValue >= 7) "秋" else "春") }
            if (sourceTerm != null && targetTerm != null && sourceTerm != targetTerm)
                errors += "文件中的学期（$source）与目标学期不同，请确认目标学期后重新导入"
            else if (sourceTerm == null && courseKey(source) != courseKey(semester.name))
                warnings += "文件标注的学期为“$source”，请核对目标学期"
        }
        if (mode == ImportMode.REPLACE && current != null)
            warnings += "将替换“${current.name}”中的 ${current.courses.size} 门课程、${current.courses.sumOf { it.lessons.size }} 项授课安排"

        val grouped = linkedMapOf<String, Course>()
        val knownLessons = mutableMapOf<String, MutableSet<Lesson>>()
        var duplicates = 0
        var conflicts = 0
        fun addCourse(source: Course, report: Boolean) {
            val incoming = source.withReadableText()
            val key = courseKey(incoming.name)
            val normalizedLessons = incoming.lessons.map { it.copy(weeks = it.weeks.distinct().sorted()) }
            val previous = grouped[key]
            if (previous == null) {
                val uniqueLessons = normalizedLessons.toMutableSet()
                duplicates += normalizedLessons.size - uniqueLessons.size
                grouped[key] = incoming.copy(name = incoming.name.trim(), lessons = uniqueLessons.toList())
                knownLessons[key] = uniqueLessons
                return
            }
            val lessons = previous.lessons.toMutableList()
            val known = knownLessons.getValue(key)
            normalizedLessons.forEach { lesson ->
                if (!known.add(lesson)) duplicates++
                else {
                    if (report && lessons.isNotEmpty()) {
                        warnings += "${previous.name}：同名课程的不同授课安排均已保留，请核对时间、地点与教师"
                    }
                    lessons += lesson
                }
            }
            val extras = previous.extra.toMutableMap()
            fun preserveExtra(label: String, value: String) {
                if (value.isBlank() || extras[label] == value) return
                var name = label
                var suffix = 2
                while (name in extras && extras[name] != value) name = "$label（${suffix++}）"
                extras[name] = value
            }
            incoming.extra.forEach { (label, value) -> preserveExtra(label, value) }
            val credits = previous.credits.ifBlank { incoming.credits }
            if (previous.credits.isNotBlank() && incoming.credits.isNotBlank() && previous.credits != incoming.credits) {
                preserveExtra("其他学分记录", incoming.credits)
                warnings += "${previous.name}：学分信息不同，已保留原有值及导入记录"
            }
            grouped[key] = previous.copy(credits = credits, extra = extras, lessons = lessons)
        }
        if (mode == ImportMode.MERGE && current?.semesterId == semester.id) current.courses.forEach { addCourse(it, false) }
        result.courses.forEach { addCourse(it, true) }
        val mergedLimits = DataValidator.validateWorkload(grouped.values.toList())
        if (mergedLimits.isNotEmpty()) return ImportPreview(emptyList(), warnings.distinct(), (errors + mergedLimits).distinct(), duplicates, conflicts)
        val courses = CourseColors.assign(grouped.values.toList())
        courses.forEach { course ->
            val pending = course.lessons.count { it.unscheduled }
            if (pending > 0) warnings += "${course.name}：$pending 项安排尚未确定上课时间，已保留周次、教师等信息"
            if (course.lessons.any { it.location.isBlank() }) warnings += "${course.name}：部分安排未提供地点"
            if (course.lessons.any { it.teacher.isBlank() }) warnings += "${course.name}：部分安排未提供教师"
        }
        // Different arrangements of one course are retained above. Only distinct courses
        // with intersecting teaching dates and clock times count as time conflicts.
        val times = LessonTimeResolver(semester)
        val validSemesterDates = DataValidator.parseDate(semester.startDate) != null && DataValidator.parseDate(semester.endDate) != null
        val records = courses.flatMap { course ->
            val key = courseKey(course.name)
            course.lessons.mapNotNull { lesson ->
                times.resolve(lesson)?.let { time ->
                    val date = lesson.date?.let(DataValidator::parseDate)
                    TimedRecord(course, lesson, time.first, time.second, key, date?.dayOfWeek?.value,
                        date?.takeIf { validSemesterDates }?.let { ScheduleEngine.weekNumber(semester, it) })
                }
            }
        }.sortedBy { it.start }
        var comparisons = 0
        var overlapWarnings = 0
        var scanLimited = false
        scan@ for ((index, record) in records.withIndex()) {
            for (otherIndex in index + 1 until records.size) {
                val other = records[otherIndex]
                if (!other.start.isBefore(record.end)) break
                if (++comparisons > 100_000) { scanLimited = true; break@scan }
                if (record.courseKey != other.courseKey && datesOverlap(record, other)) {
                    conflicts++
                    if (overlapWarnings++ < 50) warnings += "${record.course.name}与${other.course.name}存在时间重叠，请核对授课安排"
                }
            }
        }
        if (overlapWarnings > 50) warnings += "已列出前 50 处时间重叠，共确认 $overlapWarnings 处"
        if (scanLimited) warnings += "授课安排较密集，冲突数量仅为已确认下限（至少 $conflicts 处）；请继续按日期核对课表"
        return ImportPreview(courses, warnings.distinct(), errors.distinct(), duplicates, conflicts)
    }

    fun apply(state: AppState, result: ParseResult, semesterId: String, planId: String?, mode: ImportMode, planName: String): AppState {
        require(state.semesters.map { it.id }.distinct().size == state.semesters.size && state.plans.map { it.id }.distinct().size == state.plans.size) {
            "已有数据包含重复标识，请先修复数据"
        }
        val semester = state.semesters.find { it.id == semesterId } ?: throw IllegalArgumentException("目标学期不存在")
        val current = planId?.let { id -> state.plans.find { it.id == id && it.semesterId == semesterId } }
        require(mode == ImportMode.NEW || current != null) { "请选择目标学期中的方案" }
        val preview = preview(result, semester, current, mode)
        require(preview.valid) { preview.errors.joinToString("；") }
        val name = planName.trim().ifBlank { current?.name ?: result.name.trim() }
        require(name.isNotBlank()) { "方案名称不能为空" }
        val courseIds = current?.courses?.associate { courseKey(it.name) to it.id }.orEmpty()
        val courses = preview.courses.map { it.copy(id = if (mode == ImportMode.MERGE) courseIds[courseKey(it.name)] ?: newId() else newId()) }
        val plan = Plan(id = if (mode == ImportMode.NEW) newId() else requireNotNull(current).id, semesterId = semesterId, name = name, courses = courses)
        val plans = if (mode == ImportMode.NEW) state.plans + plan else state.plans.map { if (it.id == plan.id) plan else it }
        return state.copy(plans = plans, selectedSemesterId = semesterId, selectedPlanId = plan.id)
    }

    private fun identifyTerm(name: String): Pair<Int, String>? {
        val years = Regex("20\\d{2}").findAll(name).map { it.value.toInt() }.toList()
        val first = years.firstOrNull() ?: return null
        return when {
            name.contains("秋") || Regex("第?[一1]学期").containsMatchIn(name.replace(" ", "")) -> first to "秋"
            name.contains("春") || Regex("第?[二2]学期").containsMatchIn(name.replace(" ", "")) -> (if (years.size > 1) years[1] else first) to "春"
            else -> null
        }
    }

    private data class TimedRecord(
        val course: Course, val lesson: Lesson, val start: java.time.LocalTime, val end: java.time.LocalTime,
        val courseKey: String, val fixedWeekday: Int?, val fixedWeek: Int?,
    )

    private fun datesOverlap(a: TimedRecord, b: TimedRecord): Boolean {
        if (a.lesson.date != null && b.lesson.date != null) return a.lesson.date == b.lesson.date
        if (a.lesson.date != null || b.lesson.date != null) {
            val fixed = if (a.lesson.date != null) a else b
            val weekly = if (a.lesson.date == null) a else b
            return fixed.fixedWeekday == weekly.lesson.weekday && fixed.fixedWeek in weekly.lesson.weeks
        }
        return a.lesson.weekday == b.lesson.weekday && weeksOverlap(a.lesson.weeks, b.lesson.weeks)
    }
}
