package cn.crid.next.core

import org.junit.Assert.*
import org.junit.Test

class PlanCodecTest {
    private val course = Course(name = "高等数学", credits = "4", color = CourseColors.colorFor("高等数学"), extra = mapOf("考核" to "考试"), lessons = listOf(Lesson(weeks = listOf(1, 3, 5), weekday = 3, startPeriod = 1, endPeriod = 2, teacher = "陈老师", location = "主楼 101", note = "携带教材")))
    private val plan = Plan(semesterId = "private-semester-id", name = "我的课表", courses = listOf(course))

    @Test fun `version one roundtrip retains all teaching and course information`() {
        val decoded = PlanCodec.decode(PlanCodec.encode(plan))
        assertTrue(decoded.valid)
        assertEquals(plan.name, decoded.name)
        assertEquals(plan.courses, decoded.courses)
        val unassigned = plan.copy(courses = listOf(course.copy(color = 0)))
        assertEquals(unassigned.courses, PlanCodec.decode(PlanCodec.encode(unassigned)).courses)
    }

    @Test fun `export contains version and courses but no semester or settings`() {
        val text = PlanCodec.encode(plan)
        assertTrue(text.contains("\"version\": 1"))
        assertTrue(text.contains("\"format\": \"crid-next\""))
        assertFalse(text.contains("private-semester-id"))
        assertFalse(text.contains("semesterId"))
        assertFalse(text.contains("settings"))
    }

    @Test fun `credential fields cannot leak through arbitrary course metadata`() {
        val protected = plan.copy(courses = listOf(course.copy(extra = mapOf("API_Key" to "example-sensitive-value", "PASSWORD" to "example-password-value", "考核" to "考试"))))
        val text = PlanCodec.encode(protected)
        assertFalse(text.contains("example-sensitive-value"))
        assertFalse(text.contains("example-password-value"))
        assertEquals(mapOf("考核" to "考试"), PlanCodec.decode(text).courses.single().extra)
    }

    @Test fun `unsupported or missing format versions do not silently downgrade`() {
        val text = PlanCodec.encode(plan)
        assertFalse(PlanCodec.decode(text.replace("\"version\": 1", "\"version\": 2")).valid)
        assertFalse(PlanCodec.decode(text.replace("\"version\": 1,", "")).valid)
        assertFalse(PlanCodec.decode(text.replace("crid-next", "another-format")).valid)
    }

    @Test fun `unknown fields invalid time and malformed JSON are rejected`() {
        val text = PlanCodec.encode(plan)
        assertFalse(PlanCodec.decode(text.replaceFirst("{", "{\"apiKey\":\"not-a-real-secret\"," )).valid)
        assertFalse(PlanCodec.decode(text.replace("\"weekday\": 3", "\"weekday\": 9")).valid)
        assertFalse(PlanCodec.decode(text.take(text.length / 2)).valid)
        assertFalse(PlanCodec.decode("[]").valid)
    }

    @Test fun `malformed source is never echoed in parser errors`() {
        val errors = PlanCodec.decode("{\"format\":\"crid-next\",\"version\":1,\"apiKey\":\"example-private-input\"}").errors
        assertTrue(errors.isNotEmpty())
        assertFalse(errors.joinToString().contains("example-private-input"))
    }

    @Test fun `deeply nested corrupt files fail before exhausting the decoder stack`() {
        val text = "{\"format\":\"crid-next\",\"version\":1,\"courses\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
        assertFalse(PlanCodec.decode(text).valid)
        val named = plan.copy(name = "括号 [ { 与引号 \\\" 均可出现在名称中")
        assertEquals(named.name, PlanCodec.decode(PlanCodec.encode(named)).name)
    }
}
