package cn.crid.next.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A plan-only exchange format: no semester, application settings or credentials are serialized. */
object PlanCodec {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
    private val sensitiveField = Regex("(?i)(api[ _-]?key|password|secret|credential|token|authorization|^key$)")

    fun encode(plan: Plan): String {
        require(plan.name.isNotBlank()) { "方案名称不能为空" }
        val errors = DataValidator.validateCourses(plan.courses)
        require(errors.isEmpty()) { errors.joinToString("；") }
        val courses = plan.courses.map { course ->
            course.copy(extra = course.extra.filterKeys { !sensitiveField.containsMatchIn(courseKey(it)) })
        }
        return json.encodeToString(PlanPackage(name = plan.name, courses = courses))
    }

    fun decode(text: String): ParseResult {
        if (text.length > 10_000_000) return failure("数据文件过大")
        if (exceedsNestingLimit(text)) return failure("课表文件结构过于复杂")
        return try {
            val content = text.removePrefix("\uFEFF")
            val root = json.parseToJsonElement(content).jsonObject
            if (root["format"]?.jsonPrimitive?.content != "crid-next") return failure("不是 Crid Next 课表文件")
            val version = root["version"] as? JsonPrimitive
            if (version == null || version.isString || version.content != "1") return failure("暂不支持此课表文件版本")
            val pack = json.decodeFromString<PlanPackage>(content)
            val errors = buildList {
                if (pack.name.isBlank()) add("方案名称不能为空")
                addAll(DataValidator.validateCourses(pack.courses))
                if (pack.courses.any { course -> course.extra.keys.any { sensitiveField.containsMatchIn(courseKey(it)) } }) add("课表文件包含不允许的设置字段")
            }
            val warnings = pack.courses.mapNotNull { course ->
                val pending = course.lessons.count { it.unscheduled }
                if (pending > 0) "${course.name}：$pending 项安排尚未确定上课时间，已保留周次、教师等信息" else null
            }
            ParseResult(pack.name, pack.courses, warnings = warnings, errors = errors)
        } catch (_: Exception) {
            // Serializer exceptions can contain source text. Never relay them into logs or UI.
            failure("课表文件结构无效或包含不支持的字段，请重新选择文件")
        }
    }
    private fun failure(message: String) = ParseResult("导入的课表", emptyList(), errors = listOf(message))

    private fun exceedsNestingLimit(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in text) {
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
            } else when (character) {
                '"' -> inString = true
                '{', '[' -> if (++depth > 64) return true
                '}', ']' -> depth--
            }
        }
        return false
    }
}
