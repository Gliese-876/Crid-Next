package cn.crid.next.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PendingLessonsTest {
    private val semester = Semester(
        id = "semester", name = "2026 秋季", startDate = "2026-09-07", endDate = "2027-01-24", weeks = 20,
        periods = listOf(Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40")),
    )
    private val scheduled = Lesson(weeks = listOf(2, 3, 4), weekday = 1, startPeriod = 1, endPeriod = 2, teacher = "教师甲", location = "主楼")
    private val pending = Lesson(weeks = listOf(11, 12, 13), weekday = 0, teacher = "教师乙", note = "11-13周 []", unscheduled = true)
    private val lastPending = pending.copy(weeks = listOf(16), teacher = "教师丙", note = "16周 []")
    private val course = Course(name = "现代教育技术", credits = "2", lessons = listOf(scheduled, pending, lastPending))

    @Test fun `explicitly pending teaching records import with their source details intact`() {
        val parsed = ParseResult("课程表", listOf(course))
        val preview = ImportEngine.preview(parsed, semester, null, ImportMode.NEW)
        assertTrue(preview.errors.toString(), preview.valid)
        assertTrue(preview.warnings.any { it.contains("2 项安排尚未确定上课时间") })
        assertEquals(course.lessons, preview.courses.single().lessons)

        val saved = ImportEngine.apply(AppState(semesters = listOf(semester)), parsed, semester.id, null, ImportMode.NEW, "课程表")
        assertEquals(course.lessons, saved.plan!!.courses.single().lessons)
        assertEquals("教师乙", saved.plan!!.courses.single().lessons[1].teacher)
        assertEquals(listOf(11, 12, 13), saved.plan!!.courses.single().lessons[1].weeks)
        assertEquals("教师丙", saved.plan!!.courses.single().lessons[2].teacher)
        assertEquals(listOf(16), saved.plan!!.courses.single().lessons[2].weeks)
    }

    @Test fun `pending weeks remain subject to positive and semester range validation`() {
        fun errors(lesson: Lesson) = DataValidator.validateCourses(listOf(course.copy(lessons = listOf(lesson))), semester)
        assertTrue(errors(pending).isEmpty())
        assertTrue(errors(pending.copy(weeks = emptyList())).isNotEmpty())
        assertTrue(errors(pending.copy(weeks = listOf(0))).isNotEmpty())
        assertTrue(errors(pending.copy(weeks = listOf(22))).isNotEmpty())
    }

    @Test fun `pending records cannot claim any concrete time placement`() {
        val inconsistent = listOf(
            pending.copy(weekday = 1), pending.copy(date = "2026-11-16"),
            pending.copy(startPeriod = 1), pending.copy(endPeriod = 2),
            pending.copy(startTime = "08:00"), pending.copy(endTime = "09:00"),
        )
        inconsistent.forEach { lesson ->
            val errors = DataValidator.validateCourses(listOf(course.copy(lessons = listOf(lesson))), semester)
            assertTrue(errors.toString(), errors.any { it.contains("待排课安排不能同时指定") })
        }
    }

    @Test fun `missing time without an explicit pending status still blocks import`() {
        val incomplete = listOf(pending.copy(unscheduled = false), scheduled.copy(startPeriod = null, endPeriod = null))
        incomplete.forEach { lesson ->
            val preview = ImportEngine.preview(ParseResult("课程表", listOf(course.copy(lessons = listOf(lesson)))), semester, null, ImportMode.NEW)
            assertFalse(preview.valid)
        }
        assertFalse(ImportEngine.preview(ParseResult("课程表", listOf(course.copy(lessons = emptyList()))), semester, null, ImportMode.NEW).valid)
        assertFalse(ImportEngine.preview(ParseResult("课程表", listOf(course), unresolved = listOf("损坏的授课段")), semester, null, ImportMode.NEW).valid)
    }

    @Test fun `merge deduplicates only identical pending records and preserves teacher week pairs`() {
        val original = Plan(semesterId = semester.id, name = "原课表", courses = listOf(course))
        val anotherTeacher = pending.copy(teacher = "教师丁")
        val incoming = course.copy(lessons = listOf(pending.copy(weeks = listOf(13, 11, 12, 11)), anotherTeacher))
        val preview = ImportEngine.preview(ParseResult("课程表", listOf(incoming)), semester, original, ImportMode.MERGE)
        assertTrue(preview.valid)
        assertEquals(1, preview.duplicates)
        assertEquals(listOf(scheduled, pending, lastPending, anotherTeacher), preview.courses.single().lessons)
    }

    @Test fun `pending records produce no actual ghost holiday or makeup occurrences`() {
        // Also guard persisted inconsistent data: the explicit state takes precedence when displaying.
        val inconsistent = pending.copy(weekday = 1, startPeriod = 1, endPeriod = 2)
        val plan = Plan(semesterId = semester.id, name = "待排课", courses = listOf(course.copy(lessons = listOf(pending, lastPending, inconsistent))))
        val calendar = HolidayCalendar(days = listOf(
            HolidayDay("2026-11-16", "休假", statutory = true),
            HolidayDay("2026-11-21", "补课", workday = true, teachingDate = "2026-11-16"),
        ))
        for (ghosts in listOf(false, true)) {
            val settings = Settings(showOutOfWeek = ghosts, makeupMode = MakeupMode.ON)
            for (offset in 0L..139L) {
                val day = LocalDate.parse(semester.startDate).plusDays(offset)
                assertTrue(ScheduleEngine.occurrences(semester, plan, day, settings, calendar).isEmpty())
            }
        }
        assertNull(ScheduleEngine.lessonTimes(semester, pending))
        assertNull(ScheduleEngine.lessonTimes(semester, inconsistent))
    }

    @Test fun `plan exchange preserves pending arrangements and their status in version one`() {
        val plan = Plan(semesterId = semester.id, name = "课程表", courses = listOf(course))
        val encoded = PlanCodec.encode(plan)
        val decoded = PlanCodec.decode(encoded)
        assertTrue(decoded.errors.toString(), decoded.valid)
        assertEquals(plan.courses, decoded.courses)
        assertTrue(encoded.contains("\"version\": 1"))
        assertTrue(decoded.warnings.any { it.contains("尚未确定上课时间") })
        val imported = ImportEngine.apply(AppState(semesters = listOf(semester)), decoded, semester.id, null, ImportMode.NEW, decoded.name)
        assertEquals(course.lessons, imported.plan!!.courses.single().lessons)
    }

    @Test fun `version one files without the optional pending field retain scheduled behavior`() {
        val plan = Plan(semesterId = semester.id, name = "旧课表", courses = listOf(course.copy(lessons = listOf(scheduled))))
        val oldVersionOne = PlanCodec.encode(plan).replace(Regex(",\\s*\"unscheduled\": false"), "")
        assertFalse(oldVersionOne.contains("unscheduled"))
        val decoded = PlanCodec.decode(oldVersionOne)
        assertTrue(decoded.errors.toString(), decoded.valid)
        assertEquals(listOf(scheduled), decoded.courses.single().lessons)
        assertFalse(decoded.courses.single().lessons.single().unscheduled)
    }
}
