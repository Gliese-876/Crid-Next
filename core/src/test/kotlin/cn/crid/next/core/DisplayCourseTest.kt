package cn.crid.next.core

import java.time.LocalDate
import java.time.LocalTime
import java.io.File
import cn.crid.next.core.importer.TimetableParser
import org.junit.Assert.*
import org.junit.Test

class DisplayCourseTest {
    private val semester = Semester(id = "semester", name = "Fall", startDate = "2026-09-07", endDate = "2026-09-27", weeks = 3, periods = emptyList())
    private val first = Lesson(weeks = listOf(1), weekday = 1, startTime = "09:00", endTime = "10:00", teacher = "Teacher One", location = "Room One")
    private val second = first.copy(weeks = listOf(2, 3), teacher = "Teacher Two", location = "Room Two")
    private val plan = Plan(semesterId = semester.id, name = "Plan", courses = listOf(
        Course(name = "Math", lessons = listOf(first)),
        Course(name = "Math", lessons = listOf(second)),
    ))

    @Test fun currentWeekSuppliesTheCorrectTeacherAndRoomWithoutLosingOtherWeeks() {
        listOf("2026-09-07" to first, "2026-09-14" to second).forEach { (day, expected) ->
            val original = occurrences(day)
            val card = displayCourses(original).single()
            assertEquals(expected, card.representative.lesson)
            assertEquals(listOf(1, 2, 3), card.weeks)
            assertEquals(original, card.occurrences)
            assertEquals(listOf(first, second), card.occurrences.map { it.lesson })
            assertTrue(card.isActual)
        }
        assertEquals(first, plan.courses.first().lessons.single())
        assertEquals(second, plan.courses.last().lessons.single())
    }

    @Test fun currentHolidayRecordHasPriorityOverOtherWeekRecords() {
        val calendar = HolidayCalendar(days = listOf(HolidayDay("2026-09-14", "Holiday", statutory = true)))
        val card = displayCourses(occurrences("2026-09-14", calendar)).single()
        assertEquals(second, card.representative.lesson)
        assertEquals(setOf(OccurrenceStatus.HOLIDAY), card.representative.statuses)
        assertFalse(card.isActual)
    }

    @Test fun allOtherWeekRecordsRemainAvailableAsOneCourse() {
        val emptyWeekPlan = plan.copy(courses = plan.courses.map { course ->
            course.copy(lessons = course.lessons.map { it.copy(weeks = listOf(1)) })
        })
        val original = ScheduleEngine.occurrences(semester, emptyWeekPlan, LocalDate.parse("2026-09-21"), Settings(), HolidayCalendar())
        val card = displayCourses(original).single()
        assertFalse(card.isActual)
        assertEquals(2, card.occurrences.size)
        assertTrue(card.occurrences.all { OccurrenceStatus.OUT_OF_WEEK in it.statuses })
    }

    @Test fun matchingUsesTheExistingNormalizedCourseIdentity() {
        val original = occurrences("2026-09-07")
        val renamed = original.last().let { it.copy(course = it.course.copy(name = "  ＭＡＴＨ  ")) }
        assertEquals(1, displayCourses(listOf(original.first(), renamed)).size)
    }

    @Test fun aNamedCourseKeepsItsSeparateTimeAndDatePositions() {
        val firstDay = occurrences("2026-09-07").first()
        val secondDay = firstDay.copy(date = firstDay.date.plusDays(7), week = 2)
        val secondTime = firstDay.copy(start = firstDay.start.plusHours(2), end = firstDay.end.plusHours(2))
        assertEquals(3, displayCourses(listOf(firstDay, secondDay, secondTime)).size)
    }

    @Test fun datedAndRecurringRecordsKeepTheirDateAndWeekInformationWhenSharingACard() {
        val recurring = occurrences("2026-09-07").first()
        val dated = recurring.copy(lesson = first.copy(date = "2026-09-07", weeks = emptyList(), teacher = "Guest"))
        val card = displayCourses(listOf(recurring, dated)).single()
        assertEquals(2, card.occurrences.size)
        assertEquals(listOf(1), card.weeks)
        assertEquals("2026-09-07", card.occurrences.last().lesson.date)
        assertEquals("Guest", card.occurrences.last().lesson.teacher)
    }

    @Test fun noOccurrencesProduceNoCards() {
        assertTrue(displayCourses(emptyList()).isEmpty())
    }

    @Test fun overlappingOtherWeekTimesUseTheCurrentCoursesExactTimeAndKeepBothRecords() {
        val today = occurrences("2026-09-07").first().let { it.copy(end = it.start.plusMinutes(45)) }
        val otherWeek = occurrences("2026-09-07").last().let {
            it.copy(start = today.start.minusMinutes(15), end = today.start.plusMinutes(75))
        }
        listOf(listOf(today, otherWeek), listOf(otherWeek, today)).forEach { original ->
            val card = displayCourses(original).single()
            assertEquals(today, card.representative)
            assertEquals(45L, card.representative.durationMinutes)
            assertEquals(setOf(today, otherWeek), card.occurrences.toSet())
        }
    }

    @Test fun overlappingOtherWeekTimesWithoutACurrentLessonChooseAStableRepresentative() {
        val original = occurrences("2026-09-07")
        val earlierWeek = original.first().copy(statuses = setOf(OccurrenceStatus.OUT_OF_WEEK))
        val laterWeek = original.last().copy(start = earlierWeek.start.minusMinutes(15), end = earlierWeek.end.plusMinutes(15))
        listOf(listOf(earlierWeek, laterWeek), listOf(laterWeek, earlierWeek)).forEach { order ->
            val card = displayCourses(order).single()
            assertEquals(earlierWeek, card.representative)
            assertEquals(setOf(earlierWeek, laterWeek), card.occurrences.toSet())
        }
    }

    @Test fun recordsInTheSameTeachingWeeksKeepTheirDifferentTimesEvenWhenBothAreOtherWeek() {
        val first = occurrences("2026-09-07").last()
        val second = first.copy(start = first.start.plusMinutes(15), end = first.end.plusMinutes(15))
        val cards = displayCourses(listOf(first, second))
        assertEquals(2, cards.size)
        assertEquals(setOf(first, second), cards.map { it.representative }.toSet())
    }

    @Test fun suppliedPythonCourseDisplaysAsOneContinuousClassWhileKeepingBothSourceRecords() {
        val root = sequenceOf(File(".."), File(".")).first { File(it, "tests/测试用例").isDirectory }
        val file = File(root, "tests/测试用例/北京师范大学珠海校区2026-2027学年秋季学期学生课表.xls")
        val parsed = TimetableParser.parse(file.readBytes(), file.name)
        val python = parsed.courses.single { it.name == "Python合成课甲" }
        val times = listOf("08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40", "13:30" to "14:15", "14:25" to "15:10", "15:30" to "16:15")
        val autumn = Semester("autumn", "2026秋季", "2026-09-07", "2026-12-27", 16, times.mapIndexed { index, time -> Period(index + 1, time.first, time.second) })
        val schedule = Plan(semesterId = autumn.id, name = "秋季课表", courses = listOf(python))
        val original = ScheduleEngine.occurrences(autumn, schedule, LocalDate.parse("2026-09-09"), Settings(), HolidayCalendar())
        assertEquals(2, original.size)
        val card = displayCourses(original).single()
        assertEquals(LocalTime.of(13, 30), card.representative.start)
        assertEquals(LocalTime.of(16, 15), card.representative.end)
        assertEquals(165L, card.representative.durationMinutes)
        assertEquals(5, card.representative.lesson.startPeriod)
        assertEquals(7, card.representative.lesson.endPeriod)
        assertEquals(original, card.occurrences)
        assertEquals(listOf(5 to 6, 7 to 7), python.lessons.map { it.startPeriod to it.endPeriod })
        assertEquals(python.lessons, card.occurrences.map { it.lesson })
        assertEquals((1..16).toList(), card.weeks)
        assertTrue(card.occurrences.all { it.lesson.teacher == "示例盈" && it.lesson.location == "样甲楼C203(140)" })
    }

    @Test fun continuousClassProjectionIsStableAcrossInputOrderAndNeverMutatesSourceLessons() {
        val source = consecutivePeriods()
        val expected = displayCourses(source).single()
        assertEquals(expected, displayCourses(source.reversed()).single())
        assertEquals(source, expected.occurrences)
        assertEquals(source.first().course.lessons, expected.occurrences.map { it.lesson })
        assertEquals(LocalTime.of(15, 10), source.first().end)
        assertEquals(6, source.first().lesson.endPeriod)
    }

    @Test fun continuousPeriodsKeepDifferentTeachersRoomsWeeksAndStatusesSeparate() {
        val (before, after) = consecutivePeriods()
        val differentContexts = listOf(
            after.copy(lesson = after.lesson.copy(teacher = "Other teacher")),
            after.copy(lesson = after.lesson.copy(location = "Other room")),
            after.copy(lesson = after.lesson.copy(weeks = listOf(2))),
            after.copy(lesson = after.lesson.copy(note = "Separate lab session")),
            after.copy(statuses = setOf(OccurrenceStatus.HOLIDAY)),
            after.copy(date = after.date.plusDays(1)),
            after.copy(course = after.course.copy(name = "Different course")),
        )
        differentContexts.forEach { other ->
            assertEquals(2, displayCourses(listOf(before, other)).size)
        }
    }

    @Test fun nearbyClockTimesAndSkippedPeriodsDoNotTurnIndependentClassesIntoOne() {
        val (before, after) = consecutivePeriods()
        val separatePeriod = after.copy(lesson = after.lesson.copy(startPeriod = 8, endPeriod = 8))
        assertEquals(2, displayCourses(listOf(before, separatePeriod)).size)
        val clockOnly = listOf(before, after).map { it.copy(lesson = it.lesson.copy(startPeriod = null, endPeriod = null, startTime = it.start.toString(), endTime = it.end.toString())) }
        assertEquals(2, displayCourses(clockOnly).size)
        val otherWeeks = listOf(before, separatePeriod).map { it.copy(statuses = setOf(OccurrenceStatus.OUT_OF_WEEK)) }
        assertEquals(2, displayCourses(otherWeeks).size)
    }

    @Test fun otherWeeksCannotExtendTheTimeRangeOfAContinuousCurrentClass() {
        val source = consecutivePeriods()
        val otherWeek = source.first().copy(
            start = LocalTime.of(13, 0), end = LocalTime.of(17, 0),
            lesson = source.first().lesson.copy(weeks = listOf(2), teacher = "Another teacher"),
            statuses = setOf(OccurrenceStatus.OUT_OF_WEEK),
        )
        val card = displayCourses(source + otherWeek).single()
        assertEquals(LocalTime.of(13, 30), card.representative.start)
        assertEquals(LocalTime.of(16, 15), card.representative.end)
        assertEquals(source + otherWeek, card.occurrences)
        assertEquals(listOf(1, 2), card.weeks)
    }

    @Test fun aContinuationUsesTheLastCompatibleCardAndCanContinueAgain() {
        val (before, after) = consecutivePeriods()
        val earlier = before.copy(start = before.start.minusHours(1))
        val final = after.copy(start = after.end.plusMinutes(10), end = after.end.plusHours(1),
            lesson = after.lesson.copy(startPeriod = 8, endPeriod = 8))
        val cards = displayCourses(listOf(final, earlier, after, before))
        assertEquals(2, cards.size)
        assertEquals(listOf(earlier), cards.first().occurrences)
        assertEquals(listOf(before, after, final), cards.last().occurrences)
        assertEquals(8, cards.last().representative.lesson.endPeriod)
    }

    @Test fun anOverlappingLatestCandidateDoesNotHideAnEarlierCompatibleContinuation() {
        val (before, after) = consecutivePeriods()
        val overlapping = before.copy(start = before.start.plusMinutes(5), end = after.start.plusMinutes(5))
        val cards = displayCourses(listOf(before, overlapping, after))
        assertEquals(2, cards.size)
        assertEquals(listOf(before, after), cards.first().occurrences)
        assertEquals(listOf(overlapping), cards.last().occurrences)
    }

    private fun consecutivePeriods(): List<Occurrence> {
        val start = Lesson(weeks = listOf(1), weekday = 1, startPeriod = 5, endPeriod = 6, teacher = "Teacher", location = "Room")
        val end = start.copy(startPeriod = 7, endPeriod = 7)
        val course = Course(name = "Programming", lessons = listOf(start, end))
        val date = LocalDate.parse("2026-09-07")
        return listOf(
            Occurrence(course, start, date, LocalTime.of(13, 30), LocalTime.of(15, 10), 1),
            Occurrence(course, end, date, LocalTime.of(15, 30), LocalTime.of(16, 15), 1),
        )
    }

    private fun occurrences(day: String, calendar: HolidayCalendar = HolidayCalendar()) =
        ScheduleEngine.occurrences(semester, plan, LocalDate.parse(day), Settings(), calendar)
}
