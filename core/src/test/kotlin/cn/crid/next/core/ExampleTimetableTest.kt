package cn.crid.next.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Keep the public AI conversion example usable through the real import and scheduling paths. */
class ExampleTimetableTest {
    private val example = sequenceOf(File(".."), File("."))
        .map { File(it, "examples/timetable.json") }
        .first { it.isFile }
        .readText(Charsets.UTF_8)
    private val semester = Semester(
        id = "example-semester", name = "示例学期", startDate = "2026-09-07",
        endDate = "2026-12-27", weeks = 16,
        periods = listOf(
            Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40"),
            Period(3, "10:00", "10:45"), Period(4, "10:55", "11:40"),
        ),
    )

    @Test fun `public example imports into a semester and exports without losing teaching details`() {
        val parsed = PlanCodec.decode(example)
        assertTrue(parsed.errors.joinToString(), parsed.valid)
        assertEquals(3, parsed.courses.size)
        assertEquals(4, parsed.courses.sumOf { it.lessons.size })
        assertTrue(parsed.courses.all { it.id.isNotBlank() })
        assertEquals(parsed.courses.size, parsed.courses.map { it.id }.distinct().size)

        val preview = ImportEngine.preview(parsed, semester, null, ImportMode.NEW)
        assertTrue(preview.errors.joinToString(), preview.valid)
        assertEquals(0, preview.duplicates)
        assertEquals(0, preview.conflicts)
        val imported = ImportEngine.apply(
            AppState(semesters = listOf(semester)), parsed, semester.id, null, ImportMode.NEW, parsed.name,
        ).plan!!
        assertEquals(semester.id, imported.semesterId)
        assertEquals(parsed.name, imported.name)
        parsed.courses.zip(imported.courses).forEach { (source, saved) ->
            assertEquals(source.name, saved.name)
            assertEquals(source.credits, saved.credits)
            assertEquals(source.extra, saved.extra)
            assertEquals(source.lessons, saved.lessons)
        }
        assertEquals(imported.courses, PlanCodec.decode(PlanCodec.encode(imported)).courses)

        val pending = imported.courses.single { it.name == "专题研讨（示例）" }.lessons.single()
        assertTrue(pending.unscheduled)
        assertEquals(listOf(5, 9, 13), pending.weeks)
        assertEquals(0, pending.weekday)
        assertNull(ScheduleEngine.lessonTimes(semester, pending))
        assertTrue(preview.warnings.any { it.contains("专题研讨（示例）") && it.contains("尚未确定") })
    }

    @Test fun `example produces the documented recurring and irregular classes without scheduling pending work`() {
        val parsed = PlanCodec.decode(example)
        assertTrue(parsed.errors.joinToString(), parsed.valid)
        val plan = ImportEngine.apply(
            AppState(semesters = listOf(semester)), parsed, semester.id, null, ImportMode.NEW, parsed.name,
        ).plan!!
        val schedule = ScheduleEngine.prepare(
            semester, plan, Settings(showOutOfWeek = false, holidaysEnabled = false), HolidayCalendar(),
        )
        val classes = (0L until 16L * 7L).flatMap { day ->
            schedule.occurrences(LocalDate.parse(semester.startDate).plusDays(day))
        }
        assertEquals(30, classes.size)
        assertTrue(classes.all { it.isActual })
        assertFalse(classes.any { it.course.name == "专题研讨（示例）" })

        val modeling = classes.filter { it.course.name == "数学与建模（示例）" }
        val lectures = modeling.filter { it.date.dayOfWeek.value == 1 }
        assertEquals((1..16).toList(), lectures.map { it.week })
        assertTrue(lectures.all {
            it.start == LocalTime.of(8, 0) && it.end == LocalTime.of(9, 40) &&
                it.lesson.teacher == "示例教师甲" && it.lesson.location == "示例教学楼 A101"
        })
        val exercises = modeling.filter { it.date.dayOfWeek.value == 5 }
        assertEquals((2..16 step 2).toList(), exercises.map { it.week })
        assertTrue(exercises.all {
            it.start == LocalTime.of(10, 0) && it.end == LocalTime.of(11, 40) &&
                it.lesson.teacher == "示例教师乙" && it.lesson.location == "示例教学楼 B203"
        })

        val practice = classes.filter { it.course.name == "数据实践（示例）" }
        assertEquals(listOf(2, 4, 6, 10, 12, 14), practice.map { it.week })
        assertTrue(practice.all {
            it.date.dayOfWeek.value == 3 && it.start == LocalTime.of(13, 30) && it.end == LocalTime.of(15, 0) &&
                it.lesson.teacher == "示例教师乙" && it.lesson.location == "示例实验室 C305" &&
                it.lesson.note == "自备电脑"
        })
    }
}
