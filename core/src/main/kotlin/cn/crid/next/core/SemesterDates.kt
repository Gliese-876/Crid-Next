package cn.crid.next.core

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Inclusive arithmetic; a supplied week policy requires complete calendar weeks. */
object SemesterDates {
    data class Result(val startDate: String, val endDate: String, val weeks: Int)

    // Null preserves older stored semesters; editors explicitly supply the current week policy.
    fun resolve(start: String?, end: String?, weeks: Int?, weekStartsSunday: Boolean? = null): Result {
        require(listOf(start, end, weeks).count { it != null } >= 2) { "请填写起始日、终止日和周数中的至少两项" }
        require(weeks == null || weeks > 0) { "周数必须为正整数" }
        try {
            var first = start?.let(LocalDate::parse)
            var last = end?.let(LocalDate::parse)
            if (weekStartsSunday != null) {
                require(first == null || first.dayOfWeek == startWeekday(weekStartsSunday)) {
                    if (weekStartsSunday) "起始日请选择周日" else "起始日请选择周一"
                }
                require(last == null || last.dayOfWeek == endWeekday(weekStartsSunday)) {
                    if (weekStartsSunday) "终止日请选择周六" else "终止日请选择周日"
                }
            }
            if (first != null && last != null) {
                require(!first.isAfter(last)) { "起始日不能晚于终止日" }
                val days = ChronoUnit.DAYS.between(first, last) + 1L
                require(weekStartsSunday == null || days % 7L == 0L) { "请选择完整的周次" }
                val calculated = Math.toIntExact((days + 6L) / 7L)
                require(weeks == null || weeks == calculated) { "起止日期与周数不一致" }
                return Result(first.toString(), last.toString(), calculated)
            }
            val duration = requireNotNull(weeks).toLong() * 7L - 1L
            if (first != null) last = first.plusDays(duration)
            else first = requireNotNull(last).minusDays(duration)
            return Result(requireNotNull(first).toString(), requireNotNull(last).toString(), weeks)
        } catch (exception: DateTimeException) {
            throw IllegalArgumentException("请填写有效的日期（YYYY-MM-DD）", exception)
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("日期范围过大", exception)
        }
    }

    fun startWeekday(weekStartsSunday: Boolean): DayOfWeek = if (weekStartsSunday) DayOfWeek.SUNDAY else DayOfWeek.MONDAY
    fun endWeekday(weekStartsSunday: Boolean): DayOfWeek = if (weekStartsSunday) DayOfWeek.SATURDAY else DayOfWeek.SUNDAY
}

enum class SemesterField { START, END, WEEKS }

/** The two most recently edited distinct fields are authoritative; derived changes are not edits. */
data class SemesterEditor(
    val start: String? = null,
    val end: String? = null,
    val weeks: Int? = null,
    val manualOrder: List<SemesterField> = listOfNotNull(
        weeks?.let { SemesterField.WEEKS }, start?.let { SemesterField.START }, end?.let { SemesterField.END }
    ),
    val weekStartsSunday: Boolean? = null,
) {
    private val authoritative: List<SemesterField> get() = manualOrder.distinct().takeLast(2)
    val derivedField: SemesterField? get() = if (authoritative.size == 2) SemesterField.entries.first { it !in authoritative } else null
    private fun calculate(): SemesterDates.Result {
        require(authoritative.size == 2) { "请填写起始日、终止日和周数中的至少两项" }
        return SemesterDates.resolve(
            start.takeIf { SemesterField.START in authoritative },
            end.takeIf { SemesterField.END in authoritative },
            weeks.takeIf { SemesterField.WEEKS in authoritative },
            weekStartsSunday,
        )
    }
    val result: SemesterDates.Result? get() = runCatching { calculate() }.getOrNull()
    val errors: List<String> get() = runCatching { calculate() }.exceptionOrNull()?.let { listOf(it.message ?: "学期日期无效") } ?: emptyList()
    fun editStart(value: String): SemesterEditor = copy(start = value, manualOrder = edited(SemesterField.START)).derive()
    fun editEnd(value: String): SemesterEditor = copy(end = value, manualOrder = edited(SemesterField.END)).derive()
    fun editWeeks(value: Int?): SemesterEditor = copy(weeks = value, manualOrder = edited(SemesterField.WEEKS)).derive()
    private fun edited(field: SemesterField) = manualOrder.filterNot { it == field } + field
    private fun derive(): SemesterEditor = result?.let { copy(start = it.startDate, end = it.endDate, weeks = it.weeks) } ?: this
}
