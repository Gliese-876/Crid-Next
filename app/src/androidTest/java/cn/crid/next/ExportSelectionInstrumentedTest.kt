package cn.crid.next

import android.view.View
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.NumberPicker
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import cn.crid.next.core.*
import cn.crid.next.ui.ExportDialog
import cn.crid.next.ui.UiText
import org.hamcrest.Matcher
import org.junit.Rule
import org.junit.Test

/** Exercises the visible export flow and the actual native pickers; never opens the SAF writer. */
class ExportSelectionInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val text = UiText(Language.EN)
    private val autumn = Semester("export-autumn", "Autumn 2025", "2025-09-01", "2025-11-30", 13,
        listOf(Period(1, "08:00", "08:45")))
    private val autumnFirst = Plan("autumn-first", autumn.id, "Autumn first plan", emptyList())
    private val autumnActive = autumnFirst.copy(id = "autumn-active", name = "Autumn active plan")

    @Test fun layoutSelectsMatchingNativeRangeAndKeepsEachSelectionWhenSwitching() {
        val state = AppState(listOf(autumn), listOf(autumnActive), autumn.id, autumnActive.id,
            Settings(language = Language.EN, holidaysEnabled = false))
        compose.setContent { MaterialTheme { ExportDialog(state, HolidayCalendar(), text, {}) } }

        selectView("Day")
        compose.onNodeWithTag("export_range_start").performScrollTo().performClick()
        compose.onNodeWithTag("native_date_picker").assertIsDisplayed()
        compose.onNode(hasText("November 28, 2025", substring = true) and hasAnyAncestor(hasTestTag("native_date_picker"))).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("OK").performClick()
        assertRangeStart("Nov 28, 2025")
        assertPages("3 PDF pages, with one complete day per page")

        selectView("Week")
        compose.onNodeWithTag("export_range_start").performScrollTo().performClick()
        compose.onNodeWithTag("native_number_picker").assertIsDisplayed()
        moveWheelBackward("First week")
        compose.onNodeWithText("OK").performClick()
        assertRangeStart("Week 12")
        assertPages("2 PDF pages, with one complete week per page")

        selectView("Month")
        compose.onNodeWithTag("export_range_start").performScrollTo().performClick()
        compose.onNodeWithTag("native_month_picker").assertIsDisplayed()
        moveWheelBackward("Month")
        compose.onNodeWithText("OK").performClick()
        assertRangeStart("October 2025")
        assertPages("2 PDF pages, with one complete month per page")

        selectView("Day")
        assertRangeStart("Nov 28, 2025")
        assertPages("3 PDF pages, with one complete day per page")
        selectView("Week")
        assertRangeStart("Week 12")
        assertPages("2 PDF pages, with one complete week per page")
        selectView("Month")
        assertRangeStart("October 2025")
        assertPages("2 PDF pages, with one complete month per page")
    }

    @Test fun multipleSemestersKeepTheirOwnPlanChoicesAndEachAddsOnePage() {
        val spring = autumn.copy(id = "export-spring", name = "Spring 2026", startDate = "2026-02-02", endDate = "2026-03-29", weeks = 8)
        val withoutPlan = spring.copy(id = "export-empty", name = "Autumn 2026", startDate = "2026-09-07", endDate = "2026-11-29", weeks = 12)
        val springFirst = Plan("spring-first", spring.id, "Spring first plan", emptyList())
        val springAlternate = springFirst.copy(id = "spring-alternate", name = "Spring alternate plan")
        val state = AppState(listOf(spring, withoutPlan, autumn), listOf(autumnFirst, autumnActive, springFirst, springAlternate),
            autumn.id, autumnActive.id, Settings(language = Language.EN, holidaysEnabled = false))
        compose.setContent { MaterialTheme { ExportDialog(state, HolidayCalendar(), text, {}) } }

        selectView("Semester")
        compose.onNodeWithTag("export_semester_${autumn.id}").performScrollTo().assertIsOn()
        compose.onNodeWithTag("export_semester_plan_${autumn.id}").assertTextContains("Autumn active plan")
        compose.onNodeWithTag("export_semester_${spring.id}").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("export_semester_plan_${spring.id}").performScrollTo().performClick()
        compose.onNodeWithText("Spring alternate plan").performClick()
        compose.onNodeWithTag("export_semester_plan_${spring.id}").assertTextContains("Spring alternate plan")
        assertPages("2 PDF pages, with one complete semester per page")
        compose.onNodeWithText("Save to folder").assertIsEnabled()

        compose.onNodeWithTag("export_semester_${withoutPlan.id}").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("export_semester_${autumn.id}").performScrollTo().performClick()
        assertPages("1 PDF page, with one complete semester per page")
        compose.onNodeWithTag("export_semester_plan_${spring.id}").performScrollTo().assertTextContains("Spring alternate plan")
        compose.onNodeWithTag("export_semester_${autumn.id}").performScrollTo().performClick()
        compose.onNodeWithTag("export_semester_plan_${autumn.id}").performScrollTo().performClick()
        compose.onNodeWithText("Autumn first plan").performClick()
        compose.onNodeWithTag("export_semester_plan_${autumn.id}").assertTextContains("Autumn first plan")
        compose.onNodeWithTag("export_semester_plan_${spring.id}").performScrollTo().assertTextContains("Spring alternate plan")
        assertPages("2 PDF pages, with one complete semester per page")
    }

    private fun selectView(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()

    private fun assertRangeStart(value: String) {
        compose.onNodeWithTag("export_range_start").performScrollTo().assertTextContains(value)
    }

    private fun assertPages(expected: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("export_unit_summary").performScrollTo().assertTextEquals(expected)
    }

    private fun moveWheelBackward(label: String) {
        onView(withContentDescription(label)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(NumberPicker::class.java)
            override fun getDescription() = "Move the native $label wheel back one value"
            override fun perform(uiController: UiController, view: View) {
                val picker = view as NumberPicker
                val expected = picker.value - 1
                check(expected >= picker.minValue)
                // NumberPicker exposes its scroll actions through a virtual accessibility root.
                check(picker.accessibilityNodeProvider?.performAction(View.NO_ID, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null) == true)
                val deadline = SystemClock.uptimeMillis() + 2_000
                while (picker.value != expected && SystemClock.uptimeMillis() < deadline) uiController.loopMainThreadForAtLeast(16)
                check(picker.value == expected) { "The native $label wheel did not settle on the preceding value" }
                uiController.loopMainThreadUntilIdle()
            }
        })
        compose.waitForIdle()
    }
}
