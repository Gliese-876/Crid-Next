package cn.crid.next.platform

import cn.crid.next.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class ReminderSnapshotTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val semester = Semester("s", "Fall", "2026-10-12", "2027-06-27", 37,
        listOf(Period(1, "08:00", "08:45")))
    private val lesson = Lesson(weeks = (1..37).toList(), weekday = 1, startPeriod = 1, endPeriod = 1,
        location = "Teaching Block A203", teacher = "Teacher")
    private val plan = Plan("p", "s", "Plan", listOf(Course("c", "Class", lessons = listOf(lesson))))
    private val state = AppState(listOf(semester), listOf(plan), "s", "p", Settings(remindersEnabled = true,
        reminderAlarmClock = true, language = Language.EN))
    private val now = Instant.parse("2026-10-11T23:00:00Z")

    @Test fun snapshotContainsOnlyUpcomingNoticeFieldsAndKeepsThe64DueBound() {
        val daily = state.copy(plans = listOf(plan.copy(courses = listOf(
            plan.courses.single().copy(lessons = (1..7).map { lesson.copy(weekday = it) })))))
        val snapshot = ReminderSnapshot.create(daily, HolidayCalendar(), now, zone)
        assertEquals(64, snapshot.notices.map { it.due(zone) }.distinct().size)
        assertTrue(snapshot.alarmClock)
        assertEquals(Language.EN, snapshot.language)
        val encoded = Json.encodeToString(snapshot)
        listOf("selectedPlanId", "semesters", "plans", "credits", "apiKey", "password").forEach {
            assertFalse(encoded.contains(it))
        }
        assertEquals(snapshot, Json.decodeFromString<ReminderSnapshot>(encoded))
    }

    @Test fun disabledAndHolidayLessonsNeverEnterSnapshot() {
        assertTrue(ReminderSnapshot.create(state.copy(settings = state.settings.copy(remindersEnabled = false)),
            HolidayCalendar(), now, zone).notices.isEmpty())
        val holiday = HolidayCalendar(days = listOf(HolidayDay("2026-10-12", "Rest", statutory = true)))
        assertTrue(ReminderSnapshot.create(state, holiday, now, zone).notices.none { it.startLocal.startsWith("2026-10-12") })
    }

    @Test fun finalAlarmRetainsEverySimultaneousClassWithoutIncludingTheNextTime() {
        val firstTime = LocalTime.of(8, 0)
        val times = (0..64).map { minute ->
            lesson.copy(date = "2026-10-12", startPeriod = null, endPeriod = null,
                startTime = firstTime.plusMinutes(minute.toLong()).toString(),
                endTime = firstTime.plusMinutes(minute + 30L).toString())
        }
        val simultaneous = state.copy(plans = listOf(plan.copy(courses = listOf(
            Course("first", "First", lessons = times), Course("second", "Second", lessons = times)))))
        val notices = ReminderSnapshot.create(simultaneous, HolidayCalendar(), now, zone).notices
        assertEquals(128, notices.size)
        assertEquals(64, notices.map { it.due(zone) }.distinct().size)
        assertEquals(2, notices.count { it.startLocal == "2026-10-12T09:03" })
        assertFalse(notices.any { it.startLocal == "2026-10-12T09:04" })
    }

    @Test fun springClockGapDoesNotKeepAClassThatEndsBeforeItsResolvedReminder() {
        val springSemester = semester.copy(startDate = "2026-03-02", endDate = "2026-03-15", weeks = 2)
        val springState = state.copy(semesters = listOf(springSemester),
            plans = listOf(plan.copy(courses = listOf(plan.courses.single().copy(lessons = listOf(
                lesson.copy(date = "2026-03-08", startPeriod = null, endPeriod = null,
                    startTime = "02:45", endTime = "03:00")))))),
            settings = state.settings.copy(reminderMinutes = 0))
        assertTrue(ReminderSnapshot.create(springState, HolidayCalendar(), Instant.parse("2026-03-08T05:00:00Z"),
            ZoneId.of("America/New_York")).notices.isEmpty())
    }

    @Test fun timezoneChangeRecomputesLocalClassAndElapsedLeadAcrossDstAndMidnight() {
        val notice = ReminderNotice("stable", 120, "2026-03-08T03:30", "2026-03-08T04:30", "Class", "Room")
        val newYork = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-03-08T05:30:00Z").toEpochMilli(), notice.due(newYork))
        assertEquals(Instant.parse("2026-03-07T17:30:00Z").toEpochMilli(), notice.due(zone))
        val midnight = notice.copy(startLocal = "2026-10-12T00:05", endLocal = "2026-10-12T00:50", advanceMinutes = 15)
        assertEquals("2026-10-11T23:50", Instant.ofEpochMilli(midnight.due(zone)).atZone(zone).toLocalDateTime().toString())
    }

    @Test fun expiredAndAlreadyDeliveredNoticesAreRemovedAndFutureDoesNotFireEarly() {
        val snapshot = ReminderSnapshot.create(state, HolidayCalendar(), now, zone)
        val first = snapshot.notices.first()
        assertFalse(first.isDue(first.due(zone), Instant.ofEpochMilli(first.due(zone)).minusMillis(1), zone))
        assertTrue(first.isDue(first.due(zone), Instant.ofEpochMilli(first.due(zone)), zone))
        assertFalse(first.isDue(first.due(zone), first.end(zone), zone))
        assertFalse(first in snapshot.remaining(now, zone, setOf(first.id)))
        assertFalse(first in snapshot.remaining(first.end(zone), zone, emptySet()))
    }

    @Test fun oldSettingsKeepOrdinaryRemindersUntilUserSelectsAlarmClock() {
        val settings = Json.decodeFromString<Settings>("""{"remindersEnabled":true,"reminderMinutes":10}""")
        assertTrue(settings.remindersEnabled)
        assertFalse(settings.reminderAlarmClock)
    }
}
