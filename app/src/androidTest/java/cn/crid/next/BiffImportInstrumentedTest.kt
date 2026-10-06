package cn.crid.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.Lesson
import cn.crid.next.core.importer.TimetableParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiffImportInstrumentedTest {
    @Test fun binaryWorkbookPreservesUnicodeNumbersAndCachedFormula() {
        val filename = "biff-regression/binary-timetable.xls"
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(filename).use { it.readBytes() }
        assertArrayEquals(
            byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()),
            bytes.copyOf(8),
        )
        val parsed = TimetableParser.parse(bytes, "binary-timetable.xls")
        assertTrue(parsed.errors.toString(), parsed.valid)
        assertTrue(parsed.warnings.toString(), parsed.warnings.isEmpty())
        assertTrue(parsed.unresolved.isEmpty())
        assertEquals("2026-2027学年秋季学期", parsed.sourceSemester)
        val course = parsed.courses.single()
        assertEquals("数据结构", course.name)
        assertEquals("3.5", course.credits)
        assertEquals(mapOf("课程代码" to "CS001", "总学时" to "4", "上课班号" to "7"), course.extra)
        assertEquals(
            listOf(Lesson(listOf(1, 2, 3, 4), 3, 2, 3, location = "科学楼203", teacher = "张老师")),
            course.lessons,
        )
    }
}
