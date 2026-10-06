package cn.crid.next.core

import cn.crid.next.core.importer.TimetableParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CourseTextTest {
    private val semester = Semester("term", "秋季", "2026-09-07", "2026-10-18", 6,
        listOf(Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40")))
    private val first = Lesson(weeks = listOf(1, 2), weekday = 1, startPeriod = 1, endPeriod = 2,
        teacher = "Alan老师", location = "励耘楼A203", note = "使用Python完成第1周练习")

    @Test fun `Chinese Latin words and room identifiers get readable boundaries`() {
        mapOf(
            "Python程序设计" to "Python 程序设计",
            "励耘楼A203" to "励耘楼 A203",
            "南区A101教室" to "南区 A101 教室",
            "Alan老师" to "Alan 老师",
            "张老师与Émile老师" to "张老师与 Émile 老师",
            "C++程序设计与C#实验" to "C++ 程序设计与 C# 实验",
            "3D打印与.NET开发" to "3D 打印与 .NET 开发",
            "综合楼A-101与B2教室" to "综合楼 A-101 与 B2 教室",
            "综合楼Ａ１０１教室" to "综合楼 Ａ１０１ 教室",
            "\uD840\uDC00楼A101" to "\uD840\uDC00楼 A101",
        ).forEach { (source, expected) -> assertEquals(expected, courseTextSpacing(source), source) }
    }

    @Test fun `pure numbers dates times and Chinese counts keep their spelling`() {
        val text = "第1周第3节，1-4周，2026-2027学年，2026年9月7日，2026-09-07，08:00-09:00，学分3，3号楼203室"
        assertEquals(text, courseTextSpacing(text))
        assertEquals("形势与政策3", courseTextSpacing("形势与政策3"))
        assertEquals("A101 C++ C# 3D 2026-2027", courseTextSpacing("A101 C++ C# 3D 2026-2027"))
    }

    @Test fun `numeric course levels and room identifiers use explicit field rules`() {
        assertEquals("大学英语 1", courseNameSpacing("大学英语1"))
        assertEquals("形势与政策 3", courseNameSpacing("形势与政策3"))
        assertEquals("大学英语 １", courseNameSpacing("大学英语１"))
        assertEquals("第1周复习", courseNameSpacing("第1周复习"))
        assertEquals("2026-2027", courseNameSpacing("2026-2027"))
        mapOf("励耘楼203" to "励耘楼 203", "教学楼2楼" to "教学楼 2 楼",
            "教学楼2楼203室" to "教学楼 2 楼 203 室", "203室" to "203 室", "2层" to "2 层",
            "教室203" to "教室 203", "3号楼" to "3号楼").forEach { (source, expected) ->
            assertEquals(expected, courseLocationSpacing(source), source)
            assertEquals(expected, courseLocationSpacing(expected), source)
        }
        val protected = "第1周，第3节，1-4周，2026-09-07，08:00-09:00，https://example.edu/教学楼203"
        assertEquals(protected, courseLocationSpacing(protected))
        assertEquals(protected, courseNameSpacing(protected))
    }

    @Test fun `URLs unicode paths query values and email addresses stay byte equivalent`() {
        listOf(
            "https://example.edu/中文A101/课表?room=励耘楼A203&week=1-4",
            "资料https://example.edu/中文A101/课表",
            "www.example.edu/教务A101",
            "mailto:teacher+lab@example.edu",
            "联系teacher@example.edu",
        ).forEach { source -> assertEquals(source, courseTextSpacing(source), source) }
        assertEquals("使用 Python；https://example.edu/中文A101", courseTextSpacing("使用Python；https://example.edu/中文A101"))
    }

    @Test fun `spacing is idempotent and preserves deliberate text layout`() {
        listOf("Python程序设计", "C++程序设计", "南区A101教室", "任课教师Alan老师", "3D打印", "").forEach { source ->
            val once = courseTextSpacing(source)
            assertEquals(once, courseTextSpacing(once), source)
        }
        val arranged = "Python  程序设计\n地点\tA101\n教师 Alan"
        assertEquals(arranged, courseTextSpacing(arranged))
    }

    @Test fun `course identity ignores only mixed script boundary spacing`() {
        assertEquals("python程序设计", courseKey("Python 程序设计"))
        assertEquals(courseKey("Python程序设计"), courseKey(" Ｐｙｔｈｏｎ　 程序设计 "))
        assertEquals(courseKey("C++程序设计"), courseKey("C++ 程序设计"))
        assertEquals(courseKey("3D打印"), courseKey("3D 打印"))
        assertEquals(courseKey("大学物理BⅡ"), courseKey("大学物理 BⅡ"))
        assertNotEquals(courseKey("Design Thinking"), courseKey("DesignThinking"))
        assertEquals(courseKey("大学英语1"), courseKey("大学英语 1"))
        assertNotEquals(courseKey("Course 1"), courseKey("Course1"))
        assertEquals(CourseColors.colorFor("Python程序设计"), CourseColors.colorFor("Python 程序设计"))
    }

    @Test fun `readable course keeps identity color structured time and arbitrary source metadata`() {
        val raw = Course(id = "source-id", name = "Python程序设计", color = 123, credits = "3.0",
            extra = mapOf("课程代码" to "STA22802", "来源" to "励耘楼A203", "学分" to "3"), lessons = listOf(first))
        val readable = raw.withReadableText()
        assertEquals(raw.id, readable.id)
        assertEquals(raw.color, readable.color)
        assertEquals(raw.credits, readable.credits)
        assertEquals(raw.extra, readable.extra)
        assertEquals("Python 程序设计", readable.name)
        assertEquals(first.copy(teacher = "Alan 老师", location = "励耘楼 A203", note = "使用 Python 完成第1周练习"), readable.lessons.single())
        assertEquals("Python程序设计", raw.name)
        assertEquals("励耘楼A203", raw.lessons.single().location)
    }

    @Test fun `numeric location and level changes keep a legacy id and do not reimport a second course`() {
        val original = Course(id = "english", name = "大学英语1", color = 321, credits = "3",
            extra = mapOf("原地点" to "教学楼2楼"), lessons = listOf(first.copy(location = "励耘楼203")))
        val plan = Plan("plan", semester.id, "课表", listOf(original))
        val state = AppState(listOf(semester), listOf(plan), semester.id, plan.id)
        val incoming = original.withReadableText().copy(id = "incoming")
        val saved = ImportEngine.apply(state, ParseResult("课表", listOf(incoming)), semester.id, plan.id, ImportMode.MERGE, plan.name)
        assertEquals(original.id, saved.plan!!.courses.single().id)
        assertEquals(original.color, saved.plan!!.courses.single().color)
        assertEquals("大学英语 1", saved.plan!!.courses.single().name)
        assertEquals("励耘楼 203", saved.plan!!.courses.single().lessons.single().location)
        assertEquals(original.credits, saved.plan!!.courses.single().credits)
        assertEquals(original.extra, saved.plan!!.courses.single().extra)
    }

    @Test fun `local import applies readable formatting only after source extraction`() {
        val html = """<html><meta charset='UTF-8'><table><tr><th>课程名称</th><th>任课教师</th><th>学分</th><th>上课时间地点</th></tr><tr><td>Python程序设计</td><td>Alan老师;李老师</td><td>3.0</td><td>1-2周 一[1-2] 励耘楼A203,3-4周 二[1-2] 丽泽楼C203(140)</td></tr></table></html>"""
        val source = TimetableParser.parse(html.toByteArray(), "课表.html")
        assertTrue(source.valid, source.errors.toString())
        assertEquals("Python程序设计", source.courses.single().name)
        assertEquals("励耘楼A203", source.courses.single().lessons.first().location)
        val preview = ImportEngine.preview(source, semester, null, ImportMode.NEW)
        assertTrue(preview.valid, preview.errors.toString())
        val course = preview.courses.single()
        assertEquals("Python 程序设计", course.name)
        assertEquals(listOf("Alan 老师", "李老师"), course.lessons.map { it.teacher })
        assertEquals(listOf("励耘楼 A203", "丽泽楼 C203(140)"), course.lessons.map { it.location })
        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), course.lessons.map { it.weeks })
        assertEquals(listOf(1, 2), course.lessons.map { it.weekday })
        assertEquals(course.lessons, ImportEngine.apply(AppState(semesters = listOf(semester)), source,
            semester.id, null, ImportMode.NEW, "课表").plan!!.courses.single().lessons)
    }

    @Test fun `merging a spaced import keeps legacy course identity without duplicated arrangements`() {
        val original = Course(id = "legacy-course", name = "Python程序设计", color = 123, lessons = listOf(first))
        val plan = Plan("plan", semester.id, "课表", listOf(original))
        val state = AppState(listOf(semester), listOf(plan), semester.id, plan.id)
        val incoming = original.withReadableText().copy(id = "new-source")
        val source = ParseResult("课表", listOf(incoming))
        val preview = ImportEngine.preview(source, semester, plan, ImportMode.MERGE)
        assertTrue(preview.valid)
        assertEquals(1, preview.duplicates)
        assertEquals(1, preview.courses.size)
        val saved = ImportEngine.apply(state, source, semester.id, plan.id, ImportMode.MERGE, plan.name)
        assertEquals(original.id, saved.plan!!.courses.single().id)
        assertEquals(original.color, saved.plan!!.courses.single().color)
        assertEquals(listOf(incoming.lessons.single()), saved.plan!!.courses.single().lessons)
        assertEquals(saved, ImportEngine.apply(saved, source, semester.id, plan.id, ImportMode.MERGE, plan.name))
        assertEquals(original, state.plan!!.courses.single())
    }

    @Test fun `spacing never loses teacher week mappings or separate pending records`() {
        val later = first.copy(weeks = listOf(3, 4), teacher = "李老师", location = "丽泽楼C203", weekday = 2)
        val pending = Lesson(weeks = listOf(5, 6), weekday = 0, teacher = "Chen老师", note = "5-6周 []", unscheduled = true)
        val courses = listOf(Course(name = "Python程序设计", lessons = listOf(first)),
            Course(name = "Python 程序设计", lessons = listOf(later, pending)))
        val preview = ImportEngine.preview(ParseResult("课表", courses), semester, null, ImportMode.NEW)
        assertTrue(preview.valid, preview.errors.toString())
        assertEquals(1, preview.courses.size)
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)), preview.courses.single().lessons.map { it.weeks })
        assertEquals(listOf("Alan 老师", "李老师", "Chen 老师"), preview.courses.single().lessons.map { it.teacher })
        assertEquals(pending.copy(teacher = "Chen 老师"), preview.courses.single().lessons.last())
    }

    @Test fun `editor resolves the same logical course after spacing changes without changing source ids`() {
        val a = Course(id = "first", name = "Python程序设计", lessons = listOf(first))
        val b = Course(id = "second", name = "Python 程序设计", lessons = listOf(first.copy(weeks = listOf(3))))
        val plan = Plan("plan", semester.id, "课表", listOf(a, b))
        val state = AppState(listOf(semester), listOf(plan), semester.id, plan.id)
        assertEquals(listOf(a, b), CourseEditing.editTarget(state, plan.id, a.id).expectedCourses)
        val draft = Course(name = "Python 程序设计", lessons = listOf(first))
        val failure = runCatching { CourseEditing.save(state, CourseEditing.newTarget(state, plan.id), draft) }.exceptionOrNull()
        assertTrue(failure is CourseNameConflictException)
        assertEquals(listOf(a.id, b.id), failure.courseIds)
    }
}
