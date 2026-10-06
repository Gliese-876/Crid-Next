package cn.crid.next.platform

import cn.crid.next.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderPlannerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val semester = Semester("s", "Fall", "2026-10-12", "2026-10-31", 3,
        listOf(Period(1, "08:00", "08:45")))
    private val lesson = Lesson(weeks = listOf(1, 2, 3), weekday = 1, startPeriod = 1, endPeriod = 1)
    private val plan = Plan("p", "s", "Plan", listOf(Course("c", "Class", lessons = listOf(lesson))))
    private val state = AppState(listOf(semester), listOf(plan), "s", "p", Settings(remindersEnabled = true))
    private val calendar = HolidayCalendar()
    private val before = Instant.parse("2026-10-11T23:00:00Z") // Monday 07:00 local
    private val due = Instant.parse("2026-10-11T23:45:00Z").toEpochMilli()

    @Test fun schedulesInDeviceTimezoneBeforeClass() {
        assertEquals(due, ReminderPlanner.nextDue(state, calendar, before, zone))
        assertNull(ReminderPlanner.nextDue(state.copy(settings = state.settings.copy(remindersEnabled = false)), calendar, before, zone))
    }

    @Test fun holidayAndOutOfWeekRecordsNeverNotify() {
        val holiday = HolidayCalendar(days = listOf(HolidayDay("2026-10-12", "Rest", statutory = true)))
        assertTrue(ReminderPlanner.nextDue(state, holiday, before, zone)!! > due)
        assertTrue(ReminderPlanner.dueOccurrences(state, holiday, due, Instant.ofEpochMilli(due), zone).isEmpty())
        val otherWeek = state.copy(plans = listOf(plan.copy(courses = listOf(plan.courses.single().copy(lessons = listOf(lesson.copy(weeks = listOf(2))))))))
        assertTrue(ReminderPlanner.nextDue(otherWeek, calendar, before, zone)!! > due)
    }

    @Test fun simultaneousClassesAreDeliveredTogether() {
        val simultaneous = state.copy(plans = listOf(plan.copy(courses = plan.courses + Course("d", "Second", lessons = listOf(lesson)))))
        assertEquals(2, ReminderPlanner.dueOccurrences(simultaneous, calendar, due, Instant.ofEpochMilli(due), zone).size)
    }

    @Test fun lateAndChangedOrDeletedSchedulesDoNotProduceStaleNotices() {
        assertTrue(ReminderPlanner.dueOccurrences(state, calendar, due, Instant.parse("2026-10-12T01:00:00Z"), zone).isEmpty())
        assertTrue(ReminderPlanner.dueOccurrences(state.copy(plans = emptyList()), calendar, due, Instant.ofEpochMilli(due), zone).isEmpty())
        assertTrue(ReminderPlanner.dueOccurrences(state.copy(settings = state.settings.copy(reminderMinutes = 5)), calendar, due, Instant.ofEpochMilli(due), zone).isEmpty())
    }

    @Test fun lessonAfterMidnightCanBeAnnouncedPreviousDay() {
        val midnight = lesson.copy(weekday = 2, startPeriod = null, endPeriod = null, startTime = "00:05", endTime = "00:50")
        val nextDay = state.copy(plans = listOf(plan.copy(courses = listOf(plan.courses.single().copy(lessons = listOf(midnight))))))
        val expected = Instant.parse("2026-10-12T15:50:00Z").toEpochMilli()
        assertEquals(expected, ReminderPlanner.nextDue(nextDay, calendar, Instant.parse("2026-10-12T15:40:00Z"), zone))
        assertEquals(1, ReminderPlanner.dueOccurrences(nextDay, calendar, expected, Instant.ofEpochMilli(expected), zone).size)
    }

    @Test fun registersIndependentTimesThroughLongGapsWithoutNeedingMaintenance() {
        val longSemester = semester.copy(endDate = "2026-12-31", weeks = 12)
        val sparse = state.copy(semesters = listOf(longSemester), plans = listOf(plan.copy(courses = listOf(
            plan.courses.single().copy(lessons = listOf(lesson.copy(weeks = listOf(1, 5, 10))))))))
        val times = ReminderPlanner.upcomingDues(sparse, calendar, before, zone)
        assertEquals(3, times.size)
        assertEquals(due, times.first())
        assertEquals(due + 28L * 86400000, times[1])
        assertEquals(due + 63L * 86400000, times[2])
    }

    @Test fun boundedRegistrationsDeduplicateSimultaneousCourses() {
        val longSemester = semester.copy(endDate = "2027-06-27", weeks = 37)
        val many = (1..7).map { day -> lesson.copy(weeks = (1..37).toList(), weekday = day) }
        val manyState = state.copy(semesters = listOf(longSemester), plans = listOf(plan.copy(courses = listOf(
            Course("a", "A", lessons = many), Course("b", "B", lessons = many)))))
        val times = ReminderPlanner.upcomingDues(manyState, calendar, before, zone)
        assertEquals(ReminderPlanner.MAX_PENDING, times.size)
        assertEquals(times.distinct().sorted(), times)
        assertEquals(due + 63L * 86400000, times.last())
    }

    @Test fun refreshDuringValidLateWindowKeepsOnlyCurrentActualLessonsEligible() {
        val late = Instant.ofEpochMilli(due).plusSeconds(20 * 60)
        assertEquals(1, ReminderPlanner.dueOccurrences(state, calendar, due, late, zone).size)
        assertTrue(ReminderPlanner.dueOccurrences(state, calendar, due, Instant.ofEpochMilli(due).minusSeconds(1), zone).isEmpty())
        assertTrue(ReminderPlanner.dueOccurrences(state, calendar, due,
            Instant.parse("2026-10-12T00:45:00Z"), zone).isEmpty())
        val moved = state.copy(plans = listOf(plan.copy(courses = listOf(plan.courses.single().copy(
            lessons = listOf(lesson.copy(weekday = 2)))))))
        assertTrue(ReminderPlanner.dueOccurrences(moved, calendar, due, late, zone).isEmpty())
    }

    @Test fun stableDeliveryIdentitySurvivesSpacingAndDistinguishesPlanAndRoom() {
        val occurrence = ReminderPlanner.dueOccurrences(state, calendar, due, Instant.ofEpochMilli(due), zone).single()
        val first = occurrence.copy(lesson = occurrence.lesson.copy(location = "励耘楼A203", teacher = "张老师A"))
        val formatted = occurrence.copy(lesson = occurrence.lesson.copy(location = "励耘楼 A203", teacher = "张老师 A"))
        assertEquals(ReminderIdentity.deliveryId(state, first), ReminderIdentity.deliveryId(state, formatted))
        assertNotEquals(ReminderIdentity.deliveryId(state, first),
            ReminderIdentity.deliveryId(state.copy(selectedPlanId = "other"), first))
        assertNotEquals(ReminderIdentity.deliveryId(state, first),
            ReminderIdentity.deliveryId(state, first.copy(lesson = first.lesson.copy(location = "B203"))))
    }

    @Test fun deliveredClassIdentityDoesNotChangeWithDeviceTimeZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val japanDue = ReminderPlanner.nextDue(state, calendar, before.minusSeconds(3600), tokyo)!!
        assertNotEquals(due, japanDue)
        val inChina = ReminderPlanner.dueOccurrences(state, calendar, due, Instant.ofEpochMilli(due), zone).single()
        val inJapan = ReminderPlanner.dueOccurrences(state, calendar, japanDue, Instant.ofEpochMilli(japanDue), tokyo).single()
        assertEquals(ReminderIdentity.deliveryId(state, inChina), ReminderIdentity.deliveryId(state, inJapan))
    }

    @Test fun sundayWeekStartAndMakeupDatesAreIncludedInCandidateSchedule() {
        val sundayState = state.copy(semesters = listOf(semester.copy(startDate = "2026-10-11")),
            settings = state.settings.copy(weekStartsSunday = true, makeupMode = MakeupMode.ON))
        val holidays = HolidayCalendar(days = listOf(
            HolidayDay("2026-10-12", "Rest", statutory = true),
            HolidayDay("2026-10-17", "Makeup", workday = true, teachingDate = "2026-10-12")))
        val times = ReminderPlanner.upcomingDues(sundayState, holidays, before, zone)
        assertFalse(due in times)
        assertTrue(due + 5L * 86400000 in times)
    }

    @Test(timeout = 2_000) fun distantSemesterEndDoesNotCauseDayByDayTraversal() {
        val distant = state.copy(semesters = listOf(semester.copy(endDate = "+999999999-12-31")),
            plans = listOf(plan.copy(courses = listOf(plan.courses.single().copy(lessons = listOf(lesson.copy(weeks = listOf(1, 3))))))))
        assertEquals(listOf(due, due + 14L * 86400000), ReminderPlanner.upcomingDues(distant, calendar, before, zone))
    }
}
