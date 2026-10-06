package cn.crid.next.platform

import android.net.Network
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.HolidayDay
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Silent, validated refresh of the 2026 notice. Later years require a reviewed official source. */
object HolidaySync {
    private const val MAX_RESPONSE_BYTES = 1_048_576
    private const val TIMEOUT_MS = 8_000
    private val sources = listOf(
        HolidayDefaults.SOURCE,
        // Official Beijing government republication of the same complete notice.
        "https://www.beijing.gov.cn/zhengce/zhengcefagui/202511/t20251104_4258873.html",
    )
    private val allowedHosts = setOf("www.gov.cn", "www.beijing.gov.cn")

    /** null leaves the caller's persisted calendar and last successful sync intact. */
    suspend fun fetch(current: HolidayCalendar, network: Network? = null): HolidayCalendar? = withContext(Dispatchers.IO) {
        // Never replace a newer, already-supported year with this fixed 2026 source.
        if (current.year > 2026) return@withContext null
        for (source in sources) {
            coroutineContext.ensureActive()
            try {
                val page = download(source, network)
                val parsed = parseNotice(page) ?: continue
                return@withContext refreshed(current, parsed, source, Instant.now())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // Offline, timeouts, TLS failures, and HTTP errors retain the old data.
            } catch (_: SecurityException) {
                // No network permission also leaves the bundled calendar usable.
            }
        }
        null
    }

    private suspend fun download(source: String, network: Network?): String {
        val url = URL(source)
        require(url.protocol == "https" && url.host in allowedHosts && url.port == -1 && url.userInfo == null)
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "CridNext/1.0 (Android; holiday calendar)")
            if (connection.responseCode != 200) throw IOException("Unexpected HTTP status")
            val contentType = connection.contentType.orEmpty().lowercase()
            if (!contentType.startsWith("text/html") && !contentType.startsWith("application/xhtml+xml")) {
                throw IOException("Unexpected response type")
            }
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw IOException("Response too large")
            val deadlineNanos = System.nanoTime() + 15_000_000_000L
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    coroutineContext.ensureActive()
                    if (System.nanoTime() > deadlineNanos) throw IOException("Response timed out")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("Response too large")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    internal fun isVerifiedNotice(html: String): Boolean = parseNotice(html) != null

    /**
     * Parse all seven date sections, then validate the entire result before replacing any data.
     * Date values come from the fetched notice, so a valid amendment changes the saved calendar.
     * The independently verified statutory dates remain distinct from all additional rest days.
     */
    internal fun parseNotice(html: String): HolidayCalendar? = runCatching {
        if (html.length > MAX_RESPONSE_BYTES) return null
        val visible = html
            .replace(Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&#(x[0-9a-f]+|[0-9]+);", RegexOption.IGNORE_CASE)) { match ->
                val value = match.groupValues[1]
                val code = if (value.startsWith("x", true)) value.drop(1).toIntOrNull(16) else value.toIntOrNull()
                if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else ""
            }
            .replace("&nbsp;", " ")
            .replace("&ensp;", " ")
            .replace("&emsp;", " ")
        val text = canonical(visible)
        if (!text.contains(canonical("国务院办公厅关于2026年部分节假日安排的通知")) ||
            !text.contains(canonical("国办发明电〔2025〕7号"))) return null
        val headings = sectionHeading.findAll(text).toList()
        if (headings.map { it.groupValues[1] } != listOf("一", "二", "三", "四", "五", "六", "七") ||
            headings.map { it.groupValues[2] } != holidayNames) return null
        val signature = text.indexOf("国务院办公厅", headings.last().range.last + 1)
        if (signature < 0) return null
        val signedDate = Regex("^国务院办公厅(2025|2026)年(\\d{1,2})月(\\d{1,2})日")
            .find(text.substring(signature)) ?: return null
        if (parseDay(signedDate.groupValues[1], signedDate.groupValues[2], signedDate.groupValues[3], "") == null) return null
        val statutory = HolidayDefaults.calendar().days.filter { it.statutory }.groupBy { it.name }
        val days = mutableListOf<HolidayDay>()
        headings.forEachIndexed { index, heading ->
            val name = holidayNames[index]
            val endIndex = headings.getOrNull(index + 1)?.range?.first ?: signature
            val section = text.substring(heading.range.last + 1, endIndex)
            val rest = restSentence.find(section)?.takeIf { it.range.first == 0 } ?: return null
            val first = parseDay(rest.groupValues[1], rest.groupValues[2], rest.groupValues[3], rest.groupValues[4]) ?: return null
            val last = if (rest.groupValues[7].isNotEmpty()) parseDay(rest.groupValues[5],
                rest.groupValues[6].ifEmpty { first.monthValue.toString() }, rest.groupValues[7], rest.groupValues[8]) ?: return null
                else first
            val count = rest.groupValues[9].toLongOrNull() ?: return null
            if (first.year != 2026 || last.year != 2026 || last.isBefore(first) ||
                count !in 1..16 || ChronoUnit.DAYS.between(first, last) + 1 != count) return null
            val required = statutory[name].orEmpty().map { LocalDate.parse(it.date) }.toSet()
            if (required.isEmpty() || required.any { it.isBefore(first) || it.isAfter(last) }) return null
            generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.forEach { date ->
                days += HolidayDay(date.toString(), name, statutory = date in required, extraRest = date !in required)
            }
            var remainder = section.substring(rest.range.last + 1)
            if (remainder.firstOrNull()?.isDigit() == true) {
                val workEnd = remainder.indexOf("上班。")
                if (workEnd < 0) return null
                val workText = remainder.substring(0, workEnd)
                val tokens = workText.split("、")
                if (tokens.isEmpty() || tokens.size > 4) return null
                tokens.forEach { token ->
                    val match = workDate.matchEntire(token) ?: return null
                    val date = parseDay(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4]) ?: return null
                    // A workday must be a nearby date in this same supported calendar year.
                    if (date.year != 2026 || date.isBefore(first.minusDays(31)) || date.isAfter(last.plusDays(31))) return null
                    days += HolidayDay(date.toString(), name, workday = true)
                }
                remainder = remainder.substring(workEnd + "上班。".length)
            }
            // The last section may be followed by general prose. Never ignore another date rule.
            if (index != holidayNames.lastIndex && remainder.isNotEmpty()) return null
            if (unparsedDateRule.containsMatchIn(remainder)) return null
        }
        if (days.map { it.date }.distinct().size != days.size || days.count { it.statutory } != 13) return null
        HolidayCalendar(year = 2026, days = days.sortedBy { it.date })
    }.getOrNull()

    internal fun refreshed(current: HolidayCalendar, parsed: HolidayCalendar, source: String, now: Instant): HolidayCalendar {
        val confirmed = current.days.filter { it.workday && it.teachingDate != null }
            .groupBy { it.date }
            .mapValues { (_, entries) ->
                entries.mapNotNull { it.teachingDate }.distinct().singleOrNull()?.takeIf { date ->
                    runCatching { LocalDate.parse(date) }.isSuccess
                }
            }
        return parsed.copy(
            days = parsed.days.map { day -> if (day.workday) day.copy(teachingDate = confirmed[day.date]) else day },
            syncedAt = now.toString(),
            source = source,
        )
    }

    private fun parseDay(year: String, month: String, day: String, note: String): LocalDate? = runCatching {
        val date = LocalDate.of(year.ifEmpty { "2026" }.toInt(), month.toInt(), day.toInt())
        val weekday = Regex("周([一二三四五六日天])").findAll(note).toList()
        if (note.contains("周") && (weekday.size != 1 || note.count { it == '周' } != 1)) return null
        if (weekday.isNotEmpty()) {
            val announced = "一二三四五六日".indexOf(weekday.single().groupValues[1].replace("天", "日")) + 1
            if (date.dayOfWeek.value != announced) return null
        }
        date
    }.getOrNull()

    private fun canonical(value: String): String {
        val traditional = "國務辦廳關於節發電號週農曆臘勞動慶調"
        val simplified = "国务办厅关于节发电号周农历腊劳动庆调"
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        return buildString(normalized.length) {
            normalized.forEach { char ->
                if (!char.isWhitespace() && char != '\u00a0' && char != '\u200b') {
                    val index = traditional.indexOf(char)
                    append(if (index >= 0) simplified[index] else char)
                }
            }
        }
    }

    private val holidayNames = listOf("元旦", "春节", "清明节", "劳动节", "端午节", "中秋节", "国庆节")
    private val sectionHeading = Regex("([一二三四五六七])、(${holidayNames.joinToString("|")}):")
    private val restSentence = Regex(
        "^(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日(?:\\(([^()]{0,50})\\))?" +
            "(?:至(?:(\\d{4})年)?(?:(\\d{1,2})月)?(\\d{1,2})日(?:\\(([^()]{0,50})\\))?)?" +
            "放假(?:调休)?,共(\\d{1,2})天。")
    private val workDate = Regex("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日(?:\\(([^()]{0,50})\\))?")
    private val unparsedDateRule = Regex("\\d{1,4}[年月日]|放假|上班|调休|补班|补课")
}
