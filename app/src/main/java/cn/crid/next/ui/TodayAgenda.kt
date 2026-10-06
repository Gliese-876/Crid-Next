package cn.crid.next.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.crid.next.core.Occurrence
import cn.crid.next.core.OccurrenceStatus
import cn.crid.next.core.Period
import cn.crid.next.core.courseLocationSpacing
import cn.crid.next.core.courseNameSpacing
import cn.crid.next.core.courseTextSpacing
import cn.crid.next.core.displayCourses
import java.time.LocalTime
import kotlin.math.floor
import kotlin.math.roundToInt

internal enum class TodayPeriod(val id: String) {
    MORNING("morning"), AFTERNOON("afternoon"), EVENING("evening");

    fun label(text: UiText): String = when (this) {
        MORNING -> text.t("上午", "Morning", "上午")
        AFTERNOON -> text.t("下午", "Afternoon", "下午")
        EVENING -> text.t("晚上", "Evening", "晚上")
    }
}

/** A lesson keeps its complete time span in the section where it starts. */
internal fun todayPeriod(start: LocalTime): TodayPeriod = when {
    start.hour < 12 -> TodayPeriod.MORNING
    start.hour < 18 -> TodayPeriod.AFTERNOON
    else -> TodayPeriod.EVENING
}

/** Use the most common consecutive pair, including its short break but excluding meal breaks. */
internal fun todayReferenceDuration(periods: List<Period>): Int {
    val timed = periods.sortedBy { it.number }.mapNotNull { period ->
        runCatching { period.number to MinuteSpan(LocalTime.parse(period.start).minuteOfDay(),
            LocalTime.parse(period.end).minuteOfDay()) }.getOrNull()
    }
    val pairs = timed.zipWithNext().mapNotNull { (first, second) ->
        val gap = second.second.start - first.second.end
        val shorter = minOf(first.second.end - first.second.start, second.second.end - second.second.start)
        (second.second.end - first.second.start).takeIf { second.first == first.first + 1 && gap in 0..shorter }
    }
    return pairs.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
        ?: timed.map { (it.second.end - it.second.start) * 2 }.sorted().let { it.getOrNull(it.size / 2) } ?: 100
}

internal class TodayAgendaScale(val referenceMinutes: Int, val density: Float, fontScale: Float) {
    init { require(referenceMinutes > 0 && density.isFinite() && density > 0 && fontScale.isFinite() && fontScale > 0) }
    private val textScale = fontScale.coerceAtLeast(1f)
    private val minimum = 16f + 60f * textScale
    private val pixelsPerMinute = (24f + 60f * textScale) / referenceMinutes
    private val smoothing = 2f * textScale
    private val referencePixels = naturalPixels(referenceMinutes)

    fun naturalPixels(duration: Int): Int =
        (readableCourseHeight(duration, minimum, pixelsPerMinute, smoothing) * density).roundToInt()

    /** Short lessons compress vertical whitespace only; the reference keeps its original 12 dp. */
    fun verticalPaddingPixels(duration: Int): Int =
        floor(12f * density - maxOf(0, referencePixels - naturalPixels(duration)) / 2f).toInt()
            .coerceIn((8f * density).roundToInt(), (12f * density).roundToInt())
}

/** Reuse the timetable's duration curve while keeping two periods near their original card height. */
internal fun todayAgendaHeights(spans: List<MinuteSpan>, contentHeights: List<Int>, density: Float,
    fontScale: Float, referenceMinutes: Int = 100): List<Int> {
    require(spans.size == contentHeights.size && contentHeights.all { it >= 0 })
    val scale = TodayAgendaScale(referenceMinutes, density, fontScale)
    if (spans.isEmpty()) return emptyList()
    val minimums = spans.indices.groupBy { spans[it].end - spans[it].start }.mapValues { (_, indices) ->
        indices.maxOf { contentHeights[it] }
    }
    val durations = minimums.keys.sorted()
    val heights = IntArray(durations.size)
    // Content stays complete; a one-pixel step preserves duration order after rounding.
    durations.forEachIndexed { index, duration ->
        heights[index] = maxOf(scale.naturalPixels(duration), minimums.getValue(duration),
            if (index == 0) 1 else heights[index - 1] + 1)
    }
    val byDuration = durations.zip(heights.toList()).toMap()
    return spans.map { byDuration.getValue(it.end - it.start) }
}

/** A dedicated day axis compresses clock-time gaps while keeping readable, monotonic course blocks. */
@Composable
internal fun TodayAgenda(occurrences: List<Occurrence>, periods: List<Period>, text: UiText, selectedOrigin: ModalOrigin?,
    onSelect: (Occurrence, ModalOrigin) -> Unit) {
    val items = remember(occurrences) { displayCourses(occurrences).map { it.representative } }
    val groups = remember(items) {
        TodayPeriod.entries.associateWith { period -> items.indices.filter { todayPeriod(items[it].start) == period } }
    }
    val density = LocalDensity.current
    val reference = remember(periods) { todayReferenceDuration(periods) }
    val sizing = remember(reference, density.density, density.fontScale) { TodayAgendaScale(reference, density.density, density.fontScale) }
    val measurer = rememberTextMeasurer(cacheSize = items.size * 3 + 1)
    val titleStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val detailStyle = MaterialTheme.typography.bodySmall
    val timeStyle = MaterialTheme.typography.labelMedium
    val timeWidth = measurer.measure("23:59", style = timeStyle).size.width
    val axisWidth = maxOf(54.dp, with(density) { timeWidth.toDp() + 8.dp })
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("today_agenda")) {
        val contentWidth = with(density) { (maxWidth - axisWidth - 8.dp - 24.dp).roundToPx().coerceAtLeast(1) }
        // Keep measured layouts in TextMeasurer so font resolution and density invalidate them.
        val heights = run {
            val spacing = with(density) { 4.dp.roundToPx() }
            val content = items.map { item ->
                val fields = listOfNotNull(
                    item.lesson.location.takeIf { it.isNotBlank() }?.let { text.t("地点： ", "Location: ") + courseLocationSpacing(it) },
                    item.lesson.teacher.takeIf { it.isNotBlank() }?.let { text.t("教师： ", "Teacher: ") + courseTextSpacing(it) },
                )
                sizing.verticalPaddingPixels(item.durationMinutes.toInt()) * 2 +
                    measurer.measure(courseNameSpacing(item.course.name), style = titleStyle, constraints = Constraints(maxWidth = contentWidth)).size.height +
                    fields.sumOf { measurer.measure(it, style = detailStyle, constraints = Constraints(maxWidth = contentWidth)).size.height } +
                    spacing * fields.size
            }
            todayAgendaHeights(items.map { MinuteSpan(it.start.minuteOfDay(), it.end.minuteOfDay()) },
                content, density.density, density.fontScale, reference)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TodayPeriod.entries.forEach { period ->
                val indices = groups.getValue(period)
                if (indices.isEmpty()) return@forEach
                Column(Modifier.fillMaxWidth().testTag("today_period_${period.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(period.label(text), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = axisWidth + 8.dp).testTag("period_heading_${period.id}").semantics { heading() })
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        indices.forEach { index ->
                            val occurrence = items[index]
                            val height = with(density) { heights[index].toDp() }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val axisColor = MaterialTheme.colorScheme.outlineVariant
                                val timeInset = with(density) { timeStyle.lineHeight.toDp() + 5.dp }
                                Column(Modifier.width(axisWidth).height(height).testTag("agenda_time_$index").drawBehind {
                                    val x = size.width / 2f
                                    if (size.height > timeInset.toPx() * 2) drawLine(axisColor, Offset(x, timeInset.toPx()), Offset(x, size.height - timeInset.toPx()), 1.dp.toPx())
                                }, verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(occurrence.start.toString(), modifier = Modifier.testTag("agenda_start_$index"), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(occurrence.end.toString(), modifier = Modifier.testTag("agenda_end_$index"), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TodayCourseRow(occurrence, index, text, selectedOrigin, onSelect, Modifier.weight(1f).height(height), titleStyle, detailStyle,
                                    with(density) { sizing.verticalPaddingPixels(occurrence.durationMinutes.toInt()).toDp() })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayCourseRow(occurrence: Occurrence, index: Int, text: UiText, selectedOrigin: ModalOrigin?,
    onSelect: (Occurrence, ModalOrigin) -> Unit, modifier: Modifier, titleStyle: TextStyle, detailStyle: TextStyle, verticalPadding: Dp) {
    val origin = rememberModalOrigin()
    val highlight = animateFloatAsState(if (selectedOrigin === origin) 1f else 0f, AppMotion.pageSpring(), label = "agenda_origin")
    val out = OccurrenceStatus.OUT_OF_WEEK in occurrence.statuses
    val holiday = OccurrenceStatus.HOLIDAY in occurrence.statuses
    val palette = courseTilePalette(occurrence.course, out, holiday)
    val description = buildString {
        append(courseNameSpacing(occurrence.course.name))
        append(" ${occurrence.start}–${occurrence.end}")
        if (out) append(text.t("，非本周", "; not this week"))
        if (holiday) append(text.t("，休假不上课", "; cancelled for holiday"))
        if (OccurrenceStatus.MAKEUP in occurrence.statuses) append(text.t("，调休补课", "; make-up class"))
    }
    Column(modifier.testTag("today_course_$index").modalOrigin(origin)
        .clip(RoundedCornerShape(14.dp)).courseTileBackground(palette, highlight, 14.dp, out, holiday)
        .clickable { origin.capture(); onSelect(occurrence, origin) }.semantics { contentDescription = description }
        .padding(horizontal = 12.dp, vertical = verticalPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(courseNameSpacing(occurrence.course.name), modifier = Modifier.fillMaxWidth().testTag("agenda_name_$index"), style = titleStyle, color = palette.content)
        if (occurrence.lesson.location.isNotBlank()) Text(
            text.t("地点： ", "Location: ") + courseLocationSpacing(occurrence.lesson.location),
            modifier = Modifier.fillMaxWidth().testTag("course_location"), style = detailStyle, color = palette.content,
        )
        if (occurrence.lesson.teacher.isNotBlank()) Text(
            text.t("教师： ", "Teacher: ") + courseTextSpacing(occurrence.lesson.teacher),
            modifier = Modifier.fillMaxWidth().testTag("course_teacher"), style = detailStyle, color = palette.content,
        )
    }
}
