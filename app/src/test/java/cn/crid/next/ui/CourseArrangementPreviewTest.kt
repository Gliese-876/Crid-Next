package cn.crid.next.ui

import cn.crid.next.core.Language
import cn.crid.next.core.Lesson
import cn.crid.next.core.Period
import cn.crid.next.core.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseArrangementPreviewTest {
    private val text = UiText(Language.EN)
    private val semester = Semester(
        id = "preview", name = "Autumn", startDate = "2026-09-07", endDate = "2026-11-01", weeks = 8,
        periods = listOf(Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40")),
    )

    @Test fun summaryKeepsEachRoomPairedWithItsOwnTime() {
        val lessons = listOf(
            Lesson(weekday = 1, startPeriod = 1, endPeriod = 2, location = "Room 101"),
            Lesson(weekday = 3, startTime = "14:00", endTime = "15:00", location = "Lab 202"),
        )
        assertEquals(listOf(
            CourseArrangementPreview("Mon", "08:00–09:40 · Periods 1–2", "Room 101"),
            CourseArrangementPreview("Wed", "14:00–15:00", "Lab 202"),
        ), courseArrangementPreviews(lessons, semester, text))
    }

    @Test fun repeatedVisibleSummaryCollapsesWithoutCombiningDifferentRoomsOrDays() {
        val first = Lesson(weeks = listOf(1), weekday = 1, startTime = "08:00", endTime = "09:00", location = "Room 101")
        val lessons = listOf(first, first.copy(weeks = listOf(2), teacher = "Ada"),
            first.copy(location = "Room 202"), first.copy(weekday = 2))
        val summaries = courseArrangementPreviews(lessons, semester, text)
        assertEquals(3, summaries.size)
        assertEquals(listOf("Room 101", "Room 202", "Room 101"), summaries.map { it.location })
        assertEquals(listOf("Mon", "Mon", "Tue"), summaries.map { it.day })
        assertEquals(4, lessons.size)
        assertEquals(listOf(2), lessons[1].weeks)
    }

    @Test fun pendingSummaryHasNoInventedWeekdayAndDatedSummaryKeepsItsDate() {
        val summaries = courseArrangementPreviews(listOf(
            Lesson(weeks = listOf(1, 2), weekday = 0, location = "Lab 303", unscheduled = true),
            Lesson(date = "2026-09-30", startTime = "10:00", endTime = "11:00", location = "Room 404"),
        ), semester, text)
        assertNull(summaries[0].day)
        assertEquals("Awaiting a time", summaries[0].time)
        assertEquals("Lab 303", summaries[0].location)
        assertEquals("Sep 30, Wednesday", summaries[1].day)
        assertEquals("10:00–11:00", summaries[1].time)
    }
}
