package cn.crid.next.platform

import cn.crid.next.core.AppState
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.Language
import cn.crid.next.core.Occurrence
import cn.crid.next.core.courseLocationSpacing
import cn.crid.next.core.courseNameSpacing
import cn.crid.next.core.courseTextSpacing
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** The only course data needed before the first unlock. Local times survive time-zone changes. */
@Serializable
internal data class ReminderNotice(
    val id: String,
    val advanceMinutes: Int,
    val startLocal: String,
    val endLocal: String,
    val title: String,
    val details: String,
) {
    fun due(zone: ZoneId): Long = start(zone).minusSeconds(advanceMinutes.coerceIn(0, 1440) * 60L).toEpochMilli()
    fun start(zone: ZoneId): Instant = instant(startLocal, zone)
    fun end(zone: ZoneId): Instant = instant(endLocal, zone)
    fun isDue(due: Long, now: Instant, zone: ZoneId): Boolean =
        due > 0 && due <= now.toEpochMilli() && this.due(zone) == due && end(zone) > now

    companion object {
        private fun instant(value: String, zone: ZoneId) = LocalDateTime.parse(value).atZone(zone).toInstant()

        fun from(state: AppState, occurrence: Occurrence): ReminderNotice = ReminderNotice(
            id = ReminderIdentity.deliveryId(state, occurrence),
            advanceMinutes = state.settings.reminderMinutes.coerceIn(0, 1440),
            startLocal = occurrence.date.atTime(occurrence.start).toString(),
            endLocal = occurrence.date.atTime(occurrence.end).toString(),
            title = courseNameSpacing(occurrence.course.name),
            details = listOf("${occurrence.start}–${occurrence.end}", courseLocationSpacing(occurrence.lesson.location),
                courseTextSpacing(occurrence.lesson.teacher)).filter(String::isNotBlank).joinToString(" · "),
        )
    }
}

@Serializable
internal data class ReminderSnapshot(
    val language: Language = Language.SYSTEM,
    val alarmClock: Boolean = false,
    val notices: List<ReminderNotice> = emptyList(),
) {
    fun remaining(now: Instant, zone: ZoneId, delivered: Set<String>): List<ReminderNotice> =
        notices.filter { it.id !in delivered && it.end(zone) > now }

    companion object {
        fun create(state: AppState, calendar: HolidayCalendar, now: Instant, zone: ZoneId): ReminderSnapshot =
            ReminderSnapshot(state.settings.language, state.settings.reminderAlarmClock,
                ReminderPlanner.upcomingOccurrences(state, calendar, now, zone).flatMap { (due, occurrences) ->
                    val trigger = Instant.ofEpochMilli(due)
                    occurrences.filter { it.date.atTime(it.end).atZone(zone).toInstant() > trigger }
                        .map { ReminderNotice.from(state, it) }
                })
    }
}
