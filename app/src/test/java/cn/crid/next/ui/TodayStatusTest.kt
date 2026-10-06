package cn.crid.next.ui

import cn.crid.next.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TodayStatusTest {
    private val date = LocalDate.of(2026, 9, 28)
    private val semester = Semester(name = "Term", startDate = date.toString(), endDate = date.plusDays(20).toString(),
        weeks = 3, periods = emptyList())
    private val english = UiText(Language.EN)

    @Test fun minuteAndLessonBoundariesKeepCurrentAndNextSeparate() {
        val classes = listOf(item("08:00", "08:45"), item("09:00", "09:45"))
        val before = status(classes, "07:59:59")
        assertEquals(TodayCoursePhase.BEFORE_CLASS, before.phase)
        assertEquals(1L, before.minutesToNext)
        assertEquals(1L, status(classes, "07:59:59.999999999").minutesToNext)
        assertEquals(2L, status(classes, "07:58:59.999999999").minutesToNext)
        assertEquals(classes[0], before.next.single())
        assertTrue(before.current.isEmpty())
        val start = status(classes, "08:00")
        assertEquals(TodayCoursePhase.IN_CLASS, start.phase)
        assertEquals(classes[0], start.current.single())
        assertEquals(classes[1], start.next.single())
        assertEquals(60L, start.minutesToNext)
        val breakTime = status(classes, "08:45")
        assertEquals(TodayCoursePhase.BREAK, breakTime.phase)
        assertTrue(breakTime.current.isEmpty())
        assertEquals(15L, breakTime.minutesToNext)
        assertEquals(classes[1], breakTime.next.single())
        assertEquals(classes[1], status(classes, "09:00").current.single())
        val end = status(classes, "09:45")
        assertEquals(TodayCoursePhase.FINISHED, end.phase)
        assertTrue(end.current.isEmpty())
        assertTrue(end.next.isEmpty())
        assertNull(end.minutesToNext)
    }

    @Test fun cancelledOtherWeekAndOtherDateLessonsDoNotBecomeCurrentOrNext() {
        val current = item("08:00", "09:00")
        val actual = item("10:00", "11:00")
        val result = status(listOf(current.copy(statuses = setOf(OccurrenceStatus.HOLIDAY)),
            current.copy(statuses = setOf(OccurrenceStatus.OUT_OF_WEEK)),
            current.copy(date = date.plusDays(1)), actual), "08:30")
        assertEquals(TodayCoursePhase.BEFORE_CLASS, result.phase)
        assertEquals(1, result.total)
        assertTrue(result.current.isEmpty())
        assertEquals(actual, result.next.single())
    }

    @Test fun summaryCountsTheSameMergedConsecutiveSessionsAsAgenda() {
        val course = Course(name = "Mathematics", lessons = emptyList())
        val first = item("08:00", "08:45", course).copy(lesson = Lesson(weeks = listOf(1), startPeriod = 1, endPeriod = 1))
        val second = item("08:55", "09:40", course).copy(lesson = first.lesson.copy(startPeriod = 2, endPeriod = 2))
        val result = status(listOf(first, first, second), "08:50")
        assertEquals(1, result.total)
        assertEquals(TodayCoursePhase.IN_CLASS, result.phase)
        assertEquals(LocalTime.of(9, 40), result.current.single().end)
        assertTrue(result.next.isEmpty())
    }

    @Test fun overlapsPreserveEveryCurrentCourseAndAllCoursesAtTheNextStart() {
        val one = item("08:00", "09:00", named("A"))
        val two = item("08:30", "10:00", named("B"))
        val nextA = item("09:15", "10:00", named("C"))
        val nextB = item("09:15", "11:00", named("D"))
        val classes = listOf(nextB, two, nextA, one)
        val result = status(classes, "08:45")
        assertEquals(TodayCoursePhase.IN_CLASS, result.phase)
        assertEquals(listOf(one, two), result.current)
        assertEquals(listOf(nextA, nextB), result.next)
        assertEquals(30L, result.minutesToNext)
        val afterOneEnds = status(classes, "09:00")
        assertEquals(TodayCoursePhase.IN_CLASS, afterOneEnds.phase)
        assertEquals(listOf(two), afterOneEnds.current)
        assertEquals(listOf(nextA, nextB), afterOneEnds.next)
    }

    @Test fun finishedAndEmptyDaysUseTheNextActualTeachingDay() {
        val tomorrowA = item("08:00", "09:00", named("A")).copy(date = date.plusDays(1))
        val tomorrowB = item("08:00", "09:30", named("B")).copy(date = date.plusDays(1))
        val following = listOf(item("07:00", "08:00").copy(date = date.plusDays(1), statuses = setOf(OccurrenceStatus.HOLIDAY)),
            tomorrowA, tomorrowB, item("12:00", "13:00").copy(date = date.plusDays(1)))
        val finished = status(listOf(item("08:00", "09:00")), "10:00", following)
        assertEquals(TodayCoursePhase.FINISHED, finished.phase)
        assertEquals(listOf(tomorrowA, tomorrowB), finished.next)
        assertNull(finished.minutesToNext)
        assertEquals("Next class · Tomorrow", finished.nextLabel(english))
        val empty = status(emptyList(), "10:00", following)
        assertEquals(TodayCoursePhase.EMPTY, empty.phase)
        assertEquals(0, empty.total)
        assertEquals(finished.next, empty.next)
    }

    @Test fun followingDayFallbackCannotMaskAnEarlierClassToday() {
        val later = item("11:00", "12:00")
        val tomorrow = item("08:00", "09:00").copy(date = date.plusDays(1))
        val result = status(listOf(item("08:00", "09:00"), later), "08:30", listOf(tomorrow))
        assertEquals(later, result.next.single())
        assertEquals(150L, result.minutesToNext)
    }

    @Test fun otherDatePreviewsNeverPretendToBeLiveOrUseTodayCountdowns() {
        val classes = listOf(item("08:00", "09:00"), item("10:00", "11:00"))
        val future = todayCourseStatus(classes, date, date.minusDays(1), LocalTime.of(8, 30))
        assertEquals(TodayCoursePhase.FUTURE_DAY, future.phase)
        assertTrue(future.current.isEmpty())
        assertEquals(classes.first(), future.next.single())
        assertNull(future.minutesToNext)
        assertEquals("First class", future.nextLabel(english))
        val past = todayCourseStatus(classes, date, date.plusDays(1), LocalTime.of(8, 30))
        assertEquals(TodayCoursePhase.PAST_DAY, past.phase)
        assertTrue(past.current.isEmpty())
        assertTrue(past.next.isEmpty())
        assertNull(past.minutesToNext)
        listOf(Language.ZH_CN, Language.ZH_TW, Language.EN).forEach { language ->
            val text = UiText(language)
            assertEquals(text.t("当天课程安排", "Classes on this day", "當天課程安排"), future.title(text))
            assertEquals(text.t("当天课程已结束", "Classes on this day have ended", "當天課程已結束"), past.title(text))
            listOf(date.minusDays(1), date.plusDays(1)).forEach { today ->
                val emptyPreview = todayCourseStatus(emptyList(), date, today, LocalTime.of(8, 30))
                assertEquals(TodayCoursePhase.EMPTY, emptyPreview.phase)
                assertEquals(text.t("这一天没有课程", "No classes on this day", "這一天沒有課程"), emptyPreview.title(text))
                assertTrue(emptyPreview.current.isEmpty())
                assertTrue(emptyPreview.next.isEmpty())
                assertNull(emptyPreview.minutesToNext)
            }
        }
    }

    @Test fun todaysEmptyAndFinishedTitlesAreLocalized() {
        val empty = status(emptyList(), "10:00")
        val finished = status(listOf(item("08:00", "09:00")), "10:00")
        listOf(Language.ZH_CN, Language.ZH_TW, Language.EN).forEach { language ->
            val text = UiText(language)
            assertEquals(text.t("今天，留一点时间给自己", "Today, make a little time for yourself", "今天，留一點時間給自己"), empty.title(text))
            assertEquals(text.t("今日课程已结束，放松一下吧", "Today's classes are over, time to relax", "今日課程已結束，放鬆一下吧"), finished.title(text))
        }
    }

    @Test fun candidateSearchSkipsRestDaysAndUsesMakeupTeachingDates() {
        val lesson = Lesson(weeks = listOf(1), weekday = 1, startTime = "08:00", endTime = "09:00")
        val app = state(listOf(Course(name = "Monday course", lessons = listOf(lesson))),
            Settings(holidaysEnabled = true, makeupMode = MakeupMode.ON))
        val calendar = HolidayCalendar(days = listOf(HolidayDay(date.toString(), "Rest", statutory = true),
            HolidayDay(date.plusDays(5).toString(), "Make-up", workday = true, teachingDate = date.toString())))
        val next = followingTeachingDay(app, calendar, date.minusDays(1)).single()
        assertEquals(date.plusDays(5), next.date)
        assertTrue(OccurrenceStatus.MAKEUP in next.statuses)
        assertTrue(next.isActual)
        assertTrue(followingTeachingDay(app.copy(settings = app.settings.copy(makeupMode = MakeupMode.OFF)), calendar, date).isEmpty())
    }

    @Test fun exactDatesUnscheduledRowsAndTermBoundsAreRespected() {
        val app = state(listOf(Course(name = "Dated", lessons = listOf(
            Lesson(date = date.minusDays(1).toString(), startTime = "08:00", endTime = "09:00"),
            Lesson(date = date.plusDays(1).toString(), unscheduled = true),
            Lesson(date = date.plusDays(3).toString(), startTime = "08:00", endTime = "09:00"),
            Lesson(date = date.plusDays(30).toString(), startTime = "08:00", endTime = "09:00"),
        ))))
        assertEquals(date.plusDays(3), followingTeachingDay(app, HolidayCalendar(), date).single().date)
        assertTrue(followingTeachingDay(app, HolidayCalendar(), date.plusDays(3)).isEmpty())
        assertTrue(followingTeachingDay(app, HolidayCalendar(), date.plusDays(20)).isEmpty())
        assertEquals(date.plusDays(3), followingTeachingDay(app, HolidayCalendar(), date.minusDays(100)).single().date)
    }

    @Test fun sundayWeekStartAndNextWeekTeachingRecordsUseCorrectDates() {
        val sunday = date.minusDays(1)
        val term = semester.copy(startDate = sunday.toString(), endDate = sunday.plusDays(20).toString())
        val plan = Plan(semesterId = term.id, name = "Sunday", courses = listOf(Course(name = "Sunday lesson",
            lessons = listOf(Lesson(weekday = 7, weeks = listOf(1, 3), startTime = "08:00", endTime = "09:00")))))
        val app = AppState(listOf(term), listOf(plan), term.id, plan.id, Settings(weekStartsSunday = true))
        assertEquals(sunday.plusWeeks(2), followingTeachingDay(app, HolidayCalendar(), sunday).single().date)
    }

    @Test fun nearestCandidateUsesActualStartOrderWithoutRequiringReminders() {
        val first = Lesson(date = date.plusDays(2).toString(), startTime = "10:00", endTime = "11:00")
        val earlier = first.copy(startTime = "08:00", endTime = "09:00")
        val app = state(listOf(Course(name = "Late", lessons = listOf(first)), Course(name = "Early", lessons = listOf(earlier))),
            Settings(remindersEnabled = false))
        val future = followingTeachingDay(app, HolidayCalendar(), date)
        val result = status(emptyList(), "23:00", future)
        assertEquals("Early", result.next.single().course.name)
        assertEquals("Next class · ${english.date(date.plusDays(2))}", result.nextLabel(english))
        assertEquals(2, future.size)
    }

    @Test fun statusAndCountdownCopyIsLocalizedAndHasNoTrailingFullStops() {
        listOf(Language.ZH_CN, Language.ZH_TW, Language.EN).forEach { language ->
            val text = UiText(language)
            TodayCoursePhase.entries.forEach { phase ->
                val title = status(emptyList(), "08:00").copy(phase = phase).title(text)
                assertTrue(title.isNotBlank())
                assertFalse(title.endsWith(".") || title.endsWith("。"))
            }
            listOf(1L, 60L, 61L, 150L).forEach { minutes ->
                assertFalse(todayWaitLabel(minutes, text).endsWith("。"))
            }
        }
        assertEquals("还有 1 小时 1 分钟", todayWaitLabel(61, UiText(Language.ZH_CN)))
        assertEquals("還有 1 小時 1 分鐘", todayWaitLabel(61, UiText(Language.ZH_TW)))
        assertEquals("In 1 hr 1 min", todayWaitLabel(61, english))
        assertEquals("In 1 hr", todayWaitLabel(60, english))
        assertEquals("In 1 min", todayWaitLabel(1, english))
    }

    private fun status(items: List<Occurrence>, time: String, following: List<Occurrence> = emptyList()) =
        todayCourseStatus(items, date, date, LocalTime.parse(time), following)

    private fun item(start: String, end: String, course: Course = named("$start class")) =
        Occurrence(course, Lesson(date = date.toString(), startTime = start, endTime = end), date,
            LocalTime.parse(start), LocalTime.parse(end), 1)

    private fun named(name: String) = Course(name = name, lessons = emptyList())

    private fun state(courses: List<Course>, settings: Settings = Settings()): AppState {
        val plan = Plan(semesterId = semester.id, name = "Plan", courses = courses)
        return AppState(listOf(semester), listOf(plan), semester.id, plan.id, settings)
    }
}
