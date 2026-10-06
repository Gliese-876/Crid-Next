package cn.crid.next.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.crid.next.core.AppState
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.MakeupMode
import cn.crid.next.core.Occurrence
import cn.crid.next.core.PreparedSchedule
import cn.crid.next.core.ScheduleEngine
import cn.crid.next.core.courseLocationSpacing
import cn.crid.next.core.courseNameSpacing
import cn.crid.next.core.displayCourses
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal enum class TodayCoursePhase { BEFORE_CLASS, IN_CLASS, BREAK, FINISHED, EMPTY, PAST_DAY, FUTURE_DAY }

internal data class TodayCourseStatus(
    val phase: TodayCoursePhase,
    val date: LocalDate,
    val today: LocalDate,
    val total: Int,
    val current: List<Occurrence>,
    val next: List<Occurrence>,
    val minutesToNext: Long?,
)

/** The summary follows the same merged sessions as the agenda, using [start, end) intervals. */
internal fun todayCourseStatus(
    occurrences: List<Occurrence>, date: LocalDate, today: LocalDate, now: LocalTime,
    followingDay: List<Occurrence> = emptyList(),
): TodayCourseStatus {
    val actual = displayCourses(occurrences.filter { it.date == date && it.isActual }).map { it.representative }
    val current = if (date == today) actual.filter { now >= it.start && now < it.end } else emptyList()
    val pending = when {
        date < today -> emptyList()
        date > today -> actual
        else -> actual.filter { it.start > now }
    }
    val first = pending.firstOrNull()
    val next = when {
        first != null -> pending.filter { it.start == first.start }
        date == today -> {
            val future = displayCourses(followingDay.filter { it.date > today && it.isActual }).map { it.representative }
            val earliest = future.firstOrNull()
            if (earliest == null) emptyList() else future.filter { it.date == earliest.date && it.start == earliest.start }
        }
        else -> emptyList()
    }
    val phase = when {
        actual.isEmpty() -> TodayCoursePhase.EMPTY
        date < today -> TodayCoursePhase.PAST_DAY
        date > today -> TodayCoursePhase.FUTURE_DAY
        current.isNotEmpty() -> TodayCoursePhase.IN_CLASS
        pending.isEmpty() -> TodayCoursePhase.FINISHED
        actual.any { it.end <= now } -> TodayCoursePhase.BREAK
        else -> TodayCoursePhase.BEFORE_CLASS
    }
    val minutes = if (date == today) next.firstOrNull()?.takeIf { it.date == today }?.let {
        // Round up so the final seconds before a lesson never read as zero minutes.
        val wait = Duration.between(now, it.start)
        (wait.seconds + 59L + if (wait.nano > 0) 1L else 0L) / 60L
    } else null
    return TodayCourseStatus(phase, date, today, actual.size, current, next, minutes)
}

/** Search teaching dates only; holidays and make-up mappings are resolved by the schedule engine. */
internal fun followingTeachingDay(state: AppState, calendar: HolidayCalendar, date: LocalDate,
    schedule: PreparedSchedule? = null): List<Occurrence> {
    val semester = state.semester ?: return emptyList()
    val plan = state.plan ?: return emptyList()
    val query = schedule ?: ScheduleEngine.prepare(semester, plan, state.settings, calendar)
    val first = LocalDate.parse(semester.startDate)
    val last = LocalDate.parse(semester.endDate)
    if (date >= last) return emptyList()
    val weekStart = ScheduleEngine.weekStart(first, state.settings.weekStartsSunday)
    val teachingDates = plan.courses.asSequence().flatMap { it.lessons.asSequence() }
        .filterNot { it.unscheduled }.flatMap { lesson ->
            lesson.date?.let { sequenceOf(LocalDate.parse(it)) } ?: lesson.weeks.asSequence().map { week ->
                val offset = if (state.settings.weekStartsSunday) lesson.weekday % 7 else lesson.weekday - 1
                weekStart.plusWeeks(week - 1L).plusDays(offset.toLong())
            }
        }
    val makeupDates = if (state.settings.holidaysEnabled && state.settings.makeupMode == MakeupMode.ON)
        calendar.days.asSequence().filter { it.workday && it.teachingDate != null }.map { LocalDate.parse(it.date) }
    else emptySequence()
    return (teachingDates + makeupDates).filter { it > date && it >= first && it <= last }.distinct().sorted()
        .map { query.occurrences(it).filter { item -> item.isActual } }
        .firstOrNull { it.isNotEmpty() }.orEmpty()
}

/** Refresh immediately after returning to the app, then at each clock-minute boundary. */
@Composable
internal fun rememberScheduleClock(clock: Clock = Clock.systemDefaultZone()): State<LocalDateTime> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(LocalDateTime.now(clock), lifecycle, clock) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = LocalDateTime.now(clock)
                value = now
                delay(60_000L - now.second * 1_000L - now.nano / 1_000_000L)
            }
        }
    }
}

internal fun TodayCourseStatus.title(text: UiText): String = when (phase) {
    TodayCoursePhase.BEFORE_CLASS -> text.t("今日课程尚未开始", "Classes haven't started yet", "今日課程尚未開始")
    TodayCoursePhase.IN_CLASS -> text.t("正在上课", "In class", "正在上課")
    TodayCoursePhase.BREAK -> text.t("课间休息", "Between classes", "課間休息")
    TodayCoursePhase.FINISHED -> text.t("今日课程已结束，放松一下吧", "Today's classes are over, time to relax", "今日課程已結束，放鬆一下吧")
    TodayCoursePhase.EMPTY -> if (date == today) text.t("今天，留一点时间给自己", "Today, make a little time for yourself", "今天，留一點時間給自己")
        else text.t("这一天没有课程", "No classes on this day", "這一天沒有課程")
    TodayCoursePhase.PAST_DAY -> text.t("当天课程已结束", "Classes on this day have ended", "當天課程已結束")
    TodayCoursePhase.FUTURE_DAY -> text.t("当天课程安排", "Classes on this day", "當天課程安排")
}

internal fun TodayCourseStatus.nextLabel(text: UiText): String {
    val first = next.firstOrNull() ?: return ""
    return when {
        date != today -> text.t("首节课", "First class", "首節課")
        first.date == today -> text.t("下一节", "Next class", "下一節")
        first.date == today.plusDays(1) -> text.t("下一节 · 明天", "Next class · Tomorrow", "下一節 · 明天")
        else -> text.t("下一节 · ", "Next class · ", "下一節 · ") + text.date(first.date)
    }
}

internal fun todayWaitLabel(minutes: Long, text: UiText): String {
    val hours = minutes / 60
    val rest = minutes % 60
    val duration = when {
        hours == 0L -> text.t("$rest 分钟", "$rest min", "$rest 分鐘")
        rest == 0L -> text.t("$hours 小时", "$hours hr", "$hours 小時")
        else -> text.t("$hours 小时 $rest 分钟", "$hours hr $rest min", "$hours 小時 $rest 分鐘")
    }
    return text.t("还有 $duration", "In $duration", "還有 $duration")
}

@Composable
internal fun TodayStatusCard(status: TodayCourseStatus, week: Int?, text: UiText,
    onSelect: (Occurrence, ModalOrigin) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.appSurface(AppSurfaceRole.Raised), shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().testTag("today_summary")) {
        Column(Modifier.animateContentSize(AppMotion.pageSpring()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VisualCenterText(if (week == null) text.t("学期之外", "Outside the semester", "學期之外") else text.t("第 $week 周", "Week $week", "第 $week 週"),
                    modifier = alignByVisualCenter(Modifier.weight(1f)), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Row(alignByVisualCenter().semantics(mergeDescendants = true) {
                    contentDescription = text.t("${status.total} 次课", if (status.total == 1) "1 class" else "${status.total} classes", "${status.total} 次課")
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AppGlyph("course", MaterialTheme.colorScheme.onSurfaceVariant, alignByVisualCenter(Modifier.size(18.dp)))
                    VisualCenterText(status.total.toString(), modifier = alignByVisualCenter(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(status.title(text), modifier = Modifier.fillMaxWidth().testTag("today_status_title"),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            status.current.take(2).forEachIndexed { index, occurrence ->
                StatusCourse(occurrence, "today_current_$index", onSelect)
            }
            if (status.current.size > 2) Text(text.t("另有 ${status.current.size - 2} 门课同时进行", "${status.current.size - 2} more classes in progress", "另有 ${status.current.size - 2} 門課同時進行"),
                modifier = Modifier.fillMaxWidth().testTag("today_current_more"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.next.isNotEmpty()) {
                if (status.current.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(status.nextLabel(text), modifier = Modifier.fillMaxWidth().testTag("today_next_label"),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    StatusCourse(status.next.first(), "today_next_0", onSelect)
                    status.minutesToNext?.let { minutes ->
                        Text(todayWaitLabel(minutes, text), modifier = Modifier.fillMaxWidth().testTag("today_next_wait"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (status.next.size > 1) Text(text.t("同时还有 ${status.next.size - 1} 门课", "${status.next.size - 1} more classes at the same time", "同時還有 ${status.next.size - 1} 門課"),
                        modifier = Modifier.fillMaxWidth().testTag("today_next_more"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (status.phase in setOf(TodayCoursePhase.FINISHED, TodayCoursePhase.IN_CLASS)) {
                Text(text.t("本学期暂无后续课程", "No more classes this semester", "本學期暫無後續課程"),
                    modifier = Modifier.fillMaxWidth().testTag("today_next_empty"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusCourse(occurrence: Occurrence, tag: String, onSelect: (Occurrence, ModalOrigin) -> Unit) {
    val origin = rememberModalOrigin()
    Column(Modifier.fillMaxWidth().modalOrigin(origin).clip(RoundedCornerShape(8.dp))
        .clickable { origin.capture(); onSelect(occurrence, origin) }.testTag(tag).padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(courseNameSpacing(occurrence.course.name), modifier = Modifier.fillMaxWidth().testTag("${tag}_name"),
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull("${occurrence.start}–${occurrence.end}",
            occurrence.lesson.location.takeIf { it.isNotBlank() }?.let(::courseLocationSpacing)).joinToString(" · "),
            modifier = Modifier.fillMaxWidth().testTag("${tag}_detail"), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
