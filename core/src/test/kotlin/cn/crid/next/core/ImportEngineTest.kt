package cn.crid.next.core

import org.junit.Assert.*
import org.junit.Test

class ImportEngineTest {
    private val semester = Semester("fall", "2026 秋季", "2026-09-07", "2026-10-18", 6, listOf(Period(1, "08:00", "08:45"), Period(2, "09:00", "09:45")))
    private val lesson = Lesson(weeks = listOf(1, 2), weekday = 1, startPeriod = 1, endPeriod = 2, location = "主楼", teacher = "张老师")
    private val course = Course(id = "existing-course", name = "Math", lessons = listOf(lesson), color = CourseColors.colorFor("Math"))
    private val plan = Plan("original", semester.id, "原方案", listOf(course))
    private val other = Plan("other", semester.id, "其他方案", listOf(course.copy(name = "另一门课")))
    private val state = AppState(listOf(semester), listOf(plan, other), semester.id, plan.id)

    @Test fun `course identity ignores case width and outer whitespace`() {
        assertEquals(courseKey(" Math "), courseKey("ＭＡＴＨ"))
        val parsed = ParseResult("导入", listOf(course.copy(name = "  ＭＡＴＨ ")))
        val preview = ImportEngine.preview(parsed, semester, plan, ImportMode.MERGE)
        assertTrue(preview.valid)
        assertEquals(1, preview.courses.size)
        assertEquals(1, preview.courses.single().lessons.size)
        assertEquals(1, preview.duplicates)
        assertEquals(course.color, preview.courses.single().color)
    }

    @Test fun `week list order does not create a second equivalent arrangement`() {
        val parsed = ParseResult("导入", listOf(course.copy(lessons = listOf(lesson.copy(weeks = listOf(2, 1, 1))))))
        val preview = ImportEngine.preview(parsed, semester, plan, ImportMode.MERGE)
        assertEquals(1, preview.duplicates)
        assertEquals(1, preview.courses.single().lessons.size)
    }

    @Test fun `same name preserves different teachers locations weeks and times`() {
        val variations = listOf(lesson.copy(teacher = "李老师"), lesson.copy(location = "分楼"), lesson.copy(weeks = listOf(3)), lesson.copy(endPeriod = 1))
        val parsed = ParseResult("导入", listOf(course.copy(lessons = variations)))
        val preview = ImportEngine.preview(parsed, semester, plan, ImportMode.MERGE)
        assertTrue(preview.valid)
        assertEquals(5, preview.courses.single().lessons.size)
        assertEquals(0, preview.conflicts)
        assertTrue(preview.warnings.any { it.contains("不同授课安排") })
    }

    @Test fun `same named course in different weeks merges without a time conflict or lost assignments`() {
        val later = lesson.copy(weeks = listOf(3, 4), teacher = "李老师", location = "分楼", note = "后半学期安排")
        val parsed = ParseResult("导入", listOf(course.copy(name = " ＭＡＴＨ ", lessons = listOf(later))))
        val preview = ImportEngine.preview(parsed, semester, plan, ImportMode.MERGE)
        assertTrue(preview.valid)
        assertEquals(0, preview.conflicts)
        assertEquals(1, preview.courses.size)
        assertEquals(listOf(lesson, later), preview.courses.single().lessons)
        val updated = ImportEngine.apply(state, parsed, semester.id, plan.id, ImportMode.MERGE, plan.name)
        assertEquals(course.id, updated.plan!!.courses.single().id)
        assertEquals(listOf(lesson, later), updated.plan!!.courses.single().lessons)
    }

    @Test fun `different courses at the same clock time conflict only in shared teaching weeks`() {
        val differentWeeks = course.copy(name = "Physics", lessons = listOf(lesson.copy(weeks = listOf(3, 4))))
        val noConflict = ImportEngine.preview(ParseResult("导入", listOf(course, differentWeeks)), semester, null, ImportMode.NEW)
        assertTrue(noConflict.valid)
        assertEquals(0, noConflict.conflicts)
        assertFalse(noConflict.warnings.any { it.contains("时间重叠") })
        val sharedWeek = differentWeeks.copy(lessons = listOf(lesson.copy(weeks = listOf(2, 3))))
        assertEquals(1, ImportEngine.preview(ParseResult("导入", listOf(course, sharedWeek)), semester, null, ImportMode.NEW).conflicts)
    }

    @Test fun `dated courses conflict only with an actual matching recurring date and time`() {
        fun dated(date: String, start: String = "08:30", end: String = "09:30") = Course(name = "Guest lecture",
            lessons = listOf(Lesson(date = date, startTime = start, endTime = end)))
        fun conflicts(item: Course) = ImportEngine.preview(ParseResult("导入", listOf(course, item)), semester, null, ImportMode.NEW).conflicts
        assertEquals(1, conflicts(dated("2026-09-14"))) // Monday in the recurring course's second week.
        assertEquals(0, conflicts(dated("2026-09-21"))) // Monday in a different week.
        assertEquals(0, conflicts(dated("2026-09-15"))) // Same teaching week, different weekday.
        assertEquals(0, conflicts(dated("2026-09-14", "09:45", "10:30"))) // Adjacent clock times do not overlap.
    }

    @Test fun `interleaved and duplicate week lists preserve exact conflict counting`() {
        val first = course.copy(lessons = listOf(lesson.copy(weeks = listOf(5, 1, 3, 3))))
        val second = course.copy(name = "Physics", lessons = listOf(lesson.copy(weeks = listOf(6, 4, 2, 2))))
        fun preview(other: Course) = ImportEngine.preview(ParseResult("导入", listOf(first, other)), semester, null, ImportMode.NEW)
        assertEquals(0, preview(second).conflicts)
        assertEquals(1, preview(second.copy(lessons = listOf(lesson.copy(weeks = listOf(6, 3, 2))))).conflicts)
    }

    @Test fun `pending records retain full fields and cannot introduce time conflicts`() {
        val pending = Lesson(weeks = listOf(1, 2, 3), weekday = 0, teacher = "待排教师", location = "待定教室", note = "来源保留", unscheduled = true)
        val incoming = course.copy(lessons = listOf(pending))
        val preview = ImportEngine.preview(ParseResult("导入", listOf(incoming)), semester, plan, ImportMode.MERGE)
        assertTrue(preview.valid)
        assertEquals(0, preview.conflicts)
        assertEquals(listOf(lesson, pending), preview.courses.single().lessons)
    }

    @Test fun `conflicting metadata is retained rather than overwritten`() {
        val current = plan.copy(courses = listOf(course.copy(credits = "2", extra = mapOf("类型" to "必修"))))
        val parsed = ParseResult("导入", listOf(course.copy(credits = "3", extra = mapOf("类型" to "选修"))))
        val merged = ImportEngine.preview(parsed, semester, current, ImportMode.MERGE).courses.single()
        assertEquals("2", merged.credits)
        assertTrue(merged.extra.values.containsAll(listOf("必修", "选修", "3")))
    }

    @Test fun `new plans preserve all other plans and select only the new plan`() {
        val updated = ImportEngine.apply(state, ParseResult("新方案", listOf(course)), semester.id, plan.id, ImportMode.NEW, "新方案")
        assertEquals(3, updated.plans.size)
        assertEquals(plan, updated.plans.first { it.id == plan.id })
        assertEquals(other, updated.plans.first { it.id == other.id })
        assertNotEquals(plan.id, updated.selectedPlanId)
        assertEquals("新方案", updated.plan!!.name)
        assertEquals(2, state.plans.size)
    }

    @Test fun `replacement affects only the chosen plan`() {
        val incoming = course.copy(name = "Physics")
        val updated = ImportEngine.apply(state, ParseResult("导入", listOf(incoming)), semester.id, plan.id, ImportMode.REPLACE, plan.name)
        assertEquals(2, updated.plans.size)
        assertEquals(other, updated.plans.first { it.id == other.id })
        assertEquals(listOf("Physics"), updated.plan!!.courses.map { it.name })
        assertEquals(plan.id, updated.selectedPlanId)
        assertTrue(ImportEngine.preview(ParseResult("导入", listOf(incoming)), semester, plan, ImportMode.REPLACE).warnings.any { it.contains("将替换") })
    }

    @Test fun `failed import never exposes a partially written state`() {
        val invalid = ParseResult("不完整", listOf(course, course.copy(name = "损坏", lessons = listOf(lesson.copy(endPeriod = 12)))))
        rejects { ImportEngine.apply(state, invalid, semester.id, plan.id, ImportMode.MERGE, plan.name) }
        assertEquals(listOf(plan, other), state.plans)
        assertEquals(listOf(lesson), state.plan!!.courses.single().lessons)
    }

    @Test fun `merge and replacement reject nonexistent or cross semester targets`() {
        val parsed = ParseResult("导入", listOf(course))
        rejects { ImportEngine.apply(state, parsed, semester.id, "missing", ImportMode.MERGE, "目标") }
        rejects { ImportEngine.apply(state, parsed, "missing", plan.id, ImportMode.REPLACE, "目标") }
        assertFalse(ImportEngine.preview(parsed, semester, plan.copy(semesterId = "elsewhere"), ImportMode.REPLACE).valid)
    }

    @Test fun `unresolved source records and parse errors prevent saving`() {
        val unresolved = ParseResult("未排课", listOf(course), unresolved = listOf("课程缺少时间"))
        assertFalse(ImportEngine.preview(unresolved, semester, plan, ImportMode.MERGE).valid)
        rejects { ImportEngine.apply(state, unresolved, semester.id, null, ImportMode.NEW, "未排课") }
        assertFalse(ImportEngine.preview(ParseResult("错误", listOf(course), errors = listOf("文件损坏")), semester, plan, ImportMode.MERGE).valid)
    }

    @Test fun `missing optional fields warn while missing time or name blocks`() {
        val optionalMissing = course.copy(lessons = listOf(lesson.copy(location = "", teacher = "")))
        val preview = ImportEngine.preview(ParseResult("导入", listOf(optionalMissing)), semester, null, ImportMode.NEW)
        assertTrue(preview.valid)
        assertTrue(preview.warnings.any { it.contains("地点") })
        assertTrue(preview.warnings.any { it.contains("教师") })
        assertFalse(ImportEngine.preview(ParseResult("导入", listOf(course.copy(name = "  "))), semester, null, ImportMode.NEW).valid)
        assertFalse(ImportEngine.preview(ParseResult("导入", listOf(course.copy(lessons = listOf(Lesson())))), semester, null, ImportMode.NEW).valid)
    }

    @Test fun `source semester differences require resolution`() {
        assertFalse(ImportEngine.preview(ParseResult("导入", listOf(course), sourceSemester = "2025-2026学年第一学期"), semester, null, ImportMode.NEW).valid)
        assertTrue(ImportEngine.preview(ParseResult("导入", listOf(course), sourceSemester = "2026-2027学年第一学期"), semester, null, ImportMode.NEW).valid)
    }

    @Test fun `simultaneous distinct courses surface a conflict without data loss`() {
        val preview = ImportEngine.preview(ParseResult("导入", listOf(course, course.copy(name = "Physics"))), semester, null, ImportMode.NEW)
        assertTrue(preview.valid)
        assertEquals(2, preview.courses.size)
        assertEquals(1, preview.conflicts)
        assertTrue(preview.warnings.any { it.contains("时间重叠") })
    }

    @Test fun `valid independent colors survive reorder and identity variants share color`() {
        val input = (1..30).map { course.copy(name = "课程 $it", color = 0) }
        val assigned = CourseColors.assign(input)
        assertEquals(30, assigned.map { it.color }.distinct().size)
        assertEquals(assigned.associate { it.name to it.color }, CourseColors.assign(assigned.reversed()).associate { it.name to it.color })
        val variants = CourseColors.assign(listOf(course.copy(color = 0), course.copy(name = "ＭＡＴＨ", color = 0)))
        assertEquals(variants[0].color, variants[1].color)
    }

    @Test fun `semester validation rejects gaps overlaps inverted times and contradictory duration`() {
        assertTrue(DataValidator.validateSemester(semester).isEmpty())
        assertTrue(DataValidator.validateSemester(semester.copy(weeks = 1)).isNotEmpty())
        assertTrue(DataValidator.validateSemester(semester.copy(periods = listOf(Period(2, "08:00", "08:45")))).isNotEmpty())
        assertTrue(DataValidator.validateSemester(semester.copy(periods = listOf(Period(1, "09:00", "08:45")))).isNotEmpty())
        assertTrue(DataValidator.validateSemester(semester.copy(periods = listOf(Period(1, "08:00", "09:15"), Period(2, "09:00", "09:45")))).isNotEmpty())
    }

    @Test fun `course validation rejects impossible weeks dates and inconsistent time modes`() {
        fun errors(item: Lesson) = DataValidator.validateCourses(listOf(course.copy(lessons = listOf(item))), semester)
        assertTrue(errors(lesson.copy(weeks = listOf(8))).isNotEmpty())
        assertTrue(errors(lesson.copy(date = "2026-09-06")).isNotEmpty())
        assertTrue(errors(lesson.copy(date = "2026-02-30")).isNotEmpty())
        assertTrue(errors(lesson.copy(startTime = "08:10", endTime = "09:45")).isNotEmpty())
        assertTrue(errors(lesson.copy(startTime = "08:00", endTime = "09:45")).isEmpty())
        assertTrue(errors(Lesson(weeks = listOf(1), startTime = "08:00:30", endTime = "08:00:45")).isNotEmpty())
    }

    @Test fun `oversized input is rejected before deduplication color assignment or conflict expansion`() {
        val manyCourses = ParseResult("过多", List(DataValidator.MAX_COURSES + 1) { course.copy(name = "课程 $it") })
        val coursePreview = ImportEngine.preview(manyCourses, semester, null, ImportMode.NEW)
        assertFalse(coursePreview.valid)
        assertTrue(coursePreview.courses.isEmpty())
        assertEquals(0, coursePreview.conflicts)
        val manyLessons = ParseResult("过多", listOf(course.copy(lessons = List(DataValidator.MAX_LESSONS + 1) { lesson })))
        val lessonPreview = ImportEngine.preview(manyLessons, semester, null, ImportMode.NEW)
        assertFalse(lessonPreview.valid)
        assertTrue(lessonPreview.courses.isEmpty())
        assertEquals(0, lessonPreview.duplicates)
        assertTrue(DataValidator.validateCourses(listOf(course.copy(name = "字".repeat(301)))).isNotEmpty())
    }

    @Test fun `merge enforces the final plan size without changing existing data`() {
        val existing = plan.copy(courses = listOf(course.copy(lessons = List(1600) { lesson.copy(location = "地点 $it") })))
        val incoming = ParseResult("增加", listOf(course.copy(lessons = List(1600) { lesson.copy(location = "新增地点 $it") })))
        val preview = ImportEngine.preview(incoming, semester, existing, ImportMode.MERGE)
        assertFalse(preview.valid)
        assertTrue(preview.courses.isEmpty())
        assertEquals(1600, existing.courses.single().lessons.size)
    }

    @Test fun `dense valid timetables bound conflict detail and disclose incomplete counting`() {
        val dense = ParseResult("密集", List(500) { course.copy(name = "课程 $it") })
        val preview = ImportEngine.preview(dense, semester, null, ImportMode.NEW)
        assertTrue(preview.valid)
        assertEquals(500, preview.courses.size)
        assertEquals(100_000, preview.conflicts)
        assertEquals(50, preview.warnings.count { it.contains("存在时间重叠") })
        assertTrue(preview.warnings.any { it.contains("至少") && it.contains("下限") })
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected invalid import to be rejected") } catch (_: IllegalArgumentException) { }
    }
}
