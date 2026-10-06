package cn.crid.next.core

/** Both lists are sorted and distinct. */
internal fun weeksOverlap(first: List<Int>, second: List<Int>): Boolean {
    var left = 0
    var right = 0
    while (left < first.size && right < second.size) {
        when {
            first[left] == second[right] -> return true
            first[left] < second[right] -> left++
            else -> right++
        }
    }
    return false
}

/** Compact consecutive weeks without changing the represented set. */
fun formatWeekRanges(weeks: Iterable<Int>, separator: String = ", "): String {
    val ordered = weeks.toSortedSet().toList()
    if (ordered.isEmpty()) return ""
    val ranges = mutableListOf<String>()
    var start = ordered.first()
    var end = start
    fun appendRange() { ranges += if (start == end) "$start" else "$start–$end" }
    for (week in ordered.drop(1)) {
        if (week.toLong() == end.toLong() + 1) end = week
        else {
            appendRange()
            start = week
            end = week
        }
    }
    appendRange()
    return ranges.joinToString(separator)
}
