package cn.crid.next.core

/**
 * Add reading space around Latin words and room codes next to Chinese text.
 * Numeric dates, times and Chinese counts are not words; URLs and email addresses
 * retain their exact spelling. Source import results and arbitrary metadata stay intact.
 */
fun courseTextSpacing(text: String): String = adjustCourseTextSpacing(text, compact = false)

/** A trailing course level, such as 大学英语1, is separate from the course title. */
fun courseNameSpacing(text: String): String = adjustCourseTextSpacing(text, compact = false, numbers = NumberSpacing.COURSE_NAME)

/** Room/floor numbers are readable units; dates and teaching-week expressions are not room codes. */
fun courseLocationSpacing(text: String): String = adjustCourseTextSpacing(text, compact = false, numbers = NumberSpacing.LOCATION)

/** Typography at a Chinese/Latin boundary is not part of a course's identity. */
internal fun courseIdentitySpacing(text: String): String = adjustCourseTextSpacing(text, compact = true, numbers = NumberSpacing.COURSE_NAME)

/** One boundary for both local and AI imports, after extraction and before preview/storage. */
internal fun Course.withReadableText(): Course = copy(
    name = courseNameSpacing(name.trim()),
    lessons = lessons.map { lesson ->
        lesson.copy(
            location = courseLocationSpacing(lesson.location),
            teacher = courseTextSpacing(lesson.teacher),
            note = courseTextSpacing(lesson.note),
        )
    },
)

private val latinWord = Regex("\\.?[\\p{IsLatin}\\p{Nd}]+(?:[._'’/-][\\p{IsLatin}\\p{Nd}]+)*[+#]*")
private val integer = Regex("(?<![\\p{IsLatin}\\p{N}_.:-])\\p{Nd}+(?![\\p{N}_.:-])")
private val trailingCourseLevel = Regex("\\p{Nd}+$")
private enum class NumberSpacing { NONE, COURSE_NAME, LOCATION }
private val address = Regex(
    "(?:[A-Za-z][A-Za-z0-9+.-]*://|www\\.)[^\\s<>\"，；。！？、（）]+|" +
        "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}",
    RegexOption.IGNORE_CASE,
)

private fun adjustCourseTextSpacing(text: String, compact: Boolean, numbers: NumberSpacing = NumberSpacing.NONE): String {
    if (text.isEmpty()) return text
    val protected = address.findAll(text).map { it.range }.toList()
    val insert = sortedSetOf<Int>()
    val remove = BooleanArray(text.length)
    fun horizontalSpace(character: Char) = character == '\t' || Character.isSpaceChar(character)
    fun chineseAt(index: Int) = index in text.indices &&
        Character.UnicodeScript.of(text.codePointAt(index)) == Character.UnicodeScript.HAN
    fun chineseBefore(index: Int) = index > 0 &&
        Character.UnicodeScript.of(text.codePointBefore(index)) == Character.UnicodeScript.HAN
    fun boundary(from: Int, endExclusive: Int) {
        if (compact) for (index in from until endExclusive) remove[index] = true
        else if (from == endExclusive) insert += from
    }
    fun isProtected(range: IntRange) = protected.any { range.first <= it.last && range.last >= it.first }
    latinWord.findAll(text).forEach { word ->
        if (word.value.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } ||
            isProtected(word.range)) return@forEach
        val start = word.range.first
        val end = word.range.last + 1
        var before = start
        while (before > 0 && horizontalSpace(text[before - 1])) before--
        if (chineseBefore(before)) boundary(before, start)
        var after = end
        while (after < text.length && horizontalSpace(text[after])) after++
        if (chineseAt(after)) boundary(end, after)
    }
    if (numbers == NumberSpacing.COURSE_NAME) {
        trailingCourseLevel.find(text)?.takeUnless { isProtected(it.range) }?.let { number ->
            val start = number.range.first
            var before = start
            while (before > 0 && horizontalSpace(text[before - 1])) before--
            if (chineseBefore(before)) boundary(before, start)
        }
    } else if (numbers == NumberSpacing.LOCATION) {
        // Only explicit building/room units qualify. Never interpret arbitrary Chinese
        // numerals, dates, section numbers or course-week text as a location identifier.
        integer.findAll(text).filterNot { isProtected(it.range) }.forEach { number ->
            val start = number.range.first
            val end = number.range.last + 1
            var before = start
            while (before > 0 && horizontalSpace(text[before - 1])) before--
            var after = end
            while (after < text.length && horizontalSpace(text[after])) after++
            val followsBuilding = before > 0 && text[before - 1] in "楼樓馆館室区區座厅廳栋棟层層"
            val precedesFloorOrRoom = after < text.length && text[after] in "楼樓层層室"
            if (followsBuilding) boundary(before, start)
            if (precedesFloorOrRoom && (followsBuilding || before == 0)) boundary(end, after)
        }
    }
    if (insert.isEmpty() && remove.none { it }) return text
    return buildString(text.length + insert.size) {
        text.forEachIndexed { index, character ->
            if (index in insert) append(' ')
            if (!remove[index]) append(character)
        }
    }
}
