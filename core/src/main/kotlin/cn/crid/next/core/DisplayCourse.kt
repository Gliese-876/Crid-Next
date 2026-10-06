package cn.crid.next.core

/**
 * A course card at one position on the displayed day. The underlying teaching
 * records stay intact so that each teacher, room and week remains attributable.
 */
data class DisplayCourse(
    val occurrences: List<Occurrence>,
    private val projectedRepresentative: Occurrence? = null,
) {
    init { require(occurrences.isNotEmpty()) }

    /** A contiguous card uses a display-only time span; occurrences retain the source records. */
    val representative: Occurrence = projectedRepresentative ?: occurrences.minWithOrNull(representativeOrder)!!
    internal val normalizedName: String = courseKey(representative.course.name)
    val isActual: Boolean = occurrences.any { it.isActual }
    val weeks: List<Int> = buildSet {
        occurrences.forEach { if (it.lesson.date != null) add(it.week) else addAll(it.lesson.weeks) }
    }.sorted()
}

private val representativeOrder = compareBy<Occurrence> { !it.isActual }
    .thenBy { OccurrenceStatus.OUT_OF_WEEK in it.statuses }
    .thenBy { it.lesson.weeks.minOrNull() ?: it.week }
    .thenBy { it.course.name }
    .thenBy { it.lesson.teacher }
    .thenBy { it.lesson.location }

private data class DisplaySlot(
    val course: String,
    val date: java.time.LocalDate,
    val start: java.time.LocalTime,
    val end: java.time.LocalTime,
)

private val displayOrder =
    compareBy<DisplayCourse> { it.representative.date }
        .thenBy { it.representative.start }
        .thenBy { !it.isActual }
        .thenBy { it.representative.end }
        .thenBy { it.normalizedName }

private data class TeachingContext(
    val course: String,
    val date: java.time.LocalDate,
    val statuses: Set<OccurrenceStatus>,
    val weeks: List<Int>,
    val weekday: Int,
    val fixedDate: String?,
    val teacher: String,
    val location: String,
    val note: String,
    val holiday: String?,
)

private fun teachingContext(item: Occurrence, name: String): TeachingContext = TeachingContext(
    name, item.date, item.statuses, item.lesson.weeks.distinct().sorted(),
    item.lesson.weekday, item.lesson.date, item.lesson.teacher, item.lesson.location, item.lesson.note, item.holidayName,
)

private fun DisplayCourse.singleVisibleContext(): TeachingContext? {
    var context: TeachingContext? = null
    for (item in occurrences) {
        if (item.statuses != representative.statuses) continue
        val next = teachingContext(item, normalizedName)
        if (context != null && context != next) return null
        context = next
    }
    return context
}

private data class Continuation(val context: TeachingContext, val nextPeriod: Int)

private fun Occurrence.usesPeriods(): Boolean = lesson.startPeriod != null && lesson.endPeriod != null &&
    lesson.startTime == null && lesson.endTime == null

/** Consecutive period numbers establish a continuous class; nearby clock times alone do not. */
private fun joinConsecutivePeriods(courses: List<DisplayCourse>): List<DisplayCourse> {
    val joined = mutableListOf<DisplayCourse>()
    val continuations = mutableMapOf<Continuation, java.util.TreeSet<Int>>()
    courses.sortedWith(displayOrder).forEach { next ->
        val second = next.representative
        val context = next.singleVisibleContext()
        val usesPeriods = context != null && second.usesPeriods()
        val candidates = if (usesPeriods) continuations[Continuation(context, second.lesson.startPeriod!!)] else null
        val index = candidates?.descendingIterator()?.asSequence()?.firstOrNull { !joined[it].representative.end.isAfter(second.start) }
        val targetIndex = index ?: joined.size
        if (index == null) joined += next
        else {
            candidates.remove(index)
            val previous = joined[index]
            val first = previous.representative
            joined[index] = DisplayCourse(
                previous.occurrences + next.occurrences,
                first.copy(end = second.end, lesson = first.lesson.copy(endPeriod = second.lesson.endPeriod)),
            )
        }
        if (usesPeriods) continuations.getOrPut(Continuation(context, second.lesson.endPeriod!! + 1)) { java.util.TreeSet() }.add(targetIndex)
    }
    return joined
}

/**
 * Merge appearances of one named course without changing teaching records.
 * Consecutive periods with the same teaching context share one card, including
 * their internal break. Other weeks may share an overlapping current card but
 * cannot extend its time range. Independent teaching records remain intact.
 */
fun displayCourses(occurrences: List<Occurrence>): List<DisplayCourse> {
    val names = mutableMapOf<String, String>()
    val exactSlots = occurrences.groupBy {
        DisplaySlot(names.getOrPut(it.course.name) { courseKey(it.course.name) }, it.date, it.start, it.end)
    }.values.map { DisplayCourse(it) }
    val (current, otherWeeks) = exactSlots.partition { course ->
        course.occurrences.any { OccurrenceStatus.OUT_OF_WEEK !in it.statuses }
    }
    val projected = joinConsecutivePeriods(current).sortedWith(displayOrder).toMutableList()
    val sameCourse = mutableMapOf<Pair<String, java.time.LocalDate>, MutableList<Int>>()
    projected.forEachIndexed { index, course -> sameCourse.getOrPut(course.normalizedName to course.representative.date) { mutableListOf() }.add(index) }
    joinConsecutivePeriods(otherWeeks).sortedWith(
        compareBy<DisplayCourse> { it.weeks.firstOrNull() ?: it.representative.week }
            .then(displayOrder)
    ).forEach { other ->
        val key = other.normalizedName to other.representative.date
        val candidates = sameCourse.getOrPut(key) { mutableListOf() }
        val candidate = candidates.firstOrNull { index ->
            val existing = projected[index]
            val first = existing.representative
            val second = other.representative
            first.start < second.end && second.start < first.end && !weeksOverlap(existing.weeks, other.weeks)
        }
        if (candidate == null) {
            candidates += projected.size
            projected += other
        }
        else projected[candidate] = DisplayCourse(projected[candidate].occurrences + other.occurrences, projected[candidate].representative)
    }
    return projected.sortedWith(displayOrder)
}
