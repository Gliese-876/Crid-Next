package cn.crid.next.core.importer

import cn.crid.next.core.Course
import cn.crid.next.core.Lesson
import cn.crid.next.core.ParseResult
import cn.crid.next.core.PlanCodec
import cn.crid.next.core.courseKey
import java.text.Normalizer

/** BNU row exports and weekly-grid exports share one lesson model. */
object TimetableParser {
    fun parse(bytes: ByteArray, filename: String): ParseResult {
        val name = filename.substringBeforeLast('.').ifBlank { "导入的课表" }
        if (bytes.size > SpreadsheetReader.MAX_FILE_BYTES) return ParseResult(name, emptyList(), errors = listOf("文件过大，请选择不超过 15 MB 的课表文件。"))
        val prefix = String(bytes, 0, minOf(bytes.size, 1024), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        if (prefix.startsWith("{") || filename.endsWith(".json", true)) return PlanCodec.decode(bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t'))
        return try {
            val tables = SpreadsheetReader.read(bytes)
            val state = ParseState(name)
            var supported = false
            tables.forEach { table ->
                val rows = table.rows
                val listHeader = rows.entries.firstOrNull { (_, cells) ->
                    val labels = cells.map { listHeaderLabel(it.text) }
                    labels.any { it == "课程名称" || it == "课程代码及名称" } && "上课时间地点" in labels
                }
                val gridHeader = if (listHeader == null) rows.entries.firstOrNull { (_, cells) -> cells.count { weekday(it.text) != null } >= 5 } else null
                when {
                    listHeader != null -> { parseList(table, listHeader.key, state); supported = true }
                    gridHeader != null -> { parseGrid(table, gridHeader.key, state); supported = true }
                }
            }
            if (!supported) state.errors += "暂不支持此课表格式，请尝试其他导出格式"
            state.result()
        } catch (exception: Exception) {
            val message = if (exception is IllegalArgumentException) exception.message ?: "文件内容无法识别。" else "文件损坏或内容无法读取，请重新导出后再试。"
            ParseResult(name, emptyList(), errors = listOf(message))
        }
    }

    private fun parseList(table: Table, headerRow: Int, state: ParseState) {
        val header = table.rows.getValue(headerRow).associate { listHeaderLabel(it.text) to it.column }
        (table.cells.firstOrNull { it.row < headerRow && it.text.contains("学期") }?.text ?: table.sourceSemester)
            ?.let { state.sourceSemester = it }
        val extraHeaders = setOf("课程代码", "总学时", "上课班号", "修读性质", "辅修标识", "免听标识", "修读方式", "重修标识", "补修标识")
        val combined = "课程代码及名称" in header
        table.rows.entries.sortedBy { it.key }.forEach { (row, cells) ->
            if (row <= headerRow) return@forEach
            val values = cells.associate { it.column to it.text.trim() }
            fun field(key: String) = header[key]?.let { values[it] }.orEmpty()
            val sourceName = field(if (combined) "课程代码及名称" else "课程名称")
            val schedule = field("上课时间地点")
            if (sourceName.isBlank() && schedule.isBlank()) return@forEach
            val identity = if (combined) codeAndCourseName.matchEntire(sourceName) else null
            if (combined && identity == null) {
                state.errors += "第 ${row + 1} 行的课程代码或名称无法识别。"
                return@forEach
            }
            val courseName = identity?.groupValues?.get(2)?.trim() ?: sourceName
            if (courseName.isBlank()) { state.errors += "第 ${row + 1} 行缺少课程名。"; return@forEach }
            val teacher = field("任课教师").ifBlank { field("授课教师") }
            val extra = extraHeaders.associateWith(::field).filterValues { it.isNotBlank() }.toMutableMap()
            identity?.groupValues?.get(1)?.let { code ->
                if (extra["课程代码"]?.let { it != code } == true) {
                    state.errors += "$courseName：课程代码不一致，请核对源文件。"
                    return@forEach
                }
                extra["课程代码"] = code
            }
            val course = state.course(courseName, field("学分"), extra)
            if (teacher.isBlank()) state.warnings += "$courseName：未提供教师。"
            if (course.credits.isBlank()) state.warnings += "$courseName：未提供学分。"
            if (schedule.isBlank()) { state.unresolved(course, "未提供上课时间", teacher); return@forEach }
            val normalized = normalizeTime(schedule)
            val beginnings = listScheduleStart.findAll(normalized).map { it.range.first }.toList()
            if (beginnings.isEmpty() || normalized.substring(0, beginnings.first()).trim(' ', ',', ';', '\n').isNotEmpty()) {
                state.errors += "$courseName：无法识别上课时间“$schedule”。"
                return@forEach
            }
            val segments = beginnings.mapIndexed { index, start ->
                normalized.substring(start, beginnings.getOrNull(index + 1) ?: normalized.length).trim(' ', ',', ';', '\n')
            }
            val teachers = assignTeachers(state, course, teacher, segments)
            segments.forEachIndexed { index, segment ->
                val match = listSchedule.matchEntire(segment)
                if (match == null) { state.errors += "$courseName：无法识别授课安排“$segment”。"; return@forEachIndexed }
                val weekText = match.groupValues[1]
                val day = weekday(match.groupValues[2])
                val periodText = match.groupValues[3]
                val location = match.groupValues[4].trim()
                if (embeddedScheduleHeader.containsMatchIn(location)) {
                    state.errors += "$courseName：无法识别授课安排“$segment”。"
                    return@forEachIndexed
                }
                val assignedTeacher = teachers[index]
                if (day == null && periodText.isBlank()) {
                    state.unscheduled(course, weekText, segment, assignedTeacher, location)
                    return@forEachIndexed
                }
                if (day == null || periodText.isBlank()) { state.unresolved(course, segment, assignedTeacher); return@forEachIndexed }
                addLessons(state, course, weekText, day, periodText, location, assignedTeacher, segment)
            }
        }
    }

    private fun parseGrid(table: Table, headerRow: Int, state: ParseState) {
        table.sourceSemester?.let { state.sourceSemester = it }
        val weekdays = table.rows.getValue(headerRow).mapNotNull { cell -> weekday(cell.text)?.let { cell.column to it } }.toMap()
        table.cells.forEach { cell ->
            if (cell.row <= headerRow || cell.text.isBlank()) return@forEach
            if (cell.columnSpan >= 7) {
                cell.text.lines().filter { it.isNotBlank() }.forEach { line ->
                    val match = unscheduledGridRecord.matchEntire(line.trim())
                    if (match != null) {
                        val course = state.course(match.groupValues[1])
                        state.unscheduled(course, match.groupValues[3], match.groupValues[3], match.groupValues[2])
                    } else if (line.any(Char::isDigit)) state.errors += "无法确定未排课项目的课程名或时间：$line"
                }
                return@forEach
            }
            val day = weekdays[cell.column] ?: return@forEach
            val blocks = cell.blocks.ifEmpty { splitGridBlocks(cell.text) }
            if (blocks.isEmpty()) { state.errors += "第 ${cell.row + 1} 行的课程内容无法识别。"; return@forEach }
            blocks.forEach { block ->
                val lines = block.lines().map(String::trim)
                var match: MatchResult? = null
                val scheduleIndex = lines.indexOfFirst {
                    match = gridSchedule.matchEntire(normalizeTime(it))
                    match != null
                }
                if (scheduleIndex < 1 || lines[0].isBlank()) { state.errors += "无法识别课程及上课时间：${lines.joinToString(" ")}"; return@forEach }
                val course = state.course(lines[0])
                val teacher = lines.subList(1, scheduleIndex).filter(String::isNotBlank).joinToString("；")
                val schedule = requireNotNull(match)
                val location = lines.getOrNull(scheduleIndex + 1).orEmpty()
                val note = lines.drop(scheduleIndex + 2).filter(String::isNotBlank).joinToString("；")
                if (teacher.isBlank()) state.warnings += "${course.name}：未提供教师。"
                addLessons(state, course, schedule.groupValues[1], day, schedule.groupValues[2], location, teacher, lines[scheduleIndex], note)
            }
        }
        state.warnings += "此课表未提供学分，已留空。"
    }

    /** Bind teachers before splitting one source record into disjoint period groups. */
    private fun assignTeachers(state: ParseState, course: MutableCourse, source: String, segments: List<String>): List<String> {
        val teachers = source.split(';', '；').map(String::trim)
        if (teachers.size == 1) return List(segments.size) { teachers.single() }
        if (teachers.size == segments.size) return teachers
        val weeks = segments.map { segment ->
            listSchedule.matchEntire(segment)?.groupValues?.get(1)?.let { runCatching { parseWeeks(it) }.getOrNull() }
        }
        val distinctWeeks = weeks.distinct()
        if (weeks.none { it == null } && teachers.size == distinctWeeks.size) {
            return weeks.map { teachers[distinctWeeks.indexOf(it)] }
        }
        // A shorter teacher list does not identify which teachers repeat across records.
        course.extra["教师名单"] = source
        state.warnings += "${course.name}：教师名单与授课安排数量不同，名单已保留，各次教师待确认。"
        return List(segments.size) { "" }
    }

    private fun splitGridBlocks(text: String): List<String> {
        val lines = text.lines().filter(String::isNotBlank)
        val scheduleIndices = lines.indices.filter { gridSchedule.matches(normalizeTime(lines[it])) }
        if (scheduleIndices.isEmpty()) return emptyList()
        val starts = scheduleIndices.map { (it - 2).coerceAtLeast(0) }
        return starts.mapIndexed { index, start -> lines.subList(start, starts.getOrNull(index + 1) ?: lines.size).joinToString("\n") }
    }

    private fun addLessons(state: ParseState, course: MutableCourse, weeks: String, day: Int, periods: String, location: String, teacher: String, raw: String, note: String = "") {
        try {
            val parsedWeeks = parseWeeks(weeks)
            val parsedPeriods = parseNumbers(periods, 30)
            require(parsedPeriods.isNotEmpty())
            val groups = mutableListOf<MutableList<Int>>()
            parsedPeriods.forEach { period ->
                if (groups.isEmpty() || groups.last().last() + 1 != period) groups += mutableListOf(period) else groups.last() += period
            }
            groups.forEach { group -> course.lessons += Lesson(weeks = parsedWeeks, weekday = day, startPeriod = group.first(), endPeriod = group.last(), location = location, teacher = teacher, note = note) }
            if (location.isBlank()) state.warnings += "${course.name}：未提供地点。"
        } catch (_: IllegalArgumentException) { state.errors += "${course.name}：周次或节次无效“$raw”。" }
    }

    internal fun parseWeeks(source: String): List<Int> {
        val normalized = normalizeTime(source)
        val odd = normalized.contains('单')
        val even = normalized.contains('双')
        require(!(odd && even)) { "单双周含义不明确" }
        val numbers = normalized.replace(weekMarkers, "")
        return parseNumbers(numbers, 366).filter { (!odd || it % 2 == 1) && (!even || it % 2 == 0) }.also { require(it.isNotEmpty()) }
    }

    private fun parseNumbers(source: String, maximum: Int): List<Int> {
        val text = normalizeTime(source).replace(whitespace, "").replace('、', ',').replace('~', '-').replace('至', '-')
        require(numberExpression.matches(text))
        val included = BooleanArray(maximum + 1)
        var last = 0
        text.split(',').forEach { part ->
            val start = part.substringBefore('-').toIntOrNull() ?: throw IllegalArgumentException()
            val end = part.substringAfter('-', part).toIntOrNull() ?: throw IllegalArgumentException()
            require(start in 1..maximum && end in start..maximum)
            included.fill(true, start, end + 1)
            last = maxOf(last, end)
        }
        return (1..last).filter { included[it] }
    }

    private const val WEEK_EXPRESSION = "\\d+(?:\\s*[-~至]\\s*\\d+)?(?:\\s*[,、]\\s*\\d+(?:\\s*[-~至]\\s*\\d+)?)*\\s*(?:[单双]周|周)"
    // A room such as A203 or 楼 203 immediately before a comma must not become
    // the first number of the following week list (203,3-4周).
    private val listScheduleStart = Regex("(?:^|(?<=[,;\\r\\n]))\\s*$WEEK_EXPRESSION")
    private val embeddedScheduleHeader = Regex("(?<![\\d-])$WEEK_EXPRESSION(?:\\s*\\([单双](?:周)?\\))?\\s*(?:星期|周)?[一二三四五六日天1-7]?\\s*\\[")
    private val listSchedule = Regex("^(.+?周(?:\\s*\\([单双](?:周)?\\))?)\\s*(?:星期|周)?([一二三四五六日天1-7]?)\\s*\\[([^]]*)]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val gridSchedule = Regex("^(\\d[\\d\\s,、~至\\-]*(?:[单双]?周)?(?:\\([单双](?:周)?\\))?)\\s*\\[([^]]+)]$")
    private val unscheduledGridRecord = Regex("^(.+?)\\s+(\\S+)\\s+(\\d[\\d,、~至\\-]*(?:周(?:[（(][单双][）)])?)?)$")
    private val whitespace = Regex("\\s")
    private val weekMarkers = Regex("[周单双()\\s]")
    private val numberExpression = Regex("\\d+(?:-\\d+)?(?:,\\d+(?:-\\d+)?)*")
    private fun normalizeTime(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).replace('－', '-').replace('–', '-').replace('—', '-').trim()
    private fun compact(text: String) = text.replace(whitespace, "")
    private val codeAndCourseName = Regex("^[\\[［]([^\\]］\\s]+)[\\]］]\\s*(\\S.*)$", RegexOption.DOT_MATCHES_ALL)
    private fun listHeaderLabel(text: String): String = when (val label = compact(Normalizer.normalize(text, Normalizer.Form.NFKC))) {
        "[课程号]课程名", "[课程号]课程名称", "[课程代码]课程名", "[课程代码]课程名称" -> "课程代码及名称"
        "上课时间、地点", "上课时间,地点" -> "上课时间地点"
        "辅修标志" -> "辅修标识"
        "是否免听" -> "免听标识"
        else -> label
    }
    private fun weekday(text: String): Int? {
        val value = compact(text).removePrefix("星期").removePrefix("周")
        return when (value) { "一", "1" -> 1; "二", "2" -> 2; "三", "3" -> 3; "四", "4" -> 4; "五", "5" -> 5; "六", "6" -> 6; "日", "天", "7" -> 7; else -> null }
    }

    private data class MutableCourse(val name: String, var credits: String = "", val extra: MutableMap<String, String> = linkedMapOf(), val lessons: MutableList<Lesson> = mutableListOf())
    private class ParseState(val name: String) {
        val courses = linkedMapOf<String, MutableCourse>()
        val warnings = linkedSetOf<String>()
        val errors = linkedSetOf<String>()
        val unresolved = linkedSetOf<String>()
        var sourceSemester: String? = null
        fun course(name: String, credits: String = "", extra: Map<String, String> = emptyMap()): MutableCourse {
            val course = courses.getOrPut(courseKey(name)) { MutableCourse(name.trim(), credits) }
            if (course.credits.isBlank()) course.credits = credits
            else if (credits.isNotBlank() && course.credits != credits) {
                course.extra["其他学分"] = listOfNotNull(course.extra["其他学分"], credits).distinct().joinToString("；")
                warnings += "${course.name}：文件中有不同学分值，已保留。"
            }
            extra.forEach { (key, value) -> course.extra[key] = listOfNotNull(course.extra[key], value).distinct().joinToString("；") }
            return course
        }
        fun unresolved(course: MutableCourse, raw: String, teacher: String) {
            val detail = listOf(raw, teacher).filter(String::isNotBlank).joinToString("；")
            course.extra["未排课"] = listOfNotNull(course.extra["未排课"], detail).distinct().joinToString("\n")
            unresolved += "${course.name}：$detail（缺少星期或节次）"
        }
        fun unscheduled(course: MutableCourse, weeks: String, raw: String, teacher: String, location: String = "") {
            try {
                val parsedWeeks = parseWeeks(weeks)
                course.lessons += Lesson(weeks = parsedWeeks, weekday = 0, teacher = teacher, location = location, note = raw, unscheduled = true)
                val detail = listOf(raw, teacher).filter(String::isNotBlank).joinToString("；")
                course.extra["未排课"] = listOfNotNull(course.extra["未排课"], detail).distinct().joinToString("\n")
                warnings += "${course.name}：部分授课时间待安排，已保留周次和教师。"
            } catch (_: IllegalArgumentException) {
                errors += "${course.name}：周次无效“$raw”。"
            }
        }
        fun result(): ParseResult {
            if (courses.isEmpty() && errors.isEmpty()) errors += "文件中没有识别到课程。"
            return ParseResult(name, courses.values.map { Course(name = it.name, credits = it.credits, extra = it.extra.toMap(), lessons = it.lessons.toList()) }, warnings.toList(), errors.toList(), sourceSemester, unresolved.toList())
        }
    }
}
