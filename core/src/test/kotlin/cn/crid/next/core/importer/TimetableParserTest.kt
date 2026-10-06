package cn.crid.next.core.importer

import cn.crid.next.core.Course
import cn.crid.next.core.AppState
import cn.crid.next.core.ImportEngine
import cn.crid.next.core.ImportMode
import cn.crid.next.core.Lesson
import cn.crid.next.core.Period
import cn.crid.next.core.Plan
import cn.crid.next.core.PlanCodec
import cn.crid.next.core.Semester
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimetableParserTest {
    private val root = sequenceOf(File(".."), File(".")).first { File(it, "tests/测试用例").isDirectory }
    private val fixtures = File(root, "tests/测试用例")
    private val expected = File(root, "tests/expected")

    @Test fun `all six supplied samples match every independently recorded field`() {
        val files = fixtures.listFiles()!!.filter { it.extension == "xls" }
        assertEquals(6, files.size)
        files.forEach { file ->
            val bytes = file.readBytes()
            val snapshot = Json.parseToJsonElement(File(expected, file.nameWithoutExtension + ".json").readText()).jsonObject
            assertEquals(snapshot.getValue("sha256").jsonPrimitive.content, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, file.name)
            val parsed = TimetableParser.parse(bytes, file.name)
            assertEquals(emptyList(), parsed.errors, file.name)
            assertEquals(snapshot.getValue("courseCount").jsonPrimitive.int, parsed.courses.size, file.name)
            assertEquals(snapshot.getValue("lessonCount").jsonPrimitive.int, parsed.courses.sumOf { it.lessons.size }, file.name)
            assertEquals(snapshot.getValue("unscheduledLessonCount").jsonPrimitive.int, parsed.courses.sumOf { course -> course.lessons.count { it.unscheduled } }, file.name)
            assertEquals(snapshot.getValue("sourceSemester").jsonPrimitive.contentOrNull, parsed.sourceSemester, file.name)
            assertEquals(snapshot.getValue("unresolved").jsonArray.map { it.jsonPrimitive.content }, parsed.unresolved, file.name)
            assertTrue(parsed.valid, "Explicitly unassigned times remain complete importable records: ${file.name}")
            val wanted = snapshot.getValue("courses").jsonArray.map { Json.decodeFromJsonElement<Course>(it).copy(id = "") }.associateBy { it.name }
            val actual = parsed.courses.map { it.copy(id = "") }.associateBy { it.name }
            assertEquals(wanted, actual, file.name)
        }
    }

    @Test fun `grid preserves each teacher assignment and changing location`() {
        val result = TimetableParser.parse(File(fixtures, "学生选课课程表.xls").readBytes(), "课表.xls")
        val policy = result.courses.single { it.name == "合成与政策2" }
        assertEquals(listOf(10, 12), policy.lessons.single { it.teacher == "教师未" }.weeks)
        assertEquals(listOf(13), policy.lessons.single { it.teacher == "教师午" }.weeks)
        val language = result.courses.single { it.name == "合成语言听说" }
        assertEquals("SYNTHETIC TEACHER ALFA", language.lessons.single { it.weeks.first() == 1 }.teacher)
        assertEquals("教师丙", language.lessons.single { it.weeks.first() == 9 }.teacher)
        val sports = result.courses.single { it.name == "体能(入门)" }
        assertEquals(setOf("示例楼A103", "测试馆"), sports.lessons.map { it.location }.toSet())
        val psychology = result.courses.single { it.name == "合成心理Ⅱ" }.lessons.single()
        assertTrue(psychology.unscheduled)
        assertEquals((7..14).toList(), psychology.weeks)
        assertEquals("教师壬", psychology.teacher)
    }

    @Test fun `GBK combined course identity and punctuated schedule headers preserve source fields`() {
        val html = """<html><meta charset="GBK"><body><div><div>学生选课课程表</div><font>（2026-2027学年秋季学期）</font></div>
            <div>选课课程门数：1</div><table><tr><th>[课程号]课程名</th><th>总<br>学时</th><th>学分</th><th>上课<br>班号</th>
            <th>任课教师</th><th>上课时间、地点</th><th>修读<br>性质</th><th>辅修<br>标志</th><th>是否<br>免听</th></tr>
            <tr><td>[CS001]数据结构</td><td>48</td><td>3.0</td><td>03</td><td>甲;乙</td>
            <td>1-8周 四[1-3] 四107(182),9-16周 四[1-3] 样样乙学(400)</td><td>初修</td><td>主修</td><td>否</td>
            <td style="display:none">hidden-student-identifier</td></tr></table></body></html>"""
        val result = TimetableParser.parse(html.toByteArray(charset("GBK")), "任意文件名.xls")
        assertTrue(result.valid, result.errors.toString())
        assertEquals("（2026-2027学年秋季学期）", result.sourceSemester)
        val course = result.courses.single()
        assertEquals("数据结构", course.name)
        assertEquals("3.0", course.credits)
        assertEquals(mapOf("课程代码" to "CS001", "总学时" to "48", "上课班号" to "03", "修读性质" to "初修",
            "辅修标识" to "主修", "免听标识" to "否"), course.extra)
        assertEquals(listOf(Lesson((1..8).toList(), 4, 1, 3, location = "四107(182)", teacher = "甲"),
            Lesson((9..16).toList(), 4, 1, 3, location = "样样乙学(400)", teacher = "乙")), course.lessons)
    }

    @Test fun `combined identity header requires both a course code and a course name`() {
        listOf("数据结构", "[]数据结构", "[CS001]", "[CS001", "[ ]数据结构").forEach { identity ->
            val html = "<html><meta charset='UTF-8'><table><tr><th>[课程号]课程名</th><th>上课时间、地点</th></tr>" +
                "<tr><td>$identity</td><td>1-8周 四[1-3] 四107</td></tr></table></html>"
            val result = TimetableParser.parse(html.toByteArray(), "课程.xls")
            assertFalse(result.valid, identity)
            assertTrue(result.courses.isEmpty(), identity)
            assertTrue(result.errors.any { it.contains("课程代码或名称无法识别") }, identity)
        }
    }

    @Test fun `conflicting explicit course codes are rejected rather than overwritten`() {
        val html = "<html><meta charset='UTF-8'><table><tr><th>[课程号]课程名</th><th>课程代码</th><th>上课时间、地点</th></tr>" +
            "<tr><td>[CS001]数据结构</td><td>CS002</td><td>1-8周 四[1-3] 四107</td></tr></table></html>"
        val result = TimetableParser.parse(html.toByteArray(), "课程.xls")
        assertFalse(result.valid)
        assertTrue(result.courses.isEmpty())
        assertTrue(result.errors.any { it.contains("课程代码不一致") })
    }

    @Test fun `semester headings never come from hidden form fields or across an earlier table`() {
        val timetable = listTable(listOf("物理", "甲", "2", "1-3周 一[1-2] A"))
            .substringAfter("<meta charset='UTF-8'>").substringBeforeLast("</html>")
        val hiddenOrControls = listOf(
            "<input type='hidden' value='2026-2027学年秋季学期'>",
            "<select><optgroup label='学期'><option>2025-2026学年秋季学期</option><option selected>2026-2027学年秋季学期</option></optgroup></select>",
            "<textarea>2025-2026学年秋季学期</textarea><output>2025-2026学年秋季学期</output>",
            "<template><div>2025-2026学年秋季学期</div></template>",
            "<div hidden><font>2025-2026学年秋季学期</font></div>",
            "<div aria-hidden='true'><h2>2025-2026学年秋季学期</h2></div>",
            "<div style='color:black; DISPLAY: none !important'><font>2025-2026学年秋季学期</font></div>",
            "<div style='visibility:hidden'><span>2025-2026学年秋季学期</span></div>",
            "<div style='visibility: collapse;'><div>2025-2026学年秋季学期</div></div>",
        )
        (hiddenOrControls + "<div>（2026-2027学年秋季学期）</div><table><tr><td>另一个表格</td></tr></table>").forEach { before ->
            val result = TimetableParser.parse("<html><meta charset='UTF-8'><body>$before$timetable</body></html>".toByteArray(), "课表.xls")
            assertTrue(result.valid, result.errors.toString())
            assertEquals(null, result.sourceSemester, before)
        }
        val visible = "<div aria-hidden='false' style='display:block;visibility:visible'><h2>（2026-2027学年秋季学期）</h2></div>"
        val result = TimetableParser.parse("<html><meta charset='UTF-8'><body>$visible${hiddenOrControls.joinToString("")}$timetable</body></html>".toByteArray(), "课表.xls")
        assertTrue(result.valid, result.errors.toString())
        assertEquals("（2026-2027学年秋季学期）", result.sourceSemester)
    }

    @Test fun `row table retains credits course codes and unscheduled original content`() {
        val file = File(fixtures, "北京师范大学珠海校区2026-2027学年秋季学期学生课表.xls")
        val result = TimetableParser.parse(file.readBytes(), file.name)
        val python = result.courses.single { it.name == "Python合成课甲" }
        assertEquals("2.0", python.credits)
        assertEquals("ZZZ70021", python.extra["课程代码"])
        assertEquals(7, python.lessons[1].startPeriod)
        assertEquals(7, python.lessons[1].endPeriod)
        val technology = result.courses.single { it.name == "合成课课课未" }
        assertTrue(result.valid, result.errors.toString())
        assertEquals(4, technology.lessons.size)
        assertEquals(listOf(listOf(9, 10), listOf(11, 12, 13), listOf(14, 15), listOf(16)), technology.lessons.map { it.weeks })
        assertEquals(listOf(false, true, false, true), technology.lessons.map { it.unscheduled })
        assertTrue(technology.lessons.all { it.teacher == "示例戌" })
        assertTrue(technology.lessons.filter { it.unscheduled }.all { it.weekday == 0 && it.startPeriod == null && it.endPeriod == null && it.startTime == null && it.endTime == null })
        assertEquals(listOf("11-13周 []", "16周 []"), technology.lessons.filter { it.unscheduled }.map { it.note })
        assertTrue(technology.extra.getValue("未排课").contains("11-13周 []"))
        assertTrue(technology.extra.getValue("未排课").contains("16周 []"))
    }

    @Test fun `list teacher names correspond to source week segments in order`() {
        val file = File(fixtures, "北京师范大学珠海校区2026-2027学年秋季学期学生课表.xls")
        val result = TimetableParser.parse(file.readBytes(), file.name)
        val policy = result.courses.single { it.name == "合成课课子3" }
        assertEquals(listOf(10, 12, 14, 16), policy.lessons.map { it.weeks.single() })
        assertEquals(listOf("示壬", "示例月", "示例收", "示例日"), policy.lessons.map { it.teacher })
        val spring = TimetableParser.parse(File(fixtures, "校园课表-2025-2026春季学期.xls").readBytes(), "课表.xls")
        val language = spring.courses.single { it.name == "合成语言听说" }
        // The list export has a different order from the independently supplied grid.
        assertEquals(listOf("教师丙", "SYNTHETIC TEACHER ALFA"), language.lessons.map { it.teacher })
        assertEquals(listOf((1..8).toList(), (9..15).toList()), language.lessons.map { it.weeks })
    }

    @Test fun `supplied python course keeps one identity and both source records through import and reimport`() {
        val file = File(fixtures, "北京师范大学珠海校区2026-2027学年秋季学期学生课表.xls")
        val parsed = TimetableParser.parse(file.readBytes(), file.name)
        val python = parsed.courses.single { it.name == "Python合成课甲" }
        assertEquals("ZZZ70021", python.extra["课程代码"])
        assertEquals(listOf(5 to 6, 7 to 7), python.lessons.map { it.startPeriod to it.endPeriod })
        assertTrue(python.lessons.all { it.weekday == 3 && it.weeks == (1..16).toList() && it.teacher == "示例盈" && it.location == "样甲楼C203(140)" })
        val periods = (1..12).map { number ->
            val start = java.time.LocalTime.of(8, 0).plusMinutes((number - 1) * 50L)
            Period(number, start.toString(), start.plusMinutes(45).toString())
        }
        val semester = Semester("fall", "2026秋季", "2026-09-07", "2026-12-27", 16, periods)
        val imported = ImportEngine.apply(AppState(semesters = listOf(semester)), parsed, semester.id, null, ImportMode.NEW, "课表")
        val saved = imported.plan!!
        assertEquals(11, saved.courses.size)
        // Extraction above keeps every source field; the import preview adds reading spaces.
        assertEquals(python.lessons.map { it.copy(location = "样甲楼 C203(140)") },
            saved.courses.single { it.name == "Python 合成课甲" }.lessons)
        val reimported = ImportEngine.apply(imported, TimetableParser.parse(file.readBytes(), file.name), semester.id, saved.id, ImportMode.MERGE, saved.name)
        assertEquals(saved, reimported.plan)
    }

    @Test fun `teacher assignment uses source segments before splitting nonconsecutive periods`() {
        val html = listTable(listOf("物理", "甲；乙", "2", "1-2周 一[1,3-4] A,3-4周 二[5-6] B"))
        val result = TimetableParser.parse(html.toByteArray(), "课表.html")
        assertTrue(result.valid, result.errors.toString())
        assertEquals(listOf("甲", "甲", "乙"), result.courses.single().lessons.map { it.teacher })
        assertEquals(listOf(listOf(1, 2), listOf(1, 2), listOf(3, 4)), result.courses.single().lessons.map { it.weeks })
    }

    @Test fun `room numbers before a separator never become following teaching weeks`() {
        listOf("励耘楼A203", "励耘楼203", "励耘楼 203", "203").forEach { room ->
            listOf(",", ";", "\n").forEach { separator ->
                val html = listTable(listOf("物理", "甲；乙", "2", "1-2周 一[1-2] $room${separator}3,5-6周 二[5-6] 样甲楼C203"))
                val result = TimetableParser.parse(html.toByteArray(), "课表.html")
                assertTrue(result.valid, "$room / $separator: ${result.errors}")
                val records = result.courses.single().lessons
                assertEquals(listOf(listOf(1, 2), listOf(3, 5, 6)), records.map { it.weeks })
                assertEquals(listOf(room, "样甲楼C203"), records.map { it.location })
                assertEquals(listOf("甲", "乙"), records.map { it.teacher })
            }
        }
    }

    @Test fun `missing schedule separators fail instead of swallowing a lesson as location text`() {
        val html = listTable(listOf("物理", "甲；乙", "2", "1-2周 一[1-2] 主楼 3-4周 二[5-6] 分楼"))
        val result = TimetableParser.parse(html.toByteArray(), "课表.html")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("无法识别授课安排") })
    }

    @Test fun `same weeks on separate weekdays reuse an unambiguous teacher assignment`() {
        val html = listTable(listOf("物理", "甲;乙", "2", "1-2周 一[1-2] A,1,2周 三[1-2] A,3-4周 一[1-2] A,3-4周 三[1-2] A"))
        val result = TimetableParser.parse(html.toByteArray(), "课表.html")
        assertTrue(result.valid, result.errors.toString())
        assertEquals(listOf("甲", "甲", "乙", "乙"), result.courses.single().lessons.map { it.teacher })
        val sameWeeks = listTable(listOf("物理", "甲;乙", "2", "1-2周 一[1-2] A,1-2周 三[1-2] A"))
        assertEquals(listOf("甲", "乙"), TimetableParser.parse(sameWeeks.toByteArray(), "课表.html").courses.single().lessons.map { it.teacher })
    }

    @Test fun `pending schedules keep their matching teacher and week list`() {
        val html = listTable(listOf("物理", "甲;乙", "2", "1-3周 一[1-2] A,4-6周 []"))
        val result = TimetableParser.parse(html.toByteArray(), "课表.html")
        assertTrue(result.valid, result.errors.toString())
        val pending = result.courses.single().lessons.last()
        assertEquals(Lesson(weeks = listOf(4, 5, 6), weekday = 0, teacher = "乙", note = "4-6周 []", unscheduled = true), pending)
        assertTrue(result.unresolved.isEmpty())
    }

    @Test fun `teacher count mismatches preserve names without inventing per lesson assignments`() {
        val file = File(fixtures, "北京师范大学珠海校区2026-2027学年秋季学期学生课表.xls")
        val result = TimetableParser.parse(file.readBytes(), file.name)
        val physics = result.courses.single { it.name == "合成课辛BⅡ" }
        assertEquals("示例卯;示列", physics.extra["教师名单"])
        assertTrue(physics.lessons.all { it.teacher.isBlank() })
        assertEquals(3, physics.lessons.size)
        assertTrue(result.warnings.any { it.contains("合成课辛BⅡ") && it.contains("教师") })
        assertTrue(result.valid)
    }

    @Test fun `partially specified and invalid pending schedules still fail safely`() {
        listOf("1-3周 一[]", "1-3周 [1-2]", "0周 []", "4-2周 []", "").forEach { schedule ->
            val result = TimetableParser.parse(listTable(listOf("物理", "甲", "2", schedule)).toByteArray(), "课表.html")
            assertFalse(result.valid, schedule)
            assertTrue(result.errors.isNotEmpty() || result.unresolved.isNotEmpty(), schedule)
        }
    }

    @Test fun `odd even weeks ranges and list weeks are strict`() {
        assertEquals(listOf(1, 3, 5, 9), TimetableParser.parseWeeks("1-5,9周(单)"))
        assertEquals(listOf(10, 12), TimetableParser.parseWeeks("10-12双周"))
        assertEquals(listOf(1, 3, 5, 6), TimetableParser.parseWeeks("１、３、５－６周"))
        listOf("0-3周", "4-2周", "1-4单双周", "1-3可能", "999999999999周", "2周(单)").forEach { text ->
            assertTrue(runCatching { TimetableParser.parseWeeks(text) }.isFailure, text)
        }
    }

    @Test fun `overlapping and repeated ranges stay sorted and split into consecutive periods`() {
        assertEquals((1..366).toList(), TimetableParser.parseWeeks("365-366,1-366,2,1-366周"))
        assertEquals(listOf(2, 4, 6), TimetableParser.parseWeeks("6,2-6,2-4周(双)"))
        val result = TimetableParser.parse(
            listTable(listOf("物理", "甲", "2", "3,1-3,2周 一[30,4,1-2,2,4-5] A")).toByteArray(), "课表.html",
        )
        assertTrue(result.valid, result.errors.toString())
        assertEquals(listOf(1 to 2, 4 to 5, 30 to 30), result.courses.single().lessons.map { it.startPeriod to it.endPeriod })
        assertTrue(result.courses.single().lessons.all { it.weeks == listOf(1, 2, 3) })
        listOf("367周", "1-367周", "1-366,367周").forEach { source ->
            assertTrue(runCatching { TimetableParser.parseWeeks(source) }.isFailure, source)
        }
    }

    @Test fun `minimal metadata is retained with warnings without invented values`() {
        val html = listTable(listOf("物理", "", "", "1-3周 一[1,3-4] "))
        val parsed = TimetableParser.parse(html.toByteArray(), "课表.html")
        assertTrue(parsed.valid, parsed.errors.toString())
        assertEquals(2, parsed.courses.single().lessons.size)
        assertEquals(listOf(1, 3), parsed.courses.single().lessons.map { it.startPeriod })
        assertTrue(parsed.courses.single().lessons.all { it.location.isEmpty() && it.teacher.isEmpty() })
        assertTrue(parsed.warnings.any { it.contains("教师") })
        assertTrue(parsed.warnings.any { it.contains("地点") })
        assertTrue(parsed.warnings.any { it.contains("学分") })
    }

    @Test fun `same normalized name groups courses but keeps different lessons`() {
        val rows = listTable(listOf(" ＰＨＹＳＩＣＳ ", "甲", "2", "1周 一[1-2] A"), listOf("physics", "乙", "3", "2周 一[1-2] B"))
        val result = TimetableParser.parse(rows.toByteArray(), "课表.html")
        assertEquals(1, result.courses.size)
        assertEquals(2, result.courses.single().lessons.size)
        assertEquals("3", result.courses.single().extra["其他学分"])
        assertTrue(result.warnings.any { it.contains("不同学分") })
    }

    @Test fun `blank grid fields do not shift teacher into the course name`() {
        fun grid(record: String) = "<html><meta charset='UTF-8'><table><tr>" + "一二三四五六日".map { "<th>星期$it</th>" }.joinToString("") + "</tr><tr><td><span class='xkinfo'><div>$record</div></span></td></tr></table></html>"
        val missingTeacher = TimetableParser.parse(grid("物理<br><br>1-3[1-2]<br>教室A").toByteArray(), "课表.xls")
        assertTrue(missingTeacher.valid, missingTeacher.errors.toString())
        assertEquals("", missingTeacher.courses.single().lessons.single().teacher)
        assertEquals("教室A", missingTeacher.courses.single().lessons.single().location)
        val missingName = TimetableParser.parse(grid("<br>甲老师<br>1-3[1-2]<br>教室A").toByteArray(), "课表.xls")
        assertFalse(missingName.valid)
        assertTrue(missingName.errors.isNotEmpty())
        assertTrue(missingName.courses.isEmpty())
    }

    @Test fun `unrecognized malformed and empty sources fail clearly`() {
        for (bytes in listOf(byteArrayOf(), "not a timetable".toByteArray(), "<html><table><tr><td>备注</td></tr></table></html>".toByteArray(), byteArrayOf(0x50, 0x4b, 0, 0, 1))) {
            val result = TimetableParser.parse(bytes, "课表.xls")
            assertFalse(result.valid)
            assertTrue(result.errors.isNotEmpty())
        }
        val broken = TimetableParser.parse(listTable(listOf("物理", "甲", "2", "1-3周 一[8-2] A")).toByteArray(), "课表.html")
        assertFalse(broken.valid)
        assertTrue(broken.errors.any { it.contains("节次") })
    }

    @Test fun `xlsx with inline strings uses the same semantics`() {
        val xml = """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1">${xlsxCell("A1", "课程名称")}${xlsxCell("B1", "任课教师")}${xlsxCell("C1", "学分")}${xlsxCell("D1", "上课时间地点")}</row><row r="2">${xlsxCell("A2", "线性代数")}${xlsxCell("B2", "甲老师")}${xlsxCell("C2", "2.5")}${xlsxCell("D2", "1-8周(单) 三[3-4] 教学楼A")}</row></sheetData></worksheet>"""
        val result = TimetableParser.parse(xlsx(xml), "课表.xlsx")
        assertTrue(result.valid, result.errors.toString())
        val course = result.courses.single()
        assertEquals("线性代数", course.name)
        assertEquals("2.5", course.credits)
        assertEquals(listOf(1, 3, 5, 7), course.lessons.single().weeks)
        assertEquals("甲老师", course.lessons.single().teacher)
    }

    @Test fun `xlsx blocks external entities and formula interpretation`() {
        val external = """<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///secret">]><worksheet><sheetData>&xxe;</sheetData></worksheet>"""
        assertTrue(TimetableParser.parse(xlsx(external), "课表.xlsx").errors.isNotEmpty())
        val formula = """<worksheet><sheetData><row><c r="A1"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>"""
        assertTrue(TimetableParser.parse(xlsx(formula), "课表.xlsx").errors.any { it.contains("公式") })
    }

    @Test fun `JSON data can be reimported through the parser entry point`() {
        val plan = Plan(semesterId = "semester", name = "课表", courses = listOf(Course(name = "代数", lessons = listOf(Lesson(weeks = listOf(1, 2), weekday = 3, startPeriod = 1, endPeriod = 2)))))
        val parsed = TimetableParser.parse(PlanCodec.encode(plan).toByteArray(), "课表.crid.json")
        assertTrue(parsed.valid, parsed.errors.toString())
        assertEquals(plan.courses.map { it.copy(id = "") }, parsed.courses.map { it.copy(id = "") })
    }

    @Test fun `unsupported timetable returns an actionable local import error`() {
        val html = "<html><meta charset='UTF-8'><table><tr><td>Unrecognized timetable</td></tr></table></html>"
        val parsed = TimetableParser.parse(html.toByteArray(), "unknown.html")
        assertFalse(parsed.valid)
        assertEquals(listOf("暂不支持此课表格式，请尝试其他导出格式"), parsed.errors)
    }

    private fun listTable(vararg rows: List<String>): String = "<html><meta charset='UTF-8'><table><tr><th>课程名称</th><th>任课教师</th><th>学分</th><th>上课时间地点</th></tr>" + rows.joinToString("") { row -> "<tr>" + row.joinToString("") { "<td>$it</td>" } + "</tr>" } + "</table></html>"
    private fun xlsxCell(address: String, text: String) = "<c r=\"$address\" t=\"inlineStr\"><is><t>$text</t></is></c>"
    private fun xlsx(sheetXml: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            mapOf("xl/workbook.xml" to "<workbook/>", "xl/worksheets/sheet1.xml" to sheetXml).forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()
}
