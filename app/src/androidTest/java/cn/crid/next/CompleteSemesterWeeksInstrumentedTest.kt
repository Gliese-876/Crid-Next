package cn.crid.next

import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.NumberPicker
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import cn.crid.next.core.*
import cn.crid.next.data.Defaults
import cn.crid.next.ui.NativeDatePicker
import cn.crid.next.ui.PlansScreen
import cn.crid.next.ui.UiText
import java.time.DayOfWeek
import java.time.LocalDate
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompleteSemesterWeeksInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val text = UiText(Language.EN)

    @Test fun calendarDisablesEveryOtherWeekdayAndDoesNotConfirmAnInvalidInitialDate() {
        var received: LocalDate? = null
        compose.setContent { MaterialTheme {
            NativeDatePicker("Start date", LocalDate.of(2026, 9, 8), text, {}, { received = it },
                requiredWeekday = DayOfWeek.MONDAY)
        } }
        compose.onNodeWithTag("native_picker_confirm").assertIsNotEnabled()
        for (day in 8..13) dateNode(day).assertIsNotEnabled()
        dateNode(7).assertIsEnabled()
        dateNode(14).performClick()
        compose.onNodeWithTag("native_picker_confirm").performClick()
        compose.waitUntil { received != null }
        assertEquals(LocalDate.of(2026, 9, 14), received)
    }

    @Test fun nativeInputRejectsWrongWeekdaysAsWellAsTheCalendar() {
        var received: LocalDate? = null
        compose.setContent { MaterialTheme {
            NativeDatePicker("Start date", LocalDate.of(2026, 9, 7), text, {}, { received = it },
                requiredWeekday = DayOfWeek.MONDAY)
        } }
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("09082026")
        compose.onNodeWithTag("native_picker_confirm").assertIsNotEnabled()
        assertNull(received)
        compose.onNode(hasSetTextAction()).performTextReplacement("09142026")
        compose.onNodeWithTag("native_picker_confirm").assertIsEnabled().performClick()
        compose.waitUntil { received != null }
        assertEquals(LocalDate.of(2026, 9, 14), received)
    }

    @Test fun mondayTermLinksBothDatesAndWeeksWithoutPartialWeeks() = exerciseLinkedFields(false)

    @Test fun sundayTermLinksBothDatesAndWeeksWithoutPartialWeeks() = exerciseLinkedFields(true)

    @Test fun newSundayTermStartsOnSundayAndUsesExactlyTheDefaultNumberOfWeeks() {
        var current by mutableStateOf(AppState(settings = Settings(weekStartsSunday = true)))
        compose.setContent { MaterialTheme { PlansScreen(current, text, { current = it }, {}) } }
        compose.onNodeWithText("New term").performClick()
        compose.onNodeWithTag("semester_save").performClick()
        compose.waitUntil { current.semesters.size == 1 }
        val saved = current.semesters.single()
        assertEquals("2026-09-06", saved.startDate)
        assertEquals("2027-01-09", saved.endDate)
        assertEquals(18, saved.weeks)
    }

    @Test fun correctingALegacyBoundaryIsExplicitAndCancellationKeepsTheOriginal() {
        val original = Defaults.semester().copy(startDate = "2026-09-08", endDate = "2026-09-20", weeks = 2)
        var current by mutableStateOf(AppState(semesters = listOf(original), selectedSemesterId = original.id))
        compose.setContent { MaterialTheme { PlansScreen(current, text, { current = it }, {}) } }
        compose.onNodeWithContentDescription("Edit term").performClick()
        compose.onNodeWithTag("semester_save").assertIsNotEnabled()
        assertFieldDate("semester_start", "Sep 8, 2026")
        selectDate("semester_start", 7)
        compose.onNodeWithTag("semester_save").assertIsEnabled()
        compose.onNodeWithTag("semester_cancel").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("semester_start").fetchSemanticsNodes().isEmpty() }
        assertEquals(original, current.semesters.single())
        compose.onNodeWithContentDescription("Edit term").performClick()
        selectDate("semester_start", 7)
        compose.onNodeWithTag("semester_save").performClick()
        compose.waitUntil { current.semesters.single().startDate == "2026-09-07" }
        assertEquals(original.copy(startDate = "2026-09-07"), current.semesters.single())
    }

    @Test fun aShorterCompleteTermCannotSilentlyTruncateAnExistingCourse() {
        val original = Defaults.semester().copy(startDate = "2026-09-07", endDate = "2026-09-20", weeks = 2)
        val course = Course(name = "Last day seminar", lessons = listOf(Lesson(date = "2026-09-20", weekday = 7,
            startPeriod = 1, endPeriod = 2)))
        val plan = Plan(semesterId = original.id, name = "My timetable", courses = listOf(course))
        val initial = AppState(listOf(original), listOf(plan), original.id, plan.id)
        var current by mutableStateOf(initial)
        compose.setContent { MaterialTheme { PlansScreen(current, text, { current = it }, {}) } }
        compose.onNodeWithContentDescription("Edit term").performClick()
        selectDate("semester_end", 13)
        compose.onNodeWithTag("semester_save").performClick()
        compose.onNodeWithTag("semester_start").assertExists()
        assertEquals(initial, current)
        compose.onNodeWithTag("semester_cancel").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("semester_start").fetchSemanticsNodes().isEmpty() }
        assertEquals(initial, current)
    }

    private fun exerciseLinkedFields(sunday: Boolean) {
        val startDay = if (sunday) 6 else 7
        val endDay = startDay + 13
        val original = Defaults.semester().copy(startDate = "2026-09-${startDay.toString().padStart(2, '0')}",
            endDate = "2026-09-$endDay", weeks = 2)
        var current by mutableStateOf(AppState(semesters = listOf(original), selectedSemesterId = original.id,
            settings = Settings(weekStartsSunday = sunday)))
        var saves = 0
        compose.setContent { MaterialTheme { PlansScreen(current, text, { current = it; saves++ }, {}) } }
        compose.onNodeWithContentDescription("Edit term").performClick()
        selectDate("semester_start", startDay + 7)
        compose.onNodeWithTag("semester_weeks").performScrollTo()
        compose.onNode(hasText("1 weeks") and hasAnyAncestor(hasTestTag("semester_weeks")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("semester_weeks").performClick()
        incrementWeeks()
        compose.onNodeWithTag("native_picker_confirm").performClick()
        assertFieldDate("semester_end", "Sep ${endDay + 7}, 2026")
        selectDate("semester_end", endDay)
        assertFieldDate("semester_start", "Sep $startDay, 2026")
        assertEquals(0, saves)
        compose.onNodeWithTag("semester_save").performClick()
        compose.waitUntil { saves == 1 }
        assertEquals(original, current.semesters.single())
    }

    private fun dateNode(day: Int) = compose.onNode(hasText("September $day, 2026", substring = true)
        and hasAnyAncestor(hasTestTag("native_date_picker")))

    private fun selectDate(field: String, day: Int) {
        compose.onNodeWithTag(field).performScrollTo().performClick()
        dateNode(day).assertIsEnabled().performClick()
        compose.onNodeWithTag("native_picker_confirm").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("native_date_picker").fetchSemanticsNodes().isEmpty() }
    }

    private fun assertFieldDate(field: String, value: String) {
        compose.onNodeWithTag(field).performScrollTo()
        compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(field)), useUnmergedTree = true).assertExists()
    }

    private fun incrementWeeks() = onView(withContentDescription("Weeks")).perform(object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(NumberPicker::class.java)
        override fun getDescription() = "Increase the number of complete weeks"
        override fun perform(uiController: UiController, view: View) {
            val wheel = view as NumberPicker
            val expected = wheel.value + 1
            check(wheel.accessibilityNodeProvider?.performAction(View.NO_ID, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null) == true)
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (wheel.value != expected && SystemClock.uptimeMillis() < deadline) uiController.loopMainThreadForAtLeast(16)
            check(wheel.value == expected)
            uiController.loopMainThreadUntilIdle()
        }
    })
}
